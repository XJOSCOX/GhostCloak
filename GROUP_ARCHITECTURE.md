# P12 — private group architecture design

**P13.5 group message controls:** A reply uses a separate `GroupTextV3` Signal
frame with the target's logical ID; it carries no snapshot of the old text.
Reactions, own-text edits, and sender deletion use a versioned, authenticated
message-control frame. These are per-recipient Signal fan-outs with one durable
logical intent and fixed recipients, never governance entries. Every current
member must advertise `group-controls-v1`; otherwise ordinary group text keeps
working and the controls remain unavailable. A newly created or received
control must match the exact current accepted governance head. Future-head
controls wait in a bounded hidden queue for resync; stale controls are dropped.
This can lose a delayed valid edit/reaction/delete after a governance update,
which is the deliberate anti-backdating tradeoff. Blocked sender controls are
authenticated and ACKed without parsing or applying user content.

One current reaction per actor/target uses an actor-local increasing revision;
an older update cannot restore a previous emoji. Edits require the original
sender and a higher per-message revision, replace the active text without an
edit-history UI, and update reply previews through the current target. Sender
delete also requires the original sender, clears active plaintext/reactions,
and creates a distinct terminal notice. A delete arriving before its target
remains a bounded candidate until the later authenticated target proves the
claimed sender. Admin moderation is canonical and overrides sender deletion
regardless of arrival order. Terminal targets cannot be edited or reacted to.
The new invitee's dual-signed policy checkpoint carries a separate bounded
sender-deletion ID filter; no pre-join text or reactions are transferred.
Deletion is best effort and cannot retract content already seen or saved.

**P13.4C group-text moderation (implementation under assurance):** An Owner or
Admin may propose `REMOVE_GROUP_MESSAGE` for a logical group-text ID. It is a
signed policy-entry variant in the same governance sequence as membership and
posting changes, with actor and coordinator signatures over the exact target.
Every current member must first advertise the new authenticated moderation
capability; an older client cannot silently receive an unsupported entry.
The canonical pre-entry role authorizes the actor, so an Admin may moderate
Owner/Admin/Member text even while restricted from sending. A demotion or
removal prevents fresh moderation at the new head, while a valid earlier entry
remains verifiable during signed resync. The journal, one-outstanding-action
rule, 511-entry cap, fork lock, and legacy-incomplete lock apply unchanged.
The invitee-only owner/coordinator-signed policy checkpoint carries a fixed
2 KiB filter of earlier moderated IDs; it cannot reveal old plaintext. Its
rare false positives suppress a new text rather than expose an old one.
The action makes an explicit local tombstone and scrubs active text atomically
with advancing the head. A target not yet present leaves a bounded marker;
late ciphertext is authenticated and acknowledged, then discarded without
storing its plaintext or accepting unverified sender attribution. A new
invitee gets the current join checkpoint, never pre-join message bodies. This
is logical moderation, not guaranteed erasure of content already seen, copied,
captured, or retained by an older client. Group sender delete and media
moderation remain deferred. No server or database change is involved.

**P13.4B current management layer:** Group Info displays safe member labels,
roles, posting permissions, and setup/sync/locked states. Existing groups
enter A5/A6 setup only by an explicit coordinator action; all-member evidence
remains mandatory. Governed REMOVE, PROMOTE, DEMOTE, LEAVE, TRANSFER_OWNER, and
DISSOLVE share the existing signed ledger rules and retained journal order.
Non-coordinator actions use a bounded exact-transition proposal and two-step
entry co-sign flow. Ownership transfer additionally requires a separate
encrypted target request and explicit signed acceptance. Owner/Admin/Member
permissions are enforced by `GroupRules` and policy validation, independent of
UI visibility. A blocked canonical member remains in the roster and receives
only A4 maintenance as permitted; restriction is a group send policy, while
Block is a local content decision. Removal and dissolution send best-effort
terminal signed evidence before group-scoped authority closes. Local history
remains readable after leave, removal, and dissolution. A full journal or
missing governance chain disables new management, without reconstructing
entries. Admin message moderation and local group notification mute remain
deferred. The earlier phase status paragraphs below are historical.

**P13.4A6 split:** A6.1 supplies the GroupLedger barrier and atomic GroupState/companion-head transition primitive. A6.2 adds authenticated per-member capability, owner/coordinator-signed activation proposal, every-member approval, complete COMMIT, per-member installed ACK, and a distributed READY certificate. A local barrier is not group READY. Its one signed governed timer-entry path applies through A6.1 and waits for signed application ACKs from every resulting member before another entry. Raw legacy state updates and state-only resync cannot advance an activated group. A6.3 adds retained signed entries, contiguous one-entry-at-a-time existing-member resync, governed A3 admission and a candidate-only signed join checkpoint. Older A6.2 heads with missing entries are locked as legacy-incomplete without deleting history; ordinary unactivated P13.3 groups are unchanged. A7 still must bind GroupTextV2 and policy to the verified governance head. P13.3 text-only groups remain in their existing mode until activation, and management UI stays disabled. See [GROUP_GOVERNANCE.md](GROUP_GOVERNANCE.md) for the A6.3 bounds and rollout rule.

**P13.4A/A2–A5 governance prerequisites:** The separate canonical-journal design and its activation blockers are recorded in [GROUP_GOVERNANCE.md](GROUP_GOVERNANCE.md). A3 implements all-current-member approval of each new admission; A4 carries internal maintenance with a blocked canonical member without restoring direct contact or group text; A5 adds an all-current-member authority baseline for members already present. The admission-only checkpoint applies solely to a newly invited member, never to an existing member's missing chain. Both A3 and A5 historical authority have explicit prospective revision cutoffs and cannot authorize fresh actions. A6.2 supplies a bounded internal activation/entry path; P13.4 management UI remains NO-GO until governance resync, text-policy binding, and later gates pass.

