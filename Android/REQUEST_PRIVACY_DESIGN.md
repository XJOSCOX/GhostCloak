# Phase 1J — request privacy and mailbox retention

## Implemented behavior

New and existing installations default to **Require confirmation**. The private encrypted preference is captured when a new request begins. Turning it off allows text in future requests to be presented; it never reveals an existing hidden request. Existing accepted contacts are unchanged. Existing pending requests acquire a hidden record with a bounded migration grace period; account/device credentials and Signal records are not replaced.

The UI receives no messages for a hidden request, including no text, descriptor, filename, thumbnail, media type, disappearing policy or content extension. Lists/conversations show a generic request and the existing identity label. Request timer deadlines are excluded from unread UI metadata. All unaccepted attachments remain unavailable for retrieval, previews, automatic downloads and external viewing, including when visible-text requests are enabled. Notifications keep the existing stronger generic policy. App lock continues to remove the entire private UI subtree.

View identity / Verify uses the existing pinned cryptographic identity and safety-number screen. Accept only changes the local contact/request state; it does not mark the identity verified. Actual verification still requires the existing explicit comparison. Accept reveals surviving stored content and enables normal attachment access. Delivery remains a separate transport fact: a hidden request can already be Delivered.

## Threat model

Protect against unsolicited content disclosure, malicious media parser exposure, stale request resurrection, unbounded ciphertext retention, hostile retries, accidental clock changes and accidental identity reset during upgrades/testing. The authenticated TLS service supplies mailbox time and opaque transport status; it is trusted for availability/time reporting, not message confidentiality or Signal authentication. A malicious service can delay/drop traffic or lie about time; this phase does not claim to prevent that. Compromised/rooted endpoints remain outside the UI-lock threat boundary.

No new server-visible request flag, acceptance state, privacy preference, block list, disappearing duration, content type or filename is introduced. Server timing, authenticated principals, routing metadata and existing envelope sizes remain visible. No new analytics or logging of private identifiers is added.

## State and local storage

Encrypted `app/request/<device>` records contain PENDING / ACCEPTED / REJECTED / BLOCKED / EXPIRED, a frozen hidden-content preference, the initial server acceptance time when available, a boot/monotonic grace deadline and a retry watermark. The device key is an existing encrypted local namespace, not a WorkManager identifier or log field. Existing contact.request/blocked values remain for compatibility and are updated with the explicit lifecycle record.

Payload authentication/decrypt, accepted-envelope replay evidence, message commit, request state and expiry cleanup happen inside the existing engine commit transaction. Unaccepted control messages continue to be rejected by the existing control gate. Text/attachment requests can be authenticated and stored without presentation. Expired or previously rejected requests retain replay evidence while newly committed content is removed before it can reach UI. Acceptance rechecks expiry transactionally and cannot revive EXPIRED records.

Local deletion removes messages/descriptors/read records and notification ledger entries; runtime attachment-reference reconciliation deletes unreferenced encrypted cache files and uses the existing durable deletion marker. App lock/background still cancel scratch/preview access. No decryption keys or original endpoint files are migrated to another storage area.

## 72-hour request window and trusted time

For newly fetched requests, start = `Delivery.receivedAt`, the server mailbox acceptance timestamp of the initial envelope, not the time the phone eventually downloads it. Deadline = start + 72 hours. Subsequent messages do not extend a pending window. If the first fetch occurs after that deadline, authenticated processing records replay evidence, removes content and produces no active request or notification. Existing ACK semantics remain: committed incoming processing can ACK even when its local content immediately expires.

An opt-in FETCH returns serverTime. Android persists this reference alongside boot count and elapsed realtime, then projects it using monotonic elapsed time. Device wall-clock forward/backward changes do not alter this request projection. Process recreation on the same boot preserves it. After reboot, a server-timed request cannot be accepted until a fresh reference arrives. Cleanup resumes on sync. This deliberately prefers withholding stale content to granting a new 72-hour window.

Legacy requests with no reliable server acceptance timestamp receive a one-time 72-hour migration grace measured with elapsed time. The first fresh server reference binds the remaining grace to server time; if reboot happened before that first reference, the binding grants at most one server-timed 72-hour grace. Offline reboot before any server reference can defer cleanup; content remains hidden by default. This is an explicit legacy limitation, not a claim that arrival timestamps are authoritative. New server-timed requests do not use that migration path.

UI/background cleanup is best effort when Android executes the app. There is no exact alarm. Request acceptance and UI queries recheck expiry, so a delayed scheduler does not authorize stale content. Server-clock stability is assumed; monotonic projection avoids extending time when a later server sample moves backward. Network transit can delay the local projection slightly. These are retention timers, not cryptographic proof of time.

