# Phase 1K.4E release validation record — 2026-10-03

Baseline main: `0bf9e2ac9422716f770cf987aa3f624662d1cba0`.

## Biometric timeout finding

The earlier full acceptance test stayed on the protected Settings route after
changing App Lock to COMBINED. `AppLockController.configure()` advances the lock
session generation; this invalidates the Settings authorization. The UI then
shows `Authenticate to open Settings`. The old test immediately moved the
Activity through CREATED/RESUMED and announced a biometric prompt without
observing the Android system prompt. This was a test navigation and
synchronization error, not evidence of a product biometric failure.

The test now exits the Settings gate with the actual Back control, checks that
the Settings authentication screen is gone, waits for a new lock presentation,
and verifies the Android system prompt through the active accessibility window
before announcing a fingerprint injection point. The isolated biometric test
checks normal success, failed fingerprint, cancellation, manual PIN fallback,
wipe journal `NONE`, unchanged emergency configuration, and unchanged owned
Keystore aliases. Production biometric and Safe Exit code were not changed.

On the user's disposable `Pixel_10_Pro_XL` API 37 x86_64 AVD, every ADB command
used `-s emulator-5554`. The supported injection commands were:

```text
adb -s emulator-5554 emu finger remove
adb -s emulator-5554 emu finger touch 1
adb -s emulator-5554 emu finger touch 2
```

Finger 1 was enrolled in Android with a synthetic device PIN; finger 2 was an
unenrolled failure. The AVD's fingerprint service recorded accepted and rejected
scans. No physical phone was used.

## Validation performed

- Strict debug/test build passed. The attachment lifecycle and private retained
  probe regression tests passed previously on `emulator-5554`: 2 passed.
- Strict signed actual-release/test builds passed with temporary local activation.
- Actual release APK and test APK installed on the disposable AVD.
- Isolated actual-release biometric stage: 1 passed, 2 intentionally skipped by
  stage guard. Successful biometric unlocked normally; failed scan and cancel
  stayed locked; normal PIN fallback unlocked. The wipe journal remained `NONE`
  and owned Keystore aliases remained present.
- Full actual-release stage passed `normal_wrong_near_match`,
  `settings_dual_credentials`, and `biometric` checkpoints. Biometric enrollment
  and unlock prompts were positively observed before host injection.
- The full stage then timed out after the biometric checkpoint and before its
  `offline_wipe_old_state_probes_zero_network` checkpoint. During the wait, a
  live emulator UI inspection showed `Authenticate to open Settings` rather
  than the app lock PIN screen. The generic 60-second timeout stack does not
  identify which later wait failed. An attempted capture after instrumentation
  completed showed the launcher, so it was discarded rather than presented as
  failure evidence. The exact Safe Exit PIN entry and destructive result are
  therefore **unproven**; this is not evidence of a successful wipe or a proven
  production destruction defect. Log: `.research/1k4d-full-candidate-run.log`.
- Full-stage result: 1 failed, 2 intentionally skipped by stage guard. The
  copied SQLCipher DB, Signal identity, device-auth, retained attachment,
  original attachment cleanup, process death/reboot, critical/noncritical
  failure, zero-network, fresh onboarding and reconnect gates were not reached
  in this candidate. Prior scoped test passes do not substitute for those gates.

Temporary release activation was reverted. Final local source values: release
arming **FALSE**, release destructive readiness **FALSE**. Candidate APKs were
uninstalled from `emulator-5554` and local activated APK outputs removed. No
commit/push or release activation occurred. No production biometric code,
production storage logic, backend, server DB, Android DB schema, VPS, Windows
virtualization setting or physical phone was changed. No backend deployment or
database migration is required for the local fixture work.

## Phase 1K.4E acceptance-harness follow-up — stopped

The release acceptance harness now classifies normal app unlock, Settings entry,
and Safe Exit administration separately using existing UI semantics. It checks
the current Activity category, Chats/navigation category, lock state, Settings
authorization, wipe journal, and system biometric prompt without logging any
credential or account information. The harness explicitly selects the Chat tab
after Settings tests and requires a fresh app-lock presentation with Settings
authorization absent and journal `NONE` before exact Safe Exit PIN entry. A
separate `safeExitReleaseStage=wipe` runs the populated offline destruction path
without inheriting the Settings/biometric sequence.

Two harness assumptions were wrong. The Chats heading is replaced by the unread
count when there are unread messages, so screen recognition must use the Chat
navigation and composer actions. Also `NetworkController.syncActive` describes
queued/delivery work and can remain true with offline recipients; it is not an
in-flight FETCH signal, so waiting for it to become false deadlocked the test.
These were acceptance-only changes; production authentication, Safe Exit,
storage, and navigation code were not changed. The earlier Settings-auth return
is consistent with the restored protected route after configuration invalidates
Settings authorization, but an exact post-biometric reproduction remains unproven
because the full sequence has not been rerun.

