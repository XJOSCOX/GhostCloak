# Group governance and management

## P13.5 message-control boundary

Group replies (`GroupTextV3`) and reaction/edit/sender-delete controls do not
consume journal entries. They bind to the accepted activation digest, sequence,
and head digest, and the recipient verifies the authenticated actor against
the current canonical roster. A future head waits for resync; a stale head is
discarded even if it was valid when sent, because old-head acceptance would
permit backdating after removal. Posting restrictions prevent new replies but
do not prevent reactions. Only the original sender can edit or delete their
text. Admin moderation remains journal-ordered and has terminal precedence
over sender deletion. A P13.5-capable invitee starts at its signed checkpoint,
receives no old plaintext, and inherits bounded terminal ID filters.

## P13.4B management surface

Group Info projects member labels, roles, posting mode, pending status, and the
bounded journal state without displaying account IDs, device IDs, keys, or
digests. An existing group activates management only after the local owner or
delegated coordinator explicitly starts A5 baseline setup and then A6
activation. Every current member must participate; an offline member leaves
setup pending. Ordinary pre-governance text groups remain usable without a
reset. Forked, resyncing, and legacy-incomplete groups do not expose management
actions; a legacy-incomplete group needs a new group for management.
The first setup tap shows baseline preparation, including when an updated peer
has not yet confirmed capability. Once all baseline approvals are installed,
Group Info explicitly offers **Continue group setup** for activation. A missing
peer capability or other setup failure is shown on Group Info itself; no tap
silently appears to do nothing.

All member changes use the current signed `GroupTransition` and `GroupRules`,
wrapped in one A6 governance entry with the exact head and pre-state. A
non-coordinator actor signs the exact requested transition; the coordinator
validates it, signs the transition, prepares the canonical entry, and returns
that exact body for the actor's entry signature before co-signing and applying.
Head changes invalidate the proposal. Protected local intent records survive
restart; one outstanding entry serializes canonical mutations. Applied ACKs
from the resulting member roster gate the next entry. A departing member gets
best-effort signed terminal evidence without delaying that roster. No later
group entry or text is addressed to the departed member. Unsent old-head text
is retired. Already accepted ciphertext and existing local messages cannot be
recalled.

The owner may remove a Member or Admin, promote/demote (except the current
coordinator), restrict either non-owner role, change posting mode, transfer
ownership, and end the group. An Admin may remove or restrict a Member and
change posting mode; an Admin cannot manage an Owner or another Admin, promote,
demote, transfer, or end the group. Members cannot manage others; a Member or
non-coordinator Admin may leave. The Owner must transfer ownership before
leaving. The proposed new Owner must explicitly accept an encrypted request
and sign the exact transfer statement; declining changes no state. The target
becomes the sole Owner and the former Owner becomes an Admin only after the
accepted canonical entry. Ending a group is terminal and preserves already
stored history. It is not remote message deletion.

An Owner or Admin who is not coordinator requests an invitation through an
encrypted, actor-signed control bound to the exact group state and governance
head. The coordinator checks the actor's current role, signature, target trust,
capacity, and pending state before issuing the normal all-member-approved
invitation. A bounded protected replay record prevents the same request from
issuing another invitation. Members cannot request invitations.

Group restriction is a canonical send policy. Local direct-contact Block is
private and still suppresses blocked content without changing group membership
or revealing Block to others. Local notification mute is not implemented in
this phase. Admin deletion of another member's messages remains deferred to
P13.4C. See [P134B_PHYSICAL_VALIDATION.md](P134B_PHYSICAL_VALIDATION.md)
for the controlled A/B/C plan.

## P13.4A6.3 journal and admission implementation

New governed entries retain their complete signed `GroupGovernanceEntryV1` in the protected endpoint store, indexed by sequence, with a separately checked tail. The journal begins at sequence 1 after activation, is contiguous through the foundation head, and is capped at 511 entries. ACK completion permits the next entry; it never erases an earlier entry. Capacity or any missing/mismatched entry stops further governed mutation. An A6.2 activation at sequence 0 remains complete. An earlier A6.2 group with sequence above zero and no provable complete signed journal is classified `LEGACY_INCOMPLETE`: readable local history remains, but governance mutation and future policy-bound text are locked. No state reconstruction, signature synthesis, destructive reset, or automatic migration occurs. The recovery path is a new group and reinvitation.

