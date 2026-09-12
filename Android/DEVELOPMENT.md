# Android Studio development

For the Phase 1I.1 design-only attachment/voice-note proposal, current limits, implementation gates and future two-phone test matrix, see [ATTACHMENTS_DESIGN.md](ATTACHMENTS_DESIGN.md). Upload, download and recording are not implemented by this documentation phase.

For the proposed background-delivery architecture, platform limitations and future physical-device test matrix, see [BACKGROUND_SYNC_DESIGN.md](BACKGROUND_SYNC_DESIGN.md). Phase 1F.1 implements background FETCH/STORE/ACK; Phase 1F.2 adds optional generic local notifications. Phase 1G.1 adds an optional local UI lock; auth-bound storage remains proposed.

## Run staging on a phone

1. Pull the latest `main` source and open the repository's **Android/** directory in Android Studio.
2. Let Gradle sync finish. Use the **app** run configuration and **debug** build variant (the normal development default).
3. Enable USB debugging on the test phone, connect it and accept Android's debugging authorization prompt. Wireless debugging also works if already paired.
4. Select that physical device in Android Studio's device selector.
5. Click **Run app** or **Debug app**. Android Studio builds, installs and launches the app normally.

Debug builds automatically set `BuildConfig.API_ORIGIN` to `https://api.ghostcloak.org`. No Gradle property, manual APK copy or manual installation is required. Use the [two-phone staging guide](TWO_PHONE_STAGING_TEST.md) for message requests, sends and automatic foreground sync. Run updates normally; do not uninstall or clear app data to reconnect, since that removes the local identity/history. The existing debug developer tools remain available.

## Debug network Logcat

Run the **debug** app on the physical phone from Android Studio. Open **View → Tool Windows → Logcat**, select that phone and the Ghost Cloak process, and filter with `tag:GhostCloakNet` (optionally `package:org.ghostcloak.app tag:GhostCloakNet`). Keep the app foregrounded and capture roughly 30 seconds before the warning through automatic recovery; do not press Sync during the observation. Repeat independently on the other phone. Filtered entries contain sanitized operation/timing/status metadata only; release does not emit them. See [network diagnostic findings](NETWORK_DIAGNOSTICS.md) for timeout, status and polling-load analysis.

Example shape (illustrative timing, not a captured failure):

```text
SYNC START cycle=42 elapsed=0ms
FETCH START elapsed=0ms
FETCH TRANSPORT_FAILURE elapsed=15007ms exception=java.net.SocketTimeoutException
FETCH END elapsed=15007ms
FETCH API_FAILURE elapsed=15007ms apiStatus=503 apiCode=network_unavailable
status SYNCING -> OFFLINE
SYNC END cycle=42 elapsed=15010ms
SYNC START cycle=43 elapsed=0ms
status OFFLINE -> SYNCING
FETCH START elapsed=0ms
FETCH HTTP elapsed=214ms http=200
FETCH END elapsed=215ms http=200
status SYNCING -> CONNECTED
SYNC END cycle=43 elapsed=220ms
```

## Appearance

Open **Settings → Appearance** and choose **Light**, **Dark**, or **Automatic**. The choice applies immediately and persists across app restarts. Automatic follows the phone's theme and is the default. Theme colors, preference handling and screen components remain in separate files; see [design notes](DESIGN.md).

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


## Foreground synchronization (Phase 1E.4)

Opening/resuming the app starts an immediate sync. Active or pending-delivery polling waits 2 seconds after each completed cycle; idle polling gradually relaxes to 3–5 seconds. Backgrounding stops polling. The combined inbox/receipt Fetch normally uses one request per cycle. Rate limiting preserves the session and waits for Retry-After or bounded backoff automatically; manual Sync also respects that cooldown.

In Android Studio Logcat, select the debug app/device and filter `tag:GhostCloakNet`. Normal cycles should show one FETCH and no separate RECEIPT_STATUS request. ACKs and pagination are expected when receiving messages. See [network diagnostics](NETWORK_DIAGNOSTICS.md) for sanitized examples, request budget and retention fallback. These diagnostics are disabled in release builds.


