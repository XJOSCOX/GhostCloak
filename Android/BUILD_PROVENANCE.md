# Android build provenance

The Android build reads `git rev-parse --verify HEAD` from the repository checkout containing `Android/`. It embeds that full 40-character commit in `BuildConfig.GIT_SHA` and a Git-status-derived boolean in `BuildConfig.GIT_DIRTY`. The build fails if Git metadata is unavailable, the checkout root differs, or HEAD is not a full SHA. The version name/code remain product version fields, not source identity.

`GIT_DIRTY` is true for any tracked change and for untracked source-affecting files under `Android/`, `backend/`, `protocol/`, `infrastructure/`, or root Markdown. Git-ignored generated build output is excluded by Git itself; `.log` and `.tmp` files are excluded from the untracked-source check. Review the complete `git status --short --untracked-files=all` before accepting an artifact. In particular, never treat a modified-source build as reviewed solely because it has a commit SHA.

From a **clean committed checkout**, run in PowerShell:

```powershell
cd Android
.\gradlew.bat :app:assembleSecurityReviewed --no-daemon --dependency-verification strict --console plain
```

The task requires strict dependency verification, a clean source tree, JVM tests, and debug/release APK builds. It does not sign with a production key or install on a device. An ordinary Android Studio build can proceed with local edits but will display **Modified source build**. A clean source tree is necessary, though not by itself sufficient, for a reviewed artifact; retain the Gradle result and artifact hash as evidence.

Record provenance for each exact APK, without embedding its digest in source:

```powershell
$apk = (Resolve-Path '.\app\build\outputs\apk\release\app-release-unsigned.apk').Path
git rev-parse --verify HEAD
git status --short --untracked-files=all
(Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
```

The record must contain the full Git SHA, `GIT_DIRTY=false`, variant (`debug` or `release`), package `org.ghostcloak.app`, version name/code, the 64-hex APK SHA-256, and the successful reviewed-build command output. For debug, use `app\build\outputs\apk\debug\app-debug.apk` instead. `release` may be unsigned; record the digest of the **actual installed/signed APK** when applicable, because signing changes the digest. Do not infer installed bytes from a differently signed build.

For **each** test client A, B, and C, open the app's protected **Settings → Build information** and compare the displayed 12-character Source prefix against the reviewed commit. Confirm **Clean reviewed build** and version name/code. The full SHA is embedded in the APK BuildConfig; the short prefix is only a convenient screen check. Record each device's result separately. Use the selected device serial for package inspection, for example:

```powershell
adb -s <approved-device-serial> shell dumpsys package org.ghostcloak.app | Select-String 'versionCode|versionName'
```

`dumpsys package` cannot read custom BuildConfig fields, so it does not replace the in-app Source/clean-state check. A stronger byte-level comparison can pull the installed APK from each authorized test device and hash it against the exact installation artifact; this is a **manual future procedure**, not an instruction to access a connected phone now. Resolve the device's `pm path org.ghostcloak.app`, pull that base APK, and hash it with `Get-FileHash`. Split APK installations require recording and comparing every installed split. Never run unqualified `adb` when more than one device is connected.

The source marker does not attest to signing identity, compiler/toolchain trust, or backend deployment. Keep the artifact hash, strict-verification build output, and A/B/C observations with the review record.
