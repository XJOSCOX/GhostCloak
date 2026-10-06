# Blocked envelope ACK and delivery-status privacy

## Previous receive path and root cause

The sender encrypts and sends; the backend accepts an opaque envelope into the normal mailbox. Android foreground synchronization and WorkManager use the same NetworkController FETCH/receive path. The envelope was fetched and decoded as an outer transport envelope. ConversationService looked up its claimed sender device and threw BLOCKED **before** Signal authentication/decrypt or local message commit. NetworkController caught AppFailure and skipped ACK.

No blocked message row was stored. Neither authenticated sender identity nor ciphertext authenticity was established on this path. The same mailbox item could be fetched again during future foreground/background cycles, until ordinary seven-day undelivered expiry. Sender receipt reconciliation stayed SERVER_ACCEPTED/Queued because the recipient never ACKed. Persistent Queued was therefore a potential block oracle, although offline devices, cryptographic failures and connectivity loss could produce the same observation.

## Secure discard transaction

For a locally blocked peer, ConversationService calls the existing engine.decryptAndCommit. Existing Signal authentication, identity pin/change policy, recipient binding, envelope ID binding, replay checks, admission policy and resource limits remain enforced. Ordinary user content is discarded and zeroed by the engine without decoding it into a conversation payload or attachment descriptor. A4 adds one narrow exception: the authenticated outer frame can identify type 13 as a bounded group-system control. Only maintenance kinds for an already-canonical shared group with a separately anchored current group authority can enter encrypted internal pending state. Type 14 group user text, direct text, attachments, reactions, profile updates, invitations, and other content remain discarded. No blocked content becomes a visible message or notification.

The callback writes the existing app/accepted sender/envelope ciphertext digest and, only for an authorized type-13 maintenance control, a bounded encrypted pending record. This and the engine replay record, ratchet/session update and identity security state commit in the same EndpointRecords transaction. Later semantic validation and any exact backend authority lookup occur outside that cryptographic transaction. There is no visible message row, request transition, unread/read record, notification ledger entry, policy event, attachment descriptor or cache reference. No plaintext is retained for replay protection. Block remains BLOCKED and Add cannot silently unblock it.

The group-only authority is keyed by group and member. It is anchored when a signed admission/ADD is accepted after the existing direct-binding checks, or for an older valid group while the exact direct pin and signed local history are still available before Block or contact removal clears that pin. Missing evidence remains fail-closed; neither canonical state bytes alone nor a blocked status creates trust. The current group record is checked against current canonical membership, the unchanged Signal identity pin, and the exact backend-registered device-auth binding before deferred control processing or a group-system send. This authority cannot authorize direct chat, profile sharing, group user text, or a candidate who is not already canonical. It ends when the member leaves, is removed, or the group dissolves; all such records live in protected endpoint storage and are destroyed by Safe Exit/inactivity destruction.

After successful commit, NetworkController sends the existing normal recipient-device ACK. No backend-generated or offline ACK is introduced. An exact duplicate matches the persisted ciphertext digest and safely returns without decrypting again; ordinary idempotent mailbox ACK may be retried. A conflicting envelope ID/digest or invalid cryptographic envelope must not produce a new security receipt or ACK. Storage failure rolls back security receipt and ratchet together; ACK is never sent before commit.

## Crash, restart and unblock

- Crash/cancellation before commit: the transaction rolls back; redelivery can authenticate/commit later. No ACK has occurred.
- Commit then crash/network failure before ACK: the server retains the item; restart recognizes its existing receipt and retries the ordinary ACK without restoring content or advancing the ratchet again.
- Restart/reboot: Block and replay receipts remain in credential-encrypted storage. No wall-clock/grace expiry applies to these receipts. Before first device unlock, existing background eligibility rules remain unchanged.
- Phase 1O makes explicit Unblock of every peer return to REJECTED/dormant, including previously accepted contacts. Only a NEW authenticated envelope can start a new request. Consumed blocked content has no message/descriptor row to reveal. Pre-Block accepted history is retained locally but hidden while the relationship is unaccepted.
- Reject/Delete continues to permit new requests immediately; it is distinct from persistent Block. Existing accepted local deletion and disappearing-message behavior remain unchanged.

The contact blocked flag and request lifecycle transition are committed atomically on Block/Unblock. Sender disappearing timers still start on ordinary Delivered receipt observation, including device ACK of a silently discarded blocked envelope; no read/view event is introduced.

## Attachments and notifications

