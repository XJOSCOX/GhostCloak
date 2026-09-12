# Phase 1F: private background delivery design

Status: Phase 1F.1 implements the first background FETCH/STORE/ACK slice (2026-09-12). The original architecture audit was against `d939b6e`; Phase 1F.2 adds maximum-privacy local notifications. App lock remains future work. Proposed options below are not delivery guarantees.

### Implemented in Phase 1F.1

- AndroidX WorkManager 2.11.2 (current stable per [official release notes](https://developer.android.com/jetpack/androidx/releases/work)), ordinary unique periodic work named `ghostcloak-background-sync`, 30-minute interval, 15-minute flex, connected-network constraint, initial delay 30 minutes. KEEP reconciliation avoids resetting the schedule on every resume. Retry uses exponential WorkManager backoff starting at 15 minutes; no expedited or one-shot work is added.
- Existing eligible registered connections automatically reconcile scheduling at process initialization and after runtime operations. Missing identity, logout, disabled origin and demo mode are ineligible. Worker checks first unlock before touching encrypted state and never creates a new endpoint. No separate background-enable UI is added in this slice.
- Worker obtains the application's AppRuntime. Its existing mutex serializes storage/network operations; lifecycle ownership and completion-generation checks suppress background checks during foreground polling and coalesce requests waiting on the same successful cycle. Foreground delays and immediate-resume intent remain unchanged; an in-flight cycle can satisfy the resume request.
- Existing NetworkController combined FETCH, decryption/commit, ACK, outbox, request and renewal paths are reused. Background cycles have a 30-second cooperative timeout and at most four FETCH attempts, including replays/pages. Backlog continues on later opportunities. Blocking platform IO can finish after timeout before ownership is released; this is not a hard wall-clock network deadline.
- FETCH cooldown/failure count now checkpoints in encrypted records per origin. Same-boot restoration uses elapsed time; across reboot it conservatively waits the saved remaining duration again. Wall-clock changes cannot shorten it. Successful FETCH clears it. Repeated-401 renewal blocking is also persisted; explicit connect clears the block, logout clears the session and cancels unique work.
- Worker never publishes ordinary errors to the ViewModel. Transient failures produce scheduler retry; permanent/security failures skip without wiping local state. Debug `GhostCloakBg` emits only the five fixed WORK events; release is a no-op. Library logging is disabled.
- Merged manifest adds ordinary WorkManager INTERNET-adjacent capabilities: ACCESS_NETWORK_STATE, WAKE_LOCK and RECEIVE_BOOT_COMPLETED (no runtime permission prompt). SystemJobService runs in the same process and is not Direct Boot aware. Alarm service/proxies, foreground service/permission and the library diagnostics receiver are removed. WorkManager's API-30+ scheduling uses JobScheduler; its internal pre-30 force-stop alarm fallback is outside this app's supported API range. Android stopped-state restrictions remain authoritative.

### Implemented in Phase 1F.2

- Accepted incoming network messages acquire an encrypted `NotificationLedger` marker in the existing decrypt/commit transaction with content and the acceptance hash. Failed validation/decryption/storage and duplicates do not enqueue eligibility. Existing ACK and delivery semantics are unchanged.
- The application-owned runtime reconciles eligibility after operations, including partially successful sync cycles, and on existing-runtime initialization. It prunes read/deleted/blocked entries under the same operation mutex. Nothing sensitive is placed in WorkManager or Android notification metadata.
- One channel (`ghostcloak-messages`, user-visible **Ghost Cloak messages**) and one aggregate notification (local ID 1) show only **Ghost Cloak / New message**. DEFAULT importance, SECRET visibility, no public version, badges disabled, no count, sender, preview, Person, shortcut, reply action or contact group. OS/framework metadata and any silent-update group are generic. Android/OEM/user settings can override presentation; notification history/listeners may retain the generic alert and its timing.
- Eligibility progresses PENDING → POSTING → ANNOUNCED. POSTING commits before OS publication. A crash after ACK leaves eligibility; a crash during publication recovers with a silent stable-ID update. Database and OS publication cannot be atomic: this deliberately favors a missed sound over a repeated sound. New arrivals quietly update an existing aggregate. ANNOUNCED entries never re-alert on duplicate delivery. Swipe dismissal removes published eligibility without changing read markers; unpublished arrivals remain eligible.
- While any app screen is foregrounded, cancel the system aggregate and consume eligibility in favor of existing in-app unread indicators. Read state changes only through existing conversation behavior. A visibility check immediately before publication prevents posting behind a newly resumed screen. Logout suppresses eligibility and cancels the aggregate; it cannot reconnect.
- Android 13+ permission is an optional, user-initiated **Settings → Notifications → Enable notifications** action. Only one request is offered; later changes open Android settings. Denial/channel disablement cannot block decrypt/store/ACK and pending eligibility remains until presentation or foreground suppression. No Worker requests permission.
- Immutable app-local intents contain no extras or identifiers. Tap enters MainActivity through the normal root and selects Chats, including an existing task. Future app lock can intercept at that root; no app lock is implemented. No notification diagnostics or logging were added in debug or release.

**Still proposed:** randomized 20–40-minute eligibility, optional background enable controls, richer notification levels, app lock, biometric/PIN unlock and maximum-security mode. Physical overnight/OEM latency measurements remain future validation.

## 1. Executive summary

Retain existing Phase 1E foreground synchronization. Recommend one unique ordinary WorkManager periodic task for best-effort background checks, sharing the application runtime and a sync coordinator with foreground work. Start with a 30-minute period and 15-minute flex window. Add a persisted bounded randomized not-before gate; accept longer delays. Do not use expedited work, alarms, permanent sockets or a foreground service by default.

Fetch from the configured Ghost Cloak HTTPS origin (`https://api.ghostcloak.org` for staging), authenticate with existing credentials, decrypt/commit through the existing path, ACK according to current semantics, then generate a generic local notification. Notification is not transport: the encrypted mailbox remains transport. Release retains its explicit API-origin policy rather than inheriting staging.

Expected background latency is tens of minutes when Android permits execution, potentially hours or until app launch under restrictions. There is no upper-bound latency promise. Opening the app remains the user-triggered catch-up path, subject to connectivity and cooldowns. No external push provider or identifier is introduced.

## 2. Android platform constraints

The app currently supports API 30 and targets API 37 (`app/build.gradle.kts`). Validate the eventual implementation across that range and recheck target-specific quotas before rollout.

Periodic WorkManager has a 15-minute minimum interval. Flex permits scheduling within part of a period; neither interval nor flex is a deadline. Expedited work is quota-limited one-time work, not a recurring mailbox listener. Compatibility paths before Android 12 can require a foreground service. A network constraint does not establish endpoint reachability. See [work requests](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work).

Doze defers ordinary jobs/network into maintenance opportunities; App Standby, Battery Saver and OEM restrictions can delay them further. Battery-optimization exemptions are not universal guarantees and are not required by this design. See [Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby) and [resource limits](https://developer.android.com/topic/performance/power/power-details).

Process-local coroutines do not survive process death. Durable scheduling can restart work, not create an immortal process. Workers must be bounded and cooperate with stopping. See [persistent scheduling](https://developer.android.com/develop/background-work/background-tasks/persistent).

## 3. Threat model

Protect bodies, keys, contacts, identity continuity and notification privacy. Assume a functioning sandbox/Keystore but potentially curious server/network operators, nearby observers and notification listeners. A server may withhold or replay ciphertext; existing authentication, crypto and deduplication checks remain mandatory.

| Observer | Visibility | Background difference |
| --- | --- | --- |
| Ghost Cloak operator | Authenticated account/device, poll time/frequency, mailbox activity, protocol routing/submission identifiers, ACK timing; client IP where exposed by ingress | Extends observation beyond active app use. ACK means stored, not read or user present |
| Existing ingress/CDN | IP, TLS timing/size; HTTP authentication and encrypted envelopes if terminating TLS | Same path, more observations; E2EE bodies remain opaque |
| ISP/local network/DNS | Destination information depending on DNS setup, connection timing/size | Scheduled wakeups can reveal behavior |
| OS/listeners/local observers | Notification existence, timing and posted content | Generic content limits disclosure, not occurrence |

Existing Cloudflare ingress/tunnel remains an existing trust dependency. “Only Ghost Cloak” means application requests target its configured origin and add no delivery provider; it does not remove DNS, network operators or Cloudflare from the path. No infrastructure change is proposed.

Fixed polling can expose predictable reachability and behavior patterns. Radio transitions, batching and delays may fingerprint battery/network conditions. Use flex, bounded jitter, batching and slower background cadence; avoid recording precise user activity in scheduler metadata. Jitter reduces precise correlation, not anonymity. Foreground/background cadence differences remain observable. Cover traffic is not justified given battery/bandwidth cost.

## 4. Candidate approaches table

All latency estimates assume usable connectivity; none guarantees real time.

| Approach | Latency / idle | Battery / privacy-security | Permission / persistent notification / user configuration | Reboot / restart |
| --- | --- | --- | --- | --- |
| Periodic WorkManager | Minimum 15 min; proposed 30 min with flex; hours of deferral possible | Low relative to foreground polling; recurring authenticated metadata | Ordinary work needs no exact-alarm/FGS access; review future merged manifest. No persistent notification or special settings by default | Durable rescheduling; secrets wait for first unlock; force-stop prevents work |
| Ordinary one-time work | Initial delay is eligibility, not deadline; idle/quota delays remain | Useful finite work; chains risk excessive polling | Ordinary-work requirements; no persistent notification | Durable once enqueued; chain successors require crash-safe scheduling |
| Expedited one-time work | Earlier when eligible, quota-limited; no remote wake signal | Higher resource pressure, same metadata | Not periodic; pre-12 FGS/notification compatibility implications | No instant reboot-delivery guarantee |
| Direct JobScheduler | Periodic minimum/quota restrictions; no latency advantage | Similar cost, more recovery code | JobService binding; persisted jobs need RECEIVE_BOOT_COMPLETED; no ordinary-job ongoing notification | Requires explicit persisted setup |
| Inexact AlarmManager | Batched/deferred; firing does not guarantee network completion | More manual wake/recovery complexity | No exact-alarm grant for inexact use; no persistent notification | Must reschedule after reboot |
| Exact/allow-while-idle alarms | Restricted wakeups, not mailbox delivery guarantee | Battery cost and more predictable timing | Exact-alarm special access or narrowly eligible permission; unsuitable for polling; no ongoing notification | Boot rescheduling/permission handling required |
| Socket / foreground service | Faster while actually running; interruptions remain | High idle cost, continuous reachability signal | Ongoing notification, FGS type/permissions/start restrictions; special settings cannot guarantee permanence | Unreliable unattended restart; rejected |
| Existing foreground only | Immediate resume attempt then current adaptive cadence | Existing cost/metadata | Existing INTERNET permission | User opens app to resume |

See [alarm restrictions](https://developer.android.com/develop/background-work/services/alarms) and [job persistence requirements](https://developer.android.com/reference/android/app/job/JobInfo.Builder). A dataSync FGS is unsuitable as an endless listener: Android 15+ targeting rules impose a six-hour background allowance in 24 hours and prohibit that type starting from BOOT_COMPLETED. See [FGS types](https://developer.android.com/develop/background-work/services/fgs/service-types). Do not misclassify a service to evade restrictions.

## 5. Recommended architecture

1. After explicit connection and background-sync enablement, reconcile one constant-name periodic request. No usernames, IDs, tokens or message data in work names, tags, input or output. WorkManager's database is not encrypted message storage.
2. Require connectivity and unlocked credential storage. Worker obtains the process-owned runtime, never its own engine or registration path.
3. Under a shared coordinator/runtime lock, recheck identity, logout eligibility, lifecycle generation, recent completion and global cooldown. Skip checks already covered by foreground sync; otherwise execute a bounded existing NetworkController sync.
4. Existing acceptance commits decrypted content and deduplication state before ACK. Phase 1F.2 commits local notification eligibility durably with acceptance without changing the wire protocol.
5. Reconcile a generic notification from accepted, still-unread eligible messages, then finish. No notification on ciphertext arrival or failed decryption.

Keep foreground immediate-on-resume and adaptive delays unchanged. Retain periodic registration while foregrounded, but skip execution through the coordinator: repeated cancel/re-enqueue can postpone periodic work indefinitely. Supersede stale one-time work if later introduced. On resume, join an in-flight worker result or wait for safe completion before the immediate attempt; never double-poll.

Proposed jitter: after background completion persist eligibility 20–40 minutes later. At the next OS opportunity, skip if too early. Do not sleep inside workers or enqueue catch-up bursts. This can skip a period and increase delay, intentionally. Flex itself is not guaranteed randomization. Reject a frequent chained one-shot loop; its scheduling recovery and activity correlation costs outweigh unproven latency gains. Reconsider finite one-shot work only after measured need.

## 6. Notification privacy model

Default title: **Ghost Cloak**. Body: **New message**. One aggregate notification; no sender/avatar, conversation name, contact counts, preview, safety status or protocol metadata. Use an opaque local notification ID, generic channel name and immutable app-local PendingIntent opening Chats. No remote identifiers/content in extras, Persons, shortcuts, groups or actions. No inline reply initially.

Only maximum privacy is implemented in Phase 1F.2. The richer levels below remain proposals, with no UI or payload support.

| Optional level | Content | Disclosure |
| --- | --- | --- |
| Maximum privacy (default) | Ghost Cloak / New message | App usage and notification timing |
| Private | Ghost Cloak / 2 new messages | Aggregate unread activity |
| Explicit sender opt-in | Sender name, never body | Contact relationships exposed to OS/history/listeners |

Previews remain OFF and are not part of initial implementation. Default lock-screen SECRET visibility and disabled badges; later users may knowingly choose generic lock-screen content. Channel/user/OEM controls affect rendering. Any public version must remain generic. Channels apply on API 26+; ordinary notifications need POST_NOTIFICATIONS user permission on Android 13+. Denial must not prevent fetch/store/ACK. See [notification visibility](https://developer.android.com/develop/ui/compose/notifications/create-notification) and [permission](https://developer.android.com/develop/ui/views/notifications/notification-permission).

History may retain posted content; canceling is not secure deletion. Listeners, wearables and OS integrations may read/forward it; lock-screen hiding does not isolate listeners. Screenshots/external cameras capture visible notifications, and app-window screenshot protection cannot secure the system shade. Generic content minimizes this exposure. If the device OS is fully compromised, no application can guarantee plaintext confidentiality after local decryption.

## 7. Lifecycle / reboot behavior

| Event | Required future behavior |
| --- | --- |
| STARTED / resume | Existing immediate sync coalesced with in-flight work, subject to cooldown |
| STOPPED / screen off | Foreground loop cancels; only scheduled opportunities remain |
| Memory process kill | No coroutine survives; durable scheduling may reopen the same identity |
| Reboot before first unlock | No fetch/decrypt/notification; no Direct Boot migration |
| First unlock after reboot | Work may resume when permitted, not necessarily immediately |
| Relock after first unlock | Current key policy permits access in principle; OS scheduling still applies |
| In-place app update | Same-identity data/Keystore normally retained; reconcile work and preserve worker-class compatibility/migrations |
| Swipe from recents | Normally not force-stop; OEMs may stop work; no guarantee |
| Settings force-stop | No background delivery until user interaction releases stopped state; no self-revival |
| Battery Saver / background restriction / OEM auto-start disabled | Longer delays or no execution; honest settings guidance, no recurring prompts |
| Explicit logout | Cancel scheduled work; generation fence suppresses in-flight notification publication. No silent reconnect |
| Clear data / uninstall | Not an update; explicit recovery, never silent registered-identity replacement |

Android's [stopped-state boundary](https://developer.android.com/about/versions/15/behavior-changes-all) is a user control, not an ordinary retryable error. Android 15 also cancels pending intents on force-stop. Reboot must not be used to bypass it.

## 8. Storage and concurrency model

Original pre-1F.1 source audit (paths relative to Android; see implemented delta above):

- `storage/src/main/kotlin/org/ghostcloak/storage/EncryptedEndpointStore.kt`: SQLCipher Room records in ordinary-context `noBackupFilesDir`; random database secret wrapped by Keystore AES-GCM. No per-use authentication or unlocked-device-required setting. Missing/inconsistent existing key/files fail rather than regenerate. Hardware/software protection is reported, not universally guaranteed.
- `storage/src/main/kotlin/org/ghostcloak/storage/KeystoreDeviceAuth.kt`: non-exportable EC signing key with no per-use authentication setting, no exported software fallback and no replacement of missing registered keys.
- `messaging/src/main/kotlin/org/ghostcloak/messaging/NetworkAccount.kt`: account/routing/registered state, auth alias and token in encrypted EndpointRecords, scoped to API audience. NetworkController renewal logs into the existing device once; it never registers. Logout removes the stored token, preserving non-eligibility after restart.
- `app/src/main/java/org/ghostcloak/app/application/GhostApplication.kt`: one lazy runtime per process. `AppRuntime.use` holds a mutex across initialization/operations on IO. This is per instance, not cross-process; workers must use this owner, with no separate `android:process` or engine.
- `messaging/src/main/kotlin/org/ghostcloak/messaging/ConversationService.kt`: `acceptNetwork` checks accepted sender/envelope/hash, then uses `decryptAndCommit` to save incoming content/acceptance. NetworkController ACKs only after acceptance returns.

Process death, normal relocking and normal in-place updates do not inherently remove credentials. Credential-encrypted files are unavailable before first unlock; the manifest is not Direct Boot aware. Do not move secrets to device-protected storage. See [Direct Boot](https://developer.android.com/privacy-and-security/direct-boot). Actual key failure/invalidation must fail closed. Future biometric-bound settings may prohibit locked execution; defer until authorized unlock rather than weaken protections.

Future coordinator must coalesce as well as serialize: recheck completion generation after acquiring the lock to avoid redundant back-to-back FETCHes. Never close the runtime from a Worker or read Room on Main. Cancellation must not release ownership while blocking IO still operates. Current HttpURLConnection uses 5-second connect/read timeouts, not a total sync deadline; check execution budget between operations/pages.

Phase 1F.2 implements the encrypted pending-notification ledger described above. Commit eligibility with accepted message state and drain later even if a crash follows a successful ACK. Re-delivery must not create a duplicate message or alert. Use a stable aggregate notification ID and quiet updates: notification posting and database commit cannot be atomic, so exactly-once sound cannot be promised. Prefer missed repeat sound over duplicate alerts. Recheck read/deleted/blocked state and foreground visibility before publication. Dismissal suppresses re-alerts for the same messages without marking them read. Existing request/verification and changed-identity gates remain authoritative.

## 9. Rate-limit interaction

Current `ForegroundPolling` waits 2/3/5 seconds after completion: eventually about 12 idle FETCH/minute, about 30 while active, excluding latency/pages/retries. One empty NetworkController sync makes one combined FETCH with no separate receipt-status call. Busy sync can retry outbox SENDs, include receipt IDs, ACK individual deliveries and fetch up to 16 pages. Receipt expiry may cause a fallback FETCH: one cycle is not always one HTTP call.

A nominal 30-minute period is about two empty FETCH/hour (48/day), before jitter skips or retries, well below the confirmed 60 FETCH/minute collision. Preserve combined fetch/status and server limits. Proposed background budget: at most four FETCH attempts per rolling minute, counting auth replay, receipt fallback and pages. Defer remaining work without dropping it. Validate against batch/retention behavior before implementation; no backend rate-limit changes.

`transport/src/main/kotlin/org/ghostcloak/transport/FetchCooldown.kt` originally stored a monotonic deadline only in memory. A 429 uses Retry-After or 15/30/60-second fallback with a 2-second minimum. Phase 1F.1 adds encrypted checkpoints through StoredFetchCooldown before any foreground/manual/background FETCH. The implemented policy uses boot count and monotonic deadlines, conservatively rebasing the saved remaining delay after reboot rather than trusting a wall-clock deadline. Repeated reboots can extend this delay, an intentional fail-safe tradeoff; within a stable boot it expires normally. Never shorten valid Retry-After. Scheduler backoff uses the later eligibility time; resume does not bypass cooldown.

## 10. Failure handling

| Failure | Response |
| --- | --- |
| Offline / timeout / 503 | Preserve session/identity/outbox/contacts; bounded later retry with jitter; no repeated error notification or forced reconnect |
| Expired token | Existing silent renewal and exact-byte one retry, coalesced through renewal mutex |
| Repeated 401 / unavailable authentication | Pause automatic work pending explicit connect; no token in notification |
| 429 | Shared durable cooldown, no immediate retry storm |
| Key/DB failure or identity mismatch | Fail closed, actionable in-app recovery; no replacement keys or reset |
| Invalid/undecryptable envelope | Existing rejection/ACK semantics; no notification |
| ACK failure after commit | Retain message/eligibility; redelivery deduplicates and retries ACK |
| Notification denied/channel disabled | Store/ACK normally; retain unread state, no repeated permission prompts |
| Worker stopped / process death | Recover durable state; scheduler success never implies delivery |

Partial cycles can commit valid messages before later failure. Reconcile their notification eligibility independently of whole-cycle success. Notification is neither Delivered nor read nor message-request acceptance.

## 11. Battery/performance impact

Radio wakeups, DNS/TLS, SQLCipher opening and decryption cost more than the empty request alone. Batch while awake; avoid persistent connections and sleeping workers. Proposed normal cycle budget is 30 seconds checked between operations; blocking IO may exceed it and must be validated. Do not use long-running workers/FGS to drain a backlog; continue at later bounded opportunities.

Measure empty/busy wakeups, CPU, bytes and overnight battery delta versus foreground-only baseline. Record latency distributions and worst observed delays, not only fast cases. Measurements remain local and sanitized; no telemetry or remote crash SDK.

## 12. Security tradeoffs

Background decryption extends plaintext memory exposure when the app is not being viewed. Current Keystore policy permits this after first unlock; it does not imply biometric protection. Explain delayed delivery and this tradeoff when enabling background sync. Generic content limits OS disclosure but not timing; count/sender options weaken that boundary.

Persist minimal scheduler state encrypted, no extra notification body copies. Preserve backup exclusions, TLS validation, Signal/identity/storage protections and existing network trust boundaries. Mailbox retention still bounds recovery after a long absence; background scheduling cannot guarantee indefinite retention.

### App lock / secure unlock roadmap

Design only. Recommend **optional UI lock (A) initially**, accurately labeled as protection against someone using the app on an otherwise unlocked phone. Reserve **Keystore-gated lock (B)** for a separately reviewed maximum-security mode that sacrifices unattended decryption/background delivery. Neither mode changes network identity, logout semantics or Signal cryptography.

| Threat / behavior | A: UI lock | B: cryptographic/Keystore-gated lock |
| --- | --- | --- |
| Casual access to an unlocked phone | Gate every sensitive UI route/action | Gate UI and access to local decryption capability |
| Background sync while app locked | Existing encrypted store and engine remain available; fetch/decrypt/store/ACK continues | Defer sensitive sync until authorized unlock; current monolithic encrypted store cannot be opened unattended |
| App-process compromise | UI checks can be bypassed; plaintext/keys may already be in memory | Stronger protection while key is unavailable, but bypass possible after authorized unwrap if secrets remain in process memory |
| Copied local data | Existing SQLCipher/Keystore protection; app PIN is not additional database encryption | Auth-bound wrapping adds a key-use boundary; no claim of protection against all hardware/OS attacks |
| Process death | Restart locked even if grace period had not elapsed; runtime can reopen for background work | Restart locked; no cached decryption grant or unwrapped secret restored |
| Reboot / before first unlock | No access to current credential-encrypted files | Same constraint, plus explicit app-authorized key use after first unlock |
| Device relock after first unlock | UI lock timing applies; background remains possible | Proposed mode immediately closes access on device lock as well as app-lock expiry; lifecycle/OS enforcement must be tested |

Both modes remain vulnerable to malicious accessibility interaction, input capture or a compromised OS to differing degrees. Fully compromised device software can expose plaintext after local decryption. UI lock must never be marketed as cryptographic gating.

#### Unlock methods and local PIN

Offer optional Android BiometricPrompt using a supported strong biometric and an independently optional **app-local PIN** fallback. Check availability/enrollment and handle cancellation, lockout and enrollment changes without unlocking. No custom biometric collection. Biometric-only enrollment must explain loss-of-access risk if no fallback is enabled. Require a successful configured unlock to change/disable app lock or enroll/reset its PIN; creating the first lock requires confirmation of the chosen method. See [BiometricPrompt guidance](https://developer.android.com/identity/sign-in/biometric-auth).

For A, the system prompt authorizes a short-lived in-memory UI grant. For B, a successful UI callback alone is insufficient: bind authorization to an actual Keystore operation with CryptoObject and a strong biometric or explicitly configured **Android device credential**. The OS device PIN/password/pattern is not the app-local PIN. Local PIN verification cannot authorize an Android authentication-bound key. Therefore B must not offer a weaker app-PIN wrapper as a transparent fallback; that would reduce the protection to the weaker path. Users wanting local-PIN fallback should use A initially. See [CryptoObject key authorization](https://developer.android.com/reference/android/hardware/biometrics/BiometricPrompt.CryptoObject) and [Keystore authentication](https://developer.android.com/privacy-and-security/keystore).

If app PIN is implemented, use a vetted Argon2id implementation, a fresh random salt of at least 16 bytes, a versioned parameter record and constant-time verification of a 32-byte derived verifier. Starting benchmark profile: 64 MiB, three passes, four lanes; evaluate on minimum supported devices and do not silently downgrade under memory pressure. Parameters must be approved with local unlock latency/resource measurements. This profile follows [RFC 9106's memory-constrained recommendation](https://www.rfc-editor.org/rfc/rfc9106.html). A short numeric PIN still has low entropy: a strong KDF slows guessing but cannot turn it into a strong password. Propose at least eight digits and permit a longer passphrase.

Store only salt, parameters and verifier inside existing encrypted local records for A, never plaintext PIN or reversible PIN encoding. Never send it to the server or put it in logs, saved UI state, backups, analytics or clipboard. Minimize transient input lifetime; managed-runtime zeroization is best effort. Add persisted failure counters and progressively longer bounded cooldowns, surviving restart/reboot; never sleep holding the runtime lock. Local throttling can be bypassed by a compromised app/rollback and is not hardware anti-brute-force. Do not automatically wipe identity/history after failed attempts.

No email/SMS recovery, server-side PIN storage or third-party identity provider. A successful alternative already-configured unlock may authorize changing a forgotten PIN. Without one, remain locked and explain that recovery is unavailable; any future destructive reset must explicitly disclose loss and require separate confirmation. Forgetting a PIN must never invoke account creation, device registration, key regeneration or implicit logout. B key invalidation likewise fails closed without replacing identity or keeping an unprotected recovery wrapper.

#### Timing, entry points and presentation

Expose exactly **immediate, 30 seconds, 1 minute and 5 minutes**, measured from the application leaving foreground. Recommend immediate by default when lock is enabled. Use a process-owned lock state machine: Disabled, Locked, Unlocking, Unlocked and background grace deadline. Backgrounding starts a monotonic deadline; returning before expiry preserves the grant, returning after expiry locks before rendering. A background timer is not guaranteed to execute: always re-evaluate on resume before any sensitive composition/action. Process restart always starts Locked regardless of stored timing. Do not persist an unlocked flag or restore plaintext screens/drafts through saved-state mechanisms while locked.

Track application-wide activity visibility, distinguishing configuration changes, multi-window and prompt callbacks from genuinely leaving the app. Do not depend solely on delayed process-lifecycle callbacks for immediate locking. Invalidate stale biometric/PIN callbacks with an unlock-attempt generation: success from an earlier attempt cannot unlock after background expiry, logout or cancellation. Backgrounding during a prompt cancels that attempt; explicit unlock is required again if the deadline elapsed.

Notification/deep-link entry goes through the same root gate before resolving sensitive UI. An opaque pending destination may be retained, but it grants no access. No conversation text, avatar, search results, export, copy/share or accessibility semantics behind the lock screen. Authentication UI must not merely overlay a live sensitive screen. Explicit logout remains separate: lock neither revokes the access session nor permits reconnect after logout, and network login never unlocks the UI.

When enabled, keep FLAG_SECURE on sensitive app windows from their first frame, including during grace and biometric prompts, and render a neutral background snapshot cover immediately on backgrounding. Do not wait for the lock timer before protecting recents. Test rotation, task switching, dialogs and notification launch for one-frame leaks. FLAG_SECURE blocks supported screenshots/nonsecure displays but is not a defense against external cameras or compromised/OEM behavior; see [secure activities](https://developer.android.com/security/fraud-prevention/activities). Do not claim deletion of screenshots taken before enabling the feature.

While locked, force every notification to **Ghost Cloak / New message**, overriding sender/count opt-ins. On locking, replace or remove already-visible richer notifications; prior notification history/listener copies cannot be recalled. Apply a lock-generation check at publication to prevent a sender-bearing notification racing with lock. In B, no new-message notification is created from unprocessed ciphertext.

#### Maximum-security storage boundary

Current AppRuntime opens a database and Signal engine that can retain usable decrypted material. Merely changing the AES wrapping key to require biometrics would protect a future open, not an already-open database. B therefore needs a reviewed storage-access redesign: quiesce/cancel network and UI operations safely, finish or roll back transactions, close the store/engine, release cached secrets/plaintext and require a new authenticated unwrap. Managed/native memory remnants remain a limitation. Never keep the current unrestricted wrapper alongside the auth-bound wrapper as a silent bypass.

An auth-per-use CryptoObject unwrap can establish a bounded app session; it does not magically revoke unwrapped material when the UI locks. Time-based Keystore grants may also be enabled by device unlock, not specifically app unlock. Keep the root UI gate independent and choose a supported authentication policy per API rather than mapping the four UI timers directly onto hardware guarantees.

With the current monolithic encrypted records, network tokens, identity/ratchet state and messages are unavailable while B is locked. Defer FETCH, SEND retry and ACK until authorized unlock. Do not ACK ciphertext merely fetched without decrypt/commit. A later ciphertext-only staging store would need separate threat modeling, bounded storage, credential separation and crash semantics; it is not part of the initial B recommendation. Longer deferral increases mailbox-retention risk. Enabling/disabling B requires an authenticated, crash-safe rewrap/migration of existing database secrets, with no Signal/account key replacement and no unprotected residual wrapper after completion.

## 13. Implementation phases

1. Approve architecture/privacy/latency defaults; recheck API-37 rules and existing mailbox retention/batch limits.
2. Implement/test shared coordinator, durable cooldown, lifecycle/logout fences and notification eligibility. Preserve exact retry bytes and Phase 1E foreground behavior.
3. Separately approve ordinary WorkManager dependency/unique scheduling. Review strict verification and merged manifest, including possible library boot/wake-lock/network-state permissions; do not assume INTERNET-only manifest remains sufficient.
4. Separately add local channel/permission flow and generic notifications with privacy controls. No remote provider or message preview.
5. Run regression/physical matrix and limited opt-in staging rollout with truthful delayed-delivery guidance and disable control.
6. Separately implement optional A: root navigation/action gate, process-owned timing, BiometricPrompt, vetted local PIN verifier/throttling, generic locked notifications and snapshot protection. State clearly that background decryption continues. Review dependencies/permissions and run the lock matrix below before release.
7. Treat B as a later maximum-security project: review supported auth-bound key policy, store/engine teardown, crash-safe migration, recovery limitations and background suspension before implementation. Do not enable it as a transparent upgrade to A.

Phase 1F.1 implements the scheduling/coordinator/cooldown subset of steps 2–3. Phase 1F.2 implements the eligibility ledger and generic local notification/permission slice of step 4. Background settings controls, jitter, richer notifications and both app-lock modes remain unimplemented. See the implemented deltas above.

## 14. Physical-device test plan

Use two consenting staging phones with synthetic accounts/messages, API-30/API-37 coverage and Pixel/AOSP plus Samsung/OEM hardware. Never clear real user data. Test release behavior separately from sanitized debug diagnostics.

- Compare baseline and background mode over several unplugged overnight runs. Send at randomized times; record fetch, commit, ACK and notification separately. Include empty mailbox and backlog; no real identifiers/content in reports.
- Test forced and natural Doze/standby, Battery Saver, restricted background and OEM auto-start settings, then restore settings. Hours-long deferrals are possible outcomes, not deadlines.
- Kill process before fetch, after decrypt commit, around ACK and notification posting. Verify one stored message, intact ratchet, no repeated sound and recoverable ACK.
- Reboot and keep locked: no secret access before first unlock. Unlock/relock and verify without changing key policy. Invalid test keys must never cause automatic replacement.
- Compare recents swipe with Settings force-stop. Prove force-stop prevents work until user relaunch. Update in place with pending/running work; preserve identity/history and scheduling reconciliation.
- Race resume/stop, manual Sync/send, worker, recreation and logout. Assert one sync and no redundant immediate FETCH, stale notification or silent reconnect after logout. Read/delete/block races suppress notices appropriately.
- Inject token expiry, repeated 401, DNS/TLS/offline/503, 429 Retry-After, receipt expiry and cancellation. Count real HTTP attempts including replay/pages. Restart/reboot/change clocks during cooldown; no bypass/storm.
- Check generic content only after authenticated durable acceptance; rejected crypto/blocked contacts never notify. Test permission/channel denial, lock screen/history/badges/listeners/screenshots and optional levels.
- Audit packet destinations, dependencies, merged manifest, logs and scheduler database: no added broker, analytics, remote crash reporting, identifiers, content or tokens. Application delivery requests target the configured origin.
- Run existing renewal, rate-budget, lifecycle, storage reopen, outbox/dedup, identity and request suites plus new coordinator/crash/privacy tests under strict dependency verification. Report measured physical outcomes, never universal guarantees.

App-lock test matrix (future implementation):

- Exercise all four deadlines just before/at/after expiry with wall-clock changes, screen lock, Home, rotation, split screen, process kill and reboot. Restart must be locked even inside a prior grace period.
- Launch through notifications/deep links and restored tasks; no sensitive frame, accessibility node or action before unlock. Capture recents, screenshots and screen recordings around every transition on API 30/37 and OEM phones.
- Test biometric cancel/lockout, enrollment/key invalidation, unsupported authenticators, stale callbacks and optional PIN fallback. No cancellation/failure may grant access. Verify KDF vectors, unique salts, parameter validation, constant-time comparison and persisted throttling across restart without exposing PINs.
- Race lock with richer notification publication, foreground send/sync and logout. Locked notifications stay generic; A continues scheduled storage/ACK without changing identity, B defers it with no ACK of uncommitted content.
- For B, kill during migration, unwrap, transaction and teardown; verify no unrestricted wrapper remains, no identity/Signal key regeneration, no access after lock/process restart, and recoverability or explicit fail-closed behavior after key invalidation.
- Verify forgotten PIN has no server/email/SMS recovery or implicit reset; a configured alternate unlock can authorize PIN change. Verify lock is independent of logout and network session renewal.

Automated JVM/emulator coverage is part of Phase 1F.1; this document does not claim the future physical/OEM and app-lock matrices have run.

## 15. Explicit non-goals

Phase 1F.1 adds only the ordinary WorkManager scheduling dependency/components described above. Phase 1F.2 adds the local channel, optional POST_NOTIFICATIONS permission and generic publication described above. App lock remains a non-goal. No FCM, Google push APIs, OneSignal, Pusher, AWS SNS, Apple/third-party relay or external push broker. No permanent socket, exact alarms, battery-exemption prompts, analytics, remote crash reporting or cover traffic. No backend, Cloudflare/VPS, protocol, crypto, identity, delivery/request semantics, polling cadence or rate-limit changes. No instant-delivery SLA, plaintext server/notification transport, content/token logging or weakened local protections.