## Background synchronization (Phase 1F.1)

After an existing account connects, Android schedules one ordinary periodic check: 30 minutes with 15 minutes of flex and a connected-network requirement. The first check is delayed 30 minutes. Pull, open Android/ and Run as usual; no separate service or manual APK install is needed. Phase 1F.2 adds an optional notification permission action in Settings; there is no automatic prompt. WorkManager brings ACCESS_NETWORK_STATE, WAKE_LOCK and RECEIVE_BOOT_COMPLETED for scheduling; no foreground service, exact alarm or push provider is used.

Background checks reuse the application runtime, existing identity and encrypted mailbox acceptance/ACK path. They do not display action-error banners. Foreground polling still stops on backgrounding and retains its existing cadence on resume. Logout cancels scheduled work; reopening after logout never reconnects automatically. Before the first unlock after reboot, no encrypted state is accessed. Force-stop prevents work until Android permits execution following user interaction. Doze/OEM restrictions may delay checks for hours; no instant background-delivery promise is made.

Debug Logcat filter: `tag:GhostCloakBg`. Events are only WORK_START, WORK_SKIP, WORK_SUCCESS, WORK_RETRY and WORK_STOP. They contain no reason strings, IDs, URLs, bodies or credentials. Release emits none. `GhostCloakNet` remains the existing sanitized debug network trace. Do not enable general WorkManager verbose logging.

A worker uses a cooperative 30-second cycle budget and four FETCH-attempt budget. Retry starts at 15 minutes and respects the encrypted rate-limit checkpoint. Same-boot cooldowns use elapsed time; reboot conservatively restarts the last saved remaining duration. No wall-clock jump bypasses it. The proposed extra jitter, background enable controls and auth-bound storage are deferred.

Tests: run JVM `test`, plus `:app:connectedDebugAndroidTest :storage:connectedDebugAndroidTest` on an explicitly selected test emulator, all with `--dependency-verification strict`. BackgroundSyncTest exercises real encrypted endpoints over an isolated synthetic transport, unique scheduling policy, foreground coalescing, logout/reopen, renewal, cooldown, ACK/deduplication, cancellation, budgets and operation with notification publication disabled. These deterministic tests do not establish OEM scheduling latency; see the physical test plan in BACKGROUND_SYNC_DESIGN.md.


## Private local notifications (Phase 1F.2)

Use Android Studio → select the physical device → Run app as usual. In Ghost Cloak Settings, Notifications explains the optional permission. On Android 13+, **Enable notifications** requests it only after your tap. After one request, use **Notification settings** to change Android permission/channel settings. Denial does not disable messaging or background synchronization.

Only successfully accepted, decrypted and encrypted-store-committed incoming messages become eligible. One aggregate shows **Ghost Cloak / New message**, never a sender, preview, count or identifier. SECRET lock-screen visibility and disabled channel badges are defaults; Android/OEM/user settings can override presentation. OS history/listeners can observe generic content and timing. There is no external push provider. WorkManager remains best effort (30-minute interval, 15-minute flex), so notifications are not instant.

All foreground screens rely on in-app unread indicators; they suppress system alerts. Dismissal does not mark messages read. A tap opens the ordinary app root/Chats, with no conversation identifier. With Phase 1G.1 app lock enabled, the root requires unlock before showing Chats. An encrypted ledger survives process death after ACK; an uncertain publication recovers silently to avoid another sound. Notification failures do not change delivery or ACK state. Explicit logout cancels alerts and automatic reconnection eligibility.

### Two-phone validation