**Current P13.3 status:** V009/P13.3A is deployed according to the operator. Android now implements text-only group chat on the existing pairwise Signal mailbox. Each recipient has a separate durable outbox entry and V3 transport ID; one random 256-bit logical message ID remains inside the encrypted payload. No server group route, roster, migration, or blob store is added. The remaining gate is controlled A/B/C physical validation of the client, not the obsolete 1,024-receipt/128-row quota.

Existing accepted contacts may not yet have advertised group support: the marker is authenticated inside a newly received one-to-one message and cannot be inferred from an old chat row. The group picker shows these contacts as waiting, lets the user open their direct chat, and explains that the peer must send a new message from an updated client. No invitation is sent until group support and the existing active-session/unchanged-identity checks hold.

## P13.3 text-only delivery

Type-14 application frames carry versioned CBOR `(groupId, epoch, senderMemberId, logicalId, text)` inside each recipient's Signal envelope. Text is capped at 2,048 UTF-8 bytes; the CBOR body is capped at 3,072 bytes and the padded application frame at 4,096 bytes, leaving at least 12 KiB under the 16 KiB application-body cap. The sender persists the logical message and immutable recipient set in the SQLCipher endpoint store before any enqueue. Each pairwise recipient has a separate durable outbox submission; the UI reports how many recipients the server accepted, never a global delivered/read assertion. Direct-chat outbox processing runs before a bounded eight-recipient group pass, preserving access to the 60 SEND/minute server limit. A 429 retains the existing V3 ID and ciphertext for later retry.

Incoming text is committed only after Signal authentication and the exact sender device is matched to the current canonical member ID and its accepted, pinned Signal/registered device-auth binding. The group must be ACTIVE and not FORKED. **Only the current epoch is displayable.** A late older-epoch text is discarded; a future-epoch text remains hidden while a complete signed-chain resync is requested. The `(groupId, senderMemberId, logicalId)` replay marker and visible message commit atomically; 4,096 local messages and 8,192 replay markers are hard caps, after which the client fails closed instead of evicting tombstones. Local ordering is commit order with a logical-ID tie-breaker, not a claim of global ordering.

Canonical removal cancels unsent old-epoch fan-out (including queued ciphertext) under the membership/text barrier. Already accepted ciphertext cannot be recalled. New members are never added to an earlier message's persisted recipient set and receive no history sync. A local direct Block leaves canonical membership unchanged; the existing blocked-envelope path authenticates but does not parse group text from that peer. Other members' text can still be received when their individual bindings validate. A changed Signal identity or registered authority blocks the affected peer's send/receive path until the existing trust-repair and fresh-binding flow succeeds. Forked, dissolved, left, and locally removed groups retain read-only history; a valid fork requires a new group. Group notifications use generic “New message” wording in every privacy mode. Group media, naming, replies, reactions, edits, deletes, disappearing timers, search, and history sync remain deferred to P13.4+.

The server cannot read the group ID, roles, text, or local placeholder name. It can correlate each sender-recipient edge, timing, sizes, fan-out bursts, and available network metadata. Five-member text costs four SENDs. Deployed V009 limits are 16,384 live V3 receipts per sender, 100,000 globally, 512 mailbox rows and 16 MiB per recipient, eight per FETCH/ACK, 60 SEND/minute, a 14-day V3 submission horizon, and seven-day mailbox lifetime. These support the measured one-day ordinary-offline scenario, not unlimited high-volume or seven-day catch-up.

## P13.2 membership transport

The Android client now has a versioned membership-only control inside ordinary pairwise Signal envelopes. Authenticated padding advertises `group-membership/v1`; only an active accepted contact with that capability can be invited. Each invitation binds the exact account, device, pinned Signal identity digest, and backend-registered device-auth public key through the P13.2A resolver. The recipient's local encrypted store holds the pending offer, and explicit acceptance signs the offer. Membership begins only after the current coordinator accepts a canonical `MEMBER_ADDED` transition, advancing revision and epoch, and distributes it to every active member. A delegated coordinator obtains an online owner co-signature over the exact current state, invite ID, and target before issuing the offer. The owner and coordinator must both be available for this path. A fresh invitation after an expired one requires a new dual-signed admission at the same verified state or a newer revision.

An offline member rejects a revision gap and requests the missing signed chain through the same encrypted one-to-one mailbox. Resync responses are bounded to the 12,000-byte control limit and can be chunked; a blind snapshot is never accepted. A durable resync marker survives a lost response and triggers a bounded re-request after process recreation. Valid conflicting revisions persist `FORKED` and disable further membership mutation. There is no same-group winner or merge: recovery means creating a new group and inviting members again. Removal is a coordinator-ordered epoch transition sent only to remaining members; queued fanout for the removed device is discarded. A failing recipient retains its fanout intent without blocking the other recipients. A voluntary leave remains a signed state-layer foundation without a user-facing transport flow in this increment. Group media remains unavailable. Text chat is implemented in P13.3 above.

All invitations, signed state, replay records, pending controls, authority bindings, and fanout intents live in the encrypted endpoint store; Safe Exit and inactivity destruction erase that store. The invitation notification is fixed to “New group invitation” with secret visibility. The local test server sees only ordinary encrypted sends and routing metadata. A five-member state update encoded to 4,037 bytes (4,096-byte padded frame), leaving 12,288 bytes under the 16,384-byte application body cap. At five-member capacity, a normal join uses six encrypted sends (invite, acceptance, four state updates); delegated co-signing adds two. A removal uses three state sends; one resync round trip uses two sends, with additional round trips for long chains. The deployed V009 backend retains 60 SEND operations/minute per sender, 512 mailbox rows/16 MiB per recipient, and up to 16,384 live V3 submissions per sender. Four pairwise sends per five-person text still leave at most 15 such texts/minute before other traffic; higher throughput is queued and retried without bypassing the server limit. No backend deployment, migration, or nginx change is required for this Android-only membership transport.

