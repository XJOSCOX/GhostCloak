# Safe Exit recovery on a real device

Safe Exit succeeds only after owned cryptographic keys, local account/message/contact/attachment/security state, and retained access to the former identity have been destroyed and verified. The durable journal is then cleared and the normal fresh-install onboarding route opens. The secure-local-operation screen is an intermediate fail-closed screen.

## Physical defect and code audit

The lock-screen Retry button called `GhostApplication.resumeLocalOperation()`, which launched `LocalOperationCoordinator.resume()` asynchronously. Before this change, that launcher caught every ordinary exception without a release log or UI state update. The button could appear to do nothing even when the coordinator had failed. Process recreation constructs a new Application, gate, and coordinator, but repeated taps within one process reused its coordinator. The gate also deliberately stays `CORRUPT` after a failed journal write; it cannot authorize destruction from an ambiguous in-memory state.

The updated path reads the durable journal on each attempt, constructs a coordinator for that attempt, and serializes attempts. It exposes running/failed retry state while preserving the blocked fence. If an in-memory `CORRUPT` fence follows a failed write but the journal contains a valid **blocked** stage, Retry adopts that exact durable stage and resumes idempotently. It never adopts `NONE`, clears a journal early, or treats a failed verification as success. Any other disagreement remains blocked and emits a journal-failure category.

The old physical Logcat has no Safe Exit events, so the underlying failing stage on the Samsung device **cannot be established from that build**. Possible stages include owner quiescence, journal persistence, Android Keystore deletion/verification, owned-file cleanup, and final verification. The user manually installed the exact updated staging debug APK over the phone's app and confirmed that tapping Retry reached fresh onboarding. This verifies the recovery path on that phone, but it does not identify which original stage failed or prove a newly initiated wipe on Samsung hardware. No automated command or instrumentation was run on the physical phone.

## Release-safe diagnostics

In Android Studio Logcat, filter by tag `GhostCloakSafeExit`. Events contain only fixed categories and a `LocalOperationState` enum. They include screen presentation, Retry tap, coordinator construction/start, completion, and categorized journal/quiesce/Keystore/storage/finalization failure. They do not contain aliases, paths, PINs, usernames, account/device IDs, keys, tokens, messages, exception text, or attachment metadata.

For an in-place manual retest, install the exact `Android/build/safe-exit-recovery-staging-debug.apk` over an existing debug-signed installation. Android Studio Run from a different checkout may install code without these diagnostics. Do not uninstall or clear app data as part of the retest. Open the stuck screen, tap Retry once, then copy only the `GhostCloakSafeExit` lines and report whether fresh onboarding appeared. A release installation needs an APK signed with the exact same release signing certificate; the repository's release APK is unsigned, and the disposable-AVD release acceptance APK uses a test-only debug certificate.

If Retry reports `SAFE_EXIT_RETRY_FAILED_KEYSTORE`, `SAFE_EXIT_RETRY_FAILED_STORAGE`, or `SAFE_EXIT_RETRY_FAILED_FINALIZATION`, keep the app installed and the journal intact. The category is evidence for a narrow, hardware-specific repair. `SAFE_EXIT_RETRY_FAILED_JOURNAL` likewise requires a separate durability review; it is never permission to discard the journal.

## Disposable-AVD validation

Strict dependency-verified JVM tests and debug/release builds passed. On `emulator-5556` only, two Retry-screen tests and ten local-destruction fixture tests passed. An actual release-variant acceptance APK with an unreachable fixture origin completed the offline wipe sequence through `COMPLETE`, rejected old-state probes, and returned to fresh onboarding. After reboot, the post gate passed copied-state rejection and no silent old-account reconnect. The AVD was reset to remove an unknown device PIN before the final release run; no physical phone was used.

The ordinary staging debug APK was then rebuilt with `API_ORIGIN=https://api.ghostcloak.org` and preserved at `Android/build/safe-exit-recovery-staging-debug.apk`. The ordinary release output was restored to unsigned with an empty API origin; the fixture-signed release APK is not the manual physical test build.

The user initially retested with Android Studio Run; the supplied Logcat had no `GhostCloakSafeExit` lines and did not establish which checkout was installed. The user then installed the exact staging APK and reported that Retry reached fresh onboarding. On the disposable AVD, an intentionally corrupt journal showed the recovery screen and emitted `SAFE_EXIT_RECOVERY_SCREEN`, `SAFE_EXIT_RETRY_TAPPED`, and `SAFE_EXIT_RETRY_FAILED_JOURNAL`; clearing only the AVD app data then showed fresh onboarding. These observations do not justify bypassing an unreadable journal on a real device.
