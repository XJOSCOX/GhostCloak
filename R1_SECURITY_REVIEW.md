# R1 Ghost Cloak V1 security and reliability review

Status: **IN PROGRESS**. Base: P13 closure `3c10184ebf24a082876814bdeb7d055bfd2c7a1e` (2026-10-09). P13 is GO under its separately documented acceptance scope; R1 and V1 release are not yet GO. This register records only checks actually performed. No VPS or production database was accessed.

## Assets and trust boundaries (R1.1)

| Boundary | Assets worth protecting | Current entry points and controls to challenge |
| --- | --- | --- |
| Identity and contact trust | Device identity, Signal identity/private keys, device-auth signing key, Ghost Cloak ID binding, safety-number/verification state, accepted-contact and block state | Registration/recovery and prekey allocation; accepted-contact transition; identity change; local trust generation. The shareable ID is an identifier, not an authentication secret. |
| Protected local state | SQLCipher endpoint records, wrapped database secret, Signal session/ratchet state, signed governance journal, message/read state, durable outbox, attachment descriptors and keys | `EncryptedEndpointStore`, Android Keystore, app lock, Safe Exit and inactivity gates. Database and wrapped secret live in `noBackupFilesDir`; the code rejects an existing database with a missing Keystore alias or wrapped file rather than silently replacing identity. |
| Local media and transient plaintext | Encrypted attachment cache, decrypted presentation scratch, voice/profile preparation scratch, group photo companions, clipboard and external viewer copies | `AttachmentStore`, `AttachmentPresentation`, photo/voice preparation, `AttachmentViewerProvider` and URI grants. External recipients of an intentionally opened document may retain bytes; managed memory cannot guarantee erasure. |
| Android OS boundary | App process, lock screen, notifications, recents/screenshots, exported components, backup/restore and device transfer | Main launcher activity, nonexported QR activity/notification receiver/viewer provider, system picker/viewer, WorkManager. The manifest disables backup and cleartext traffic; backup and data-extraction XML exclude root/file/database/shared-preference/external domains. Merged manifests and OEM behavior remain to test. |
| Direct E2EE | Message requests, text/media, replies/reactions/edits/deletes, timers/View Once, encrypted profile sync, Block and sender ordering | Untrusted envelopes cross network and are authenticated/decrypted by Signal before application controls. Replay, ratchet commit, blocked-content discard, terminal-state nonresurrection and capability downgrade remain adversarial test targets. |
| Group E2EE | Admission proofs, owner/coordinator authority, signed governance journal, member/role/posting state, group profile/photo, ordered text/media, timers, moderation and ownership | Pairwise Signal fan-out plus authenticated group control wire. A removed member may retain earlier content but must not receive future authorized group content. No backend group roster or governance state is expected. |
| Backend and ingress | Account/device/auth sessions, directory and allocation state, opaque mailbox, blob capability/store, rate-limit state, migration history and DB credentials | `ProductionHttpServer`, `MailboxService`, `BlobService`, PostgreSQL roles, nginx origin allowlist, Cloudflare Tunnel. The server and edge can observe routing, timing, size and traffic patterns; shared group-blob downloaders may be correlated. |

Source anchors: `Android/storage/src/main/kotlin/org/ghostcloak/storage/EncryptedEndpointStore.kt`, `Android/app/src/main/AndroidManifest.xml`, `Android/app/src/main/java/org/ghostcloak/app/access/AndroidLocalDestruction.kt`, `Android/messaging/src/main/kotlin/org/ghostcloak/messaging/GroupGovernanceV1.kt`, `Android/messaging/src/main/kotlin/org/ghostcloak/messaging/DurableOutbox.kt`, `backend/src/main/kotlin/org/ghostcloak/backend/{ProductionServer,MailboxService,BlobService}.kt`, `protocol/METADATA_PRIVACY.md` and `infrastructure/CLOUDFLARE_TUNNEL.md`.

## Threat actors and claimed limits (R1.2)