## P13.2A authenticated registration binding (2026-10-05)

P13.2A adds the prerequisite key-resolution path, **not group invitations or messaging**. The backend's existing authenticated `/v2/directory/capability` request accepts an optional exact `(deviceId, accountId, SHA-256 Signal identity digest)` query and returns versioned `DeviceBinding(accountId, deviceId, registered authPublicKey, identityDigest)` only when all three match its registration row. Missing and mismatched targets return the same generic 404. The legacy request and response are unchanged, and this branch never reads or allocates a prekey. It shares the existing LOOKUP rate limit. No public lookup, bulk query, reverse auth-key search, new nginx route, or schema migration is added.

`GroupAuthorityResolver.resolveTrustedGroupAuthority` is an internal Android API. It requires an active accepted contact, an ACTIVE session and usable existing Signal pin; sends that contact's exact account/device and pin digest; validates the backend response, canonical bounded P-256 key, and unchanged local relationship/pin after the network round trip. The first result is pinned in the encrypted endpoint store to that Signal digest. A changed auth key under the **same** Signal identity fails closed. Signal identity change, explicit trust replacement, block and contact removal delete the cache; Safe Exit and inactivity destruction delete the encrypted endpoint store. There is no offline fallback. Future P13.2 group-control code must recheck the active relationship and Signal pin at the moment of use; a binding lookup alone does not authorize a group transition.

Trust chain: backend registration `(account, device, auth key, Signal identity)` → locally accepted contact account/device → existing pinned Signal identity → exact authenticated backend binding → group signing authority. HTTPS authenticates the backend response; the backend remains authoritative for registration metadata, **not** group membership. The backend already knows the queried device and identity; it additionally observes which authenticated client asks for that exact peer's auth key. Knowledge of account/device/identity digest is a prior-knowledge gate, not server proof of a local accepted relationship. A caller can query only one exact target at a time; the key is public but linkable, so Android exposes this API only after its local accepted-contact check. Deploy the backend before using the updated Android client; an old backend rejects the new optional fields and the client fails closed. Older clients send the unchanged request and continue one-to-one messaging.

Recovery with the current backend preserves the account, device, registered auth key and Signal identity. A future rebind or replacement device must not reuse the old pin silently: changed identity fails until the existing trust-repair path approves it and a fresh binding is fetched. P13.2 invitation/capability, membership distribution, fork recovery, and group UI remain paused. P13.3 message delivery remains out of scope.

## P13.1 dormant foundation (2026-10-05)

The client now has an internal, encrypted-local-record group state ledger and no group UI, transport, or backend route. It is **not a usable group feature**. The ledger accepts only canonical CBOR states, bounded signed transitions, and complete signed resync chains. Group IDs and one-use invitation IDs are independent 256-bit CSPRNG values. Members retain separate account, device, and internal member IDs. The maximum is five including the owner.

**Signing key review.** The Android registered device-auth key is the existing non-exportable Android Keystore P-256/SHA256withECDSA signing key. `EndpointNetworkState.signGroupStatement` reuses it only for recognized length-prefixed `GhostCloak.Group*.v1` domains, after checking registration and the pinned public key. Group genesis, actor, coordinator, invitation, acceptance, and ownership-transfer signatures have distinct domains. A signed transition binds group ID, old/new revision and epoch, actor, canonical operation, old-state SHA-256 digest, and resulting-state SHA-256 digest. A signature cannot be reused as a login/recovery proof; the existing device-auth statement uses a different domain and format. No new key or custom cryptographic primitive was added. **P13.2 must convey and bind the signing public key to each accepted contact's pinned Signal identity and account/device pair inside authenticated E2EE transport.** Until then, the injected `GroupTrustedPeer` predicate has no production implementation and the ledger is not wired to a product flow. A changed Signal identity must fail that predicate before accepting its group signatures; existing group traffic enforcement remains a P13.3 gate.

The owner is the initial coordinator. An actor signs its requested transition, and the current coordinator separately signs the ordered revision. A coordinator can be owner or admin; only the owner can delegate that role, with the current coordinator countersigning. If the coordinator is offline, membership and policy mutations pause. The last accepted epoch may later continue messaging, subject to P13.3 delivery rules. There is no timeout-based failover, timestamp authority, or server-side roster. An ownership transfer also requires the target's signature; the former owner becomes admin. Local block state never enters canonical group state.

| Operation | Actor | Target/consent | Epoch |
| --- | --- | --- | --- |
| Add | Owner or admin | Accepted contact; inviter-signed, target-accepted, one-use invite bound to parent digest | +1 |
| Remove | Owner: non-owner; admin: member only | Cannot remove coordinator; owner protected | +1 |
| Leave | Member or non-coordinator admin | Self only; owner must transfer/dissolve first | +1 |
| Promote / demote | Owner | Member→admin / non-coordinator admin→member | +1, because sending/ordering authority changes |
| Transfer owner | Owner | Active target signs acceptance; old owner becomes admin | +1 |
| Delegate coordinator | Owner | Active owner/admin; current coordinator signs | +1 |
| Profile / timer | Owner or admin | Strictly bounded placeholder digest/revision or existing timer enum | No bump |
| Dissolve | Owner | Terminal; no new mutations/messages | +1 |

