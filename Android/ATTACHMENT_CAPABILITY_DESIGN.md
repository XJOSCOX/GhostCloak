# Phase 1J.1 identity-signed attachment capability

> **HISTORICAL PHASE SNAPSHOT.** The implementation and rollout statements below record their phase and may predate v2 Ghost Cloak IDs, V007/V008, seven-day mailbox expiry, and later privacy controls. Use the [current audit](../SECURITY_AUDIT_CURRENT.md) and [deployment runbook](../infrastructure/DEPLOYMENT.md) for current status; do not execute old route or migration instructions against a live target.

Status: approved implementation; no VPS deployment or physical-phone tests performed. Supersedes the audit of main `1a50d47`.

## Root cause and security boundary

Previously, `ConversationPayload` advertised support only through a fixed marker in authenticated spare text/policy padding. A receiving endpoint saved that positive evidence; an outgoing message's delivery ACK could not establish recipient support. Request Accept is a private local operation, so it also could not advertise compatibility. Long frames can lack space for the marker. Removing the send guard would expose old peers to unsupported descriptors.

The replacement is one coarse **ATTACHMENT_V1** statement signed by the existing Signal identity key. No new identity, credential, signature algorithm or bulk encryption change is introduced. Signing remains inside `SignalProtocolEngine`; `:capabilities` is a narrow public-key verification module shared by Android and backend. The backend does not depend on the endpoint crypto engine or encrypted store. Normal signed-prekey signatures are untouched.

Existing TOFU identity pins and changed-identity/session checks remain mandatory. First contact still cannot defeat malicious directory identity substitution without safety-number comparison. A server can omit proof, deny availability or replay a still-valid statement. This is bounded software compatibility evidence, not continuous attestation of the current binary; silent downgrade within its validity window cannot be perfectly detected.

## Exact signed format

`PublicBundle.capability` is optional and omitted when null. Its canonical CBOR `AttachmentCapability` contains `version=1`, `flags=1`, signed `issuedAt`/`expiresAt`, a 32-byte SHA-256 bundle digest and a 64-byte Signal identity signature, bounded to 512 encoded bytes. No exact app version, OS/model, preferences, request acceptance or media information is advertised.

The digest covers the exact canonical public bundle with proof removed. The deterministic signature transcript is length-prefixed UTF-8 domain `GhostCloak.IdentityCapability.ATTACHMENT_V1`, configured audience host, account ID, device ID and routing ID, followed by length-prefixed identity public bytes, big-endian version/flags/issue/expiry integers and length-prefixed bundle digest. This binds all identifiers, the key and every prekey byte/identifier without appending data to Signal keys/signatures. Existing fixed key/signature lengths and SQL constraints remain unchanged.

Lifetime is at most 24 hours. Publication validates signature, authenticated owner/account/routing/identity, exact still-unused bundle, flag/version/size/time. Per-bundle replacement cannot decrease issue time. Client verification uses the existing pin after session establishment and the later of returned server reference/local wall time, failing closed on future/expired proof. Client directory evidence has an encrypted persistent per-device issue-time floor and wall/monotonic expiry deadline. Reboot requires refreshing directory evidence; process recreation on the same boot preserves it. Clock skew can conservatively refuse support. Identity replacement clears both evidence sources and the floor.

Missing text padding clears only message-derived evidence, not validated directory evidence. Authenticated incoming-message proof remains the legacy positive fallback; delivery ACK, username, successful login or presence of PQ keys are never treated as support. A withheld/missing directory proof cannot establish support. Invalid/mismatched proof fails closed, while transport failure remains a connectivity result.

## Authenticated opt-in API

All paths use existing POST/CBOR/session authentication and the centralized one-retry 401 renewal boundary.

- `POST /v1/devices/capabilities`, request type `capabilities`: empty advertisement list inspects only the authenticated owner's unused pool (maximum 32), returning bundles and server time. At most 16 exact bundles with proofs replace only their advertisements atomically. No account creation, duplicate prekey upload, consumed-key resurrection or new-key generation occurs. A consumption race rejects the batch for later retry.
- `POST /v1/directory/lookup`: `capabilities=true` opts into proof plus server time. Legacy requests omit the flag; legacy responses strip proofs and omit server time, keeping the exact old canonical encoding.
- `POST /v1/directory/capability`, request type `capability_lookup` with existing device ID: returns an unused bundle/optional proof and server time without consuming a one-time key or establishing another session. Existing account/device/routing and identity pin are checked before storing evidence. An empty pool has no proof until normal refill.

Publication shares the existing PREKEYS rate bucket; directory refresh shares LOOKUP. No rate limit changes. After connection and post-refill sync, best-effort maintenance inspects at most once per five minutes per runtime, renewing missing proofs or proofs with less than half their lifetime remaining. Recreated runtimes inspect immediately. Healthy idle clients add approximately 0.2 inventory calls/minute; publication batches happen only when needed. Explicit logout and existing fetch cooldown disable maintenance. Unknown peers refresh on attachment preparation, without contact scanning or key consumption. Unsupported old-backend schema falls back to legacy lookup/text/message evidence; network errors never imply support.

## Phase 1J privacy and first-message behavior