| Actor | Can attempt / observe | Must not be able to do; verification still needed |
| --- | --- | --- |
| A. Unauthenticated network peer | Send malformed requests, exhaust bounded public work, observe public endpoint responses. | Authenticate as an account, allocate another account's prekeys without authorization, read mailbox or blobs without the required authorization/capability. Rate-limit and parser abuse tests are pending. |
| B. Registered account | Use its own token, target known shareable IDs, send ciphertext, try cross-account IDs and quota exhaustion. | Fetch/ACK another account's mailbox, forge source identity or access another blob without authorization. Backend route tests are pending. |
| C. Accepted contact | Send authenticated content and application controls, including malformed/stale/replayed data. | Bypass the recipient's current trust generation or force terminal plaintext to reappear. Direct protocol tests are pending. |
| D. Current group member | See current group state/content and signed controls addressed to it; send within its role. | Forge owner/coordinator/admin authority, skip journal entries, force a fork winner or send while restricted. Governance tests are pending. |
| E. Removed group member | Retain content already delivered and attempt replay or stale-head sends. | Obtain future authorized group content or rejoin without a new approved admission. Removal/offline tests are pending. |
| F. Blocked contact | Continue network sends; ciphertext may still be authenticated to maintain ratchet/replay state. | Cause blocked application content to be parsed/displayed or learn block state from a special response. Block-oracle tests are pending. |
| G. Compromised/stale client | Misbehave with its own keys or omit newer capabilities; a compromised device can expose its own local plaintext. | Downgrade other participants' authenticated policy or make unsupported controls silently accepted. Compatibility review is pending. |
| H. Network observer | See network endpoints, timing and sizes; may block or delay traffic. | Read E2EE plaintext or forge a valid TLS peer/ciphertext. TLS failure tests are pending; anonymity is not claimed. |
| I. Ghost Cloak backend | See registered/routing identifiers, metadata, ciphertext, mailbox/blob traffic and authenticated downloader correlation. | Read Signal plaintext, group roster/name/About from encrypted payloads or attachment encryption keys. Backend compromise can deny, replay or reorder delivery; client checks must withstand it. |
| J. Cloudflare/hosting | See edge/origin transport metadata and possibly HTTP routing/headers according to deployment. | Derive encrypted message plaintext without endpoint keys. Perfect metadata privacy is not claimed. Live configuration is not inspected in this review. |
| K. Device thief without app unlock | Possess the device and try local file extraction, backup, UI/notification leakage and lock bypass. | Open protected content without the required local authentication or recover secrets from an incomplete Safe Exit. Keystore/lock tests are pending; unlocked-device compromise is outside this actor. |
| L. Malicious Android app | Send intents, supply content URIs, probe providers, inspect clipboard/notifications where OS permits. | Read private files or obtain viewer grants without explicit user flow. Merged-manifest and URI tests are pending. |
| M. Corrupted local storage | Cause missing DB/wrapped-key files, truncated records, tampered ciphertext or journal gaps. | Trigger silent identity replacement or accept a reconstructed governance chain. Partial-loss/corruption tests are pending. |
| N. Crash/process death | Interrupt a transaction, submission, upload, governance step or deletion at arbitrary points. | Leave a false committed state, duplicate logical content, resurrect terminal content or expose old data after restart. Failure-injection matrix is pending. |

## Preliminary source checks (not final findings)

- The main manifest has `allowBackup=false` and `usesCleartextTraffic=false`. Its launcher activity is exported; QR capture, notification dismissal and attachment viewer are explicitly nonexported. The debug-only photo test provider is declared nonexported in the debug manifest. In the clean release merged manifest, WorkManager `SystemJobService` is exported but protected by `android.permission.BIND_JOB_SERVICE`, and the AndroidX `ProfileInstallReceiver` is exported but protected by `android.permission.DUMP`. The debug photo test provider is absent. Actual URI grant behavior remains to test.
- `EncryptedEndpointStore` uses a 256-bit Android Keystore AES-GCM wrapping key and a 32-byte random SQLCipher secret. It explicitly fails when existing database/wrapped material and Keystore alias are inconsistent. This source reading is not a corruption/fault-injection pass.
- `AndroidLocalDestruction` enumerates owned Keystore aliases and local files, checks deletion, revokes attachment URI grants and cancels notifications. This source reading does not prove Safe Exit on every OEM, open file or crash point.
- A first logging search found typed diagnostic events and failure categories. No plaintext leak is claimed or ruled out until the release logging and exception-path audit completes.