Every accepted change advances exactly one revision. The canonical state contains sorted members, exactly one active owner, and an active owner/admin coordinator. Invalid/unknown actors, untrusted keys, stale event IDs, used invites, invalid role changes, and wrong epoch are rejected. A duplicate state digest at the same revision is idempotent. A *valid signed* rival at the same revision, or a signed next revision with the wrong parent digest, persists `FORKED` and halts changes; unsigned garbage cannot freeze the ledger. No last-write-wins or automatic fork merge exists. An admin demoted at revision N cannot authorize N+1 from an old parent. Invite tombstones and signed event IDs remain in the encrypted group record for its lifetime, bounded to 256 used invites and 512 revisions/events; there is no unsafe wall-clock tombstone expiry. The local record is capped at 4 MiB; state, event, and invite caps are 4,096 / 8,192 / 2,048 bytes.

Offline resync for an **existing** member requires the complete signed chain from the local revision to the target snapshot, applied atomically. A standalone coordinator-signed high-revision snapshot is **not sufficient**: it could skip an intervening coordinator transfer or removal. An absent intermediate event yields `NEEDS_RESYNC`; a valid conflicting chain yields `FORKED`. A removed device processes only through its removal event and must not receive later state, future fan-out, attachment keys, or sender material. A new member's bootstrap is deliberately not wired in P13.1: P13.2 must define a current-state admission proof that does not disclose unnecessary prior roster history. No historical message plaintext may be included. The current state record can establish `ACTIVE`, `FORKED`, `REMOVED`, `LEFT`, or `DISSOLVED` locally; invitation transport and its `INVITED` persistence remain P13.2 work. Future group message and attachment descriptors must authenticate `(groupId, epoch, senderId, messageId)` inside Signal, and reject stale/wrong epochs.

P13.2 gates: implement accepted-contact key binding, encrypted capability-gated one-use invite/acceptance transport, safe snapshot-chain distribution, removal minimization, identity-change blocking, and explicit fork-recovery UX/protocol. P13.3 still needs actual epoch-gated pairwise delivery and the quota/capacity review. No backend deployment or database migration follows from P13.1.

Measured JVM fixture encoding: a two-member state was 1,122 bytes, signed add event about 2,756 bytes, and invite about 835 bytes, below the respective 4,096/8,192/2,048 limits. A 16,384-byte application body cap is still the outer transport constraint; P13.2 must measure full encrypted envelope and padding overhead for worst-case five-member controls before sending them. In the same JVM fixture, 250 genesis create/validate cycles took about 122 ms, 100 P-256 sign/verify cycles 48 ms, and 100 one-event resync validations about 194 ms; these are diagnostic measurements, not a device benchmark.

**Original P12 status: DESIGN ONLY, 2026-10-05.** P13.1 later added the dormant local state foundation above. No group capability, server route, or UI is implemented. The current product is one device per account and one-to-one Signal E2EE. This proposal is a target contract for review, not a claim that current clients can safely exchange group messages.

## Product boundary and recommendation

V1 should use **pairwise Signal fan-out with no server group roster**. A sender encrypts an independent, authenticated one-to-one envelope for each other active member. Group ID, epoch, sender, message ID, and encrypted group state stay *inside* those envelopes. Start with a **five-member maximum including the owner**, subject to capacity changes and the gates below. This minimizes new cryptography and uses the current durable outbox, identity pins, mailbox and attachment capability model. It is not anonymous: the server sees each delivery edge and timing. An initial group must comprise accepted contacts with group capability; no public groups, links, directory group search, calls, or multi-device linking.

The five-member cap is the reviewed V1 bound. Four recipients mean four submissions for one group message. Deployed V009 supports 16,384 live V3 receipts per sender, 100,000 globally, and 512 queued mailbox rows or 16 MiB per recipient, while retaining 60 SEND operations per sender per minute. The P13.3A capacity study supports ordinary one-day offline catch-up at its stated rate, but not unlimited or seven-day high-volume use. A 20-person group would cost 19 sends per message and reach 60/minute after only three messages in a minute, so this design retains the five-member bound.

| Criterion | Pairwise fan-out (V1 choice) | Sender keys / group session |
| --- | --- | --- |
| Security complexity | Existing one-to-one ratchets and pins; new group authorization, convergence and replay logic still required | New sender-key storage, distribution, rotation, sender authentication and replay/order handling in addition to membership |
| Performance | O(N) encryption and uploads; five members cost four ciphertexts | One content encryption per sender, but delivery still needs routing to N recipients |
| Server metadata | Sees every sender-to-recipient edge, timing, sizes and correlated delivery burst; cannot read encrypted group ID/profile | A single group ciphertext can reduce duplicate content, but a routing list or per-recipient delivery still reveals edges; an opaque group mailbox could expose a stable group handle |
| Implementation effort | Moderate, mainly application state machine and durable per-recipient fan-out | High; cryptographic design/security review and migration required |
| Scalability | Suitable only for small groups after quota review | Better for medium groups after proven rekey/distribution design |
| Future multi-device | Add delivery to each authenticated device; cost grows with devices | Add per-device sender-key distribution/revocation; greater key-state growth |

The pinned `org.signal:libsignal-client:0.102.1` JAR **does contain** `GroupCipher`, `GroupSessionBuilder`, `SenderKeyStore`, `SenderKeyDistributionMessage`, and `SenderKeyMessage` under `org.signal.libsignal.protocol.groups`. Their exposed APIs support sender-key sessions, not Ghost Cloak group creation, canonical membership, admin authorization, device linking, rekey barriers, privacy-preserving routing, or app-level replay tombstones. `zkgroup` classes are also present, but are not a drop-in private-group service for this backend. Do not implement custom group cryptography or infer the safety of a sender-key product from these class names. A later sender-key phase needs a separate cryptographic protocol and security review.

## Threat model and honest guarantees

Protect group content, name, photo, About and controls from server plaintext access; admit messages only from authenticated current members; reject a member forging another member's sender identity or admin action; reject duplicate/old membership events and message/control replays; exclude removed members from **new-epoch** deliveries; and keep newly joined members from receiving earlier-epoch history by default. One-to-one identity changes must stop affected traffic until explicitly resolved. The server and an invited attacker can delay, omit, reorder, replay or correlate deliveries. A malicious member can save content, disclose keys/descriptors they received, capture media, or try to equivocate about state. A compromised device can use its existing sessions and retain previously received material until revoked; V1 cannot recover those bytes or promise forensic erasure.