An existing member requests an exact activation/sequence/head/state tuple from the current coordinator over Signal. The coordinator serves only a current canonical member and never serves entries before that member's join. Each response contains exactly the next signed entry. Every entry passes the existing governed transition validator and commits state, foundation head, journal tail, authority evidence, and replay state in one endpoint-store transaction. A durable resync marker survives interruption; a restarted client can resume at its last committed head. A valid competing signed successor freezes the group as `FORKED`. A missing entry, untrusted historical signer, or mismatched parent fails closed. Five-member fixture sizes were approximately 4.0 KiB for one entry, 17.6 KiB for five, 34.6 KiB for ten, and 85.6 KiB for 25; one-entry responses stay within the 12,000-byte control cap.

Activated-group admission retains A3's all-current-member approval and target acceptance, then binds the certificate and invite to the exact governance head with a separately signed `GovernedAdmissionBindingV1`. A changed head invalidates the pending admission and requires a new proposal. The current owner and coordinator sign a candidate-specific `GroupGovernanceInviteeCheckpointV1`; a delegated coordinator must obtain the owner's signature. The coordinator issues the ADD as the next governance entry. Existing members verify and apply that entry normally, including their own A3 approval and candidate authority evidence. Only the exact new invitee can atomically install its join-point state, barrier/head, checkpoint, signed ADD and candidate authority. An existing member cannot use that checkpoint to skip earlier entries. The candidate receives no earlier governance entries or message plaintext. Five-member-result fixtures measured approximately 10.3 KiB for the invitation, 9.8 KiB for a checkpoint-sign request, 6.0 KiB for bootstrap, 0.6 KiB for the binding, and 1.2 KiB for the checkpoint.

The sections below record the earlier foundation stages. Their then-current
feature gates are historical; the P13.4B section above states the current
management surface. Admin message moderation remains deferred.

## P13.4A6.2 activation and one ordered entry

Governance-v1 support is advertised only in Signal-authenticated type-13 padding by each current canonical member. A peer with missing evidence keeps ordinary P13.3 text available but cannot start activation; a bounded capability echo can refresh evidence without changing legacy frame size. The coordinator proposes an exact active GroupState revision/digest, A5 certificate digest, roster digest, owner/coordinator IDs, random activation ID, and versioned initial head material. The current owner and coordinator sign that one canonical body under separate domains. A delegated coordinator requests the owner's signature through restricted A4 maintenance; it cannot substitute its own key.

Each member independently checks the exact current state and A5 baseline, all current peers' governance capability and registered bindings, both signatures, and absence of pending admission or conflicting activation. It persists its signed approval before sending. The coordinator requires the exact complete member set, sorts the approvals, and distributes the signed COMMIT. Each recipient independently verifies it and installs the A6.1 barrier and local COMMIT/installed-ACK evidence in one protected transaction. Sending an approval alone does not activate the group. After every member's signed installed ACK is collected, a deterministic READY certificate is distributed; an endpoint needs both its local barrier and READY before governed mutation. Offline peers stall the ceremony without quorum or timeout bypass. An exact duplicate resumes; a different activation is rejected. A state change makes the pending proposal stale and requires a fresh A5 baseline; it cannot be reinterpreted.

The first versioned `GroupGovernanceEntryV1` binds activation digest, sequence, prior governance head, event ID, pre/post state revisions and digests, actor/action, and the digest and bytes of the exact existing signed GroupTransition. Actor and current coordinator sign its canonical body under new governance domains. The receiver checks those signatures and exact head/parent, then A6.1 validates the wrapped transition and atomically advances GroupState and head. A6.2 exercises only an internal timer change; no management UI is exposed. Exact entry and per-recipient send evidence persist for retries. One unconfirmed entry per group is allowed: the coordinator waits for signed application ACKs from every member in the resulting roster before creating the next. For a later REMOVE, that rule excludes the removed member. Legacy raw state updates and state-only resync cannot cross the barrier.

