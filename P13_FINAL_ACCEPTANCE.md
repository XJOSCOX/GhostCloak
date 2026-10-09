# P13 final acceptance record

Status: **P13 COMPLETE — GO** for the agreed acceptance scope as of 2026-10-09.
The final clean-build replay and the user's physical-development-build checks
are recorded under "Final P13 signoff" below. Earlier NO-GO statements in this
record describe the state at those dated checkpoints; they are not the final
release decision. This signoff does not declare R1 or the V1 release candidate GO.

## Current implementation

- Maximum five members; pairwise Signal fan-out, with no server-side group
  roster or group-message state.
- Every current member approves admission. Signed, retained, bounded
  governance entries serialize membership, roles, posting policy, profile,
  timer and moderation changes. Existing members verify a contiguous chain;
  only the exact new invitee may use an admission checkpoint.
- User content is accepted only at the current governance head. Delayed
  old-head content may be lost after a group change. Sender sequences order
  text, replies and media within one sender/head, without claiming a global
  order across senders. Direct chats use their separate authenticated sender
  sequence.
- Disappearing deadlines are local. Delete, moderation and expiry cannot
  retract copies already seen or saved. Terminal UI must never preview old
  text, captions or media.
- The backend observes recipient routing, timing, sizes and fan-out. Shared
  group media may correlate authenticated downloaders. Group name, About,
  roster, content, caption, filename and media keys stay inside E2EE content.

## Automated evidence from this phase

The strict Gradle `test lintDebug :app:assembleDebug
:app:assembleDebugAndroidTest` run passed with 650 JVM tests and no
failures or skips. The focused group/chat-list Compose suite passed 33 tests
on the disposable Pixel 10 Pro emulator. Existing fixtures cover five-member admission evidence/frame sizes,
group governance and ordering, Block, Safe Exit and inactivity destruction.
They do **not** yet constitute a complete five-member end-to-end feature
fixture or every restart/offline case below.

Earlier two-emulator development checks on this source line received 10/10
offline direct messages and 6/6 offline group messages once, in sender order;
the six group rows retained order after an app restart. These used test
accounts and do not replace the final clean-build replay.

In this phase, the Pixel 10 Pro XL sent 20 labeled group texts while the
Pixel 10 Pro app was stopped. After restart, the Pro displayed all 20 exactly
once in sender creation order. Label 09 was sent after label 10, so the
observed sequence was 01–08, 10, 09, 11–20. This verifies offline delivery
for one two-member group, not the five-member or late-insertion matrix.
The existing Android Studio checkout also passed strict app JVM tests and
debug assembly after the P13.9 files were copied into it; unrelated staged
assets and local edits were left in place.

Group unread is derived locally from incoming, visible, nonterminal message
rows and encrypted local read markers keyed by logical message ID. Opening a
group marks its currently visible messages read and clears its pending local
notification ledger; no receipt is transmitted. Replays and reordering do not
add rows, and expired/deleted/moderated rows leave no phantom unread badge.
On the first startup of an upgraded endpoint, before network fetch, a one-time
protected-store baseline marks existing group rows read; later arrivals remain
unread. The baseline is idempotent across restart and leaves app data intact.
Messaging tests cover 20 distinct late-ordered texts, replay, restart, three
media kinds, deletion, moderation, expiry and the upgrade baseline; network fixtures cover blocked
content and post-removal traffic. On the two emulators, the recipient showed
26 previously unread group messages, opening the group cleared them, a fresh
incoming message showed **1**, that count survived an app restart, and opening
the group cleared it. This is a two-member development check, not final
five-member signoff.
After adding the upgrade baseline, an in-place emulator update preserved the
test account and displayed no historical unread badge; one new message then
displayed **1**. The full JVM/lint/debug/instrumentation-build run and all 33
focused Compose tests passed on this final baseline revision.

**STILL OPEN:** a complete five-member end-to-end replay on the final reviewed
build; the remaining restart/offline interruption points; live mixed-media
ordering at five members; complete Block, Safe Exit and inactivity behavior;
and physical-device replay. Later sections record partial five-member emulator
passes, but none substitutes for these remaining acceptance checks. These
remain required before P13 completion or a commit/push.

## R1 handoff retest (2026-10-07)

The R1 brief said final P13 acceptance had passed, but supplied no test record.
The user asked Codex to run the remaining checks with disposable emulators.
Five emulator instances were available: the existing Pixel 10 Pro XL and Pro
test identities, plus three newly created disposable Pixel profiles. The strict
Gradle `test lintDebug :app:assembleDebug :app:assembleDebugAndroidTest` gate
passed after the fix; its XML contains 237 app, 80 messaging, 330 test-support and 4
attachments JVM tests, with zero failures or skips.

Fresh onboarding on two new emulators failed before identity creation. The
P13.9 unread migration wrote `app/group-text/read-v1-initialized` into an empty
endpoint store, violating Signal identity creation's nonempty-store guard.
The migration now leaves a store without `local/device` untouched. A focused
test proves the store stays empty and identity creation succeeds; the existing
history-baseline test now uses a store with an identity. A third brand-new
emulator created its identity successfully on the fixed build. Only the two
new disposable installs that failed before identity creation were cleared;
their fixed builds then created identities successfully. Existing emulator
identities were updated in place, and one A-to-new-C encrypted contact request
was accepted with a C-to-A reply. No physical device or established identity
was cleared.