Pairwise Signal authenticates each delivered envelope to a device, but does **not** itself make all members agree on one roster. The group state protocol must be reviewed for fork and omission behavior. A signed hash chain can detect conflicting snapshots when members compare them; it does not by itself prevent a malicious current coordinator from signing two forks. Until fork detection/repair and offline convergence are tested, no global-consistency guarantee or group implementation is approved. Do not describe removal as instant: a sender unaware of the new epoch may still deliver an old-epoch message to a former member. A conforming sender must never send **new-epoch** material to that former member. A recipient that has accepted a newer epoch rejects late old-epoch messages locally; the removed recipient can still read old copies already delivered to it.

Residual server metadata includes authenticated sender account/device, destination routing IDs, ciphertext counts/sizes, submission IDs, timing, IP/TLS-edge data, attachment upload/download size and timing, and correlations across a fan-out burst. There is no server-visible plaintext group name, ID, roster or photo in option A, but traffic analysis can infer a likely member set. Neither E2EE nor a random group ID hides this traffic graph.

## Identifiers, principals and invitations

Generate a **256-bit cryptographically random internal group ID** with domain-separated encoding; do not derive it from an owner, account, device, clock or public Ghost Cloak ID. It is not a public directory locator or server route in V1. A separate random, one-use invite ID prevents replay; no shareable invite URL/QR or public group ID in V1. Keep both inside accepted-contact Signal envelopes and local encrypted storage. Future server-side routing, if considered, needs a new metadata review and must not reuse the internal ID naively.

Model `GroupMember` separately from `DeliveryEndpoint`. Today one accepted, pinned Signal device represents one member. Store a stable group-local member ID plus the currently authorized device fingerprint/address; do not use a display name as identity and do not expose the backend's internal account UUID. Future account-level membership and multiple devices require a verified account-to-device binding, explicit device add/remove events and new-epoch distribution. They must not silently treat a replacement device as the old member. A changed Signal identity freezes that member's participation for affected peers until their existing trust process approves it; UI shows **Member security identity changed** and each member's own verified/unverified/changed state. Verifying an owner does not verify everyone. There is no synthetic whole-group safety number.

An encrypted direct invitation identifies the inviting actor, group ID, canonical state hash/revision/epoch, a bounded encrypted group profile preview, requested role, invite ID and expiry policy. Only an accepted contact with authenticated group capability may be invited. The invitee sees no previous messages and must explicitly accept. Acceptance is an encrypted response to the coordinator. Only then may the coordinator commit `MEMBER_ADDED`, increment epoch, distribute the new signed snapshot to continuing members and the joiner, and fan out future messages. Declines/expired invites make no membership change. Invite spam is rate-limited locally and eventually server-side as ordinary sends; a public link would leak existence and needs separate abuse design.

## Roles and canonical state

V1 roles are `OWNER`, `ADMIN`, `MEMBER`. The creator is owner. Owner may designate admins, change information/timer, request member changes, transfer ownership, delegate the active coordinator, or dissolve the group. An admin may propose an add/remove or information/timer change, subject to the canonical coordinator's validation. A member may send, react/edit/delete their own messages and leave. Admin deletion of someone else's message is deferred. The owner cannot leave without a committed ownership transfer; if the owner and all delegated authority are lost, the group is frozen and members may leave or create a new group. No timer-based owner takeover.

To avoid concurrent authoritative branches without a trusted server sequencer, **exactly one active coordinator** commits each revision: initially the owner, optionally one explicitly delegated admin. Admins send signed, encrypted proposals to that coordinator. The coordinator verifies actor authority against the *previous* state and issues the next canonical signed snapshot. Delegation/handoff is itself a canonical event signed by the outgoing coordinator and authorized by the owner; no unilateral competing signer while delegated. The coordinator being offline delays **membership/profile/timer changes**, while messages in the settled current epoch may continue. This availability cost is deliberate and must be tested with offline users. A later multi-admin concurrent commit system would require a separate deterministic ordering/consensus design, not “highest timestamp wins.”

Conceptual state:

```text
GroupStateV1 {
  groupId, revision, epoch, previousStateHash,
  ownerMemberId, activeCoordinatorMemberId,
  members: (memberId, role, authorizedDeviceIdentity)[],
  profileRevision, profileDigest, disappearingSeconds,
  dissolved
}
GroupEventV1 {
  eventId, groupId, previousStateHash, nextRevision, nextEpoch,
  actorMemberId, eventType, targetMemberId?, bodyDigest,
  actorAuthorization, coordinatorCommit
}
```

`GROUP_CREATED`, `MEMBER_ADDED`, `MEMBER_REMOVED`, `MEMBER_LEFT`, `ADMIN_PROMOTED`, `ADMIN_DEMOTED`, `OWNER_TRANSFERRED`, `COORDINATOR_DELEGATED`, `GROUP_INFO_CHANGED`, `TIMER_CHANGED`, and `GROUP_DISSOLVED` need explicit authorization rules and a fixed versioned encoding. A dedicated group action-signing identity, its binding to the authenticated Signal device and its safe storage need crypto review before coding; pairwise delivery authentication alone is insufficient for portable signed snapshots. All recipients verify the actor, coordinator, previous hash, strictly increasing revision, event ID and authorized transition *before* committing. No wall-clock ordering. Duplicate event IDs are idempotent tombstones; conflicting same-revision hashes halt sends and demand an explicit fork-repair flow, never an arbitrary winner. Event/snapshot persistence and replay state commit atomically with any visible state transition.