1. Install the same staging debug build on Phones A and B using Android Studio Run. Connect existing accounts and enable notifications on B through Settings.
2. Background B normally (do not force-stop). Send a synthetic message from A. Wait for a normal WorkManager opportunity; Doze/OEM restrictions may delay it well beyond 30 minutes.
3. B should show one **Ghost Cloak / New message** notification, with no sender, body, count or avatar. Check the locked screen and system shade; default SECRET visibility hides it on the locked screen.
4. Send more messages while B remains backgrounded. After later delivery, confirm one generic aggregate, without per-contact entries. Swipe it away, then open B: unread indicators should remain until the conversation is opened.
5. Repeat with a notification tap: B opens normally to Chats; the message is stored and readable after selecting the conversation. A eventually shows Delivered through its existing receipt sync.
6. Deny B's notification permission in Android settings. Background B and send again. No notification should appear. Open B later and verify the message is present and A eventually shows Delivered; denial must not block FETCH/STORE/ACK.
7. With B's conversation open, send from A. Confirm the message appears through foreground sync without a redundant system alert. Repeat with B on another foreground screen: use its unread indication.
8. Log out B explicitly, background it and confirm it does not reconnect or notify until explicit connection.

Automated coverage: NotificationLedgerTest checks real decrypt/commit rollback, deduplication and read/block/delete pruning; LocalNotificationTest checks runtime publication, denied permission, crash recovery, dismissal, foreground suppression, immutable root intent and generic platform payload. Existing lifecycle, renewal, cooldown and background delivery suites must also pass. Automated emulator tests do not establish two-phone/OEM timing; run the physical checklist separately.

Phase 1F.2 automated validation (2026-09-12): 81 JVM tests across test-support and debug/release app variants, 10 isolated PostgreSQL tests, and 72 Android emulator tests (62 app, 10 storage) passed. Debug/release assemblies and IDE source/Javadoc/sample resolution passed with strict dependency verification. The dedicated API-37 test emulator was used; the two-phone checklist above still needs physical-device validation.


## App lock (Phase 1G.1)

Pull and use Android Studio → select device → Run. Open Settings → Privacy → App lock. The default is Off. Choose PIN (6–64 digits, entered twice), strong Biometric, or Biometric + PIN fallback, and Immediately / 30 seconds / 1 minute / 5 minutes. Biometric modes require a successful system prompt before saving. Only strong enrolled biometrics are accepted; Android device PIN/password is not a fallback. If unavailable, enroll a supported biometric in Android settings or choose the local PIN mode.

Changing/disabling lock requires confirming the currently configured method. A successful configured biometric permits replacement of a forgotten PIN. There is no email/SMS/server recovery. Without a working configured method, remain locked; Android Clear storage/uninstall destroys local data and may lose account/device identity and history. The app never silently resets keys or identity. Logging out retains app-lock configuration and unlocking never reconnects a logged-out account.

This is **UI/app-access lock**, not biometric-bound storage: background WorkManager still decrypts/stores/ACKs using the unchanged encrypted database and Keystore. Notifications remain Ghost Cloak / New message with no sender/body/count. Background scheduling and foreground cadence are unchanged. The private foreground loop is absent while the root is locked; normal foreground synchronization resumes after unlock. Phase 1G.2 Maximum Security Mode is a future storage-gating project.

Global FLAG_SECURE protects Ghost Cloak content even with lock Off; screenshots/recordings and recent-app previews should be blank/protected on supported Android implementations. This changes developer screenshot workflows: use synthetic Compose test rendering, not disabling the production flag. External cameras and compromised/OEM systems remain outside this guarantee.

### Physical validation checklist

- Confirm an existing install stays Off. Enroll each mode on a supported physical phone. Verify biometric success, cancellation, non-match, temporary lockout, hardware unavailability and PIN fallback. No cancellation should unlock or spam errors.
- Verify wrong PINs are rejected and delays increase after repeated failures. Kill/relaunch during a cooldown; it must remain enforced. Restart/reboot never restores an unlocked grant. Use synthetic test PINs, never put a real PIN or verifier in logs/reports.
- Test immediate, 30-second, one-minute and five-minute modes on each side of the deadline. Rotate while unlocked and during authentication. Background during PIN verification/system prompt, then return; stale success must not unlock. No private frame should appear before unlock on cold start or resume.
- With B locked and backgrounded, send from A. Wait for WorkManager. B should receive only the generic notification, and A should eventually show Delivered. Tap B's notification: lock screen first, then Chats after unlock. Verify the message exists; no new identity or login should result from unlocking. Repeat with notification permission denied.
- Inspect Recents and attempt screenshots/screen recording with lock Off, enabled/unlocked, grace period, locked, and during enrollment. Check OEM behavior and accessibility semantics. No sender, profile, network details or unread count belongs on the lock screen.
- Log out, then lock/unlock/restart: app access can unlock but network must remain logged out. Verify biometric-authenticated PIN replacement and that no recovery/reset is offered when all methods are unavailable.

