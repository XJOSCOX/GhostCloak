# Android Studio development

For the proposed background-delivery architecture, platform limitations and future physical-device test matrix, see [BACKGROUND_SYNC_DESIGN.md](BACKGROUND_SYNC_DESIGN.md). Phase 1F.1 implements background FETCH/STORE/ACK; Phase 1F.2 adds optional generic local notifications. App lock remains proposed.

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

A worker uses a cooperative 30-second cycle budget and four FETCH-attempt budget. Retry starts at 15 minutes and respects the encrypted rate-limit checkpoint. Same-boot cooldowns use elapsed time; reboot conservatively restarts the last saved remaining duration. No wall-clock jump bypasses it. The proposed extra jitter, background enable controls and app lock are deferred.

Tests: run JVM `test`, plus `:app:connectedDebugAndroidTest :storage:connectedDebugAndroidTest` on an explicitly selected test emulator, all with `--dependency-verification strict`. BackgroundSyncTest exercises real encrypted endpoints over an isolated synthetic transport, unique scheduling policy, foreground coalescing, logout/reopen, renewal, cooldown, ACK/deduplication, cancellation, budgets and operation with notification publication disabled. These deterministic tests do not establish OEM scheduling latency; see the physical test plan in BACKGROUND_SYNC_DESIGN.md.


## Private local notifications (Phase 1F.2)

Use Android Studio → select the physical device → Run app as usual. In Ghost Cloak Settings, Notifications explains the optional permission. On Android 13+, **Enable notifications** requests it only after your tap. After one request, use **Notification settings** to change Android permission/channel settings. Denial does not disable messaging or background synchronization.

Only successfully accepted, decrypted and encrypted-store-committed incoming messages become eligible. One aggregate shows **Ghost Cloak / New message**, never a sender, preview, count or identifier. SECRET lock-screen visibility and disabled channel badges are defaults; Android/OEM/user settings can override presentation. OS history/listeners can observe generic content and timing. There is no external push provider. WorkManager remains best effort (30-minute interval, 15-minute flex), so notifications are not instant.

All foreground screens rely on in-app unread indicators; they suppress system alerts. Dismissal does not mark messages read. A tap opens the ordinary app root/Chats, with no conversation identifier. App lock is not implemented yet. An encrypted ledger survives process death after ACK; an uncertain publication recovers silently to avoid another sound. Notification failures do not change delivery or ACK state. Explicit logout cancels alerts and automatic reconnection eligibility.

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
