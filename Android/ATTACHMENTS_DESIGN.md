# Encrypted attachments and voice notes

Status: Phase 1I.1 DESIGN ONLY, audited against main `5b793f5`, 2026-09-12. All attachment capabilities, APIs, schemas and limits below are proposals. No implementation or infrastructure configuration is changed.

## 1. Executive summary

Use one client-encrypted blob service for IMAGE, VIDEO, DOCUMENT and AUDIO/VOICE_NOTE. Only a small descriptor travels through existing authenticated E2EE messages. Start with bounded whole-object transfers, streaming AEAD, manual foreground downloads and self-hosted opaque files. Add recording after the foundation and photo/document flows pass their security gates.

### Current audit and blockers

| Evidence | Existing constraint / required future work |
|---|---|
| [Envelope.kt](protocol/src/main/kotlin/org/ghostcloak/protocol/Envelope.kt), [NetworkV1.kt](protocol/src/main/kotlin/org/ghostcloak/protocol/NetworkV1.kt) | Body 16,384 bytes, packet 131,072; request 196,608; response 1,100,000; batch eight. Never inline bulk media or inflate mailbox limits. |
| [Disappearing.kt](messaging/src/main/kotlin/org/ghostcloak/messaging/Disappearing.kt) | Versioned text/policy payload, maximum text 16,368 bytes; unknown types rejected. Attachment descriptor type and compatible-peer rollout are missing. Old clients must not receive unsupported attachment payloads. |
| [ProductionServer.kt](../backend/src/main/kotlin/org/ghostcloak/backend/ProductionServer.kt) | POST/CBOR buffering, mailbox body cap, ten-second request timeout. Separate streaming blob handlers are required, not changed mailbox limits. |
| [MailboxService.kt](../backend/src/main/kotlin/org/ghostcloak/backend/MailboxService.kt) | Default mailbox TTL 24 hours, configurable to seven days; 128 envelopes/8 MiB per mailbox, 2,048 global messages. Separate blob quotas/retention are needed. |
| [GhostCloakTransport.kt](transport/src/main/kotlin/org/ghostcloak/transport/GhostCloakTransport.kt) | ByteArray requests/responses, five-second connect/read timeouts, response cap. New streaming adapter must reuse session renewal rather than copy the auth engine. |
| [DurableOutbox.kt](messaging/src/main/kotlin/org/ghostcloak/messaging/DurableOutbox.kt) | 128 small ByteArray entries; stable ciphertext/submission retry and bounded pending lifetime. Only descriptors belong here; add a separate file transfer journal. |
| [EncryptedEndpointStore.kt](storage/src/main/kotlin/org/ghostcloak/storage/EncryptedEndpointStore.kt) | SQLCipher records, Keystore-wrapped DB key, private no-backup storage. Separate files are NOT encrypted automatically. File encryption/refcounts/crash cleanup are missing. |
| [AppRuntime.kt](app/src/main/java/org/ghostcloak/app/application/AppRuntime.kt), [NetworkController.kt](app/src/main/java/org/ghostcloak/app/application/NetworkController.kt) | Shared runtime and synchronization gates. Bulk transfers must not hold the mailbox mutex; use a runtime-owned coordinator with short metadata commits. |
| [NotificationLedger.kt](messaging/src/main/kotlin/org/ghostcloak/messaging/NotificationLedger.kt) | Encrypted acceptance references and generic notifications; future file deletion must integrate without extra download/play notifications. |
| [AppLockController.kt](app/src/main/java/org/ghostcloak/app/access/AppLockController.kt) | UI lock, not a storage cryptographic gate. New recorder/player/viewer resources require explicit lock cancellation. |
| [BackgroundSync.kt](app/src/main/java/org/ghostcloak/app/application/BackgroundSync.kt) | Unique 30-minute/15-minute-flex network-constrained work; identity/logout/first-unlock gates. Keep descriptor FETCH/STORE/ACK only. |
| [Deployment](../infrastructure/DEPLOYMENT.md) | No audited attachment capacity. Actual disk, bandwidth, ingress body/deadline limits and host backup coverage remain deployment gates. |