Activation and entries use bounded protected endpoint records and the existing pairwise Signal maintenance transport, including locally blocked canonical members. Safe Exit and inactivity destruction remove those records with the endpoint store. No backend route, database migration, or new server-visible group data is introduced. At the A6.2 checkpoint, resync and the admission-only checkpoint were still disabled; their A6.3 implementation is described above. GroupTextV2 policy/head binding and group management UI remain disabled.

## P13.4A6.1 dormant ledger barrier and atomic mutation foundation

A6.1 adds a separate encrypted-local `GovernanceBarrierV1` record binding an exact active GroupState revision and digest, the A5 baseline certificate digest, and an activation digest. A6.2 installs it only after a fully verified all-member COMMIT. Duplicate exact installation is idempotent; a different barrier is rejected. The companion `GovernanceHeadFoundationV1` starts at sequence zero and is written in the same endpoint-store transaction.

The ledger itself checks the persisted boundary. Legacy `apply` cannot advance beyond the anchor; legacy `applySnapshot` checks the entire chain before mutation and can replay only through the exact anchor. A valid alternate anchor fails closed, and an invalid unsigned event cannot force a fork. Legacy `acceptAdmission` cannot replace a barriered group; future activated invitees require a separate checkpoint-bound bootstrap. Genesis duplicate/fork rules remain, and no path removes the barrier. A missing companion head or a mismatched current state/head is an integrity failure. A pre-anchor state may be read only by the restricted legacy recovery path; normal group-state reads fail closed until it reaches the exact anchor.

The internal `applyGovernedTransition` validates the existing signed `GroupTransition` with the same `GroupRules`, signature, replay, fork, and capacity checks as legacy `apply`. It then writes the companion head and GroupState in one `EndpointRecords.transaction`; any storage or commit failure rolls both back. A6.2 verifies a separate signed entry before invoking it. A6.3 must provide ordered governance resync and a new-invitee checkpoint; A7 must bind GroupTextV2 and policy to the accepted governance head. Management UI remains **NO-GO**.

## P13.4A5 existing-member authority baseline (Android foundation)

A5 adds a separate, versioned, one-time baseline protocol for a group's **exact current state**. It does not alter any signed v1 state, transition, genesis, admission, invite, or text bytes. The coordinator proposes the state digest, revision, lifecycle, ordered complete member IDs and full `GroupMember` digests, member-set digest, owner, coordinator, and random proposal ID. It signs `GhostCloak.GroupAuthorityBaselineProposal.v1`. Every current member checks that state locally, independently checks each other member's exact account/device/device-auth key/Signal identity against the authenticated exact device-binding lookup and its current group-scoped Signal pin, then persists its own signed `GhostCloak.GroupAuthorityBaselineApproval.v1` before sending. At five members that is four checks per endpoint, twenty directed checks overall. A local Block does not restore a direct contact or expose blocked content; the A4 group-only authority path and type-13 maintenance channel carry the ceremony. An identity/key mismatch prevents approval or activation.

The coordinator requires one valid approval from **every** member of the exact parent state, in canonical member-ID order. A missing or offline member waits without timeout, owner override, or quorum. The complete certificate goes to all members. Each endpoint checks the signatures and its own durable approval, rechecks all current peer bindings, and atomically writes one activation record and all current-member historical anchors in the encrypted endpoint store. A changed state or valid fork makes an unactivated attempt stale. Duplicate exact evidence is idempotent. A permanently unavailable canonical member prevents completion; governance cannot work around that availability problem in A5.

This approval says only that each member verified the current registered keys at the **baseline state**. It does not certify earlier signatures. A5 historical records start at the baseline parent revision; A3 candidate records start at the ADD revision; a newly admitted member's signed admission provides its own join-point anchor for already-present peers. The historical verifier requires the event's parent revision and exact member key. It is passed only to signed-chain replay, never to fresh `apply`, current maintenance, text, direct contacts, or profile sharing. Role authorization still comes from the canonical state at each event. A later A3 admission extends authority for the new member without rewriting the A5 cutoff; removal ends current authority while retaining bounded historical evidence for retained signed events.