The five-member admission, governed feature matrix, restart/offline/media,
Block/Safe Exit, and clean reviewed-build physical replay remain untested.
This is still **NO-GO** for final P13 acceptance and the dependent R1 phase.

## Five-emulator continuation (2026-10-07)

Five test identities ran on Pixel 10 Pro XL, Pixel 10 Pro, and three disposable
Pixel emulators. Existing identities were updated in place; no physical device
or established app data was cleared. All ten pairwise direct-contact
relationships were accepted with an encrypted reply. A fresh A-owned group
reached A/B/C/D at four members. Two separate attempts to invite E then
stalled: A/B/C approved, but D did not. A read-only diagnostic found identical
group-state digests and revision on A and D, and matching E account, Signal
identity digest, and registered device-auth public key. D had no recorded
admission-v2 capability for A because A's first direct message arrived as a
contact request, before D accepted the relationship. The valid signed v2
proposal was discarded by that stale capability flag.

The receive path now accepts a proposal only from the current coordinator of
an active, nonforked group, with a matching signed parent state and an active
accepted direct contact. After verifying the candidate binding and persisting
its own signed approval, it records the proposal as authenticated v2 capability
evidence. It rechecks that the coordinator remains an active contact. The
all-member approval requirement and candidate binding checks are unchanged.
Previously queued proposals without the new recovery marker replay once to a
missing approver after upgrade; newly queued proposals do not get that replay.
The original three-member admission regression and a new five-member fixture
pass when one approver lacks the old direct capability flag. The fixture also
checks that a simulated pre-fix pending proposal requeues once, then stays
stable on the next sync.

After an in-place Android Studio debug build update on all five emulators, A
resent its already signed pending proposal. D approved; E received and
accepted the invitation. A/B/C/D/E each displayed an active five-member
group. A enabled group management, waited for member readiness, continued
setup, and all five displayed **Group management is ready**. A switched posting
from Everyone to Admins only: all five displayed the new policy, and E's
ordinary-member composer showed the restriction. After A restored Everyone,
E's composer was available again. This is a dirty
development-build emulator replay, not final clean-build signoff.

An earlier separate three-member emulator group also passed: Owner/Member
roles; Everyone → Admins only → Everyone; B promotion/demotion/re-promotion;
B restricting/unrestricting C; C receiving while restricted and sending again
after unrestriction; C removal with read-only local history and no later group
traffic; and direct A↔C/B↔C chats continuing after removal. These checks did
not cover five-member restart/offline interruption, media, Block, Safe Exit,
ownership transfer, leave, or end-group paths.

The strict full JVM run after the fix contained **652 tests, zero failures,
zero skips**, and strict debug plus instrumentation APK assembly passed. The
existing Android Studio checkout also passed strict focused five-member test
and debug assembly with unrelated local work preserved. No backend or schema
change was made. Final P13 status remains **NO-GO** until the remaining
five-member matrix and clean reviewed-build physical replay are recorded.

## Five-member offline ordering continuation (2026-10-08)

On the same disposable five-emulator group, E's app was stopped while A sent
20 labeled texts. All 20 eventually arrived once, but the first batch used
legacy group text without a sender sequence: two peers had not yet provided
authenticated ordering capability evidence. Delivery alone was therefore not
evidence of the required ordering behavior. A ready group now probes peers
whose ordering capability remains unknown, including after an in-place
upgrade. The new probe phase avoids treating an older sent marker as proof
that the current probe was received. A permanent network regression clears
ordering evidence on a Ready group, leaves the older marker, and verifies
that the new probe queues. The messaging and network JVM tests,
strict debug assembly, and instrumentation APK assembly passed after that
change; the existing Android Studio checkout built strictly and all five
emulators were updated in place without clearing data.

After the peers exchanged authenticated capability evidence, A sent a fresh
20-text batch with E stopped. All 20 sender rows carried sender sequences.
E received all 20 exactly once in sender creation order after restart. A
second E restart preserved that order and count. This closes the ordered
five-member text/offline batch for this **dirty development build**. It does
not establish mixed-media ordering, every interruption point, or clean
reviewed-build signoff. The temporary emulator diagnostic test was removed
from the source tree after the replay.

## Five-emulator governance continuation (2026-10-08)

The Pixel 10 Pro emulator no longer had its prior test account when this run
began. A new disposable B identity was created there; A/C/D/E retained their
existing encrypted stores. The four existing identities exchanged accepted
direct messages with B. A created a fresh group, invited B/C/D/E in sequence,
and all five independently reported the same active group ID and member count.
No established identity, group, direct chat, or physical device was cleared.

A completed the all-member authority baseline and governance activation. The
owner received five signed approvals and five signed installation
acknowledgments; all five devices reached **READY**. One D acknowledgment was
already authenticated and queued on A when first inspected, and processed on
the next explicit sync. It was not a missing signature or fork.