Strict dependency-verified actual release/test candidate builds passed. The
first isolated wipe attempt timed out before destruction because of the unread
heading assumption. The second reached and positively checked a normal unlocked
Chats route, then a new app-lock screen with Settings authorization false and
journal `NONE`, but timed out on the obsolete `syncActive` wait. That wait has
been removed and the revised release test APK compiled successfully. Before it
could be rerun, the disposable AVD disappeared from ADB. A restart attempt of
`Pixel_10_Pro_XL` (API 37, x86_64) with hardware acceleration failed with
`WHPX: Failed to setup partition, hr=80070005` and `failed to initialize WHPX:
Invalid argument`; `emulator-check.exe accel` still reported WHPX usable. The
initial sandboxed restart also could not write the emulator's existing feature
flag lock file; a permitted restart advanced past that and hit the WHPX error.
No physical phone was targeted, and Windows virtualization settings were not
modified.

The corrected isolated destructive E2E, full release suite, copied DB, Signal,
device-auth, retained attachment, process-death/reboot, failure injection,
zero-network, fresh onboarding, and reconnect gates remain **unverified**.
Temporary release flags were reverted to `EMERGENCY_PIN_ARMING_ENABLED=false` and
`EMERGENCY_WIPE_DESTRUCTIVE_READY=false`; activated APK outputs were removed.
There is no commit or push and release destruction remains disabled. A future
run must start by restoring the disposable AVD and rebuilding an isolated
temporary candidate; it must not treat prior scoped debug or biometric passes
as acceptance of the remaining gates.

### Host emulator recovery (later on 2026-10-03)

The Windows `HypervisorPlatform` optional feature was enabled with no automatic
reboot, then Windows was restarted at the user's request. After restart, the
existing `Pixel_10_Pro` API 36 x86_64 AVD completed boot under WHPX and remained
connected as `emulator-5554` during a follow-up stability check. Its data and
configuration were not wiped or edited by this recovery. This restores the
emulator as a test target but does **not** satisfy any pending Phase 1K.4E
release acceptance gate; both committed release flags remain false.

### Phase 1K.4E resumed actual-release candidate (2026-10-03)

The temporary candidate enabled release arming and destructive readiness only
locally, used `https://fixture.invalid` as a deliberately unreachable release
origin, and built the release app and release instrumentation APK under strict
dependency verification. Only `adb -s emulator-5554` was used. The current
`Pixel_10_Pro` AVD is API 36.1 / x86_64; the physical device was untouched.

The isolated actual-release `wipe` stage passed after asserting an ordinary
Chats route, a new app-lock presentation with `AuthPurpose.APP_UNLOCK`, Settings
authorization false, and wipe journal `NONE`. It observed destruction start and
passed offline copied-DB, Signal identity, device-auth, retained ciphertext,
attachment-cleanup, and zero-network probes. The separate `post` stage passed
after an emulator reboot, including fresh onboarding and no silent old-account
reconnect. Synthetic fingerprint enrollment allowed the isolated biometric
stage to pass success, failed/cancelled prompt, PIN fallback, and unchanged wipe
journal. The full pre-reboot stage passed normal/wrong/near-match PIN, Settings,
biometric, exact Safe Exit PIN, and offline wipe checks. Each instrumentation
stage reported one executed test and two intentional stage-guard skips, so
`OK (3 tests)` is not three executed acceptance tests.

During the subsequent full-flow reboot, the emulator process terminated before
the `post` stage. Windows Application Event 1000 recorded
`qemu-system-x86_64.exe` faulting in NVIDIA `nvoglv64.dll` version
`32.0.16.1692` with exception `0xc0000409`. A backup of the AVD configuration
was made, and only `hw.gpu.mode` was changed from `auto` to `software`. A direct
hardware-accelerated launch still failed before Android boot with
`WHPX: Failed to setup partition, hr=80070005`; `emulator-check.exe accel`
continued to report WHPX installed and usable. The necessary CPU-emulation
fallback connected as ADB `offline` but did not boot. No Windows virtualization
settings were changed in this attempt.

The full post-reboot stage, critical/noncritical failure-injection matrix, and
final rerun after permanent release activation remain **unverified**. Therefore
release arming and destructive readiness were restored to **FALSE** in local
source; no activation commit or push was made. The temporary activated APK may
remain installed in the disposable AVD's data image until that AVD is started
again; do not use it for real accounts. This is an infrastructure blocker, not
evidence of a failed product security assertion. No production auth, Safe Exit,
crypto, storage, backend, or database migration code was changed.