Local message deletion currently preserves replay evidence; incoming expiry begins at commit and outgoing expiry only at observed delivery ACK. Neither model currently owns media files. The app minimum is API 30. This audit does not imply that the current client, server or database can transfer attachments.

## 2. Threat model

Protect content, keys, names, captions, EXIF, thumbnails and waveforms from server/storage/proxy operators and network observers. Defend against substitution, truncation, replay, enumeration, malicious authenticated peers, resource exhaustion and crashes. TLS protects session credentials; E2EE protects content from the service itself.

Authentication does not make a file benign. A compromised unlocked endpoint, privileged OS, deliberate recipient copy or external viewer defeats local controls. UI app lock is not root/forensic protection. Deletion means logical removal and best-effort unlinking, not guaranteed flash erasure. Server withholding, timing correlation and denial remain possible.

Never log bodies, filenames, source paths/URIs, IDs, capabilities, tokens, keys, dimensions, duration or waveforms. This includes HTTP access logs and crash reports. Future diagnostics use sanitized category/result codes only.

## 3. Attachment encryption format

Recommend vetted Tink Java/Android Streaming AEAD `AES128_GCM_HKDF_1MB`, fresh library-generated key per object, 128-bit key material/derived AES key. Never reuse Signal/session keys as bulk keys. Single-shot AEAD risks whole-file buffering; adding a separate native crypto stack is unnecessary initially. Pin and verify an Android-compatible Tink release and vectors in 1I.2. [Tink recommendation](https://developers.google.com/tink/streaming-aead).

Tink generates a random salt and seven-byte nonce prefix. A segment nonce includes that prefix, a four-byte index and final-segment flag; each segment has a GCM tag. HKDF binds associated data into key derivation. Use the library framing and final EOF verification unchanged. GCM is not arbitrarily nonce-misuse resistant: fresh keys, library randomness and immutable retries are mandatory. Do not implement counters/tags locally. [Construction](https://developers.google.com/tink/streaming-aead/aes_gcm_hkdf_streaming).

Format v1 fixes the algorithm. Canonical associated data is a fixed domain string `GhostCloakAttachment/v1` plus the fixed 32-byte blob ID. The library header supplies nonce requirements; no invented IV. Interrupted encryption restarts with a NEW key and ID, never guessed cipher state.

Inside E2EE only: version, ID, separate read capability, strictly allowlisted single-key Tink serialization, true/padded/ciphertext lengths, ciphertext SHA-256, media kind, optional sanitized filename/MIME/dimensions/duration, and effective disappearing duration. No source path or URL. Reject extra keys, unknown algorithms and malformed parameters before constructing a primitive. Proposed one attachment/message, descriptor at most 4 KiB, caption at most 4 KiB; complete framing/padding still fits 16 KiB. Exact canonical encoding, vectors and peer-version negotiation are implementation blockers.

Pad plaintext with random bytes to the next 64 KiB boundary up to 1 MiB, then next 1 MiB boundary. Authenticated true length determines trimming. No padding exposes exact length; crypto chunking alone exposes final-segment length. Coarse buckets cost less than 64 KiB/1 MiB respectively, obscure small differences, but do not hide broad media classes. Avoid extreme cover padding.

Enforce caps while streaming; verify ciphertext length/digest, every tag, final EOF and all authenticated lengths before publishing plaintext. Reject appended/truncated/reordered data and arithmetic overflow. Digest binds the exact ciphertext, never replaces AEAD. Keep memory to a small number of 1 MiB segments. No partial-file rendering; future random-access playback needs separate finality review.

## 4. Blob identifier/authorization model

Client-generated 256-bit CSPRNG IDs, canonical encoding, never names/timestamps/account IDs/plaintext hashes. Validate fixed syntax and reject path-like input; collision requires a new key/ID. Require both an authenticated session and an independent 256-bit read capability for download. Store capability hash only; compare in constant time. Capability travels in E2EE and a redacted request header, never query/URL. ID alone is insufficient.

Uploader session owns reservation/upload, without recipient ACLs. A forwarded full descriptor grants an authenticated holder download/decryption until TTL; bearer capability sharing cannot be prevented. Capability alone reveals ciphertext, not the key. Rate-limit probes and use uniform unavailable responses. Never follow arbitrary descriptor origins or forward credentials across redirects.

## 5. Server-visible metadata

Server/ingress observe encrypted size, upload/download times, authenticated uploader/downloader, IP/network metadata, object counts and retention dates. They can correlate repeated retrieval and nearby mailbox sends. No anonymity claim follows from random IDs.

All types use octet-stream, opaque filenames without extensions and common routes. No plaintext type/name/caption/key/dimensions/duration/EXIF/thumbnail/waveform/timer is sent. Format framing is public. Size/timing may suggest voice/video; padding does not conceal meaning perfectly. No plaintext-hash deduplication.

## 6. Upload architecture

Select/record -> bounded private staging -> sanitize -> capture effective policy at Send -> encrypt -> finalize immutable ciphertext and encrypted journal -> reserve/upload -> confirm durable server completion -> enqueue the normal E2EE descriptor with visible message state atomically.

Persist key/capability/ID/digest/length/duration in SQLCipher before network activity. Random local filenames only. Fsync/atomic rename plus recovery reconciles non-atomic filesystem/DB boundaries. Retry identical ciphertext and reservation; preserve existing outbox submission safety. Upload failure never sends a descriptor.

One runtime-owned bulk transfer at a time initially, outside the mailbox mutex. Reuse coalesced one-retry authentication renewal, cooldown and logout eligibility; never register identity. Check cancellation/generation again before committing completion. Attachment errors stay on the item, not global reconnect UI. Foreground polling remains unchanged.

## 7. Download architecture

Authenticated descriptor commit -> accepted conversation plus explicit Download -> validate lengths/storage -> authenticated GET -> private ciphertext quarantine -> length/digest check -> streaming AEAD into private plaintext quarantine -> final verification -> recheck lock/deletion/expiry -> READY.

No parser, thumbnail, MIME sniffer or duration extractor receives quarantine bytes. Reject HTTP compression transformations, oversized streaming bodies and redirects. Do not trust Content-Length alone. On failure remove quarantine, leave descriptor delivery state intact. Full verification needs disk, not whole-object RAM: budget ciphertext plus plaintext/workspace. No public Gallery/Downloads writes. Whole-object v1 retries discard partial downloads.

## 8. Local storage lifecycle

| Class | Protection and lifetime |
|---|---|
| A plaintext staging/quarantine | Private credential-encrypted backup-excluded scratch, random names. Delete on cancel/failure/lock/background; sweep before startup media UI. |
| B encrypted upload cache | Private no-backup AEAD files with SQLCipher journal. Survive restart/reboot/update while delivery references require them. |
| C encrypted download cache | Private no-backup AEAD files; keys/references in SQLCipher. Survive restart/reboot/update subject to expiry/delete/eviction; proposed 100 MiB evictable budget. |
| D decrypted view/preview | Private scratch only, no durable plaintext library. Delete on viewer close, lock/background/logout, delete or expiry. Crash leaves startup sweep obligation. |

Before first device unlock do nothing. Update/startup must reconcile journals and sweep scratch. Logout cancels transfers and deletes A/D; retain B/C only for history/outbox the existing logout preserves, with automatic network disabled.

Delete/expiry removes presentation files, derivatives and unneeded keys. A hidden pending outbox may still require descriptor key/capability/ciphertext for authorized delivery: retain that minimum transport reference invisibly until terminal reconciliation. Do not falsely claim all bytes removed while delivery depends on them. Reference counts plus a durable deletion queue reconcile crashes; SQL transactions cannot atomically unlink files. Sweep unreferenced files on runtime activity/startup, no new worker. External exports cannot be recalled.

## 9. Photo/video handling

Use single-item Photo Picker contracts with SAF fallback. API 30 does not guarantee picker availability; no Google Play dependency or broad media/storage permission is required. Bounded copy into private staging, narrow URI grants, release when unnecessary. A selected system provider may fetch its own cloud content; Ghost Cloak adds no cloud service. [Picker availability/fallback](https://developer.android.com/training/data-storage/shared/photo-picker).

Normalize supported still images into new JPEG/PNG, bake orientation, bound dimensions; reject unsupported animation/HDR rather than preserving unknown metadata silently. Source decoders also process untrusted input: bounds-first/sample decoding and resource limits apply before encryption. Video normalization to a tested MP4 profile belongs to 1I.5. If sanitization fails, do not silently send original media.

## 10. Document handling

SAF ACTION_OPEN_DOCUMENT, one explicit stream, no recursive scan/directory grant. Enforce bytes despite absent/false provider size. Preserve arbitrary document bytes; disclose embedded author/location/revision metadata may remain encrypted inside them. No document sanitization promise.

Optional E2EE filename: normalize, cap 128 UTF-8 bytes, remove separators/controls/bidirectional overrides, reject dot/empty names and use generic fallback. Never use display name as a path or log URI/name. External opening requires explicit selected viewer and narrow temporary read-only content URI. Revoke access on close/lock/expiry where possible; explain other apps may retain copies. No automatic privileged WebView/document preview.

## 11. Voice-note recording

Tap mic -> contextual permission request -> visible recording and elapsed time -> tap stop -> private preview/play/pause -> delete/re-record or Send. No upload before Send. Request RECORD_AUDIO only after Record; denial affects recording alone. No cloud transcription, speech upload, third-party audio service or duration/content analytics.

Record only on the initiating foreground unlocked screen. Background/lock/logout/interruption stops and discards active recording, releases mic, never resumes automatically. Rotation cannot create a second recorder. Enforce byte/time caps, handle empty/failed stop, unlink canceled plaintext immediately where practical. Killed-process scratch is cleaned on startup, never automatically uploaded. Android requires runtime microphone permission and restricts background capture. [MediaRecorder](https://developer.android.com/media/platform/mediarecorder).

## 12. Voice format and playback

Recommend native Opus/Ogg, mono 48 kHz, target 32 kbit/s, five minutes maximum. API 30 exceeds the Android 10 Opus encoder baseline; AAC-LC/M4A has broader historical support but generally costs more bytes for speech. Both use platform decoding; neither guarantees parser safety. [Android formats](https://developer.android.com/media/platform/supported-formats).

MediaRecorder OGG explicitly supports Opus from API 29; do not assume its WEBM mode is Opus. Test OEM/API 30 and current devices. Unsupported recorder configuration fails locally, never raw PCM/cloud fallback. AAC/M4A remains a later explicit alternative if device evidence warrants it. [OutputFormat](https://developer.android.com/reference/android/media/MediaRecorder.OutputFormat). Opus publishes royalty-free patent grants/software terms; review dependency notices at implementation. [License](https://opus-codec.org/license/).

Play authenticated private content inside Ghost Cloak: play/pause/progress, no autoplay, cloud/network player or lock-screen notification controls. Stop/release on lock/background/logout and recheck expiry on open. Full verification precedes playback; speed controls and progressive streaming are future work.

## 13. Metadata/EXIF policy

Rebuild known photo/video formats without GPS, device make/model, original capture time, comments or arbitrary tags. Preserve only bounded rendering necessities. Removing selected EXIF tags is insufficient; test orientation/color and hidden tags. Unsupported normalization fails closed. Future original preservation must be explicit, still E2EE. Documents remain unchanged as disclosed. New voice containers contain no user/device tags. Server never receives plaintext metadata.

## 14. Thumbnail/waveform policy

Initial recipient-generated derivatives only after complete decrypt/authentication; sender preview local only. Retained derivatives encrypted; decrypted surfaces follow scratch/lock lifecycle. This delays previews but avoids new requests and descriptor pressure.

Separate fresh-key encrypted thumbnail blobs could reduce future latency at storage/request/correlation cost. E2EE inline thumbnails/waveforms consume scarce payload budget. Neither is initial scope. Never plaintext server thumbnail or separately uploaded waveform. Prefer recipient-generated waveform over transmitting speech characteristics.

## 15. Disappearing-message integration

Freeze effective duration at Send before upload, carry it inside authenticated content. Policy changes affect future sends. Recipient timer starts at authenticated descriptor durable commit, then ACK. Sender PENDING/SERVER_ACCEPTED has no deadline; observed recipient ACK atomically transitions to DELIVERED and establishes deadline once. Repeated receipt/restart never extends it.

ACK means descriptor stored, NOT media downloaded/viewed/played. Short timers may expire before manual download: disclose this, never delay/reset timers. Expiry cancels transfer/playback, hides files and removes derivatives/unneeded caches; preserve replay evidence and reconcile unread/notification ledger. OS suspension may delay unlinking, but logical access fails closed on resume. No timer metadata goes to server.

## 16. Local-delete integration

Delete is local-only, not recall/unsend. Hide immediately, revoke viewer access, remove plaintext/derivatives/unneeded encrypted references, preserve replay tombstones. Never delete remote blob because of local delete or descriptor ACK; recipient may still need it. Hidden delivery references follow section 8. Future orphan deletion needs proof of no pending recipient dependency.

## 17. Message-request safety

Authenticated small descriptors may enter the existing request path under existing limits. Show generic attachment request, no body download/parser/thumbnail/waveform before acceptance and explicit action. Treat authenticated names/types/dimensions as untrusted claims. Blocking cancels transfers; acceptance is not verification and cannot bypass changed-key gates.

## 18. Parser/malicious-file defenses

Enforce byte caps and overflow-safe arithmetic before disk/allocation exhaustion. After authentication sniff allowlisted formats, never trust extension/MIME. Proposed image input bounds: 32 megapixels and 8,192 pixels per edge, sampled decode; output at most 4,096 per edge. Bound decoder memory/time and handle failure. Video decoder/transcoder budgets need device measurement before 1I.5.

Never unpack archives or recursively inspect ZIPs; no automatic document preview. Authenticated PDFs/docs may remain malicious and require explicit external viewing. Prefer maintained Android decoders over plugins/privileged WebViews; no invulnerability claim. Test authenticated malicious files separately from corrupted ciphertext. Apply import-side parser budgets too.

## 19. Background behavior

V1 media transfers are foreground, unlocked and user initiated only. Background cancels body transfer and plaintext scratch; retain finalized ciphertext/journal for later explicit retry. No service/socket/alarm/new worker or force-stop workaround.

Existing worker fetches/decrypts/stores/ACKs descriptor only; existing generic notification has no name/preview/waveform. Tap goes through root app lock to Chats. Current UI lock permits background descriptor storage; future Keystore-gated mode needs separate key-availability analysis. No Direct Boot/device-protected credentials and no notification behavior change.

## 20. Size/duration limits

Proposed binary MiB limits, not measured VPS capacity:

| Type | Plaintext cap | Other cap |
|---|---|---|
| Image | 10 MiB | Section 18 pixels, one/message |
| Document | 20 MiB | No internal preview |
| Voice note | 4 MiB | Five minutes, whichever first |
| Video, 1I.5 only | 25 MiB | Initially two minutes/1080p, subject to device tests |

Source import capped at 25 MiB before normalization; large camera originals are deliberately excluded initially. Common server ciphertext maximum 26 MiB including padding/AEAD; enforce exact computed format length too. Server cannot enforce private media types, only common byte cap. Defer 20 MiB images/50 MiB documents/100 MiB videos due to retries, scratch and abuse costs. Five minutes limits battery/storage and roughly halves ten-minute voice cost.

## 21. Retention/orphan cleanup

RESERVED/PARTIAL -> COMPLETE -> EXPIRED -> removed. Proposed partial/reservation TTL one hour from creation; complete TTL seven days from atomic completion. Repeated requests/downloads never extend deadlines.

No server 'referenced by mailbox' state: linkage is encrypted. A complete upload followed by failed message send is indistinguishable from a used blob and gets the same seven days. This intentionally trades longer completed-orphan retention for avoiding outer linkage APIs. Only incomplete reservations have short orphan TTL. Local journal retries obey existing outbox lifetime, never refresh forever.

Do not delete on first GET or descriptor ACK; retries/offline recipients still need bodies. No body ACK endpoint initially. Default mailbox TTL is 24 hours: seven-day blobs provide later manual-download opportunity, not delivery beyond mailbox TTL. Configuring mailbox TTL near seven days requires revisiting the availability window before rollout. Missing/expired body is Unavailable, not altered delivery state. Only explicit new send can create a new attachment; never silently resurrect/reset timers.

## 22. Server storage design

Propose `/var/lib/ghostcloak/attachments`, dedicated service owner, directories 0700/files 0600, outside web root/DB data. Canonical random names, no extensions/user directories/symlinks/listing. Bounded temporary file on same filesystem -> fsync/atomic rename -> COMPLETE. Authenticated application serving only, no shared proxy cache.

Cleanup every 15 minutes and startup reconciles DB/files/quotas. Serve-time expiry rejects immediately even if physical deletion lags. Reserve capacity transactionally; bulk I/O stays outside mailbox advisory/domain transaction lock. Local VPS storage is simplest but shares disk/failure limits. Later self-hosted S3-compatible storage adds operational complexity; review maintained options/licensing then, not assume a particular MinIO distribution. No external object store selected.

Exclude ephemeral blobs from initial backups, accepting availability loss on VPS failure. Audit host snapshots too. Backed-up metadata restore must reconcile missing objects and expiry before serving. Any later blob backup requires explicit bounded retention (at most seven days) and restore filtering; E2EE remains intact but privacy retention increases. No claim of physical erasure from snapshots/operator copies.

## 23. API proposal

Separate versioned surface; nothing implemented:

| Endpoint | Auth / minimal data | Behavior |
|---|---|---|
| POST /v1/attachments | Session; ID, ciphertext length/digest, capability hash | Reserve quota; identical owner request returns state/deadline, also reconciles uncertain completion |
| PUT /v1/attachments/{id} | Owner session, exact octet-stream | Whole immutable object; stream length/digest check; atomic completion; retry never extends TTL |
| GET /v1/attachments/{id} | Session + separate read-capability header | Complete unexpired ciphertext only |

No filename/type, listing/directory/delete, multipart filename, arbitrary URL or query credentials. Uniform unavailable GET errors; bounded control bodies/headers; conflict on nonidentical reservation. Existing one-retry 401 and 429 cooldown apply; 413 cap/503 transient are item errors. Streaming deadline/ingress validation is required independently of mailbox policy.

Minimal DB fields: random ID, owner account/device for auth/quota, capability hash, ciphertext length/digest, creation/completion/expiry dates, state/reserved bytes. No key, plaintext type/name/contact/message link/timer. Ciphertext digest is operational integrity metadata, never plaintext hash.

## 24. Quotas/abuse controls

Staging proposal: 200 MiB retained/reserved per account across devices; 50 MiB completed/day; two reservations and one active upload/account; ten reservation attempts/minute. Charge reservations immediately; idempotent retries do not double-charge or refund capacity. Bound downloads/concurrency; propose 500 MiB egress/account/day plus service cap. Account-creation abuse remains a limitation, not a reason for invasive analytics.

Initial global pool 10 GiB, preserve at least 5 GiB free plus separately budgeted OS/DB capacity. Operator must confirm resources before enabling. Twenty accounts uploading 50 MiB/day for seven days consume 7,000 MiB before overhead; 100 exceed this pool. Stop new reservations on pressure without deleting promised unexpired objects or blocking mailbox operations.

## 25. Failure recovery

| Failure | Required outcome |
|---|---|
| Cancel recording | Release mic/delete scratch, no upload |
| Kill during recording/encryption | Startup cleanup; fresh key/ID on explicit restart |
| Interrupted upload | No descriptor send; bounded retry of finalized ciphertext |
| Upload success/message failure | Reconcile journal then existing outbox retry; TTL cleans eventual orphan |
| Message success/local state loss | Recover original submission/ciphertext; no duplicate message or policy reset |
| Partial download | Never render, discard/retry whole object initially |
| Authentication failure | Delete quarantine, FAILED; no plaintext fallback or delivery change |
| Full device disk | Clean partials; retain descriptor for manual retry |
| Lock/delete/expiry/logout race | Generation/reference recheck prevents publication; close handles/reconcile deletion queue |
| Lost/expired remote file | Unavailable body, never false download/play receipt |

Separate transfer state from MessageState: PREPARING/ENCRYPTING/UPLOADING precede descriptor PENDING; QUEUED is SERVER_ACCEPTED; DELIVERED remains descriptor ACK. Receiver DOWNLOADING/VERIFYING/READY is independent. FAILED/CANCELED/EXPIRED describes body availability. Avoid ambiguous SENT labels; do not overload receipt meaning. Restart preserves captured duration/deadline.

## 26. Battery/bandwidth policy

32 kbit/s voice: approximately 240 kB/minute, 1.2 MB/five minutes before overhead; 64 kbit/s AAC approximately 2.4 MB/five minutes. Photos may normalize to 1-4 MiB but caps remain authoritative. Three attempts at 25 MiB approach 75 MiB per direction. At 1 Mbit/s one 25 MiB upload takes about 210 seconds before overhead, incompatible with current ten-second mailbox route.

Manual foreground body downloads initially; no auto cellular video, explicit large-transfer confirmation. A later optional Wi-Fi-only automatic-download setting for accepted contacts should default off. Whole-object v1 is simpler; resume belongs to 1I.5 if measurements justify it. Resume only immutable ciphertext at validated offsets, with full final verification and no partial rendering. Bounded retry/backoff honors cooldown without altering mailbox polling. No telemetry/cover traffic.

## 27. Implementation phases

- **1I.2 foundation:** finalize descriptor/compatible-peer rollout, pinned Tink vectors, streaming auth adapter, encrypted journal/refcounts, capability API, quotas/TTL/crash recovery. Test tampering/finality/nonce freshness/overflow/logout races before user media features.
- **1I.3 photos/documents:** picker/SAF, sanitization, explicit download/view, unknown-sender gates, expiry/delete integration and external-viewer privacy.
- **1I.4 voice:** just-in-time mic, Opus/Ogg, private playback, lifecycle/lock cancellation and caps.
- **1I.5 video/larger transfers:** measured normalization/ingress/capacity; resumability only after integrity review. Raising limits requires separate evidence.

Each implementation slice retains Phase 1E/1F/1G/1H tests, adds JVM/emulator/two-phone coverage, strict dependency verification and debug/release builds. Backend/PostgreSQL tests apply to future server changes. Design-only 1I.1 cannot test nonexistent transfers.

## 28. Physical-device test plan

Use API 30 and current Android/OEM phones plus deterministic emulator/fault fixtures; record expected/actual results without private metadata.

| Area | Matrix / acceptance |
|---|---|
| Voice | Record/cancel/preview/send; permission denial; cap; background sender; offline recipient then download/play; disappearing/local delete/lock; no autoplay/mic after background |
| Photo | Picker/fallback, EXIF/location/time removal and orientation; offline recipient; corrupted blob/pixel bomb; disappearing cleanup |
| Video | Oversize, cancel upload, slow cellular/Wi-Fi change; bounded memory, no automatic cellular download |
| Document | Arbitrary bytes preserved, spoofed MIME/name/path, oversized unknown-length stream, malicious PDF/ZIP; no automatic parser; explicit viewer/grant limitations |
| Delivery/expiry | Descriptor ACK before download still Delivered; queued beyond timer remains; ACK starts sender timer once; restart/policy change preserve duration; short incoming expiry blocks later download |
| Delete | Delete during transfer/verification, preserve authorized recipient delivery, no stale publication/replay resurrection |
| Lock/background | Generic notification/root lock/Chats; no name/preview/waveform/Recents leak; worker descriptor only; first-unlock/logout gates |
| Crypto/auth | Wrong key/capability/ID, reordered/truncated/appended segments, repeated 401/concurrent renewal; fail closed, one renewal |
| Recovery | Kill every journal/file/DB/send boundary; full disk/missing file/TTL/duplicate PUT; no false Delivered or exposed scratch |
| Server privacy | Inspect controlled test files/DB/modes/quotas/backups and proxy/app logs: opaque ciphertext, no plaintext names/type/key/timer/private diagnostics |

Actual VPS capacity/ingress behavior, Tink integration and peer-version negotiation remain release blockers. Use controlled fixtures before real private attachments.

## 29. Explicit non-goals

No implementation, dependencies, endpoints, migrations, upload/download/recording/picker/player/permissions in this phase. No changed notification content, remote deletion, view-once, screenshot detection, calls/video calls/live streaming, cloud transcription, AI media processing, external storage/push, microphone service or weakened app lock/identity/crypto/session behavior. No indefinite availability, traffic anonymity or erasure of exported copies promised.

Recommended order: **1I.2 encrypted blob foundation -> 1I.3 photos/documents -> 1I.4 direct voice notes -> 1I.5 video/resumability**, satisfying each gate before proceeding.