## Initial release-surface checks (R1.3–R1.5, incomplete)

A source search across Android `main`/`release`, transport, messaging, crypto, storage and backend production Kotlin found only the fixed-enum Safe Exit recovery `Log.i` calls in the Android production source sets; `PhotoDiagnostics` and `DocumentDiagnostics` are no-op in the release source set. This is not yet a complete sensitive-data-flow audit of exception strings, libraries, generated code or runtime logs. The clean release merged manifest was inspected, not merely the source manifest. Its non-app exported WorkManager/ProfileInstaller components have signature/system-level binding permissions as noted above. The release merge retains `allowBackup=false`, `usesCleartextTraffic=false` and the explicit extraction/backup exclusions. No immediate exported-component or explicit production-logging blocker was found in this preliminary pass; URI-grant and actual-device attempts remain open.

## HIGH finding R1-STORAGE-01: existing database reinitialization

**Historical severity: HIGH. Status: FIXED in the focused R1 storage change; R1 overall remains in progress.** An existing endpoint could retain its Android Keystore alias and wrapped SQLCipher secret while its database file was truncated to zero or one byte. Before the fix, Room/SQLCipher accepted those tiny files and initialized a fresh schema, so higher layers could see an absent identity and treat a damaged existing account as new. The protected-store initialization contract is:

| Keystore alias | Wrapped secret | Database path | Outcome |
| --- | --- | --- | --- |
| absent | absent | absent | New endpoint initialization permitted |
| present | present | present | Existing endpoint; validate and open |
| present | present | absent | Fail closed; no implicit recovery |
| present | absent | present | Fail closed; no implicit recovery |
| present | absent | absent | Fail closed; no implicit recovery |
| absent | present | present | Fail closed; no implicit recovery |
| absent | present | absent | Fail closed; no implicit recovery |
| absent | absent | present | Fail closed; no implicit recovery |

Before opening an existing database, `EncryptedEndpointStore` now requires the database path to be a regular file of at least 512 bytes. SQLite's minimum page size is 512 bytes; the correctly initialized, logically empty SQLCipher fixture measured 16,384 bytes on the test emulator. The conservative threshold rejects tiny files before Room may create schema without assuming every supported database has the emulator's observed size. SQLite corruption exceptions from record reads, writes, deletes and key scans are mapped to `EndpointStorageFailure`, as transaction/open failures already were. Detection does not delete or rewrite the corrupt database, wrapped secret or Keystore alias.

The disposable-emulator storage regression covers lengths 0, 1, 16, 128, 511, 512, 1,024, half the original file and original minus one byte; same-size random bytes; first-byte and later-byte flips; repeated opens; valid logically empty database reopen; a directory at the database path; and existing identity/ratchet and wrapped-secret cases. The later-byte flip was not rejected at initial open, but its first authenticated identity read failed and mapped to `EndpointStorageFailure`; an arbitrary byte flip in unused encrypted file space may remain undetected while all protected records are intact. A separate app-runtime fixture proves an existing identity with an emptied database cannot reach the normal `ConversationService.open()` path; another creates and reopens a fresh identity. Strict dependency verification passed throughout. Full `EndpointStorageTest`: 14/14; focused app-runtime tests: 2/2; attachment instrumentation: 18/18. JVM: messaging 83/83, test-support 311/311, app debug 125/125, app release 118/118. Debug and release assembly and `lintDebug` passed from the development checkout. A separate clean-source reviewed-build check is still required for formal release provenance.

## Local-state/crash cluster evidence (R1-L1–L24, in progress)

On the disposable E emulator, strict-verification instrumentation passed: `EndpointStorageTest` 15/15 (including a new partial-initialization regression), `LocalDestructionAndroidTest` 11/11, `LocalOperationInfrastructureTest` 13/13, viewer grant revocation 1/1, app-lock picker return 2/2, app-lock storage 1/1, app-lock screen 3/3, inactivity record 1/1, Safe Exit recovery UI 2/2, notification/privacy 8/8, attachment storage 1/1, attachment UI access 2/2, photo preparation 18/18, profile-photo preparation 2/2, and inline photo presentation 5/5. These are separate class-filtered runs; a comma-separated Gradle class filter executed only its first class and is not counted as a combined run. No production account or VPS was used.

