# RELEASE.md — Signed APK you can install without USB / adb

This is the path to a **deployable, signed release APK** that you download
directly on the Pixel 7 Pro (from a GitHub Release or a CI artifact) and install
by **tapping it** — no USB cable, no `adb`, no "USB debugging".

Two builds exist:

| Build | Command | Signer | Installable by tapping? |
|---|---|---|---|
| Debug | `./gradlew assembleDebug` / `scripts/deploy-pixel.sh` | debug keystore | Yes, but usually pushed via adb |
| **Release** | `./gradlew assembleRelease` | **your release keystore** (or debug fallback) | **Yes** |

The `release` build type is wired to a `signingConfig` (see
`app/build.gradle.kts`) that reads its keystore + passwords from **environment
variables or Gradle properties** — nothing secret is committed. If none are set,
it **falls back to the debug keystore** so the build never fails for lack of
secrets (the APK is still installable, just debug-signed).

> The distributed release APK ships with an **empty** Anthropic API key. The app
> reads your key at runtime from encrypted storage (entered on the onboarding
> screen). CI never bakes a key into the release APK.

---

## Signing-key continuity — read this once

Android identifies an app by its **signing key**. If you install a release-keystore
APK over a debug-signed one (or vice-versa), Android refuses the update
(`INSTALL_FAILED_UPDATE_INCOMPATIBLE`) — you must uninstall first, **which wipes
app data** (including your stored API key and session history).

**So: once you generate a release keystore, keep it forever** (back it up
somewhere safe). Every future release signed with the same key installs cleanly
over the last one and preserves data. The debug-keystore fallback is fine for
quick local builds, but pick a release keystore for anything you actually keep on
the phone.

---

## One-time setup: generate a release keystore

Run locally (needs JDK 17's `keytool`, on `PATH` or at
`$(/usr/libexec/java_home -v 17)/bin/keytool`). Pick your own passwords:

```bash
keytool -genkeypair -v \
  -keystore assist-release.jks \
  -alias assist \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass 'CHANGE_ME_STORE_PASS' \
  -keypass  'CHANGE_ME_KEY_PASS' \
  -dname 'CN=Assist, O=Assist, C=US'
```

This writes `assist-release.jks`. It is **gitignored** (`*.jks` / `*.keystore`) —
never commit it. Back it up outside the repo.

### Build a signed release APK locally

Point the build at the keystore via env vars (or `-P` Gradle properties):

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
ASSIST_KEYSTORE_FILE="$PWD/assist-release.jks" \
ASSIST_KEYSTORE_PASSWORD='CHANGE_ME_STORE_PASS' \
ASSIST_KEY_ALIAS='assist' \
ASSIST_KEY_PASSWORD='CHANGE_ME_KEY_PASS' \
./gradlew assembleRelease

# -> app/build/outputs/apk/release/app-release.apk
# verify the signer:
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs \
  app/build/outputs/apk/release/app-release.apk
```

Equivalent Gradle-property names (e.g. in `~/.gradle/gradle.properties`, never
the repo): `assistKeystoreFile`, `assistKeystorePassword`, `assistKeyAlias`,
`assistKeyPassword`.

---

## Recommended path: build the signed APK in GitHub Actions

The `.github/workflows/release.yml` workflow builds a **signed** release APK and
makes it downloadable. It triggers on a pushed `v*` tag (attaches the APK to a
GitHub Release) or manually via **workflow_dispatch** (uploads the APK as a
workflow artifact).

### 1. Base64-encode the keystore and add GitHub secrets

```bash
# macOS — copies the base64 to the clipboard:
base64 -i assist-release.jks | pbcopy
# (Linux: base64 -w0 assist-release.jks)
```

In the GitHub repo: **Settings → Secrets and variables → Actions → New
repository secret**. Create **exactly these four secrets**:

| Secret name | Value |
|---|---|
| `ASSIST_KEYSTORE_BASE64` | the base64 blob from the command above |
| `ASSIST_KEYSTORE_PASSWORD` | your keystore store password |
| `ASSIST_KEY_ALIAS` | `assist` (the alias you chose) |
| `ASSIST_KEY_PASSWORD` | your key password |

(If you omit these, the workflow still succeeds but produces a **debug-signed**
APK.)

### 2. Cut a release

**Tagged release (recommended)** — creates a GitHub Release with the APK attached:

```bash
git tag v0.1.0
git push origin v0.1.0
```

Watch the **Release APK** workflow in the Actions tab. When it finishes, the
signed APK (`assist-0.1.0.apk`) is attached to the new Release under
**Releases**.

**Manual run** — Actions tab → **Release APK** → **Run workflow**. The APK is
uploaded as the `assist-release-apk` artifact on that run (download it from the
run summary).

> Bump `versionCode` / `versionName` in `app/build.gradle.kts` before tagging so
> updates install over the previous version.

### 3. Install on the Pixel 7 Pro (no USB)

1. On the phone, open the GitHub Release page (or the Actions run) in Chrome and
   **download** `assist-<version>.apk`.
2. Tap the downloaded file. Android prompts to allow installs from this source:
   **Settings → "Install unknown apps" → Chrome (or your browser) → Allow from
   this source**.
3. Back out, tap the APK again → **Install** → **Open**.
4. Grant the runtime permissions on the onboarding screen (Accessibility "full
   control", Display over other apps, mic, notifications) and paste your
   Anthropic API key.

Future releases signed with the **same** keystore install straight over the top,
keeping your key and history.

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` when installing | An APK signed by a different key is already installed. Uninstall the app (loses data), then install the new APK. Keep one keystore going forward. |
| "App not installed" with no detail | Usually the same signer mismatch as above, or a truncated download — re-download the APK. |
| Workflow APK is debug-signed | The four `ASSIST_*` secrets aren't set (or a typo). Re-check Settings → Secrets. |
| `keytool: command not found` | Use `$(/usr/libexec/java_home -v 17)/bin/keytool`. |
| Want to confirm the signer of an APK | `apksigner verify --print-certs app-release.apk` (apksigner is in `$ANDROID_HOME/build-tools/35.0.0/`). |

See also **[`DEVICE.md`](DEVICE.md)** (on-device first-run + permission grants)
and **[`README.md`](README.md)** (build loop + CI).