An A5 capability marker rides only in authenticated, ignored padding of an otherwise valid type-13 control. For a peer whose capability has not yet been seen, the initiator sends a valid legacy resync request with that marker and waits for reciprocal evidence before sending any A5 control. This preserves old-client decoding. The backend already knows individual exact registered bindings and can correlate bursts of exact lookup timing, but it receives no group ID, roster, roles, certificate, or local Block status: controls stay inside pairwise Signal E2EE. Evidence is bounded by 64 local groups, one pending and one active certificate per group, five member anchors per activation, and the existing 512-event ledger cap.

**Activation remains NO-GO.** A5 clears only the existing-member historical-binding prerequisite. Still required: ordered governance journal transport, an atomic post-activation cutoff of legacy state updates/resync, governance resync, GroupTextV2 governance-head binding, current-head-only policy enforcement, and restart/fork adversarial validation. P13.4 management UI remains unavailable. Earlier A2/A3 stop findings below record the path to the A3 and A5 implementations; they are not a claim that governance is enabled.

This is a design record, not an enabled protocol. Group management UI and governance activation remain unavailable. The existing P13.3 group format, text transport, and local ledgers are unchanged.

## P13.4A3 admission stop finding

The all-member admission handshake is **NO-GO** in the current blocked-contact architecture. `ConversationService.acceptNetwork` authenticates and commits a blocked sender's Signal envelope and replay receipt, then returns before decoding `ConversationPayload` or queuing a group control. `LocalRepository.block` also ends accepted-contact status and clears that peer's group capability and device-authority pin. `GroupMembershipTransport.send` and `trusted` require an active accepted contact and current registered-key resolution. Consequently, a locally blocked device that is still a canonical group member cannot receive an admission proposal, independently approve it, or send an approval through the current group path. Treating a missing approval as an owner-bypass or quorum would violate the all-member requirement. Parsing a special admission control from blocked content, or sending a distinct block-dependent response, would change the blocked-envelope privacy contract and needs a separate approved protocol design with block-oracle tests. No such exception is implemented here; A3 explicitly requires stopping when blocked controls cannot be processed under the current discard rule.

The authenticated exact `/v2/directory/capability` lookup can return a device binding for an exact proposed account/device/Signal-identity tuple, but the present Android `GroupAuthorityResolver` intentionally requires an active accepted contact and its pinned Signal identity before using that path. A3's proposed admission-scoped verifier would need to preserve that boundary and prove the registration tuple independently. It would not prove real-world identity or create a direct accepted contact. This is a design possibility, not an implemented verification path or authority anchor. A blocked or offline current member cannot be silently skipped; admission must remain pending. Existing groups retain no synthetic historical approval record. A future baseline could only establish prospective authority from an exact state accepted by every member and cannot retroactively prove missed history.

### P13.4A4 group-system channel

A4 adds a restricted type-13 group-system channel for an already-canonical peer whose direct relationship is blocked or otherwise unaccepted. Authenticated user content, including type-14 group text, remains discarded while blocked. Only state update, resync request/response, and existing owner/coordinator admission request/response may enter the encrypted internal queue; invitation and acceptance controls have no block exception. A group/member-specific current authority is anchored from signed admission or ADD and independently verified contact/key material. An older group can establish this anchor only while its matching direct pin and signed local admission/ADD evidence still exist. Missing proof fails closed. Current group authority cannot authorize a direct contact, group text, or historical signatures. It is removed/disabled when canonical membership ends. This permits an already-canonical blocked member to exchange maintenance controls without restoring direct acceptance, but it does **not** implement A3's all-member approval or historical-authority anchor. Governance activation remains **NO-GO**.

## Frozen compatibility boundary

