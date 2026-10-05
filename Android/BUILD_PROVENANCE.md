# Android build provenance

The Android build reads `git rev-parse --verify HEAD` from the repository checkout containing `Android/`. It embeds that full 40-character commit in `BuildConfig.GIT_SHA` and a Git-status-derived boolean in `BuildConfig.GIT_DIRTY`. The build fails if Git metadata is unavailable, the checkout root differs, or HEAD is not a full SHA. The version name/code remain product version fields, not source identity.

`GIT_DIRTY` is true for any tracked change and for untracked source-affecting files under `Android/`, `backend/`, `protocol/`, `infrastructure/`, or root Markdown. Git-ignored generated build output is excluded by Git itself; `.log` and `.tmp` files are excluded from the untracked-source check. A dirty debug build is allowed and displays **Source status: Modified development build**. A clean ordinary build displays **Clean source build**; that label alone does not attest that the reviewed-build gate ran.

## Routine development in Android Studio

Open the existing `Android/` project in Android Studio and use **Run** to update test clients A, B, and C in place. Keep the same package and compatible signing key so Android preserves app data and existing identities. Do not uninstall or clear data. A separate clean checkout, manual APK hashing, or manual `adb install` is not required for routine development. When checking which source is installed, open the protected **Settings → Build information** on each client and record the displayed Source SHA prefix and source status. A dirty build is useful for active development and controlled adversarial-preparation tests, but it is **not** a final release-assurance artifact. Each development test record should include the full checkout Git SHA and `GIT_DIRTY=true/false`; the screen shows the first 12 SHA characters for convenient comparison.

## Formal reviewed build

From a **clean committed checkout**, run in PowerShell:

```powershell
cd Android
.\gradlew.bat :app:assembleSecurityReviewed --no-daemon --dependency-verification strict --console plain
```

The task requires strict dependency verification, a clean source tree, JVM tests, and debug/release APK builds. It is for a release candidate, formal security signoff, or final production artifact; it does not sign with a production key or install on a device. A clean source tree is necessary, though not by itself sufficient, for a reviewed artifact; retain the Gradle result and artifact hash as evidence. Replay final release-signoff tests on this clean reviewed build after development testing.

Record provenance for each exact APK, without embedding its digest in source:

```powershell
$apk = (Resolve-Path '.\app\build\outputs\apk\release\app-release-unsigned.apk').Path
git rev-parse --verify HEAD
git status --short --untracked-files=all
(Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
```

The record must contain the full Git SHA, `GIT_DIRTY=false`, variant (`debug` or `release`), package `org.ghostcloak.app`, version name/code, the 64-hex APK SHA-256, and the successful reviewed-build command output. For debug, use `app\build\outputs\apk\debug\app-debug.apk` instead. `release` may be unsigned; record the digest of the **actual installed/signed APK** when applicable, because signing changes the digest. Do not infer installed bytes from a differently signed build.

For formal signoff on **each** test client A, B, and C, open the app's protected **Settings → Build information** and compare the displayed 12-character Source prefix against the reviewed commit. Confirm **Clean source build** and version name/code, and separately retain the passing reviewed-build gate record. The full SHA is embedded in the APK BuildConfig; the short prefix is only a convenient screen check. Record each device's result separately. When package inspection is needed, use the selected device serial, for example:

```powershell
adb -s <approved-device-serial> shell dumpsys package org.ghostcloak.app | Select-String 'versionCode|versionName'
```

`dumpsys package` cannot read custom BuildConfig fields, so it does not replace the in-app Source/clean-state check. A stronger byte-level comparison can pull the installed APK from each authorized test device and hash it against the exact installation artifact; this is a **manual future procedure**, not an instruction to access a connected phone now. Resolve the device's `pm path org.ghostcloak.app`, pull that base APK, and hash it with `Get-FileHash`. Split APK installations require recording and comparing every installed split. Never run unqualified `adb` when more than one device is connected.

The source marker does not attest to signing identity, compiler/toolchain trust, or backend deployment. Keep the artifact hash, strict-verification build output, and A/B/C observations with the review record.