## Disappearing messages

Incoming disappearing expiry still starts on authenticated durable commit. A request's content is deleted at whichever valid deadline happens first: its incoming disappearing deadline or the 72-hour request deadline. Acceptance never resets either deadline and never reveals previously expired plaintext. The request identity/card may remain until its own deadline after its disappearing content is gone. Outgoing disappearing timers still begin only at recipient delivery ACK observation; EXPIRED_UNDELIVERED never starts one. The server never sees the duration.

## Delete, Block and later requests

Delete sets REJECTED, removes content and the active request, and does not set blocked. The same cryptographic sender can start a new request only with a new envelope accepted by the server more than one hour after the local server-time rejection watermark, and only while that new envelope is within 72 hours. Requests received during cooldown are authenticated/deduplicated and discarded; continued attempts refresh the watermark. Existing replay evidence prevents replay from acting as a new request. Without authoritative time, restarting a rejected request fails closed until sync supplies a reference.

Block stores the private blocked contact state and BLOCKED request tombstone, removes request content, and prevents future presentation from that identity. A blocked packet does not receive a distinct server error: the service has no block list. Its missing ACK is observable in the same way as an unavailable/offline recipient, eventually followed by general mailbox expiry. This is not perfect traffic-analysis resistance: a sender may make guesses from timing. Existing directory lookup still discloses valid usernames with available prekeys; nonexistent and exhausted identities retain the same generic contact-unavailable response. This phase does not claim username enumeration has been eliminated.

The existing 60-operation/minute/principal server limit, 128 queued envelopes / 8 MiB per mailbox, 2,048 global mailbox rows, 1,024 submission records per sender, 10,000 global submission records, and 200 local contacts bound the current single-device staging design. The private one-hour retry cooldown adds presentation-spam protection without a server-visible relationship flag. No separate request-only server counter is introduced, because the server cannot safely classify requests. New-identity/Sybil spam and capacity exhaustion remain threats; these conservative fixed capacities fail closed, not by collecting invasive fingerprints.

## Seven-day undelivered TTL

The production policy is now seven days from original server mailbox acceptance. Default was previously one day. V005 updates still-queued legacy rows to received_at + seven days. New server rows use the same deadline. FETCH always excludes expired ciphertext, even when a bounded cleanup pass has not deleted every expired row yet. ACK only succeeds as a delivery receipt while the owned row is still unexpired. The existing database transaction/advisory lock serializes ACK, fetch and cleanup; at the expiry boundary an ACK cannot convert an expired envelope into Delivered.

The server does not classify request-init envelopes. They use seven days too. Android's 72-hour policy is stricter and can discard an offline request on arrival. This explicitly chooses relationship privacy over separate server request TTLs, as allowed by the phase requirements.

The existing 30-second retention worker invokes indexed, bounded mailbox deletion (maximum 128 rows per pass). Opportunistic cleanup uses the same method. Deletion is transactional, idempotent and crash-safe. Existing bounded challenge/session cleanup remains. No full mailbox scan is performed to select cleanup candidates in PostgreSQL. Existing fetch/quota queries and in-memory fixtures retain the prototype's hard table caps.

## Sender reconciliation, retry and retained evidence

Opt-in FETCH `retention=true` returns `DeliveryStatus.expired` in addition to the existing acknowledged bit. These fields are mutually exclusive. Updated Android changes a matching SERVER_ACCEPTED message to EXPIRED_UNDELIVERED only from the authenticated response. The bubble stays locally visible with **Expired before delivery**, the disappearing timer stays unset, receipt polling stops for that message and any leftover outbox entry is discarded before retry. Never-server-accepted pending work retains its separate previous semantics. A late process restart does not reset the server clock.

Expired payload deletion retains the existing small submission hash/server-ID/ACK evidence plus mailbox_expires_at. These tombstones are no longer automatically purged by dedupeTtl. They let late-returning senders reconcile accurately and prevent the same submission/ciphertext from recreating a mailbox row. They remain bounded by the existing per-sender/global caps; consequently long-lived busy installations can exhaust these limits. An explicit future receipt retirement/account lifecycle design is required before increasing staging scale. No silent deletion of evidence or account takeover/recovery is used to escape capacity. Local history and encrypted attachment cache needed by retained sender history remain; no remote attachment recall is implied.

Legacy receipts already deleted by an earlier backend cannot be reconstructed: current fallback still fetches the inbox without those unavailable receipt IDs, and such old history may remain queued rather than inventing Delivered/Expired evidence. All retained/new receipts use authoritative terminal reconciliation. This migration limitation must not be represented as successful delivery. Separate blob retention remains unchanged and can make a late attachment body unavailable even after its envelope was stored; Delivered still does not promise attachment download/viewing.

