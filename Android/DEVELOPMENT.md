# Android Studio development

## Run staging on a phone

1. Pull the latest `main` source and open the repository's **Android/** directory in Android Studio.
2. Let Gradle sync finish. Use the **app** run configuration and **debug** build variant (the normal development default).
3. Enable USB debugging on the test phone, connect it and accept Android's debugging authorization prompt. Wireless debugging also works if already paired.
4. Select that physical device in Android Studio's device selector.
5. Click **Run app** or **Debug app**. Android Studio builds, installs and launches the app normally.

Debug builds automatically set `BuildConfig.API_ORIGIN` to `https://api.ghostcloak.org`. No Gradle property, manual APK copy or manual installation is required. Use the [two-phone staging guide](TWO_PHONE_STAGING_TEST.md) for username creation, contacts, sends and manual Sync. Run updates normally; do not uninstall or clear app data to reconnect, since that removes the local identity/history. The existing debug developer tools remain available.

## Build-type separation

| Build type | Default `BuildConfig.API_ORIGIN` | Explicit override |
|---|---|---|
| debug | `https://api.ghostcloak.org` | `ghostcloakApiOrigin` (debug only); empty value enables local-development mode |
| release | Empty: network controls disabled | `ghostcloakReleaseApiOrigin` only |

Release API_ORIGIN never uses the debug/legacy `ghostcloakApiOrigin` setting. A missing release setting produces a network-disabled release build, not a staging-connected one; that is not a production-ready configuration. A release origin must be reviewed and set explicitly. The build rejects `api.ghostcloak.org` as a release destination, including a trailing-dot spelling or alternate port. Both build types retain HTTPS/DNS-origin validation and the existing BuildConfig interface. No credentials belong in these settings; the staging hostname is public configuration.

Changing build type/origin is not an identity migration mechanism. Use staging/test identities for debug development. Release signing and production endpoint selection remain separate release-management work.

## Command line (optional)

From the repository root:

```powershell
# Same staging default as Android Studio Run.
.\Android\gradlew.bat -p Android :app:assembleDebug --dependency-verification strict

# Explicit local-only debug build for development.
.\Android\gradlew.bat -p Android :app:assembleDebug '-PghostcloakApiOrigin='

# Release without an origin: builds with network disabled.
.\Android\gradlew.bat -p Android :app:assembleRelease

# Release with an explicit reviewed origin (replace this example).
.\Android\gradlew.bat -p Android :app:assembleRelease '-PghostcloakReleaseApiOrigin=https://release.example.invalid'
```

A developer can put a debug override in their local Gradle settings, but it is not needed for staging. An explicit empty override takes precedence over the debug default; remove it if Android Studio unexpectedly shows local-only copy. Do not carry a local-only override into the normal staging workflow.

## Checks

`test` runs the build-configuration regression against both debug and release generated BuildConfig classes. It checks default and explicitly supplied origin values and that release does not use staging. Run Android tests on a selected emulator with `:app:connectedDebugAndroidTest :storage:connectedDebugAndroidTest`; the local-demo test injects a local-only runtime, and network tests use synthetic transports rather than staging accounts. Normal Run still uses the staging default.

Strict IDE attachment validation remains:

```powershell
.\Android\gradlew.bat -p Android -I gradle/verify-ide-sources.init.gradle verifyIdeSources --no-configuration-cache --dependency-verification strict
```