On this disposable five-member group, A changed posting Everyone → Admins
only → Everyone. Each of B/C/D/E observed both policy states; each change
finished with five application acknowledgments and no pending owner control.
B sent one encrypted group text, and A/C/D/E each stored it exactly once with
sender sequence 1. After all five app processes were stopped and restarted,
each retained READY, the final posting state, and that one message. A promoted
B to Admin and later demoted B. Both roles propagated to all five and each
change completed its acknowledgment set.

A restricted C. C's production send path returned `group_send_unavailable`,
while C still received a new encrypted text from B. A then unrestricted C;
all five reflected the unrestricted state, and C's send path succeeded. A
changed the disappearing timer Off → one hour → Off. All five showed both
changes; the owner finished with no pending controls. With E's app process
stopped, A changed the group name and About. E received the signed profile on
restart, the owner collected five acknowledgments, and a second signed change
restored the default name and empty About on all five. The test made no claim
about profile-photo bytes or their decoding.

These checks used temporary instrumentation against the production group
transport on **dirty Android Studio development builds**. The test APK was
installed in place without uninstalling the app or clearing data. The
temporary diagnostic source is removed after recording the results. This run
does not cover mixed-media ordering, five-member photo/document/voice flows,
photo companion delivery, all offline mutation interruption points, five-
member removal/ownership/leave/end, Block, Safe Exit/inactivity destruction,
accessibility, or the clean reviewed-build replay. P13 remains **NO-GO** and
R1 remains gated.

## Separate-group lifecycle continuation (2026-10-08)

In a separate disposable two-member group, A and B reached governance READY.
A requested ownership transfer to B. B's device showed one pending request,
explicitly signed acceptance, and both devices subsequently agreed on B as
Owner and A as Admin. After the transition completed, B ended the group.
Both local ledgers reported DISSOLVED with no pending change. A and B could
not send to the dissolved group; their direct accepted contact remained.

For a separate B-leaves case, a short-lived diagnostic process ended after
marking the invitation as accepting. That attempt stayed pending and is **not
counted as a product failure or a pass**. The temporary probe was corrected to
complete the encrypted acceptance submission before exiting. A fresh
disposable two-member group then became active and reached governance READY.
B requested leave; after the signed transition, A reported ACTIVE with one
member and B reported LEFT with one member. B's group send was rejected while
the A/B direct accepted contact remained. No account, app data, or existing
chat was cleared.

These separate two-member lifecycle checks do not replace the remaining
five-member removal and offline-interruption checks, mixed media, Block,
Safe Exit/inactivity, accessibility, or the clean reviewed-build replay.
The final P13 decision remains **NO-GO**; R1 has not begun.

## Five-member mixed-message regression (2026-10-08)

A permanent `NetworkTest` fixture now admits five members, enables the
authenticated group ordering/media capabilities, and sends one sender's text,
photo descriptor with caption, voice-note descriptor with caption, reply,
document descriptor with caption, and text at the same governance head. All
five local conversations contain the same six logical IDs in sender sequence
order 1–6 and retain the expected media kinds. The fifth member is held
offline during the fan-out, sees none of the six before reconnecting, then
receives each exactly once in order. The focused JVM test passed
with strict dependency verification. This verifies encrypted descriptor
fan-out and ordering in the fixture; it does **not** upload or download real
blobs, decode images, play audio, or verify UI rendering on five devices.
Those live media checks remain open.

The same five-member network fixture also holds E offline while B removes C
through signed governance. E still has the prior five-member state until
reconnection, then converges to four members with no pending B control.
After removal, B's new group text reaches retained member E but not removed
member C. A↔C and B↔C direct-contact relationships remain active. This is an
automated offline transition check, not the complete live-device interruption
matrix.

## Five-member final validation

### Group photo regression (2026-10-08)

An existing two-member group on the Pixel 10 Pro XL reproduced the user's
incoming **Photo unavailable · Retry** state. The group descriptor path
incorrectly used the direct-conversation lookup; the encrypted blob could
then download, but clearing its auto-download marker on the main thread
raised Room's main-thread transaction exception. After those fixes, an
app-lock initialization race could leave foreground media access revoked,
with the tile stuck at **Waiting for photo…**. The foreground media owner
now resumes when the lock grants content access.

The updated debug build was installed in place from the existing Android
Studio checkout, preserving the test identity and group. The same incoming
12 KiB group photo and an outgoing 900 KiB group photo both rendered in the
conversation after tapping **Load photo**. This is a two-member emulator
regression check. Five-member media fan-out, interruption/restart behavior,
other media kinds, physical devices, and clean reviewed-build replay remain
open; P13 remains **NO-GO**.

### Five-member media and layout continuation (2026-10-08)

Five Android 16/API 36 emulators were started and updated in place from the
existing Android Studio debug build (`1.0`, version code `1`; source HEAD
`d130014d18b9899fc7804a55d85533f8113e0b9b`, dirty development tree).
No app data was cleared and no physical device was installed or controlled.
The active group roster shown on B contained the owner `Simulator7`, B, C, D,
and E. The owner was not one of the four group-participant emulators used in
this continuation; the fifth running emulator held a separate test identity.