`GroupState`, `GroupChange`, `GroupTransition`, `GroupGenesis`, and `GroupAdmission` v1 are canonical, signed formats. Their field order, defaults, encoding, and digest must remain unchanged. A governance journal must use a separate versioned record and a separately typed encrypted Signal control. It may wrap an existing signed `GroupTransition`; it must not replace or reinterpret one. A pre-governance group continues P13.3 text until every active member has authenticated governance-v1 capability.

## Proposed canonical order

An activation at sequence 0 binds the exact current group ID, revision, state digest, default `EVERYONE` posting mode, and empty restriction/tombstone sets. The current owner and coordinator sign the same domain-separated activation statement. If they are the same device, these signatures are not a quorum; security comes from chain ordering and fork detection.

Each later canonical entry has a version, group ID, monotonically increasing sequence, previous governance digest, exact pre-event GroupState revision/digest, pre-event policy digest, random event ID, actor ID, action, action payload, and resulting GroupState revision/digest and policy digest. The actor and current coordinator sign the exact canonical body under distinct governance domains. Both signatures and authorization are checked against the state produced by the preceding entry. A wrapped membership action also carries its existing actor/coordinator-signed `GroupTransition`; `TRANSFER_OWNER` retains its existing target consent signature. Apply the GroupTransition and governance head in one endpoint-store transaction, with an injected-failure/restart test proving rollback.

Posting mode, restrictions, and moderation tombstones are *derived by replay*, never trusted as an unsigned local policy. `REMOVE_GROUP_MESSAGE` names only the logical message ID. A removed member's restriction is removed in the same state transition. Dissolution is terminal. All existing privileged GroupState mutations, including profile and timer changes if ever exposed, must use the journal after activation. Old type-13 state updates and old state-only resync must be rejected once activated; otherwise they bypass the total order.

For example, if G10 is B's moderator action while B is admin and G11 demotes B, an offline recipient accepts G10 only as the exact successor of G9 and then G11. After accepting G11, it rejects a newly signed action by B extending G11 because B lacks authority. A different valid successor to G9 is a fork, not a backdated action or a winner. The old state cannot be selected using a timestamp, server arrival order, or a signature over the old role alone.

## Forks, bounds, and resync

A valid competing successor of an accepted head permanently freezes governance and new group text on that device; history remains readable. Recovery is a new group. A sequence gap leaves the entry unapplied and requests a bounded missing chain from the current coordinator, tied to the requester's exact `(sequence, digest)`. Every entry is verified and applied in order. No high-sequence snapshot fast-forward is allowed for existing members. If history or an authenticated historical signer binding is unavailable, the device stays in `NEEDS_GOVERNANCE_RESYNC` and cannot send/receive new group text or management actions. A bounded journal may stop management at capacity instead of truncating required evidence. Concrete entry, ledger, restriction, tombstone, and resync-size limits require measured fixtures before activation.

The user approved a narrower checkpoint **only for a newly invited member**. That checkpoint must bind the invite ID, invitee member/device, the P13.2 dual-signed current-state admission proof, activation digest, current governance `(sequence, head digest)`, current policy digest, and current state digest. The then-current owner and coordinator sign it under a new domain. The invitee verifies the admission and checkpoint together and begins at that head, with no historical group message plaintext or old group governance history. Existing members must still verify every intervening entry. A checkpoint cannot be used to repair an existing member's gap.

## Capability and transport gate

Capability evidence must come from authenticated Signal content for **each active member**, not copied text, a local toggle, directory metadata, or a server claim. Before activation, any missing capability leaves P13.3 text operational. After activation, all members receive governance controls through durable per-recipient pairwise Signal outbox entries. Controls must not be sent to a P13.3-only peer. Revocation or loss of an active member's accepted contact/identity binding freezes affected governance processing; no network fallback or blind trust is introduced.

## Blockers before implementation can be enabled