| Transition | Actor allowed in previous canonical state | Additional rule |
| --- | --- | --- |
| Create | Creator/initial owner | New random ID, revision 1, epoch 1; no pre-existing group accepted |
| Invite, add, remove | Owner or admin | Accepted-contact capability and explicit invite acceptance for add; owner cannot be removed by an admin |
| Leave | The affected member | Owner must transfer ownership first |
| Promote/demote admin, transfer ownership, delegate coordinator, dissolve | Owner | Owner transfer requires the recipient's explicit acceptance; outgoing coordinator commits the handoff |
| Change profile or timer | Owner or admin | New revision; policy applies prospectively |
| Send, reply, react, edit/delete own message | Current member | Edit/delete additionally require original sender binding |

These are proposed **actor** rights. The single active coordinator still signs the canonical commit for every transition; an admin proposal cannot by itself advance an epoch. Simultaneous admin add/remove proposals are serialized by that coordinator against the latest state, with the loser revalidated or rejected. If an owner promotion races an admin removal, both are evaluated in committed revision order rather than by delivery time. Out-of-order recipients hold a future revision until the missing predecessor arrives and reject a conflicting predecessor; they never choose by wall clock or server arrival order.

Membership changes increment the epoch and revision; profile/timer/role changes increment revision, and role or signing-authority changes should increment epoch if they affect send authorization. Define this precisely in P13.1. Message data binds the accepted state hash and epoch. A sender with missing, conflicting or stale state cannot emit group traffic; a receiver quarantines future-epoch traffic until it verifies the missing state chain, and rejects traffic from a sender absent from that message's epoch. Late old-epoch traffic is rejected once the receiver has moved forward. Offline peers fetch queued encrypted state controls before displaying newer messages. Mailbox retention means an offline member beyond seven days may need an explicit encrypted state resync from a current member; no unauthenticated server snapshot is trusted. If recovery cannot prove a continuous authorized chain, fail closed and offer re-invitation/new group.

Removal and leave commit a new epoch. Every updated sender stops fan-out to removed/leaving endpoints; no shared group decryption key needs rotation in V1. Continuing members receive the new state through pairwise sessions. No history is automatically sent to a joiner. Removed/leaving members may keep their old history and old attachment keys; a group dissolve similarly stops future sends but cannot erase other devices' copies. Local **Delete group** only removes that device's group view/history after confirmation and does not send a membership event. **Leave** and **Dissolve** are authenticated group events; only the owner can dissolve. Future public account deletion must define automatic leave, ownership transfer and offline group implications before release.

## Messages, features and media

Each recipient gets a distinct one-to-one Signal ciphertext with a **new versioned group payload** containing the random group ID, epoch, canonical state hash/revision, authenticated sender member/device binding, group message ID, kind and encrypted content/control. These fields are all inside Signal; the outer envelope remains ordinary pairwise routing. All copies of one logical message use the same random group message ID but distinct per-recipient submission/envelope IDs. Never show a message until its sender and state are validated. Maintain a durable local tombstone keyed by `(group ID, epoch, sender member ID, group message ID)` and bounded/capacity-fail-closed replay policy; the existing per-envelope Signal replay ledger does not alone deduplicate group fan-out, delayed controls or cross-epoch IDs. State updates must be processed before data for their epoch. Durable fan-out tracks per-recipient delivery/retry independently; no all-or-nothing delivery claim. A single failed recipient must not silently mark the whole group message delivered.

| Feature | V1 disposition |
| --- | --- |
| Text, local search, pin/archive/mute | Reuse UI ideas and encrypted local storage, but add group-scoped indexing and controls. Search remains on-device; mute never stops delivery. |
| Replies | Group-scoped target ID and sender binding; show unavailable original if not locally present. |
| Reactions | Group message ID → map of **member ID → latest reaction/sequence**; multiple members may react independently. |
| Edit | Only the original authenticated sender, higher monotonic revision, same group target; fan out to current eligible members. Delete stays terminal. |
| Delete for everyone | Original sender only; best-effort encrypted control. No admin deletion of others' messages in V1; screenshots, blocked/offline/old clients and exported copies may remain. |
| Disappearing | One group policy, changed only by authorized owner/admin state event. Applies prospectively; does not promise remote erasure or use wall clock as authority for event ordering. |
| Photos/documents/voice notes, captions and local voice masking | Reuse validated local encryption/format limits where possible; group authorization, per-recipient descriptor fan-out, expiry and cache cleanup are new. Voice masking remains local. |
| View Once | **Defer in V1.** Per-recipient consumption, delayed fan-out and removal semantics need their own review; do not weaken the one-to-one implementation. |
| Notifications | P8 Maximum privacy: “New group message” only. Less private modes may show group name/content only while unlocked and permitted; no member list/photo leak in Maximum privacy. |

For a media message, prefer **one freshly encrypted blob per logical attachment** and distribute its descriptor/key/capability only inside the separate Signal envelopes to members authorized at that epoch. This saves N uploads but creates a common blob ID/size and correlated downloads visible to the server. Per-member blobs would reduce direct shared-blob correlation at much higher upload/storage cost and still leave fan-out timing metadata. Each group media message requires a new random attachment key; do not reuse keys across epochs or attachments. A removed member who already received a descriptor can retain plaintext or fetch the old blob while its capability and retention permit; removal does not revoke those bytes. New-epoch media is never sent to that member. Server blobs currently have a seven-day complete retention cap and 26 MiB per-blob limit; future downloads are not guaranteed. Attachment fan-out, account quotas, blob capability exposure and failure handling need P13.5 review.