B sent a synthetic 2 KiB JPEG with an encrypted caption to that five-member
group. C, D, and E each received one row; each reached the rendered `Photo`
image state after `Load photo`. E rendered it again after an app restart. With
E's app process stopped (without clearing its data), B sent a synthetic text
document. E received one document row after restart, showed its private
filename, and reached the verified document viewer. A second E restart still
showed one document row. B also recorded an emulator voice note with the
`Subtle` local mask; C, D, and E each received a voice-note row, and E reached
the `Pause` playback state. The viewer was closed and playback paused after
verification. These checks cover the sender and three available recipients;
they do not prove the unavailable owner's rendering or every upload/fan-out
crash boundary.

On D, dark theme, 1.4× font, and landscape preserved the group composer,
Group Info, member list, Leave action, and Back navigation; original display
settings were restored. This is a focused layout check, not a full TalkBack or
all-screen accessibility signoff. The full strict Gradle `test lintDebug
:app:assembleDebug :app:assembleDebugAndroidTest` gate passed. It is still a
dirty development build, so P13 remains **NO-GO** for final release signoff.

### Blocked group sender continuation (2026-10-08)

On disposable B, C was blocked through Contact details. C then sent the
unique group text `P13BlockedCProbe179148`. D displayed the probe in the
same five-member group while B's group conversation did not display it.
B's blocked-contacts page then showed C's Ghost Cloak ID, and the contact
was unblocked after the check; the page returned to `No blocked contacts`.
Unblocking alone left C outside B's Contacts list, as designed for a fresh
request. C sent a new encrypted direct message, B accepted the request, and
C reappeared in B's Contacts with the prior direct history visible.
The blocked group probe remained absent from B's group view after reacceptance.
This verifies the visible blocked-sender behavior on B and delivery to one
unblocked peer. It does not establish ratchet/ACK internals, other blocked
control types, or full five-member Block acceptance. No account or app data
was cleared.

The `GroupInfoScreenTest` and `GroupInvitationScreenTest` instrumentation
classes were run on disposable D using the strict-built Android test package:
**26 tests passed**. These isolated Compose checks cover group invitation,
Group Info, chat, composer, media controls, moderation, and Back behavior;
they do not replace the pending live five-member and reviewed-build replay.
Four additional isolated classes (`GroupDisappearingScreenTest`,
`BlockedEnvelopeTest`, `ChatOrganizationScreenTest`, and
`InactivityAndroidTest`) passed **11 tests** on the same emulator. The
blocked-envelope fixture checks ACK/retry and ratchet persistence with
synthetic endpoints; the live check above establishes only UI-visible
suppression and peer delivery.

### Five-emulator live governance replay (2026-10-08)

The disposable Pixel 10 Pro owner `P13TestBNew` invited `Simulator8`,
`P13TestC`, `P13TestD`, and `P13TestE` sequentially into a new group. All five
emulators displayed `5 members` and `Group management is ready.` The owner
displayed Owner; the other four displayed Member. This is a five-participant
group, not four participants plus an uncounted owner.

The owner switched posting from Everyone to Admins only. All five Group Info
pages displayed Admins only, and C's chat composer said `Only admins can send
messages`. The owner's composer remained enabled. The owner restored Everyone,
which appeared on all five. E was promoted to Admin; its own Group Info showed
`You · Admin`, the owner and other members showed E as Admin, and E gained
admin actions. E restricted C; all visible rosters showed `Member · Restricted
from sending`, and C's composer said `You can't send messages in this group`.
While restricted, C received E's new `P13RestrictedReceipt` group text. E
allowed C to send again; C's composer returned and its
`P13UnrestrictedC179149` message reached `Sent to 4`, with D displaying it.
The owner demoted E; E's Group Info returned to `You · Member` and its admin
controls disappeared. The owner set disappearing messages to 1 hour; all five
Group Info pages showed 1 hour. The confirmation stated that existing messages
keep their prior timer. The owner restored Off, and all five pages showed Off;
the confirmation stated that existing disappearing messages keep their current
expiry. These live results cover the basic five-member posting, promotion,
restriction, unrestriction, demotion, and timer propagation path on the current
dirty development build. They do not close the offline/restart, removal,
ownership, media, or clean reviewed-build requirements below.

An offline profile-edit attempt was **not scored**: E's app process was stopped,
but ADB text injection did not alter the focused group name or About field on
the owner emulator. The editor was closed without saving and E was reopened;
no profile update was issued. The existing isolated Compose test covers text
replacement in those fields, but live offline profile propagation still needs
a separate replay.

### Five-member photo download retest (2026-10-08)

The owner sent the repository's 96×48 JPEG fixture as one encrypted group
photo. The outgoing row reached `Sent to 4`; all four recipients showed one
incoming 2 KiB photo row. After tapping `Load photo`, Simulator8 and C emitted
`ATTACH_DOWNLOAD_SUCCESS`. D instead showed `Photo unavailable · Retry`, and
retrying and restarting its app without data loss reproduced the failure. E
remained at `Waiting for photo…` even after an app-process restart. D/E still
show the sender as an accepted contact; all normal message syncs remained
connected. No app data, identity, or group was cleared. This was a live
intermittent failure, not a permanent failure to display every group photo.