1. The current `GroupMembershipTransport` has state-only paths for `ADD`, `REMOVE`, `STATE_UPDATE`, and state resync. Activation without atomically replacing or gating **every** one of these lets GroupState advance without the governance head. A separate journal implementation alone is unsafe.
2. `GroupAuthorityResolver` accepts only active accepted contacts. During a long offline gap, a signer may join and later leave or be blocked before the offline member receives the chain. The current `trusted()` path cannot verify that signer. The safe present behavior is to keep the group frozen; accepting a later owner/coordinator snapshot would violate the existing-member chain requirement and the approved mutual-contact binding rule. A durable, reviewable historical binding proof or an explicit permanent-fail-closed policy must be tested before promising recovery.
3. P13.3 type-14 text has only an epoch, no governance head. A governance posting restriction can change without changing that epoch. Send/receive authorization therefore needs an explicit, tested policy-order rule and a transaction barrier with pending text; merely checking policy in a future UI is insufficient.
4. The current invitation control has no checkpoint bound to its admission proof. The approved new-member checkpoint format, verification, and delegated owner/coordinator co-signing path are not implemented.
5. No end-to-end restart, fork, mixed-version, five-member size, and durable fan-out tests yet prove these paths.

These are implementation and assurance gaps, not permission to weaken the checks. No backend state, migration, trusted clock, or custom cryptography is needed by the proposed design. P13.4 management UI remains **NO-GO** until the code and test gates above pass. No deployment is required for this design record.

## P13.4A2 historical-authority finding

The A2 offline example exposes a missing proof at **admission**, before a historical cache can be created. Suppose C has accepted GroupState revision N−2 and goes offline. B is then added at N−1, signs an action at N, and leaves at N+1. Before C returns, C's direct relationship with B ends. C has never verified or stored B's group authority. The existing ADD embeds B's public key and is signed by the actor/coordinator, but `GroupRules.derive(ADD)` also requires `trusted.matches(B)`; the production `trusted()` implementation requires C's currently active accepted contact and freshly resolved registered key. That check now fails. Filling a historical cache from the ADD alone would turn the actor/coordinator's assertion into C's missing direct-contact verification, contrary to the P13.2 mutual-contact rule and A2's no-TOFU requirement.

An immutable historical record **can** preserve an authority C already verified. It cannot manufacture evidence C never received. The user approved requiring every current member to approve an addition while that member's direct contact is active. The revised admission protocol must obtain each member's authenticated approval of the exact target `(account, device, Signal identity digest, registered auth key)`. The approval must be bound to group ID, parent state digest/revision, invite ID, and target member ID, signed under a new domain, and persisted by that member before it can go offline. The coordinator may finalize ADD only after all current members' approvals. A recipient later verifies its own prior approval against the canonical ADD, then anchors the immutable historical authority record atomically with applying ADD. Other members' approvals cannot substitute for the recipient's missing approval. If an approval is absent or the contact/key changed, admission waits or fails closed. This changes the P13.2 invitation flow and needs separate size, expiration, replay, fork, and crash review.

Until that proof exists, the safe behavior is to retain the gap, refuse later governance/text, and request no blind snapshot. The A2 requirement that C **must** verify N−1 through N+1 after losing the direct contact cannot be met by the present records. Therefore the A2 prerequisite gate is **NO-GO**; governance activation remains unavailable. Neither the legacy state bypass nor text-head binding should be enabled piecemeal as a supposed completed A2 protocol.

The approved admission handshake must use a **new** versioned, domain-separated approval format outside the frozen v1 objects. Each current member independently resolves the target against its own active accepted contact and pinned Signal identity, then signs the exact parent-state digest/revision, invite ID, target member/account/device, registered auth key, and Signal identity digest. Its pending approval is persisted before an encrypted response is sent. The coordinator may issue the invitation and later commit `ADD` only after collecting approvals for the exact current roster; any parent change invalidates that set. The resulting control and bounded resync chain must carry the approvals without altering `GroupTransition` v1 bytes. A recipient verifies its **own** durable approval before accepting delayed `ADD` without a current contact; it never accepts another member's assertion in place of its own. Applying `ADD` anchors the historical key and evidence in the same encrypted transaction. Historical evidence may validate past chain signatures only; new controls and text still require current trust. A direct-contact deletion does not erase the already anchored group history, while Safe Exit destroys the whole endpoint store.

