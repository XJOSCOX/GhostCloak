# Phase 1J.1 attachment capability audit and deployment proposal

Status: **audit only; implementation stopped at the requested deployment/migration gate**. Audited main `1a50d47` on 2026-10-02. There is no capability implementation, V006 file, server connection or deployment in this change.

## Confirmed current mechanism

`ConversationPayload` in `messaging/Disappearing.kt` places `GhostCloak/padding/attachments/v1!` in spare application padding of outgoing text/policy frames. It is inside authenticated Signal ciphertext, outside user text. Some long frames have insufficient spare padding and do not advertise it. The decoder produces `Content.supportsAttachments` only when that marker is in the padding; quoting the marker as message text is not proof.

After authenticated decrypt and commit, `ConversationService.acceptNetwork` writes the result through `LocalRepository.attachmentPeer`. The encrypted record is `app/attachment-peer/<remoteDeviceId>` with a one-byte positive value; absent means unconfirmed. A later non-advertising text/policy frame clears that value. Explicit identity replacement also clears it. This is software compatibility evidence, not manual identity verification or request acceptance.

Thus B-to-A text can establish B's support at A. A-to-B text establishes A's support at B, and its ACK proves only recipient storage. It cannot establish B's support at A. B's Accept is a private local operation; it neither sends a capability statement nor changes an ACK into compatibility proof. The physical results match these paths exactly.

The send preparation guard in `app/attachments/AttachmentPresentation.kt` uses `AppRuntime.supportsAttachments`, which delegates to this encrypted peer record. The IO send path also checks support. Removing a UI guard alone would neither establish evidence nor make descriptor type 3 safe for old clients.

## Directory and prekey audit

`NetworkV1.PublicBundle` contains device/registration and prekey identifiers, identity public key, EC/PQ public keys and their existing signatures. `DirectoryEntry` adds server-supplied account/device/routing/username fields. Neither contains a client protocol version or attachment capability. `NetworkController.add` imports the bundle through the normal Signal trust/session path; it does not learn attachment support.

`PreKeyManager.create` signs the serialized EC signed-prekey public key and serialized Kyber public key using the existing Signal identity private key. Those signatures do **not** cover application capabilities. Older attachment-incapable clients use the same key formats. Presence of a PQ key, registration ID, signed-prekey ID, username, successful login or successful ACK is not evidence that descriptor type 3 is understood.

IDs are not covered by a capability signature and must not be repurposed as feature flags. Key/signature formats have fixed validation and database length constraints. Appending capability bytes to a Signal key/signature would break ordinary key validation and signature processing. Introducing a new hidden probe/message type would require its own compatibility protocol; masquerading a probe as text or a disappearing-policy update would alter message/request semantics.

`NetworkCodec` rejects unknown schema fields and enforces canonical encoding. Adding a field unconditionally to directory responses would break old clients. `PostgresDatabase.prekeys` reconstructs bundles from normalized EC/PQ/signed-key tables and `prekey_bundles`; there is no arbitrary advertisement field that would survive this reconstruction and a server restart. An in-memory-only advertisement would not meet durable upgrade/discovery requirements.

## Recommended replacement, pending approval

Use one coarse flag, **ATTACHMENT_V1**, in a separate, bounded capability statement signed with the recipient's existing Signal identity key using the library signature API. Do not alter Signal prekey signatures, generate another identity or expose private keys to the network layer. A capability signature is an application statement, with its own explicit domain, not a new encryption construction.

Bind the statement to the deployment/audience, account ID, device ID, routing ID, identity public key, statement version, capability flag and the digest of the exact public prekey bundle supplied by lookup. Encode a deterministic length-delimited transcript and compute the bundle digest without the capability statement itself. Binding to the actual bundle prevents the server from attaching a proof from another device/bundle to an old client's unsigned bundle. Validation belongs alongside the existing pinned-identity/session validation, before persisting positive evidence or permitting attachment preparation.

A valid signature under the directory key does not eliminate first-contact identity substitution by a malicious directory. Existing TOFU pins, changed-key rejection and explicit safety-number comparison remain necessary. A server can omit proof and deny availability. A previously valid bundle/proof can also be withheld or replayed; the implementation must specify bounded validity and rollback handling before acceptance. A signature attests what the device advertised at publication, not continuous remote attestation of the currently running binary. Do not claim perfect detection of a silent downgrade.

Keep proof transport opt-in. Legacy lookup requests and responses retain their exact schema/encoding. Updated callers request capabilities; only those responses include the optional signed statement. Publishing must let an upgraded registered device attach proofs to its existing unused bundles, as well as publish proofs with future refill bundles. No re-registration, username change, identity replacement or forced refill of already published one-time IDs is acceptable. An existing positive proof from authenticated messages remains supported; distinguish its provenance from directory evidence so a missing padding marker cannot accidentally erase separately validated directory evidence.