The existing Android Studio debug build was reinstalled in place on D and E,
preserving both identities and app data. A temporary debug-only diagnostic
showed `PHOTO_FETCH_STARTED`, `ATTACH_DOWNLOAD_SUCCESS`, and `PHOTO_READY` on
both after another tap. All four recipients then opened the first photo in the
full-screen viewer. The owner sent a **second distinct** 2 KiB photo to the
same five-member group; its outgoing row reached `Sent to 4`. Each recipient
received the second photo, tapped `Load photo`, and displayed **two** rendered
photo images with no unavailable or waiting state. D/E again recorded
`PHOTO_READY`. Temporary diagnostic source was removed from both the worktree
and Android Studio checkout after the retest.

D and E were then force-stopped and reopened without clearing data. Their
five-member group and both photo rows remained present. Tapping `Load photo`
again on each device emitted a new `ATTACH_DOWNLOAD_SUCCESS` and rendered the
photo with no unavailable state.

This verifies the current photo send, download, render, and viewer path on all
five emulators. The earlier D/E failures are not yet explained, so a reliable
offline/restart and larger-media replay remains necessary before final P13
signoff. No claim is made that an underlying intermittent cause was fixed.

For one signed governance interruption replay, E's app was force-stopped while
the owner promoted C from Member to Admin. The owner showed C as Admin and
group management ready. On reopening, E synchronized the pending update and
showed the same five-member roster, C as Admin, and group management ready.
This confirms one offline member caught up without a stuck state; the broader
offline/restart mutation matrix remains open.
The owner then removed C's Admin role; E's roster changed back to Member after
normal sync, with no duplicate roster entry or stuck pending state observed.

Use A = Owner physical, B = Admin emulator/physical, C = Member physical,
D = Member emulator, E = Member emulator. Keep A/C app data and identities;
install updates in place from Android Studio. Record the installed Git SHA,
dirty status, app version, OS version, and each test result. Final signoff
requires a clean reviewed build on all five participants.

1. Establish accepted, pinned direct contacts and current authenticated
   capability evidence between the owner and each invitee. Other members need
   not be direct contacts. Create a new group on A; send four invitations
   together, accept them out of order, and verify an unavailable invitee does
   not block another accepted invitee. Each canonical join still requires all
   current members' signed approval. Confirm the sixth invitation is
   unavailable and noncontact members' IDs remain masked in UI.
2. Enable group management. Confirm all five reach Ready and display the same
   roles, name, photo, About, posting mode and timer. Restart one member during
   setup; confirm one canonical activation and no duplicate invite.
3. Change name, About and photo. With E offline, verify no unvalidated photo
   appears as current; reconnect E and confirm the committed photo. Remove
   the photo and verify old cached presentation is gone.
4. Set the timer to 1 hour and back to Off. Check local timer wording and
   that older messages keep their original deadline. Switch Everyone to
   Admins only and back; restrict and allow C. Verify C's composer accurately
   explains both denial modes and unaffected members can still receive.
5. With E offline, send 20 sequential texts from A. Reconnect E and verify
   exactly 20 rows in A's creation order, without duplicates. Insert a late
   message into a visible 1,3,4 sequence and check it renders 1,2,3,4 without
   a crash or disruptive jump. Repeat in a direct chat.
6. Send text, photo, voice note, reply, document, text in that exact order.
   Verify sender order, captions, private filenames, voice playback and
   download retry. Restart after upload and after partial recipient fan-out;
   confirm one logical media row and durable retry to remaining members.
7. Change/remove a reaction, reply to edited and terminal targets, edit own
   text, sender-delete own text, and admin-moderate another member's text and
   media where supported. Verify admin moderation precedence, no resurrected
   preview/thumbnail/caption or duplicate reaction chip after restart.
8. Promote/demote B, then promote again. Remove E after a pending/offline
   update. E keeps permitted local history but receives no future group
   content or keys. Direct A↔E remains independent; test B↔E independently
   only if those two have separately established a direct relationship.
9. Before transfer, verify B has the required authenticated relationship with
   every current member. Transfer ownership A→B with explicit acceptance.
   Test B leaving in a
   separate group. End another group as its Owner; all participants see a
   read-only lifecycle without a false remote-erasure claim.
10. Repeat one profile, timer, management and moderation change with an
    offline member or app restart during the pending update. Confirm bounded
    resync, one canonical action, and clear pending/error UI. Exercise Block
    and identity change separately; neither may restore blocked user content
    or auto-trust a changed identity.
11. Re-run direct requests, text, ordered offline batch, disappearing,
    reply/reaction/edit/delete, photo/document/voice with voice masking,
    View Once, profile and Block. Test Safe Exit/inactivity destruction against
    group descriptors, cached photos, plaintext, ordering counters and
    governance records on a **disposable** identity only.
12. Check light/dark themes, large font, TalkBack, Back navigation and
    rotation on Group Info, member actions, ownership, timer, profile edit,
    invite, composer and media dialogs. No critical action may be clipped or
    silently triggered by Back.

## Release decision

### Phone-owned five-member development replay (2026-10-08)

