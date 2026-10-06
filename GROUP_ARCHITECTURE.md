# P12 — private group architecture design

**Status: DESIGN ONLY, 2026-10-05.** No group capability, protocol, storage schema, server route, or UI is implemented by this document. The current product is one device per account and one-to-one Signal E2EE. This proposal is a target contract for review, not a claim that current clients can safely exchange group messages.

## Product boundary and recommendation

V1 should use **pairwise Signal fan-out with no server group roster**. A sender encrypts an independent, authenticated one-to-one envelope for each other active member. Group ID, epoch, sender, message ID, and encrypted group state stay *inside* those envelopes. Start with a **five-member maximum including the owner**, subject to capacity changes and the gates below. This minimizes new cryptography and uses the current durable outbox, identity pins, mailbox and attachment capability model. It is not anonymous: the server sees each delivery edge and timing. An initial group must comprise accepted contacts with group capability; no public groups, links, directory group search, calls, or multi-device linking.

The five-member cap is a design target, **not a claim that current quotas support normal use**. Four recipients mean four submissions for one group message. Current production limits are 60 SEND operations per sender per minute, 1,024 retained submissions per sender, 128 queued mailbox rows or 8 MiB per recipient, and 2,048 global mailbox rows. Submission dedupe lasts 30 days; mailboxes last up to seven days. Thus even with no direct traffic, 1,024 submissions cover at most 256 five-person group messages per sender per 30 days, before control events, retries or other chats. A 20-person group would cost 19 sends per message and reach 60/minute after only three messages in a minute. **P13 must review and test quotas, fair scheduling, partial fan-out, and ingress bandwidth before shipping groups.** Raising limits blindly is not an approval to implement groups.

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