Only the small E2EE descriptor envelope is authenticated and discarded. No blob GET, local descriptor/cache/presentation entry, thumbnail, document filename or viewer grant is created. Existing AppRuntime attachment-reference reconciliation sees no new message reference. Blob retention remains independent and unchanged on the server. No Block list, Block flag, timestamp or reason is sent to the mailbox/blob service. There is no blocked notification, even a generic one.

## Delivery meaning and residual leakage

Delivered proves only that the recipient device securely processed and acknowledged the encrypted envelope. It proves neither reading, acceptance, display, human opening of the app, attachment download/viewing, nor absence of blocking.

Online normal and online blocked recipients both ACK after secure processing. Offline normal and blocked recipients stay Queued until actual recipient processing or the same seven-day undelivered TTL. Block no longer deliberately causes a persistent Queued result. Timing, online availability, source/network metadata, ciphertext size and traffic volume remain observable; there is no claim of perfect indistinguishability. Invalid keys/ciphertext, trust-change locks, local capacity exhaustion or storage errors can still prevent ACK, equally under normal authentication policy. No failure is converted into fabricated delivery.

## Bounds and deployment

Existing mailbox batch/cycle budgets, server request/mailbox/blob quotas and rate limits remain unchanged. Both engine and application replay evidence retain the existing 10,000-envelope bound; blocked messages consume that bounded security capacity like normal envelopes. Group controls retain the 12,000-byte body and 128-pending-control bounds; group-system outbox entries use the existing 128-entry durable outbox. No unbounded sink collection or expensive media processing is introduced. Exhaustion fails closed; no replay evidence is silently evicted to manufacture ACKs. Repeated abusive valid traffic still costs bounded Signal processing and may exhaust a device's existing security capacity; this is a limitation, not a new block-specific server policy.

Android-only correction: no backend change/deployment, SQL migration, V007, new endpoint, permission or diagnostics. V006 and seven-day mailbox expiry are unchanged. The A4 deferred group-authority check uses the existing capability lookup when connectivity is available; an offline check remains pending after the ordinary mailbox ACK.

## User-run physical retest (upgrade in place)

1. Fresh A sends a request to B. B Blocks. A sends a second text. B syncs and sees no request/content/unread/notification; A eventually sees Delivered.
2. Keep B offline after Block. A sends and initially sees Queued. Open B: it silently consumes/ACKs; A eventually sees Delivered, B sees nothing.
3. Send photo and document from blocked A. B sees no preview/filename/notification and performs no blob download; A eventually sees Delivered. Blob follows ordinary server retention.
4. Explicitly Unblock A. Old blocked messages remain absent. A sends NEW text: normal pending request/contact behavior resumes.
5. Repeat using Delete rather than Block. NEW text/photo must still create the normal generic hidden request.

Automated tests use synthetic identities/local fixtures and the owned disposable AVD only. Never instrument/uninstall/clear either physical phone. No VPS deployment or access is part of this correction.

## Validation results

P13.4A4 development validation: strict dependency verification, test-support JVM tests and app debug JVM tests, and Android lint passed. The blocked canonical-group integration test covers authenticated maintenance receive, ordinary direct/group-text discard, forged group ID/actor rejection, durable outgoing state update, restart retry and dormant Unblock. The delegated-admission test also covers a locally blocked canonical coordinator's existing request/response path. This proves the restricted channel for current controls; the proposed A3 all-member approval protocol is not implemented or validated here. No physical-device or on-device Safe Exit exercise was performed in A4.

Passed 126 core JVM tests (six new blocked-envelope tests), four attachment-format tests, 33 debug and 26 release unit tests, debug/release builds, and strict dependency verification including IDE source artifacts. Tests cover text and photo/document ACK, offline queuing, no attachment/cache/notification state, commit rollback, restart/reboot-clock recreation, duplicate replay, identity binding, atomic Block/Unblock and the prior Reject regressions.

Disposable AVD: GhostCloak_Phase1J1_Disposable, API 37, emulator-5566 only. An initial full run passed 130 app and 10 storage tests. After the final atomic Block/Unblock adjustment, repeated full app runs exposed intermittent existing UI failures: Compose SlotTable disposal in AppLockScreenTest, then a first-launch submit-callback assertion in ScreenTest (129/130 passed). Both new privacy tests passed. Final separate targeted reruns of AppLockScreenTest, ScreenTest, BlockedEnvelopeTest and RelationshipRegressionTest passed all 10 tests. These flakes are documented; no app-lock/onboarding UI change or test suppression was made. The final storage suite passed all 10 tests.

Validation used the workspace's pre-existing uncommitted AGP/Gradle/verification-metadata updates. Those updates and launcher artwork are excluded from this privacy correction. No physical phone, account-bearing AVD or VPS was used.