## Protocol compatibility and migration

No E2EE framing, bulk encryption format or Signal keys change. Existing FETCH request encoding is unchanged unless retention is explicitly enabled. Legacy callers receive the old status shape and no serverTime; updated backend supports them. Updated Android must be deployed AFTER the matching backend: older strict decoders reject the new opt-in request field. Existing endpoints are reused; no nginx/Cloudflare route is added.

Required migration: **V005__mailbox_retention.sql**. It adds message_deduplication.mailbox_expires_at, backfills from remaining mailbox rows, changes remaining mailbox deadlines to seven days from acceptance, and creates the expiry cleanup index. Missing legacy payloads get zero expiry; acknowledged still takes precedence. V001–V004 are unchanged and checksummed. Startup requires schema version 5; running the old backend after migration is not a supported rollback.

Deployment (not executed by this change):

1. Build the immutable backend distribution with `./gradlew :backend:installDist --dependency-verification strict` from Android/.
2. Follow infrastructure/DEPLOYMENT.md for private backup and release ownership. Stop the existing ghostcloak service for the schema change; keep credentials in the existing protected environment.
3. Install the new distribution in a new release directory. Run that release's `bin/backend migrate` with the migration-role environment. This validates V001–V004 and applies/checksums V005 transactionally. Do not edit schema_history or run tests against production.
4. Point the service to the matching release and start it with the restricted service-role environment. Verify health and sanitized logs; confirm schema version 5 and seven-day queued deadlines using a privileged local audit without dumping content/IDs publicly.
5. Then update Android in place with the existing signing/package/origin. Do not clear data, uninstall, recreate identities or alter account ownership. Backend deployment and migration are required; neither is claimed performed here.

## Validation and physical retest

Host suites cover request hiding and reveal, captured preference, legacy grace, monotonic time, reboot/reference requirements, Delete cooldown, blocking, replay, independent disappearing semantics, seven-day boundary, ACK/expiry ordering, duplicate cleanup, restart, idempotent resend and new-envelope delivery. The same retention probe runs against PostgreSQL V005. Existing attachment tests now expect no pre-acceptance UI payload; the old Delete-as-Block expectation is replaced with a hidden rejected request.

Android testing MUST use a fresh disposable AVD. This phase uses GhostCloak_Phase1J_Disposable on emulator-5564 with no account data copied from any other AVD/phone. Never run connected tests on an account-bearing installation, even an emulator. JVM/test builds do not authorize physical device cleanup.

Validation completed: 107 core JVM tests, 33 app JVM tests, 15 isolated local PostgreSQL tests, debug/release builds and strict dependency verification passed. Disposable Android coverage comprises 125 app tests and 10 storage tests. The final full app run passed 124 tests and exposed a first-launch test click/teardown race; the corrected four-test NetworkScreenTest rerun passed. Its compact-AVD keyboard/scroll click now invokes the enabled button's semantics action, and teardown disposes polling/joins ViewModel work before closing the fixture runtime. No production account-creation code changed. Physical phones and the existing user AVD were not test targets. No connection to or deployment on the live VPS was performed.

After backend deployment and in-place Android updates on both phones:

1. Use two existing test identities that are not accepted contacts (or separate disposable test identities, never reset a real account). A sends `secret test` to B.
2. B unlocks: list/card is generic, no secret text, attachment details or disappearing timer. Open View identity / Verify and compare safety numbers through the existing trusted channel. Acceptance alone must not produce Verified.
3. B accepts: surviving text appears; reply normally. Confirm A's Delivered was based on B's storage ACK, independently of acceptance.
4. Repeat with a photo/document from an unaccepted test sender: no thumbnail, filename or body download before Accept; after Accept the normal attachment flow works.
5. Repeat Delete: request/content/unread disappears, sender is not marked blocked; a fresh request is permitted only after the private cooldown. Repeat Block: future messages never surface and no distinct blocked API response appears.
6. Change privacy to immediate-visible, create a NEW request and confirm text visibility; an older hidden request must remain hidden until accepted. Return the preference to Require confirmation.
7. Run the injected-clock request and mailbox tests for 72h/7d boundaries instead of changing either real phone's clock or waiting days. Use dedicated staging identities/tooling for any deployment-level timing exercise; no production time override endpoint is included.

## Non-goals

No view-once, unsend, remote deletion, reactions, read receipts, message editing, voice/video/calls, groups, new identity/recovery flow, push provider or notification content is introduced. No physical-device reset or backend deployment occurs automatically.
