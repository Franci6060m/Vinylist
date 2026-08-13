package com.francistech.vinylist;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Battery-optimization exemption for background playback.
 *
 * This is deliberately separate from anything in AndroidManifest's normal
 * <uses-permission> list: "Files and media" is the only entry Android's own
 * App info > Permissions screen ever shows for this app, because that screen
 * only lists *runtime* (dangerous-group) permissions -- FOREGROUND_SERVICE,
 * WAKE_LOCK, POST_NOTIFICATIONS etc. are all install-time permissions Android
 * grants automatically and intentionally never surfaces there, so adding more
 * of those to the manifest cannot make new rows appear on that screen. That's
 * expected Android behavior, not something to work around.
 *
 * What genuinely was missing, and *is* independently switchable per-app in
 * Settings (and heavily enforced by Samsung's own battery manager on top of
 * stock Android's Doze/App Standby), is "unrestricted" battery usage. Even
 * with a correctly-held foreground Service + WakeLock, an app still on
 * "Optimized"/"Restricted" battery usage can have its process killed outright
 * after enough background time -- which matches "closes automatically after
 * a while" better than anything fixable purely in-process. This plugin lets
 * the app ask the system for that exemption directly (the standard Android
 * API for it), and as a fallback opens the phone's own battery-usage screen
 * for this app so the person can flip it by hand if the direct prompt isn't
 * available on their OEM skin.
 */
@CapacitorPlugin(name = "Battery")
public class BatteryPlugin extends Plugin {

    @PluginMethod
    public void isIgnoringBatteryOptimizations(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("ignoring", isIgnoring());
        call.resolve(ret);
    }

    private boolean isIgnoring() {
        if (Build.VERSION.SDK_INT < 23) return true; // Doze didn't exist yet
        PowerManager pm = (PowerManager) getContext().getSystemService(android.content.Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getContext().getPackageName());
    }

    /** Fires the system's own "Allow [app] to ignore battery optimizations?"
        dialog. One tap for the person, no navigating Settings by hand --
        but some OEM builds (notably some Samsung/MIUI versions) block or
        ignore this Intent, hence the openBatterySettings() fallback below. */
    @PluginMethod
    public void requestIgnoreBatteryOptimizations(PluginCall call) {
        if (Build.VERSION.SDK_INT < 23 || isIgnoring()) {
            JSObject ret = new JSObject();
            ret.put("ignoring", true);
            call.resolve(ret);
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getContext().getPackageName()));
            getActivity().startActivity(intent);
            // No reliable onActivityResult signal for this one across OEMs --
            // JS re-checks isIgnoringBatteryOptimizations() when the app
            // resumes (same 'resume' listener already used for the native
            // notification resync), which is when the real answer is known.
            call.resolve(new JSObject());
        } catch (Exception e) {
            call.reject("Could not open the battery optimization prompt", e);
        }
    }

    /** Fallback / manual path: opens this app's page in the phone's own
        battery-usage settings, for OEM skins where the direct request above
        doesn't stick or isn't honored. */
    @PluginMethod
    public void openBatterySettings(PluginCall call) {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getContext().getPackageName()));
            getActivity().startActivity(intent);
            call.resolve();
        } catch (Exception e) {
            call.reject("Could not open app settings", e);
        }
    }
}