Automated tests cover verifier/KDF configuration, rollback-safe persisted throttling, lifecycle deadlines and stale callbacks, encrypted runtime recreation, background acceptance/ACK/notifications while locked, identity/session preservation, absent private composition and MainActivity FLAG_SECURE across recreation. Biometric callbacks are simulated in state-machine tests; real sensor quality, prompt behavior, OEM screenshots/Recents and physical KDF latency still require the checklist above. No biometric-bound SQLCipher key or app-provided destructive reset is implemented.

Phase 1G.1 automated validation (2026-09-12): 99 JVM tests (65 test-support, 17 per debug/release app variant), 10 isolated PostgreSQL tests and 76 API-37 emulator tests (66 app, 10 storage) passed. Strict dependency verification covered assemblies and IDE sources/Javadocs/samples; all 37 added artifacts were compared with fresh publisher downloads. Debug and release builds passed. The existing concurrent session-renewal test now explicitly orders the expired SEND before competing FETCH so its retry-byte assertion is deterministic. Physical biometric sensors, OEM privacy behavior and timing remain manual validation items.


### Automatic biometric prompt

With Biometric or Biometric + PIN fallback enabled, a locked cold start, expired-timeout resume or notification launch automatically presents the strong-biometric prompt once the Activity is resumed and ready. Returning within the unlock grace period does not prompt. Cancel/error/non-match never causes an automatic reopen; use the manual biometric button or configured PIN. A non-match closes the prompt and leaves the lock screen available for manual retry. Leaving and returning creates a new presentation; rotation, theme changes and recomposition do not. Rotation cancels an in-flight attempt and leaves manual retry available. Unavailable biometrics consume that presentation's automatic opportunity without looping; PIN fallback/recovery guidance remains available. PIN-only mode focuses its entry without starting biometrics. Enrollment and app-lock management still require explicit user actions.

The attempt flag is process-local and is never a biometric-success cache. Process recreation starts locked and gets one new automatic attempt. Notification navigation still waits behind the root gate and continues to Chats after successful unlock. Background synchronization, notification content, PIN throttling and identity/session behavior are unchanged.

Automatic-prompt validation (2026-09-12): 105 JVM tests and 77 emulator tests (67 app, 10 storage) passed, including existing app-lock, Phase 1E and Phase 1F coverage. Debug/release builds and emulator tests passed with strict dependency verification. The foreground-sync fixture now awaits cancelled ViewModel jobs before closing its databases. Automatic prompt orchestration and callbacks are tested with simulated biometric results; physical sensor presentation remains a manual device check.

## Phase 1H.1 — testing local cleanup

Delete means LOCAL DEVICE ONLY: it does not recall, unsend, notify the recipient, delete remotely or alter another participant's copy. Long-press a message → Delete → confirm. Conversation options → Clear conversation → confirm removes history and keeps the contact. Cancel must leave content unchanged. Pending outgoing sends can still finish delivery; this is not an unsend feature.

On two test devices, delete an unread incoming message and check the local badge/count; clear one conversation and check other conversations remain. Send a queued message, delete its local display, then let the recipient sync: delivery should continue. The receiver's history stays intact when the sender deletes a delivered message. Check clear preserves request/accepted, verification and block state. Deletion requires the app to be unlocked and cannot be triggered by a notification intent.

Automated coverage exercises local unread/notification cleanup, all three notification ledger states, transaction rollback, encrypted-store reopen, acceptance-hash replay rejection, identity/session preservation, pending-send completion without history resurrection, unrelated receipt progress, aggregate notification cancellation, UI confirmations and root-lock isolation. Existing storage, Phase 1E/1F and app-lock regression suites remain part of validation. No schema migration or dependency change is needed.