The user's physical-phone owner group `TestGroupP13NOGO` appeared on all four
connected Android 16 emulators (`5554`, `5556`, `5558`, `5560`) as `5 members`,
Active, with `Group management is ready.` The group About, Everyone posting
mode, and Off disappearing timer were visible. Three emulators displayed the
existing group photo immediately; the fourth was set to manual download and
displayed it after `Load photo`. The owner reports all devices show the same
source and source status, but the exact phone SHA was not recorded. The
emulators run versionName 1.0/versionCode 1 from the current modified
development build; source HEAD is `d130014d18b9899fc7804a55d85533f8113e0b9b`.

With emulator `5560` force-stopped, emulator `5554` sent uniquely labeled
group texts `P13LiveR1_01` through `_20` to this five-member group. The sender
showed `Sent to 4` for the completed sends. After reopening `5560`, a scan
through the conversation found all 20 labels in numeric order with no visible
duplicate. After a second app-process restart without clearing data, the
conversation still showed the batch in order and its chat-list preview showed
`P13LiveR1_20`. The first fast UI attempt at labels 04 and 05 did not submit;
they were sent individually before the final scan. This establishes one live
offline recipient/restart ordering pass, not all interruption points or a
database-level proof of uniqueness.

The physical-phone owner promoted the contact named Simulator2. On emulator
`5554`, Group Info then showed `You · Admin` and displayed admin controls.
Simulator2 changed posting from Everyone to Admins only. A regular member on
`5556` displayed `Only admins can send messages`. Simulator2 restored Everyone,
which its Group Info confirmed; the regular member's `Message group` composer
and Send control returned. This is a live cross-device owner promotion and
admin-authorized policy change on the five-member group. The owner phone and
all other members were not directly inspected after the final restoration.

For an offline governance/profile replay, emulator `5560` was force-stopped
while promoted Admin `5554` changed the group About to
`P13OfflineProfileProbe`. After reopening, `5560` showed that value, all five
members, and group management ready. An immediate attempt to restore the
original About did not become visible. Once the phone owner reopened the app
and synced, the admin retried Save; both `5554` and `5560` then showed the exact
original `Testing for P13 to be a GO so we can go to R1` and management ready.
No group or account data was cleared. This demonstrates one offline member
catch-up and a subsequent owner-coordinated update, but the first restoration
attempt's lack of visible completion was not diagnosed and is not counted as
a clean no-stall pass.

For live five-member moderation, regular member `5556` sent the unique text
`P13ModerationProbe`; its outgoing row reached `Sent to 4`. Admin `5554`
selected Remove message and confirmed the UI warning that previously seen
copies cannot be recalled. The owner's phone showed the removed notice after
Sync. The admin and sender emulators then displayed `Message removed by an
admin` with the original text absent. After force-stopping and reopening the
sender app, the chat-list preview and message row still showed only that
terminal notice. This covers one text moderation/restart path; media
moderation, reaction/reply interactions and offline moderation remain open.

In the same live group, regular member `5556` sent `P13EditProbe1`, then edited
it to `P13EditProbe2`. Admin `5554` displayed only the revised text and an
`Edited` marker. The admin added a thumbs-up reaction, which appeared as one
chip on the sender, then toggled it off; the chip disappeared after sync. The
sender used Delete for everyone on that edited message. Both devices displayed
`This message was deleted` with neither text visible. After restarting the
admin app, the group-list preview still showed only the deletion notice. This
is one live text edit/reaction/delete path; reply-to-terminal, media controls,
offline replay and all-participant restart checks are still unverified.

The phone owner then sent a 57 KiB PDF document and a three-second, 13 KiB
voice note to the same group. All four emulators displayed the document row
followed by the voice row; the PDF filename was visible only inside the
decrypted recipient UI. On `5554`, tapping Play voice note emitted
`ATTACH_DOWNLOAD_SUCCESS` and Android audio playback delivered frames. Tapping
the document emitted `ATTACH_DOWNLOAD_SUCCESS` and opened Ghost Cloak's
document detail. The app warned that an external viewer may retain a copy;
the external-viewer handoff was cancelled, so the PDF's contents were not
inspected or exported by this test. The user also reports photo, voice and
document work on the owner phone. This is a live delivery/playback/download
pass, but it does not cover the exact required text/photo/voice/reply/document/
text sequence, partial upload/fan-out restart, retry after failure, or every
recipient opening each media item.
After an app-process restart without data clearing, `5560` still displayed
both media rows. The PDF remained inside Ghost Cloak; its external-viewer
handoff was not part of this check.

The local Android Studio strict dependency-verification run of `test lintDebug
:app:assembleDebug :app:assembleDebugAndroidTest` completed successfully on
the modified development checkout. It is not the required clean reviewed-build
gate. The full five-member mixed-media/interruption matrix, some profile and
moderation cases, accessibility checks, and clean-build physical replay remain
open. P13 therefore remains **NO-GO** for final release signoff.

### Disposable-emulator admission and lifecycle replay (2026-10-08)