Valid discovery enables a first photo/document without any recipient reply or private acceptance announcement. It does not accept the sender on the recipient. Before Accept, the small descriptor is authenticated/stored/ACKed through existing message semantics, but no body download/render/thumbnail/filename/type/dimensions/disappearing timer or external grant is exposed. After Accept, existing photo presentation can automatically fetch/verify/display; documents still require explicit Download/Open. Generic notification content, app lock, local deletion, disappearing-message timing, blob encryption, mailbox delivery and background sync policies are unchanged.

## Exact V006 schema

`backend/src/main/resources/db/V006__attachment_capabilities.sql`:

```sql
ALTER TABLE prekey_bundles ADD COLUMN attachment_capability bytea NULL;
ALTER TABLE prekey_bundles ADD CONSTRAINT attachment_capability_size
    CHECK (attachment_capability IS NULL OR octet_length(attachment_capability) BETWEEN 1 AND 512);
```

The existing migrator records version 6 and its SQL SHA-256 in `schema_history`; startup requires exactly validated versions 1–6. There are no new tables/indexes or Android DB migration. NULL old entries remain unconfirmed. V001–V005, accounts, credentials, identities, key material/history, messages and blob records are not rewritten. Proof persists on retained bundle rows across reconstruction/refill/restart and disappears with consumed rows. Existing service-role table privileges cover the column; DDL requires the dedicated migration role.

## Owner deployment order (not performed)

1. Back up privately and stop backend service. Build/package the matching complete backend distribution, including the new verifier module and pinned libsignal JNI/runtime dependency; copying only the main JAR is insufficient. Confirm VPS JVM/platform compatibility with these native bindings.
2. Use the established private configuration and dedicated migration role to run the matching `bin/backend migrate`. It applies/checks V006 transactionally. Do not hand-edit schema checksums or migrate as the service role.
3. Add POST-only `/v1/devices/capabilities` and `/v1/directory/capability` to the local nginx origin allowlist. The repository template includes them; preserve header stripping, no logs/no-store, size/rate limits. Validate nginx and reload using the owner procedure. No Cloudflare configuration change is required.
4. Start the matching backend with the service role. Check health, old lookup encoding, authenticated publication/refresh and unchanged accounts using controlled test identities. Blob endpoints/configuration stay unchanged.
5. Update Android in place. Let B connect/sync and publish before A discovers it. A publication race/outage retries on the bounded maintenance schedule; normal app restart also probes immediately. Never clear data, rename a real account or create a replacement identity as a workaround.

## Rollback considerations

Prefer a forward correction. The previous backend refuses schema version 6. To revert, stop service and use the migration role in one reviewed transaction to drop only `attachment_capability` (also removing its size constraint) and remove only version 6 from `schema_history`. Validate unchanged V001–V005 checksums, restore the previous complete distribution and remove only the two new ingress paths. Stored proof is lost; account/keys/mailbox/blob data remain. Modern clients retain text and legacy incoming-message proof fallback; directory discovery requires redeployment. Do not restore an old snapshot over newer messages for this additive rollback.

## Validation

`CapabilityTest` covers existing-pool publication, first photo/document hidden requests without a reply, old-peer text/upgrade refresh, no consumption on refresh, forgery/transplant/domain/account/routing/identity/bundle/version/flag/time rejection, issue-time rollback floors, restart/deadline/reboot behavior and legacy omission/fallback. `PostgresTest.v006ProofMigrationDurabilityConsumptionAndRefill` checks additive migration preserving identity/payload, NULL old proofs, SQL size bound, restart, consumption cleanup and refill preservation. Disposable-AVD `CapabilityDiscoveryTest` exercises real SQLCipher/Keystore runtime publication, discovery, upgrade/reopen, no reply and logout. Existing Phase 1E/1F/app-lock/attachment/request-privacy tests remain required. Final run results are in the task report; physical testing is owner work after deployment.

Validated 2026-10-02: 112 core JVM, 16 isolated PostgreSQL, four attachment-format, 33 debug/26 release unit, 127 app and ten storage disposable-AVD tests passed. Debug/release assemblies and complete backend distribution passed strict dependency verification. IDE verification checked the Gradle source ZIP and 604 sources/Javadocs. Tests used the workspace's pre-existing Gradle 9.8.0/AGP 9.4.1 tooling changes; those separate changes and launcher edits are outside this capability commit. The only connected test target was newly created `GhostCloak_Phase1J1_Disposable` (`emulator-5566`, API 37). No physical installation/store was accessed, and no VPS connection/deployment was performed. Publication signing also rejects a server issue-time reference more than five minutes from local wall time. Replaying the same issue time cannot extend its original local deadline, even after omission/clock rollback.

## Physical two-phone procedure after deployment

Update installations in place; do not erase real accounts. Use separate disposable/non-contact test accounts/installations for each first-request case.

1. Let updated B complete connection/sync/proof publication; B sends no chat to A. A finds B and sends a photo first. B sees only a generic hidden request: no photo, filename, dimensions, timer, preview or body download. Accept; existing photo fetching/inline display becomes eligible.
2. Repeat with separate unknown test accounts for a document. Generic before Accept; filename/row only after Accept, explicit Download/Open required. Neither side needs a short-text reply for modern signed discovery.
3. Repeat text-first request followed by private Accept and A's attachment, still with no B reply.
4. Test an actual old client: attachment refused, text still works. Update that same test installation in place, let it publish, then retry A's attachment to exercise non-consuming refresh.
5. Restart, background, app-lock and log out test clients. Pins/account identities remain unchanged; locked/hidden content remains inaccessible, and logout does not silently publish or reconnect.