Phase 1H.1 validation (2026-09-12): 108 JVM tests and 81 API-37 emulator tests (71 app, 10 storage) passed. Debug and release builds passed. All Gradle validation ran with strict dependency verification. No physical-device result is claimed for this slice.

## Phase 1H.2 — disappearing-message development checks

Upgrade both devices to a build supporting Phase 1H.2 before testing. Legacy text is readable as Off; older clients cannot decode the new authenticated application framing. No timer is sent outside ciphertext. The maximum framed text body is now 16,368 UTF-8 bytes; Signal/envelope limits are unchanged.

In an accepted conversation, open Conversation options → Disappearing messages and select Off, 30 seconds, 5 minutes, 1 hour, 1 day or 1 week. The selection applies locally immediately and sends an encrypted policy update. The peer sees a distinct system row without a new-message alert or unread increment. Test Off propagation and nearly simultaneous changes; each device uses its last locally processed authenticated policy event. Existing messages retain their own duration. Clear keeps the policy but removes history/system rows.

For a 30-second message, the sender counts from observing the recipient delivery ACK and the receiver from decrypt/commit, not reading. Leave the recipient offline beyond the timer and restart the sender: Pending and Queued on server must remain without a countdown. Reconnect the recipient: its commit starts its timer; the sender starts its own timer only when it observes Delivered. Repeat polling/restart to verify that deadline is preserved. A pending policy row explains that it applies locally while retrying; permanent outbox failure requires selecting the timer again. Initial controls do not bypass an unaccepted request: send a normal message and explicitly accept before the receiver applies a deferred control.

Test expiry while on Chats, in a conversation, locked and backgrounded; repeat after process recreation and reboot. Expired previews/body text must not flash on unlock even when cleanup was delayed. Verify aggregate notifications disappear when their last eligible message expires, and expired messages do not produce a delayed permission-grant notification. Background deletion is best-effort when Android grants execution, not an exact alarm. Existing account logout does not stop local expiration or silently reconnect.

Within a boot, elapsed deadlines survive process recreation and wall-clock rollback. Forward wall changes also expire content, and observed expiry cannot be undone by moving time back. Reboot plus manual wall rollback remains a documented limitation. Local enforcement does not prevent a modified recipient/device from keeping copies, nor securely erase NAND/flash, memory or external backups.

Automated tests cover payload validation/rollback, every timer, authenticated racing updates, atomic outbox/policy enqueue, delayed delivery ACK, per-message immutable durations, duplicate expiry/replay, monotonic/forward/reboot clocks, encrypted-store recreation, request gates, notification suppression/cancellation, UI timer selection/system rows and filtering stale expired snapshots. Existing Phase 1E/1F/1G/1H.1 suites remain regression coverage.

Phase 1H.2 validation (2026-09-12): 115 JVM tests and 85 API-37 emulator tests (75 app, 10 storage) passed, including delayed-cleanup unlock and offline/logout expiration coverage. Debug/release builds and all Gradle validation passed with strict dependency verification. No backend or dependency update was required. Physical-device Doze/OEM timing and two-phone upgrade checks remain manual validation; no physical-device result is claimed.


Delivery-ACK correction: existing queued outgoing deadlines from the earlier build are cleared before cleanup. Already-delivered deadlines are preserved; previously deleted records cannot be restored. Queued messages retain their original encrypted duration across policy changes. If the recipient never receives the message or ACK cannot be observed within existing receipt-retention limits, the sender's timer never starts. Local Delete remains available and is not unsend.

Delivery-ACK correction validation: 117 JVM tests and 87 API-37 emulator tests (77 app, 10 storage) passed. Debug/release builds and tests passed with strict dependency verification. Coverage includes a recipient offline for two hours, queued and delivered encrypted-store restart, repeated ACK idempotence, original duration after policy change, and legacy queued-deadline cleanup. Physical-device timing was not rerun in this change.