On Simulator6 (`5562`) and Simulator4 (`5558`), a newly created two-member
group stalled after the invitee accepted: the owner remained at one member
despite both apps syncing. A temporary redacted debug trace showed the signed
formal invitation repeatedly waiting for `group_intro_pending`; the invitee
had no owner-introduction setup. Replaying that same signed setup, then the
invitation, completed the authenticated HELLO/ACK exchange and both devices
reached two members. Temporary diagnostic logging was removed because the
production no-payload-logging architecture gate rejects it.

The transport now replays the identical signed owner-introduction setup for
an outstanding invitation at a bounded interval and includes it in manual
Resend invitation. It does not create a new invite ID, synthesize signatures,
or relax proof verification. A JVM regression deliberately discards the first
setup and passes only when the invitee joins without another acceptance.
The focused group test and architecture logging test pass. The existing
Android Studio checkout received an additions-only patch, preserving staged
launcher assets and other local edits; its strict debug build and focused
regression also pass. The full `:test-support:test` JVM suite and
`:app:assembleDebugAndroidTest` compilation subsequently passed from that
checkout with strict dependency verification and offline resolution
(`BUILD SUCCESSFUL in 2m 18s`; 306 JVM tests, zero failures/skips).
This compiles but does not execute the
instrumentation tests on devices.

On a second fresh group, the invitee accepted and both emulators displayed
two members and `Your group is ready` within 18 seconds without a retry tap.
Both then reached `Group management is ready`. The member explicitly left;
the owner showed one member and the leaver's conversation said history was
read-only. In the first disposable group, the owner requested transfer,
Simulator4 explicitly accepted, and both devices showed Simulator4 as Owner
and Simulator6 as Admin. The new owner ended the group; both conversations
displayed `This group is dissolved. History is read-only.` These tests did not
clear either account or touch the phone-owned five-member group.

This closes the reproduced missing-setup delay and the two-member lifecycle
paths above. It does not close the remaining five-member interruption matrix,
clean reviewed-build replay, or physical-device release signoff; P13 remains
**NO-GO** for final release assurance.

### Five-member offline photo replay (2026-10-09)

In the existing phone-owned five-member test group, emulator `5554` sent a
known repository image with the unique encrypted caption
`P13OfflinePhotoProbe` while recipient emulator `5558` was force-stopped.
After the recipient restarted, it showed exactly one photo row with that
caption. Manual **Load photo** rendered a verified image, without an
unavailable/retry tile. After another app-process restart without clearing
data, the conversation still showed exactly one row and rendered the photo.
This closes this one offline-recipient photo/restart path. It does not explain
the earlier intermittent D/E load failures or cover a live sender interruption
during a real blob upload. Passed invitation and media checks should not be repeated
without a new failure or code change.

### Five-member partial media fan-out regression (2026-10-09)

The permanent five-member network fixture now stops the sender's media
transport after the first recipient's encrypted submission is accepted. The
other three recipient ciphertexts remain queued. It removes the sender's
original local descriptor, reconstructs the production outbox/transport from
the persistent store, and resumes while the fifth member is offline. After
that member reconnects, all four recipients contain exactly one logical
photo row, its caption and a usable local descriptor; the sender records four
completed recipient submissions. The focused strict-verification JVM test
passed. This proves the durable encrypted descriptor/key fan-out boundary;
it does not perform a real blob upload or decode pixels in the fixture.

### Fresh five-emulator admission and pending-control saturation (2026-10-09)

A disposable owner on emulator `5562` sent four simultaneous encrypted group
previews to accepted contacts on `5554`, `5556`, `5558`, and `5560`. All four
recipients accepted. The initial fresh group stalled at one member because
`5554` had accumulated 128 byte-identical, authenticated introduction controls
from an older incomplete test group. The pending-control capacity check threw
before the new signed setup could be queued, and each network fetch ended in
local `ERROR` despite HTTP 200. No account or app data was cleared.

The pending queue now coalesces exact encoded duplicates, retaining one copy
of each distinct authenticated control. It does not synthesize approvals,
change the 128-distinct-control cap, or choose a fork winner. A permanent JVM
regression fills all 128 slots with identical older controls, queues a new
authenticated setup, and verifies that both distinct controls remain. A
second bounded-window test verifies that 17 distinct pending controls all
become visible across two reads, so an older unresolved prerequisite cannot
starve a later setup indefinitely. Both focused strict tests pass. After
installing the deduplication fix in place on `5554`, sync
recovered, and the owner advanced from one to five members in the same fresh
group. The group displayed all four invited members. Signed admission still
occurred sequentially; two explicit retries of the same pending approval
request were used to speed this run, so this is not evidence of a latency
target being met without user action. No unrelated Android Studio edits were
discarded. The owner completed the all-member activation ceremony, and all
five emulator Group Info screens showed **5 members** and **Group management
is ready**. Temporary diagnostic source and logging were removed.

