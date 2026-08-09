# Connecting to Codemagic — step by step

## 1. Push this project to GitHub
```
cd vinylist-capacitor
git init
git add .
git commit -m "Vinylist Capacitor project"
```
Create a new (private is fine) repo on GitHub, then:
```
git remote add origin https://github.com/<you>/vinylist-app.git
git push -u origin main
```

## 2. Create a signing keystore (one-time, do this locally)
```
keytool -genkey -v -keystore vinylist-release.keystore \
  -alias vinylist -keyalg RSA -keysize 2048 -validity 10000
```
It'll ask for passwords and your name/org — answer anything, just **write
down the two passwords and the alias** (`vinylist`). Keep this `.keystore`
file somewhere safe outside the repo — never commit it to git.

## 3. Sign up at codemagic.io and connect the repo
- Sign in with your GitHub account.
- "Add application" → pick the `vinylist-app` repo.
- Codemagic will detect `codemagic.yaml` in the repo root automatically and
  offer the `vinylist-android` workflow — select it.

## 4. Upload the keystore
In Codemagic: **Team settings → Code signing identities → Android
keystores → Add keystore**.
- Upload the `.keystore` file from step 2.
- Enter the store password, key alias (`vinylist`), and key password.
- Name the reference group **`vinylist_keystore`** — this must match the
  `groups:` line in `codemagic.yaml` exactly, or the build won't find it.

## 5. Fix the email in codemagic.yaml
Open `codemagic.yaml`, replace `your-email@example.com` with your real
address (or delete the `publishing:` block if you'd rather just download
manually from the dashboard).

## 6. Run it
Back on the app page in Codemagic, click **Start new build**, pick the
`vinylist-android` workflow, hit go. Takes roughly 10–15 minutes the first
time.

## 7. Get the file
When it finishes, the build page's **Artifacts** tab has:
- `app-release.aab` — upload this to Play Console if you go that route.
- `app-release-unsigned.apk` or `app-release.apk` — this is the file you
  share directly. Anyone can download and install it (they'll need to allow
  "install unknown apps" once for whatever app they downloaded it through).

If you enabled the email step, both files also land in your inbox as a
build notification with download links.