The irreversible activation boundary belongs in every raw state mutation path (`apply`, `applySnapshot`, state update, state resync, and invitation acceptance), not only in the UI or network dispatcher. An activated record must bind one exact v1 state digest and journal head; old state-only updates crossing it are rejected. A governance-wrapped transition must apply v1 state and journal head in one transaction. No activation API should be reachable before that entire path, the all-member capability gate, and the new-invitee checkpoint are implemented.

Post-activation text needs a new Signal-only frame with `(groupId, epoch, senderMemberId, logicalId, governanceSequence, governanceHeadDigest, text)`. The v1 frame remains untouched for unactivated groups. A recipient displays the v2 frame only at its **current** accepted governance head and only if policy at that head authorizes the sender. A future-head frame stays bounded and hidden pending signed-chain resync; an older-head frame is discarded even if it was once authorized. This prevents a removed or restricted sender from creating a new message falsely claiming an earlier permissive head. Locally queued but unsent older-head fan-out must be cancelled when the head advances; already submitted ciphertext cannot be recalled.

## P13.4A3 admission implementation (supersedes the A2 implementation gap above)

New admission controls use the A4 type-13 group maintenance channel. A version-2 proposal binds the complete candidate `GroupMember`, its digest, the exact parent state and digest, invite ID, coordinator ID, and ADD event ID; the coordinator signs it under `GhostCloak.GroupAdmissionProposal.v2`. Every current canonical member independently compares the proposed account, device, registered device-auth key, and pinned Signal identity against an authenticated exact binding lookup while its direct contact with the candidate is active. The approval is signed under `GhostCloak.GroupAdmissionApproval.v2` and stored in encrypted endpoint records before sending. No candidate lookup creates a direct contact or a global authority record. A member locally blocked by the coordinator can still receive and return these maintenance controls using A4 group-scoped authority, with no change to direct-contact blocking.

The certificate orders one valid approval for every member of the exact parent state by member ID. A missing/offline member prevents the invitation and ADD; neither owner nor coordinator can bypass it. One coordinator-side proposal is pending per group until ADD completes or the parent state becomes stale. The target must still explicitly accept the normal one-use invitation. Any parent revision or digest change invalidates the proposal and signatures. An authenticated capability marker is required from the candidate and all parent members before selecting this path; older group behavior remains available for peers without that marker and carries no A3 historical assurance.

On ADD, the exact certificate and historical candidate anchor commit in the same protected-storage transaction as the v1 signed state transition. Existing members without required evidence defer ADD and request the bounded certificate through A4; they do not infer trust from the ADD itself. A new member verifies the dual-signed current-state admission and certificate, then anchors only the join-point members and their current keys. It receives no pre-join history. Historical authority verifies retained signed chains after later removal or contact deletion, but cannot authorize fresh controls or messages; those still require current canonical membership and current authority. Certificates and historical anchors remain protected for the bounded retained group history and are destroyed with the endpoint store by Safe Exit or inactivity destruction.

This clears only the prospective new-member historical-authority prerequisite. Existing groups have no fabricated certificates for current members and still need an all-current-member baseline ceremony. Governance activation, legacy state-update cutoff, ordered governance controls and resync, GroupTextV2 policy-head binding, and restart/fork adversarial gates remain **NO-GO**. No server route, database migration, or device installation is part of A3.

## P13.4A7 governance-bound text and policy

A governed group derives one version-1 policy from its retained signed journal. The activation digest domain-separates the canonical policy digest over version, posting mode (`EVERYONE` or `ADMINS_ONLY`), and sorted restricted member IDs. The initial policy is `EVERYONE` with no restrictions. Policy actions use a separately signed `GroupGovernancePolicyEntryV1`; they advance the **same** sequence and head as membership entries without fabricating a `GroupTransition`. A policy-only entry leaves GroupState unchanged. Owner or admin may change posting mode; owner may restrict any non-owner, while admin may restrict only a member. Duplicate/no-op actions are rejected. Removal and owner transfer normalize restricted IDs in the same atomic governed advancement. The 511-entry cap applies to both entry types; at capacity management stops but valid current-head text can continue.