After the strict Android Studio JVM/debug build passed, its APK was installed
in place on all five emulators without clearing data. The existing
`P13V2Final` group still showed five members on every device. A second
disposable group, `P13NoRetry`, sent four invitations together at 09:08:34
local time. All four recipients accepted by 09:09:19. Without any retry tap,
the owner showed two, three, four, then five members at 09:09:48, 09:10:10,
09:10:32, and 09:10:54. Every recipient then displayed the same five-member
group. The last join completed about 95 seconds after all acceptances; this
proves automatic completion on the current development build, but joining
remains sequential and this single run does not establish a general latency
bound. The installed APK SHA-256 was
`797ED013891E15E5ADB5E01272EBDF2205D80861768B244E6C215CF768D61696`.
It embeds source `d130014d18b9899fc7804a55d85533f8113e0b9b` with
`GIT_DIRTY=true`.

The Android Studio checkout passed strict dependency verification for
`:test-support:test` (310 tests), `:app:testDebugUnitTest` (125 tests),
`:app:assembleDebug`, `:app:assembleDebugAndroidTest`, and `:app:lintDebug`
with zero JVM failures or skips. APK assembly alone does not execute device
tests.

On disposable emulator `5562`, the compiled Android test APK executed
`GroupInvitationScreenTest`, `GroupInfoScreenTest`, and `MessengerDesignTest`:
37 Compose instrumentation tests passed. These cover invitation and group
management controls, composer visibility with the keyboard, chat opening at
last-seen rows, and unread presentation. They do not replace manual TalkBack,
large-font, rotation, or full five-device interaction checks.

The first capability-advertisement repair exposed two compatibility hazards:
extending a short direct text to a second 256-byte bucket could fail an older
client's minimum-frame check, while dropping its inline display name could
leave a newly accepted contact showing only an ID. The final encoder keeps
the original 256-byte frame and inline display name, using a compact
authenticated group-plus-admission marker when the separate markers do not
fit. Focused tests verify a short named reply remains exactly 256 bytes,
advertises admission v2, and preserves the name; existing tests check that
quoted marker text cannot impersonate a capability. A direct ordering test
was updated to check contiguous sequences rather than assuming the first
sequence is 1 after the earlier capability exchange.

### User-reported physical check (2026-10-08)

The user reports testing on their phone and the other user's phone. They
confirmed that group photo/document/voice media, member controls,
offline/restart behavior, Block, and Safe Exit all passed. Both phones showed
the same source SHA and `Modified development build` in Settings → Build
information. The exact SHA and a test-by-test log were not supplied. These
are user-observed physical **development-build passes**, not a clean-reviewed-
build acceptance replay. The release decision remains NO-GO until the clean
reviewed build and any remaining acceptance items are recorded.

Keep P13 and R-series release hardening at **NO-GO** if any HIGH/CRITICAL
correctness or security issue appears, or until the five-member fixture,
restart/offline matrix and clean-build physical/emulated replay have passing
records. Never repair a fork by choosing a winner, accept stale-head content,
reconstruct an incomplete journal, clear A/C data, or add server group state
as a polish workaround.

## Final P13 signoff (2026-10-09)

The P13 implementation commit is `7a337ec5d575bd11ac484c36a9eba6aae9bf2be2`,
pushed to `origin/main`. A separate, clean checkout at that exact commit passed
strict dependency verification with `:app:assembleSecurityReviewed` (debug and
release assembly and JVM gates), `:app:assembleDebugAndroidTest`, and
`:app:lintDebug`. The clean run reported 311 test-support JVM tests, 125 app
debug JVM tests, and 118 app release JVM tests, with zero failures or skips.
The focused `GroupInvitationScreenTest`, `GroupInfoScreenTest`, and
`MessengerDesignTest` Android instrumentation run passed 37 tests on a
disposable emulator. The generated build provenance was
`GIT_SHA=7a337ec5d575bd11ac484c36a9eba6aae9bf2be2` and
`GIT_DIRTY=false`. The reviewed debug APK SHA-256 was
`C99CCD1FF99A6B9239DBF8BE2240D7BE1ACDA81B52EE9036AF6E096DB55E65DA`.

That clean APK was installed in place on one connected Samsung SM-S938U1 phone
and five disposable emulators, without uninstalling or clearing app data.
Settings → Build information on the phone showed source `7a337ec5d575` and
`Clean source build`. The phone-owned group showed five active members and
`Group management is ready`; its four emulator members retained the same
group. A marked group message sent from emulator `5554` reached the physical
phone, and a marked reply sent from the phone reached emulator `5554`; the
sender reported `Sent to 4` for each. The clean-build replay therefore passed
five-member state persistence and bidirectional group delivery on the agreed
one-phone-plus-four-emulator setup.

The user separately reported that photo/document/voice media, member controls,
offline/restart behavior, Block, and Safe Exit passed on two physical phones
running the same modified development source. The exact SHA for those earlier
phone checks and a test-by-test device log were not provided, so they remain
user-reported development-build evidence. The extensive five-emulator and
focused interruption checks above provide additional recorded evidence. The
second physical phone was not available for a clean-build replay. The user
explicitly accepted the one-phone-plus-four-emulator clean replay, together
with the earlier physical checks, as sufficient for the **P13 GO** gate.

This decision closes P13, not the broader security/reliability or production
release gates. R1 must evaluate its own blockers independently; any newly
found HIGH/CRITICAL issue reopens the relevant release decision. The Android
Studio checkout remains the normal development/test workspace and retains its
unrelated local edits, so its debug builds continue to be labeled modified
development builds.