The added initialization regression stages an alias alone, a wrapped secret alone, and a database alone. Each open throws `EndpointStorageFailure`, repeated alias-only opens do not create missing files, and the existing wrapped/database bytes remain unchanged. Existing storage cases cover missing DB or wrapped secret, missing Keystore alias, directory DB path, malformed/truncated wrapping frames, and corrupted/tiny DB. A correctly initialized but logically empty SQLCipher database reopens; an existing identity with a zero-byte DB cannot reach normal onboarding. This proves selected state combinations, not every I/O error, no-space condition, or each instruction boundary inside first-run creation. Those remain open.

Safe Exit fixture coverage includes exact offline PIN destruction, inaccessible retained DB/auth/attachment material after the key boundary, retry after file/key deletion failure, durable recovery journal across recreation, notification cancellation, and an installed backup-policy assertion. The URI test grants a synthetic viewer child URI to the instrumentation package, then proves revoking the viewer root removes that child grant. Because instrumentation shares the app UID, this is **not** proof that a separate foreign application cannot read the provider. A shell-UID guess did not resolve the nonexported provider, but a dedicated foreign-package attempt and expired/wrong-message URI cases remain open. The backup test and merged-release-manifest inspection confirm `allowBackup=false` and explicit full-backup/data-extraction exclusions; an actual OS backup/device-transfer attempt is still open.

The app-lock first-frame/recreation test passed, as did locked-background storage and picker-return tests. Notification fixtures passed maximum/contact/content presentation and suppression of sensitive metadata while locked. The inactivity fixture passed durable record reopen and corrupt-record fail-closed handling. These do not yet cover the full reboot/clock, biometric, system-recents, or OEM notification matrices. Real process-death injection for direct/group outbox, media upload, and stale-recipient transitions remains open; existing messaging tests cover selected rollback, replay, governance-journal, removal, and retry behavior but are not a substitute for the requested complete interruption matrix.

Strict JVM results for this pass: messaging 83/83, test-support 311/311, app debug 125/125, app release 118/118. `lintDebug`, debug assembly, and release assembly passed. A clean-source `assembleSecurityReviewed` passed separately at base `29df3d4fc01ecd6d922bf814075553c36dbb9170`; it must be rerun after any new reviewed commit. No new HIGH/CRITICAL finding was demonstrated in these checks. This cluster, R1, and V1 release remain **NO-GO** while the listed gaps remain.

### Storage-bound snapshot (verified subset)

| State | Code-enforced bound | At capacity / security consequence |
| --- | --- | --- |
| Direct message rows | 5,000 `app/message/` keys (`LocalRepository.capacity`) | Rejects further message creation with `LOCAL_CAPACITY`; does not silently evict history. |
| Group governance journal | 511 signed entries (`GroupGovernanceJournalV1.MAX_ENTRIES`) | Governed mutations reject at capacity; retained contiguous history is not compacted. |
| Group membership/state events | 512 maximum (`GroupStatements.MAX_EVENTS`) | New state transitions reject at limit; signed history is preserved. |
| Group sender-delete markers | 512 (`GroupSenderDeleteFilterV1.MAX_ENTRIES`) | New marker rejects at capacity; terminal content must not be resurrected. |
| Group profile-photo companion | 8,192 encoded bytes (`ProfileRules.MAX_PHOTO_BYTES`) | Oversized companion rejects before activation. |

Bounds and at-capacity behavior for replay records, pending frames, outbox, unread/read markers, sequence counters, reactions, moderation markers, retry artifacts, and attachment cache still require source-and-test verification. This table deliberately does not infer an aggregate storage cap from a per-record limit.

## Remaining R1 work

R1.3–R1.66 in the approved brief remain open, including logging/privacy, exported/URI tests, corruption and crash injection, direct/group adversarial cases, backend authorization, isolated PostgreSQL, release build, dependency review, accessibility, and full test accounting. Classify any finding before fixing it; a HIGH/CRITICAL issue or other stated R1 stop condition blocks R1 GO and the V1 release candidate.
