# Phase 1E.1 review and rollout

The backend already permitted authenticated one-way sends to published routes. Mutual-add was an application receive gate. The existing Signal engine supports Unverified first-contact sessions; this phase admits them as explicit message requests. Accept enables replies without marking the identity Verified. Block & delete retains a blocked tombstone to prevent automatic recreation. Known identity changes still require explicit approval.

## Receive and delivery safety

`decryptAndCommit` runs existing Signal decryption and application acceptance in one endpoint transaction. Its non-suspending callback must use the same EndpointRecords instance and cannot retain plaintext. Failed storage rolls back the ratchet, new pin and message receipt. Repeated delivery recognizes the saved exact-envelope hash before ACK. Cryptographic algorithms and ciphertext formats are unchanged.

Delivered means the receiving client decrypted and stored the message and the server received its ACK. It can occur before the person accepts a request. It never means read, verified or merely uploaded. ACK atomically sets a boolean on the existing submission row and removes mailbox ciphertext. Expiry cannot create an ACK receipt. Receipt retention remains the existing seven-day deduplication TTL, with no new timestamps or network metadata.

## API compatibility and deployment

The existing authenticated Fetch route adds optional `includeSenders`, `submissionIds` (maximum 8) and `skipMessageIds` (maximum 128). Sender profiles use existing account/device/routing/username data and are returned only with the recipient's mailbox. Bounded skipping lets a sync cycle process later messages without falsely ACKing blocked or invalid messages. Status lookups use the authenticated sender plus submission ID; unknown and foreign IDs both fail with 404. No route, listener or proxy change is needed.

Added wire fields omit default values, preserving canonical legacy request/response bytes. Deploy the new backend before new Android clients: the old backend rejects extended Fetch. Legacy clients remain compatible with legacy responses from the new backend. Local contact request flags default false for existing data; downgrade after writing new state is unsupported.

V002 adds `acknowledged boolean NOT NULL DEFAULT false` to message_deduplication. Immutable V001 is unchanged. Follow the existing deployment and backup procedure: stop the old backend, run the new distribution's `bin/backend migrate` with the separate migrator environment, check success, then start the new backend with the restricted service role. Normal startup validates both migration checksums. The old binary rejects schema version 2; binary rollback alone is unsafe. Historical ACKs cannot be reconstructed and old rows remain false.

This source change does not deploy to staging, change Cloudflare/VPS settings, or test physical phones. After deployment, follow [the two-phone guide](Android/TWO_PHONE_STAGING_TEST.md).

## Lifecycle and design

The lifecycle-owned foreground loop starts immediately at STARTED, waits four seconds after each cycle, and cancels at STOP. A model mutex prevents duplicate loops; AppRuntime serializes network and user operations. Storage checks run on IO. Sessions are reused, with one reauthentication retry on 401. Logout clears the token and disables automatic sync. An already running bounded HTTP operation may finish during cancellation; no new background polling cycle starts.

The visual direction uses [Signal's message requests](https://signal.org/blog/message-requests/): separate requests, explicit acceptance and no implied human trust. Chats have avatar/name/local-preview rows and a new-chat action. Conversation bubbles, the request banner and optional verification remain clear. Shared components, screens, navigation and theme stay separate. No unavailable call/media/presence controls are shown. Lifecycle integration follows [Android repeatOnLifecycle](https://developer.android.com/reference/androidx/lifecycle/RepeatOnLifecycleKt).

## Verification and limits

OneWayTest and the shared PostgreSQL scenario cover unverified one-way exchange, pending/queued/ACK-delivered transitions and foreign receipt/ACK rejection. Failed-acceptance testing checks ratchet and pin rollback. Existing replacement-key, replay, outbox and transport privacy tests remain required. Android ForegroundSyncTest exercises start/stop/resume, automatic requests/replies/status and no polling after stop. MessengerDesignTest checks request consent and presentation. Strict dependency and IDE attachment verification remain enabled.

Polling is not push. Closed apps wait until reopened. Existing contact/mailbox/prekey capacities remain. Each cycle refreshes at most eight queued receipts, rotating across a larger backlog; bulk histories can take several cycles. Deleted requests retain blocked tombstones; a full blocked-request management screen is deferred. Expired unconfirmed messages can remain Queued because final status is unavailable. No read receipts, presence, new permissions, services, relay or Ghost Mode are added.

Validation completed: 58 JVM core tests plus debug/release app configuration tests; 10 PostgreSQL tests including V001-to-V002 preservation; 31 Android emulator tests; 533 IDE source/Javadoc artifacts under strict verification. Synthetic chat and request screenshots were inspected. Live staging and physical-phone verification remain operator steps after backend deployment.
