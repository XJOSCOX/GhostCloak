# Phase 1M — Inactive Device Protection: blocked security design

Status: **not implemented, not enabled, and not offered in Settings**. The proposed
user-facing name is **Inactive Device Protection** and the requested choices are
Off (default), 7, 14, 30, 60, and 90 days. This is a stop-condition finding, not
an activation specification. Audit baseline: `d65b3bf` (2026-10-03).

## Threat model and blocking ambiguity

The protected event is the absence of a *successful normal Ghost Cloak unlock*.
Correct App Lock PIN or successful strong biometric unlock would refresh the
reference only after its durable write. Wrong PIN, cancelled/failed biometric,
background sync, message/attachment handling, notifications, process start and
mere foreground presence must not refresh it. App Lock OFF cannot supply this
proof, so enrollment must require App Lock and disabling App Lock must first
require disabling protection. Settings access alone is not a substitute for a
fresh, action-specific authentication.

The requested local-only, offline, cross-reboot deadline cannot be established
from the Android clocks currently available to an ordinary app. Consider a
durable record last refreshed at day 0 with a 30-day period. After one reboot,
the app sees the same record, one increased boot count, near-zero
`elapsedRealtime()`, and wall time day 1 in either of these histories:

1. The device was off for one day and its clock is correct. It must retain the
   account and show normal App Lock.
2. The device was off for 31 days and its clock was set back to day 1 before
   boot. It must arm Safe Exit before any normal state opens.

The observations are identical; no local algorithm can choose both outcomes.
Persisting the highest wall-clock reading detects rollback **below a previously
observed value**, but not rollback to a plausible time *above* it while powered
off. Repeated offline reboot/rollback can therefore extend the effective
deadline indefinitely. Charging an arbitrary minimum time for each reboot risks
premature irreversible destruction of a legitimate device and still cannot
determine how long a single shutdown lasted.

The forward case is symmetric: one reboot with wall time five years later can
mean five real years offline (destruction required) or a clock accidentally set
five years ahead (the request says not to destroy solely for that). A protected
clock-anomaly screen could prevent disclosure, but it cannot prove which case
occurred or fulfill the automatic-expiry promise. Letting normal unlock simply
refresh the reference in that screen could defeat an already-expired deadline.

Android documents `elapsedRealtime()` as monotonic *since boot* and the
network-time clock as unavailable without a recent network synchronization; its
own documentation says that network time is not suitable as a security source.
Android Keystore's wall-clock expiry checks are software-enforced because wall
time comes from the non-secure world. These do not provide an app-accessible,
trusted offline clock that measures powered-off time across arbitrary reboot.

Primary references: [Android SystemClock API](https://developer.android.com/reference/android/os/SystemClock),
[Android Keystore features](https://source.android.com/docs/security/features/keystore/features),
[KeyMint rollback-resistance scope](https://source.android.com/docs/security/features/keystore/implementer-ref).
Key rollback resistance concerns restoring deleted keys, not the elapsed time
since the app's last authentication.

**Stop condition:** section 50 of the Phase 1M request forbids implementation if
reboot can silently extend the deadline indefinitely or wall-clock rollback can
trivially defeat it. Both occur under the required offline/local-only model.
No automatic Safe Exit should be armed based on a guessed cross-boot interval.

## Audited implementation boundaries

The existing Safe Exit path is suitable as the *only* destruction engine once a
deadline has been established unambiguously. `LocalOperationGate.armAfterCredential()`
durably writes `NONE -> ARMED` and immediately fences state; despite its name,
the method would need an explicitly reviewed authorization surface for expiry.
`GhostApplication` observes the journal and uses `SafeExitRecovery` to reconstruct
`LocalOperationCoordinator` after process death. The coordinator quiesces owners,
destroys owned Keystore entries, removes app-owned files, verifies the fresh
baseline, clears the journal, and returns to onboarding. Its Retry path already
re-reads the durable journal. A future implementation must call this same path,
without a second wipe engine or a persisted human-readable trigger reason.

The early gate currently reads only the Safe Exit journal. On journal `NONE`,
`GhostApplication.onCreate()` starts normal owners; `MainActivity.onStart()` may
start App Lock, media, and runtime visibility. Therefore a future inactivity
check must be synchronous and ordered *before* the normal-owner collector and
before Activity startup/resume access. Checking from Settings, a ViewModel, a
periodic worker, or after decrypting the app-lock record would be too late.
`AppRuntime.readAppLock()` currently opens the encrypted endpoint through the
normal runtime, so the future no-backup deadline record must be independently
readable by the early gate without constructing `AppRuntime` or Signal state.

On successful normal unlock, `AppLockController.verifyPin(..., UNLOCK)` and
`completeBiometric()` currently grant the in-memory unlock before returning.
Future code must durably refresh the inactivity reference **before** `grant`
exposes content; a failed write must fail closed. MANAGE/SETTINGS/ENROLL proof,
wrong PIN, or Safe Exit PIN must not extend the deadline. Existing failed-attempt
throttling must remain. App Lock configuration must reject turning lock OFF while
protection is enabled, even if Settings UI is bypassed.

Protection administration would require a fresh MANAGE normal PIN/strong
biometric grant, distinct from the existing three-minute Settings grant.
Enabling must also require explicit acknowledgement of permanent local loss.
Shortening requires fresh MANAGE proof. Extending or disabling requires fresh
MANAGE proof, the current Safe Exit PIN when one exists, and explicit disable
confirmation. If manual Safe Exit is OFF, fresh MANAGE proof is the strongest
available credential; ordinary already-unlocked access is insufficient. The
feature must not create a second Safe Exit PIN verifier. Current Safe Exit
administration already has a shared failed-attempt budget and a short-lived
current-PIN proof that can inform this design.

The future versioned record should contain only enabled state, selected period,
last successful-auth reference, high-water observations, and the minimum boot
reference needed for checking. It belongs under `noBackupFilesDir`, protected by
checked atomic writes and readback like the Safe Exit journal, with no account,
contact, name, key or server identifier. It must be deleted and verified absent
by Safe Exit cleanup. Existing `allowBackup=false`, backup rules, and device
transfer exclusions already exclude app-private state. No Android SQL migration,
backend deployment, server DB migration, Nginx/Cloudflare change, network event,
notification, analytics event or foreground service should be needed.

## Decision needed before implementation

To resume Phase 1M, choose an explicitly revised security contract with a
testable time source and a defined anomaly policy. Options include a
**best-effort local feature** that treats uncertain cross-boot time as a protected
state and openly does *not* promise automatic destruction at the true deadline,
or a **trusted external time policy** that changes the offline/no-server
requirements. Hardware-specific trusted-time support would need device/API
qualification and a conservative unsupported-device policy. None is silently
substituted here.

Once a revised contract is approved, required tests include exact-boundary and
near-boundary fake-clock cases; same-boot monotonic progress; reboot, rollback,
forward anomaly, and force-stop; PIN/biometric success and failure; admin
authentication; offline expiry; journal arming before any sensitive owner;
process-death/reboot recovery and Retry; fresh onboarding with no old Ghost Cloak
ID or secrets; and destructive acceptance only on a disposable AVD. Important
physical phones and the VPS remain outside automated validation.

This audit made no executable change. Existing `SafeExitRecoveryTest` ran with
strict dependency verification: 5 tests passed, 0 failed. No Phase 1M expiry,
destructive AVD, offline, force-stop, or reboot test can run because the feature
was stopped before implementation. No physical device or VPS was accessed.