Group name/About and optional photo are versioned encrypted state, not server plaintext. For photo, start by evaluating the P6 inline approach (JPEG, at most 256×256 and 8,192 encoded bytes); current profile updates pad to at most 8,704 bytes inside the 16,384-byte application body limit. A group state carrying a roster, signatures and photo may exceed that budget even for five members. **P13.1 must measure exact encoded state/control sizes** and may separate a bounded encrypted photo control or descriptor instead; do not assume the P6 budget automatically carries over. Member clients validate format, dimensions and revision before display. No automatic historical photo disclosure to a pending invitee beyond the explicitly encrypted preview.

## Backend choices, abuse, compatibility and rollout

Option A, **no server group state**, is selected for V1: ordinary pairwise mailboxes carry all encrypted invitations, snapshots and content. This needs no group table or server-authoritative roster, but fan-out volume and delivery correlation are high. Option B, an opaque encrypted group mailbox, could reduce duplicated uploads but exposes a stable group route and requires new access/revocation semantics. Option C, a server member routing list, simplifies fan-out but directly discloses membership and makes server state central to authority; reject for V1. Do not silently treat the server's delivery list as group membership truth.

Before implementation, model rapid creation, invite spam, member churn, repeated fan-out, attachment amplification, per-sender and per-recipient quota pressure, stale offline queues, and a malicious member intentionally triggering retries. Bound active groups, outstanding invites, pending per-recipient fan-out and state-log length on clients; preserve generic failure responses and no block oracle. Server rate/quota changes require separate backend review, migrations only if necessary, isolated tests and deployment planning. This document changes none of them.

P13.3A defines the reviewed **text-launch transport capacity** in [the receipt-retirement note](infrastructure/P13_3A_RECEIPT_RETIREMENT.md): 16,384 live V3 submissions per sender, 100,000 globally, 512 mailbox rows and 16 MiB per recipient, 20,000 mailbox rows globally, unchanged 60 SEND/minute, and eight-item FETCH. These limits support one day offline at 20 texts/day/member across five five-member groups, but not seven days offline or 100 texts/day/member across five groups. V009 is deployed according to the operator; P13.3 text is implemented on Android, with controlled A/B/C validation still required. Group media capacity is outside this gate.

**Blocking policy:** V1 local block affects direct chats only and never silently removes a group member. Group membership still authorizes group delivery. A device may locally hide blocked member content/notifications without ACK or wire behavior differences, but must authenticate, commit replay/ratchet state and avoid exposing block state to senders. If this is unacceptable UX, offer explicit leave or admin removal. No automatic block-triggered rekey, no sender-visible block signal. A group should disclose this distinction clearly before launch.

An old client continues normal one-to-one messaging. Invitation requires an **authenticated, versioned group capability** from the prospective member; never send unrecognized group payloads to a peer merely because a newer sender supports them. Unknown group controls fail safely without changing one-to-one state. Mixed versions may exclude a member from new group features or require a group-wide capability floor; do not silently downgrade authentication or group semantics. No migration of existing one-to-one chats into a group without explicit user action.

## Implementation gates and proposed phases

1. **P13.1 — group identity/state foundations, design-to-code gate.** Finalize versioned identifiers/principals, actor/coordinator signature binding, event authorization matrix, canonical hash-chain/fork handling, epoch and offline resync semantics, encoded-size budget, storage/replay limits and property-based state-machine tests. Add only dormant local models/fixtures after independent security review; no user-visible groups, messages, backend route or migration yet. Explicitly decide whether the single-coordinator availability tradeoff is acceptable.
2. **P13.2 — invitations and membership.** Capability-gated encrypted direct invites, acceptance, roles, ordered snapshots, leave/removal/dissolve, identity-change fail-closed behavior, offline/fork tests.
3. **P13.3 — text fan-out.** Durable per-recipient outbox, epoch-gated receives, partial delivery status, quota/performance review and isolated backend capacity tests.
4. **P13.4 — replies, reactions, edits and delete controls.** Group sender authorization, replay tombstones, out-of-order tests and honest best-effort UI.
5. **P13.5 — media.** Shared encrypted blob descriptor fan-out, quota/retention/removal tests, photo and voice-note handling; keep View Once deferred.
6. **P13.6 — policy and privacy UI.** Group timer authority, encrypted profile, local organization/search, P8 notification levels, blocked-member UX.
7. **P13.7 — hardening and rollout.** Adversarial membership forks, offline convergence, lost owner, compromised/replaced device, scale/abuse tests, mixed-version and operational review. Release only after backend quota and metadata impacts are approved.

No P13 phase is authorized by P12. Sender keys or a larger group size require another explicit cryptographic/operational decision. The safest P13.1 outcome may be to revise or reject this design if canonical-state or availability guarantees cannot be demonstrated.

### Source baseline inspected

- `Android/gradle/libs.versions.toml` at P11 main pins libsignal `0.102.1`; the cached exact-version JAR was inspected with `jar tf` and `javap` for the group classes listed above. Availability of classes is not an integration or security test.
- `Android/protocol/src/main/kotlin/org/ghostcloak/protocol/Envelope.kt` bounds the application body at 16,384 bytes and packet at 131,072 bytes; `NetworkV2.kt` sets an eight-item fetch batch and 196,608-byte request body.
- `backend/src/main/kotlin/org/ghostcloak/backend/MailboxService.kt` and `PostgresDatabase.kt` define the SEND rate/default 60 per minute, mailbox/submission caps, and seven-day/30-day retention defaults described above. Production constructs `PostgresRateLimiter` with that default in `ProductionServer.kt`.
- `Android/messaging/src/main/kotlin/org/ghostcloak/messaging/Profiles.kt` defines the current 8,192-byte photo and 8,704-byte padded profile-control limits. These are **not** measured group-control limits.

### P13.4A3 prospective admission proof