Use absence/explicit unsupported evidence for a compatibility result, and transport failures for a connectivity result. Invalid or mismatched proof never establishes support. Refresh previously unsupported/unknown peers after an upgrade through the authenticated capability lookup/publication path; avoid consuming another one-time prekey merely to refresh an existing contact's capability. Define that refresh operation and its rate limit explicitly before implementation.

## Required backend and database change

The backend must accept and durably retain bounded signed bundle advertisements, bind publication to the authenticated device, serve proof only to opt-in clients, preserve legacy responses and enforce validation/rate limits. Existing upload, blob, mailbox, ACK and retention semantics remain unchanged.

A reviewed **V006** would add nullable capability storage associated with `prekey_bundles`, with fixed bounds/constraints. The exact SQL and publication/refresh API must be reviewed together: current bundle consumption/refill rewrites these rows, so proof must survive retained bundles and be removed with consumed bundles. Existing entries remain NULL/unconfirmed. V001-V005 must not be edited, and existing accounts, keys, credentials, message history and blob records must not be rewritten. **No V006 has been created.**

Deployment order after approval: private backup; stop the backend for the numbered schema migration using the established migration-role procedure; apply the reviewed V006 with the matching immutable backend release; start and verify legacy and opt-in API behavior; then update Android in place. Upgraded B publishes proof for existing unused bundles before A can discover it. An old backend remains incapable of directory proof; an updated client must fall back safely to existing authenticated-message evidence/text, without registering a new account or asserting support from a network error.

The server learns at most a coarse software compatibility flag and the existing device/bundle binding. It does not learn request acceptance, privacy settings, media type being sent, filenames, dimensions, message text, app/OS version, model, online status or download preferences. The signature cannot make the advertised flag opaque to a server that can parse the statement; this limited fingerprint is the explicit tradeoff for directory discovery.

## First-message and old-client behavior after implementation

Verified lookup proof enables the sender's existing sanitize/encrypt/upload/descriptor flow for an initial photo or document. It never accepts the sender on the recipient. Phase 1J continues to authenticate/store the small descriptor privately and return no request content to UI by default. Before Accept there is no body download, thumbnail, filename, dimensions, timer or external grant. After Accept, existing accepted-photo presentation becomes eligible for automatic encrypted fetch/verification/inline display; documents retain manual Download/Open. No notification content is added.

No valid ATTACHMENT_V1 proof and no existing authenticated-message proof means attachment sending stays refused, with newer-client guidance. Text still works. False/forged proof cannot bypass the normal request/block/changed-identity/app-lock gates.

## Required implementation validation

- Modern B publishes proof; A discovers it without any B-to-A chat message. Photo and document each work as the first request.
- Hidden request returns no private UI/accessibility content and causes no blob download/render/grant; Accept enables photo fetching while document download remains manual.
- Genuine old B has no proof, attachment send is refused and text still succeeds. Legacy callers' request/response encodings stay unchanged.
- Reject changed bits/signature, another device/account/routing binding, substituted identity, another bundle digest, invalid version/size and stale/rolled-back proof. Test withholding without falsely marking supported.
- Upgrade B with an existing unused pool: proof publication/refresh works without reused prekey publication conflicts, consumed-key resurrection, a new account or new identity.
- Previously learned message evidence survives reopen; identity replacement invalidates both evidence sources. Test padding absence versus independently valid directory evidence.
- PostgreSQL migration/restart preserves accounts, keys, existing queued messages, proof for retained bundles and legacy API behavior; consumption/refill cleans or retains the matching proof correctly.
- Run core JVM, isolated PostgreSQL and disposable-AVD tests, debug/release builds and strict dependency/IDE-source verification after implementation. Never test on account-bearing physical phones or the user's existing AVD.

Audit validation is source-based: the existing `AttachmentTest.sameVersionPeersNeedIncomingTextNotJustOutgoingDelivery` already exercises the observed one-way limitation and reopen preservation. No implementation tests or device tests are claimed for this proposal.

## Physical two-phone retest after deployment

Update existing installations in place and use two fresh disposable/non-contact test identities; do not erase real accounts. Let B complete its normal connection/proof publication, but send no B-to-A chat message. A finds B and sends a photo first. B must see only a generic hidden request, then Accept; the photo should fetch and appear inline. Repeat with separate non-contact test identities for a document: generic before Accept, document row after Accept, explicit Download/Open. Repeat text-first request and an older-client refusal. No short-text reply is needed between updated clients once valid publication/discovery succeeds.
