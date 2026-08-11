package com.francistech.vinylist;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * In-app update: checks a GitHub repo's "latest release" directly (no
 * separate manifest file to hand-maintain), downloads the APK attached to
 * it (emitting progress), and hands the downloaded file to Android's own
 * package installer.
 *
 * This does NOT auto-install silently -- Android does not allow a normal app
 * to do that. What it does do is everything up to that point (check,
 * download, progress) natively and in-app, then opens the system installer
 * UI pre-loaded with the downloaded APK so the person just taps "Install".
 * That last tap is Android's own confirmation dialog, not something this
 * plugin can skip.
 *
 * The release workflow this is built around: tag a GitHub release like
 * "v1.2.0", write your notes in the release description, attach the APK
 * with the SAME filename every time (see FIXED_APK_ASSET_NAME below) -- and
 * that's it. Nothing else to edit anywhere, for any release, ever. The
 * "url" passed in from JS is just:
 *   https://api.github.com/repos/<owner>/<repo>/releases/latest
 * which GitHub itself always keeps pointed at whichever release is newest --
 * you never touch that URL again after the one-time setup.
 */
@CapacitorPlugin(name = "AppUpdate")
public class AppUpdatePlugin extends Plugin {

    /** Every release's APK attachment must use this exact filename so the
        app can always find it in the release's asset list without needing
        a separate manifest to say which asset is the right one. */
    private static final String FIXED_APK_ASSET_NAME = "vinylist-latest.apk";

    /** Current installed version, so JS doesn't have to hardcode it anywhere. */
    @PluginMethod
    public void getCurrentVersion(PluginCall call) {
        try {
            PackageInfo info = getContext().getPackageManager().getPackageInfo(getContext().getPackageName(), 0);
            JSObject ret = new JSObject();
            ret.put("versionName", info.versionName);
            ret.put("versionCode", currentVersionCode(info));
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("Could not read current version: " + e.getMessage());
        }
    }

    /** Fetches GitHub's "latest release" for the repo at `url` (a
        .../releases/latest API URL) and compares its tag against the
        installed version. No hand-maintained manifest involved -- the tag
        name and release description ARE the manifest. */
    @PluginMethod
    public void checkForUpdate(PluginCall call) {
        String urlStr = call.getString("url");
        if (urlStr == null || urlStr.isEmpty()) { call.reject("Missing url"); return; }

        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(urlStr);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                // GitHub's API rejects requests with no User-Agent at all,
                // and the Accept header pins the response shape/version.
                conn.setRequestProperty("User-Agent", "Vinylist-Android-App");
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                conn.connect();

                int status = conn.getResponseCode();
                if (status != 200) {
                    final int s = status;
                    runOnMain(() -> call.reject("Update check failed: HTTP " + s));
                    return;
                }

                StringBuilder sb = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                }
                JSONObject json = new JSONObject(sb.toString());

                // "v1.2.0" or "1.2.0" -> both fine, the leading v is optional.
                String tagName = json.optString("tag_name", "");
                String versionName = tagName.startsWith("v") || tagName.startsWith("V") ? tagName.substring(1) : tagName;
                long remoteCode = versionCodeFromName(versionName);
                String notes = json.optString("body", "");