## Phase 1K.4E completed on a fresh disposable AVD

The previous infrastructure stop is superseded by this run. A fresh
`GhostCloak_Disposable_API36` Pixel 6 / API 36 / x86_64 AVD booted as
`emulator-5556` under WHPX with SwiftShader. Both the original and fresh AVD
initially failed WHPX partition setup (`0x80070005`) while another game was
running; after the user closed it, the fresh AVD booted and stayed stable across
repeated reboots. This correlation does not prove the game's process caused the
WHPX failure. No Windows feature was changed during this run. Every ADB command
explicitly targeted `emulator-5556`; the connected physical phone was untouched.

The previously failing full sequence returned to Settings authentication because
changing App Lock to COMBINED invalidated its memory-only Settings authorization
while the protected Settings route was still selected. The acceptance harness now
selects the Chat tab, verifies an ordinary Chats screen, backgrounds and resumes,
and verifies `APP_UNLOCK` rather than `SETTINGS_ENTRY` or `SAFE_EXIT_ADMIN` before
entering the destructive PIN. Its diagnostics contain only Activity, navigation,
auth-purpose, lock, Settings-authorization, journal and system-prompt categories.
No production authentication or Safe Exit code changed.

The temporary activated actual-release APK used the unreachable
`https://fixture.invalid` origin and the existing application/runtime with a
socket-free test transport. The release and test APKs were signed with the local
debug certificate **only for this disposable AVD** using
`release-acceptance.init.gradle`; distribution signing was not changed. The
isolated wipe, isolated biometric, full release run, and post-reboot runs all
passed. The test-only inline journal observer recorded the actual release wipe
in this order:

`NONE -> ARMED -> QUIESCING -> KEY_DESTRUCTION_PENDING ->
KEY_DESTRUCTION_COMPLETE -> STORAGE_CLEANUP_PENDING -> FINALIZING -> COMPLETE ->
NONE`.

The isolated and full release runs each verified populated identity and
messages, exact Safe Exit PIN only on the ordinary app-lock surface, offline
destruction, fresh onboarding, zero fixture-network requests during destruction,
and rejection of a copied SQLCipher DB, old Signal store, old device-auth key,
and retained encrypted attachment. Original attachment upload caches and
plaintext scratch were absent afterward. The full run also passed normal PIN,
wrong/near-match PIN, protected Settings and Safe Exit administration,
biometric success, and clean exit from Settings. The isolated biometric test
passed success, failed scan, cancellation, manual PIN fallback and journal
`NONE`. After emulator reboots, the post stages confirmed fresh onboarding,
reconnected network, and no silent old-account restoration.

Additional gates passed on the same AVD: 10/10 Android destruction/failure
injection tests (including critical Keystore failure, noncritical cleanup
failure, retained-artifact checks and process/runtime restart) and both stages
of the opt-in reboot test. Strict dependency-verified JVM tests passed 316/316
after the old disabled-feature assertions were updated for the activated build.
The full debug Android suite passed 171 tests with four intentional guarded
skips, zero failures (`OK (175 tests)`). A 420-dpi emulator exposed a
sub-dp keyboard-coordinate assertion rounding error; its test now tolerates
one dp while retaining the double-inset regression check.

Only after these gates passed, release arming and destructive readiness were
left `true` in source. A forced strict dependency-verified rebuild executed all
137 release app/test build tasks. The rebuilt actual-release full run passed,
again recording every journal state above; its reboot/post stage also passed.
The release instrumentation reports `OK (3 tests)` per stage because it counts
two stage-guard skips and one executed test. The Android suite's four guarded
skips are separate opt-in probes; their relevant reboot/failure paths were run
explicitly as described above.

No backend deployment, server database migration, Android database migration,
VPS connection, or physical-device test is needed for this activation. Before
rolling back an activated build, retain a tested path for devices already
inside a durable Safe Exit operation; merely disabling release flags in a new
build must not be relied upon to undo a wipe already armed or started.

The exact staged commit was also exported without unrelated working-tree edits
and built with main's pinned Gradle 9.7.1 / AGP 9.4.0 and strict dependency
verification: 316 JVM tests passed, debug and ordinary release builds passed,
and the actual-release acceptance APK compiled. That staged release APK was
installed on `emulator-5556`; its full wipe run and post-reboot/reconnect run
both passed, including the complete journal progression above. The final
activated-flag debug Android suite separately completed with 171 executed tests,
four intentional guarded skips and zero failures.