`GroupTextV2` is an independently versioned Signal-only frame binding group, epoch, authenticated sender member, logical ID, activation digest, sequence, head digest, policy digest, and up to 2,048 UTF-8 text bytes. Receivers display it only at their exact current authenticated head and only if that head's derived policy authorizes the authenticated sender. A future-head frame stays in bounded encrypted local pending storage (32 per group, 128 globally), triggers signed governance resync, and is reconsidered after the chain advances. A stale head or wrong digest is discarded; there is no historical-head allowance for backdated text. Replay and visible-message creation are atomic. Locally unsent fanout for a superseded head is retired; already accepted recipient submissions remain honestly marked sent.

Pre-governance groups retain the frozen `GroupText` v1 path. Installing a governance barrier freezes legacy sending and retires unsent v1 fanout; after READY, only V2 may display. A group without a complete retained journal, with a fork, or awaiting the authenticated V2 capability of every current member is read-only for new text. Blocked canonical members still exchange only A4 maintenance controls; blocked user text is discarded before application parsing and cannot create a notification. No server group-policy processing or block oracle is added.

New invitees receive the existing A6.3 join checkpoint plus a separate owner-and-coordinator-signed policy proof bound to its unsigned body and exact parent head. The new join anchor stores that proof and checkpoint indivisibly as `GovernedJoinEvidenceV2`; existing members cannot use it to skip retained history. The resulting ADD inherits the parent's posting mode and does not restrict the new member automatically. Older A6.3 join records remain readable; new A7 bootstraps require the companion proof. This paragraph records the A7 boundary; P13.4B and P13.4C add management and text moderation later.

## P13.4C text-moderation order

`REMOVE_GROUP_MESSAGE` extends the versioned governance policy action, without
altering frozen `GroupAction` or direct-chat delete controls. It binds the
logical message ID, actor, current state/policy digests, activation digest,
sequence, previous head, and both signatures. Policy content is unchanged;
the same governance head advances by one. The pre-entry Owner/Admin role is
required even for text by the Owner or another Admin. Restriction governs
posting, not moderation. Current authority, all-member authenticated
moderation capability, READY status, complete journal, absence of a pending
action, and capacity are required for a new proposal. An exact duplicate
entry is idempotent; a new action for an already moderated ID is rejected.
Signed entries remain in the contiguous journal for offline resync, including
after a moderator is later demoted or removed. New invitees start at their
signed join checkpoint and receive no historical message bodies. A fixed
2 KiB moderation filter is included in the owner/coordinator-signed policy
proof, computed from bounded earlier logical IDs. The owner checks the
coordinator's exact filter before signing. A false positive fails closed by
suppressing the affected new text; it cannot reveal a moderated body.

The encrypted endpoint store retains at most one moderation marker per
governance entry, bounded by the 511-entry journal. Existing text rows become
explicit `REMOVED_BY_ADMIN` tombstones with an empty body in the same storage
transaction as the governance advance. Queued/late target plaintext is
removed or discarded; no content notification is generated afterward.
Already posted notifications are reconciled against the ledger. Search over
group text is not implemented. A tombstone is a local presentation change,
not a promise to erase screenshots, copied text, notification history, or
content already viewed. Blocked user content stays opaque; only authenticated
internal maintenance can carry the moderation entry. Sender group delete and
moderation of media remain outside this phase.

### P13.6 governed media

`GroupMediaV1` is a new application frame; frozen group-text bytes remain unchanged. Its descriptor and caption travel only inside pairwise Signal envelopes. A recipient checks the exact active governance sequence, head digest, policy digest, epoch, sender membership, and posting authorization before making media visible or fetchable. Future-head media waits hidden for bounded resync; stale-head media is dropped. The media capability marker is authenticated in ordinary group control traffic. All members must advertise it for group media send, while text remains usable without it. Membership or policy advancement retires unsent old-head descriptors. Local Block still authenticates and acknowledges but discards group media content. Moderation and sender deletion of an accepted media logical ID clear its descriptor, caption, pending download, and local presentation reference; neither action recalls an already received or saved blob.