For capable group members, a new candidate joins only after every member of the exact parent roster has independently verified the candidate's authenticated account/device/auth-key/Signal-identity binding and signed a versioned approval. A4 type-13 maintenance carries proposals and approvals even across a local direct-contact Block. The coordinator waits for the complete deterministic certificate before offering the normal target-accepted invitation; a changed parent invalidates the attempt. The ADD, certificate, and historical authority anchor commit together, and missing evidence is requested through bounded maintenance resync. Historical keys serve retained chain verification only. An offline or unreachable member stalls admission without a timeout bypass. Existing P13.3 groups do not gain retrospective proofs for members already present, and governance activation still requires the existing-member baseline and later policy/order gates. See [GROUP_GOVERNANCE.md](GROUP_GOVERNANCE.md).

### P13.4A5 existing-member authority baseline

The coordinator can initiate a one-time certificate for an exact active canonical state. Every current member verifies every other current member's exact registered account/device/device-auth key/Signal identity through the group-scoped current authority path, even if one direct relationship is locally blocked, and signs the same roster/state digest. All signatures are required. Each endpoint commits the complete certificate, activation record, and historical anchors for the full current roster atomically. An offline member delays activation; a changed identity, stale state, fork, or unavailable verification fails closed. Signed approval is evidence only from the baseline revision forward, never a claim about older history. A3 admission after the baseline adds a separate join-point authority for the new member; prior A5 cutoffs remain. Historical keys are consulted only with an event revision during chain replay, never for fresh state/text/maintenance. The exact domain, sizes, privacy costs, and remaining governance blockers are in [GROUP_GOVERNANCE.md](GROUP_GOVERNANCE.md).

### P13.4A7 current-head group text

Governance-ready groups use a separately versioned `GroupTextV2` inside the same recipient-specific Signal fanout. Its activation digest, governance sequence/head, and canonical policy digest must match the recipient's **current** signed head; a past permissive head cannot authorize newly backdated text. Future-head text is bounded, encrypted, hidden, and reconsidered only after complete signed resync; stale text is discarded. Policy-only entries and membership entries share one retained 511-entry journal and one head. An authenticated text-v2 capability from every active member gates governed text and policy actions. The A6 barrier freezes pre-governance v1 sends; incomplete or forked governance is read-only with history preserved. A new invitee receives a separately co-signed policy checkpoint; existing members still replay every intervening entry. Local Block continues to hide user content without changing the A4 maintenance channel. Group moderation and full management UI remain deferred.

### P13.6 group media

An updated governance-ready group can send photos, documents, and voice notes. The sender prepares the media locally, applies any voice mask once, encrypts and uploads one blob with the existing attachment format, then stores one logical group message and a recipient plan. Each recipient gets the same versioned descriptor and optional caption in a separate Signal envelope through the durable outbox. The descriptor, filename, caption, attachment key, and group identifiers are absent from the upload request. Every member must advertise authenticated group-media capability before the attachment composer is enabled; ordinary text continues for mixed-version groups.

Media is bound to the exact current governance head, policy digest, epoch, sender membership, and send permission. Future-head descriptors stay hidden and bounded pending resync; stale-head descriptors are discarded. Removed and newly joining members receive no new or historical descriptors. A locally blocked sender's application content stays opaque and causes no media fetch or presentation. A recipient can download only after authenticated acceptance. The same local download preferences and private cache apply. Terminal sender deletion or admin moderation scrubs local descriptor and caption references and retires unsent outbox slots; already delivered media or externally saved copies cannot be revoked. Replies and reactions may target a media logical ID; editing media and group View Once are deferred.

The existing download protocol sends the blob capability to the backend in a request header, so the backend **does see it at download time**. It also sees the blob ID, account requesting the download, encrypted size, and request timing. One shared blob allows correlation of the accounts that download it and possible inference of co-recipients. This accepted traffic-analysis limit is not a claim of hidden group membership. The server does not receive the group ID, roster, filename, caption, or attachment encryption key as plaintext application fields. No backend route or schema changes are required.

### P13.7 group profile and photo evidence

The shared group name (64 UTF-8 bytes), About (256 UTF-8 bytes), and optional photo commitment form `GroupProfileV1`. A signed profile replacement consumes the same governance sequence and head as membership and policy changes; neither the frozen `GroupState` nor the 12,000-byte group-control cap changes. Owner and Admin may edit at the current head; all current members must advertise the authenticated profile capability. Existing groups start with the deterministic `Group` / empty About / no photo profile. Mixed-version groups retain ordinary group messaging but cannot edit the shared profile.

The JPEG is prepared locally at no more than 256 square pixels and 8,192 bytes, with metadata stripped. Only its digest and length occur in canonical profile entries and the retained signed journal. A separate versioned companion carries the bytes through authenticated pairwise Signal group maintenance. The companion is evidence, never authority: it becomes visible only after matching the signed activation, head, profile digest, and photo commitment, plus structural and Android decode validation. An early companion remains hidden and bounded; a missing companion leaves a generic avatar and an updating state. Renames reuse verified bytes by digest. Removal deletes obsolete bytes. All current members retain the current verified photo in encrypted endpoint storage, allowing a remaining member to supply it after the original editor leaves. Current-member exact-head requests recover it after offline resync; the new invitee receives a signed current-profile checkpoint and a separate companion, without earlier profile or photo history. Signal sender authentication and exact governance commitment suffice, so the companion adds no independent signature or authority chain.

Measured five-member maximum bootstrap with current filters: 8,752 bytes before the profile proof and 9,841 bytes with the maximum name/About/photo commitment, leaving 2,159 bytes below the unchanged control cap. A maximum 8,192-byte JPEG produces an 8,527-byte companion, 8,769-byte control, and 8,960-byte padded group-system frame. Photo bytes never use the attachment backend or a server profile route. The server sees ordinary encrypted routing metadata only. Local Block does not suppress authenticated internal group-state maintenance. A removed or departed member keeps its last accepted profile for read-only history and receives no later updates; a dissolved group remains read-only. OS notifications do not include group-photo or About content.
