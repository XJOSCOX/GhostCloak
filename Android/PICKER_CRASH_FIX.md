# Photo/document picker Activity Result regression

## Root cause

Before this correction, `debugRuntimeClasspath` resolved Activity, Activity Compose
and Activity KTX to **1.8.2**, but Fragment to **1.2.5**. Biometric **1.1.0**
introduced Fragment 1.2.5; AppCompat 1.2.0 also requested older Fragment 1.1.0.
There was no Fragment KTX dependency. The catalog's Activity Compose declaration
was 1.8.0, already upgraded transitively to 1.8.2.

The Compose launcher uses ComponentActivity's ActivityResultRegistry, whose
generated request codes use bits above the lower 16 bits. FragmentActivity 1.2.5
overrides `startActivityForResult` and rejects those codes before Android can open
the picker. Both PickVisualMedia and OpenDocument use this path. No attachment
preparation or network request is needed to trigger the crash.

AndroidX explicitly documents that Fragment **1.3.0 or newer** is required for
Activity Result APIs on FragmentActivity/AppCompatActivity:
[Fragment 1.3.0 release notes](https://developer.android.com/jetpack/androidx/releases/fragment#1.3.0).

## Correction

Declare stable Fragment **1.8.9** directly, so compatibility no longer depends on
Biometric's old transitive minimum. Align the catalog Activity Compose declaration
with the already resolved **1.8.2**. After resolution, Activity / Activity Compose /
Activity KTX remain **1.8.2**, Fragment is **1.8.9**, and Fragment KTX remains absent.

MainActivity stays a FragmentActivity: LockScreens constructs AndroidX
BiometricPrompt with that host. Changing it to ComponentActivity would break that
integration. Biometric 1.1.0, application ID, permissions, picker contracts, URI
callbacks, staging, lifecycle hooks and navigation are unchanged. There is no
request-code truncation, custom registry, or manual startActivityForResult in
production code. New checksums were compared with fresh Google Maven downloads;
strict verification remains enabled.

## Regression coverage

PickerLaunchTest hosts the production AttachmentComposer in a debug-only
FragmentActivity. It clicks the attachment control and Photo/Document, exercises
the real registry and superclass launch, and asserts a request code above 65535.
Instrumentation intercepts the OS picker only after the superclass call, returning
cancellation. These four tests reproduced the exact lower-16-bits exception before
the dependency update and passed afterward. They cover repeated open/cancel/open,
stop/resume, and recreation before and after launcher registration.

Additional pending-result tests defer the OS cancellation callback in the debug host,
keep the registry result outstanding across stopped-host recreation, then deliver cancellation
and assert that attachment preparation never starts. This is lifecycle/result
coverage, not a simulation of OEM picker UI or biometric authentication. Existing
app-lock/media tests cover locked/background access separately.

## Physical retest

1. Pull main, sync Gradle, select the existing phone and Run the debug app in place.
2. Open a conversation, tap the attachment control, then Photo. The system picker
   must open. Cancel and repeat twice; then select a supported JPEG/PNG and confirm
   the existing preview/staging flow appears.
3. Repeat with Document: cancel/reopen, then choose a small document and confirm
   its preview/name and send flow. No broad storage permission should be requested.
4. While each picker is open, background the app long enough for app lock, return,
   and cancel/select. Unlock must still gate attachment access. Also rotate while
   the picker is open and repeat after Activity recreation.

No backend deployment or database migration is required.

## Validation commands

Completed validation (2026-09-12/13): **154 JVM tests**, **98 app connected tests**
and **10 encrypted-storage connected tests** passed with zero failures. All six
picker tests additionally passed on the API 30 AOSP emulator. Debug/release builds
and strict dependency verification passed, including 556 IDE source/Javadoc
artifacts. The debug picker harness is absent from release DEX. PostgreSQL tests
are not applicable to this Android dependency-only correction. Physical OEM picker
selection and biometric interaction remain a user retest, not a claimed automated
physical-device result.

Run from `Android/` with strict verification:

```powershell
.\gradlew.bat :app:dependencyInsight --configuration debugRuntimeClasspath --dependency androidx.activity --dependency-verification strict
.\gradlew.bat :app:dependencyInsight --configuration debugRuntimeClasspath --dependency androidx.fragment --dependency-verification strict
.\gradlew.bat :app:dependencyInsight --configuration debugRuntimeClasspath --dependency fragment-ktx --dependency-verification strict
.\gradlew.bat :test-support:test :attachments:test :app:testDebugUnitTest :app:testReleaseUnitTest :app:assembleDebug :app:assembleRelease --dependency-verification strict
.\gradlew.bat -I gradle/verify-ide-sources.init.gradle verifyIdeSources --dependency-verification strict
.\gradlew.bat :app:connectedDebugAndroidTest :storage:connectedDebugAndroidTest --dependency-verification strict
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.ghostcloak.app.PickerLaunchTest --dependency-verification strict
```

Release runtime dependencyInsight was also checked for Activity and Fragment.
The API 30 AOSP emulator has no backported photo picker, so Photo uses
`ACTION_OPEN_DOCUMENT`; the API 37 emulator uses the platform photo-picker
contract. Instrumentation intercepts the resulting intent after the real
FragmentActivity launch validation. OEM UI interaction and selecting actual files
still require the physical retest above.