                String apkUrl = "";
                JSONArray assets = json.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.getJSONObject(i);
                        if (FIXED_APK_ASSET_NAME.equalsIgnoreCase(asset.optString("name", ""))) {
                            apkUrl = asset.optString("browser_download_url", "");
                            break;
                        }
                    }
                    // Fall back to the first .apk asset if the exact fixed
                    // name isn't found, so a one-off differently-named
                    // upload still works rather than silently finding nothing.
                    if (apkUrl.isEmpty()) {
                        for (int i = 0; i < assets.length(); i++) {
                            JSONObject asset = assets.getJSONObject(i);
                            String name = asset.optString("name", "");
                            if (name.toLowerCase().endsWith(".apk")) {
                                apkUrl = asset.optString("browser_download_url", "");
                                break;
                            }
                        }
                    }
                }

                PackageInfo info = getContext().getPackageManager().getPackageInfo(getContext().getPackageName(), 0);
                long currentCode = currentVersionCode(info);

                final String finalApkUrl = apkUrl;
                JSObject ret = new JSObject();
                ret.put("available", remoteCode > currentCode && !finalApkUrl.isEmpty());
                ret.put("versionCode", remoteCode);
                ret.put("versionName", versionName);
                ret.put("notes", notes);
                ret.put("apkUrl", finalApkUrl);
                ret.put("currentVersionCode", currentCode);
                runOnMain(() -> call.resolve(ret));
            } catch (Exception e) {
                runOnMain(() -> call.reject("Update check failed: " + e.getMessage()));
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    /** Downloads the APK at `url` into app-private storage, emitting "downloadProgress" events. */
    @PluginMethod
    public void downloadUpdate(PluginCall call) {
        String urlStr = call.getString("url");
        if (urlStr == null || urlStr.isEmpty()) { call.reject("Missing url"); return; }

        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                File outFile = new File(getContext().getExternalFilesDir(null), "vinylist-update.apk");
                URL url = new URL(urlStr);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.connect();

                int status = conn.getResponseCode();
                if (status != 200) {
                    final int s = status;
                    runOnMain(() -> call.reject("Download failed: HTTP " + s));
                    return;
                }

                long total = conn.getContentLengthLong(); // -1 if the server doesn't send Content-Length
                long downloaded = 0;
                int lastPercentSent = -1;

                try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(outFile)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                        downloaded += read;
                        if (total > 0) {
                            int percent = (int) ((downloaded * 100) / total);
                            if (percent != lastPercentSent) {
                                lastPercentSent = percent;
                                final int p = percent;
                                final long d = downloaded, t = total;
                                runOnMain(() -> {
                                    JSObject progress = new JSObject();
                                    progress.put("percent", p);
                                    progress.put("bytesDownloaded", d);
                                    progress.put("totalBytes", t);
                                    notifyListeners("downloadProgress", progress);
                                });
                            }
                        }
                    }
                }

                JSObject ret = new JSObject();
                ret.put("filePath", outFile.getAbsolutePath());
                runOnMain(() -> call.resolve(ret));
            } catch (Exception e) {
                runOnMain(() -> call.reject("Download failed: " + e.getMessage()));
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    /** API 26+ only: whether this app is currently allowed to prompt the installer at all. */
    @PluginMethod
    public void canInstallPackages(PluginCall call) {
        JSObject ret = new JSObject();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ret.put("allowed", getContext().getPackageManager().canRequestPackageInstalls());
        } else {
            ret.put("allowed", true); // no such per-app toggle before Android 8
        }
        call.resolve(ret);
    }

    /** Sends the person to the system settings screen to grant "install unknown apps" for this app. */
    @PluginMethod
    public void requestInstallPermission(PluginCall call) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + getContext().getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
        }
        call.resolve();
    }

    /** Hands the downloaded APK to the system installer. The final "Install" tap is Android's own dialog. */
    @PluginMethod
    public void installUpdate(PluginCall call) {
        String filePath = call.getString("filePath");
        if (filePath == null || filePath.isEmpty()) { call.reject("Missing filePath"); return; }
        File file = new File(filePath);
        if (!file.exists()) { call.reject("Downloaded file not found"); return; }

        try {
            Uri apkUri = FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
            call.resolve();
        } catch (Exception e) {
            call.reject("Could not launch installer: " + e.getMessage());
        }
    }

    private long currentVersionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? info.getLongVersionCode() : info.versionCode;
    }

    /** Turns a semver-ish string like "1.12.5" into a single comparable
        integer (1*1_000_000 + 12*1_000 + 5), the exact same formula
        prepare-android.sh uses to set the installed app's own versionCode
        from version.json at build time -- so a GitHub release tagged
        "v1.2.0" and a build whose version.json says "1.2.0" always produce
        the identical number and compare correctly against each other.
        Missing/non-numeric parts default to 0 rather than throwing, so a
        stray non-semver tag just won't look newer instead of crashing the
        check. */
    private long versionCodeFromName(String versionName) {
        String[] parts = (versionName == null ? "" : versionName).split("\\.");
        long major = parts.length > 0 ? parseIntSafe(parts[0]) : 0;
        long minor = parts.length > 1 ? parseIntSafe(parts[1]) : 0;
        long patch = parts.length > 2 ? parseIntSafe(parts[2]) : 0;
        return major * 1_000_000 + minor * 1_000 + patch;
    }

    private int parseIntSafe(String s) {
        try {
            // Strip any non-digit suffix (e.g. "0-beta" -> "0") so a tag
            // like "v1.2.0-beta" still parses instead of failing outright.
            String digits = s.replaceAll("[^0-9].*$", "");
            return digits.isEmpty() ? 0 : Integer.parseInt(digits);
        } catch (Exception e) {
            return 0;
        }
    }

    private void runOnMain(Runnable r) {
        if (getActivity() != null) getActivity().runOnUiThread(r); else r.run();
    }
}
