# Owner-introduced group membership (design gate)

Status: **implemented and replayed on five disposable emulators in the working
tree; Android Studio checkout reconciliation remains open**.
The October 8 five-emulator reproduction showed accepted invitations waiting at
two members when each invitee had an accepted direct contact only with the
owner. A five-account in-process regression now reaches the same five-member
canonical roster with no invitee-to-invitee direct contacts. A five-emulator
replay subsequently reached the same roster and management READY on all five.
This is a development build, not release signoff.

In the October 8 replay, Pixel 10 Pro XL (Simulator2) exchanged fresh direct
messages with Simulator3, Simulator4, Simulator5, and Simulator6, then selected
all four in one group creation. All four emulators received the invitation.
Simulator6 accepted before Simulator3 and reached the signed two-member group,
so an unaccepted earlier preview did not block the first join. Simulator3,
Simulator4, and Simulator5 accepted afterward but remained at “Acceptance sent.
Waiting for signed membership confirmation”; the owner stayed at two members.
That deployed five-member owner-only-contact acceptance gate was **NO-GO**.
After an in-place Android Studio debug update, a separate group created as
“P13NameTest” displayed that signed name in the invitation on Simulator3,
converged at two members on both devices, and retained the name after both app
processes restarted. This verifies the name feature, not the owner-only
five-member admission design.

## Requested trust model

The owner must have an accepted, authenticated one-to-one relationship with
every invitee. Other members need not be direct contacts. Each invited person
must explicitly accept. Current members still sign the exact canonical-parent
admission; an offline member may delay an admission, but an invitee who never
accepts must not prevent a different accepted invitee from being considered.
No owner override, guessed binding, quorum, or unsigned snapshot is allowed.

Noncontact members' Ghost Cloak IDs must not appear in group UI, Contacts,
direct chats, notifications, logs, or profile sharing. The user approved
**UI-only masking**: encrypted group setup may carry a member's ID internally
to establish pairwise Signal sessions, so each group member's device may learn
the ID. This must not create an accepted direct contact. Server-side prekey
allocation may expose pairwise lookup edges and requires a privacy review.

## Versioned protocol change

1. Bind an owner-signed candidate account/device/device-auth/Signal identity
   introduction to the exact parent, invite ID, and all-member admission
   proposal. Verify the registered binding independently; never substitute
   the owner's assertion for a changed direct Signal pin. An existing direct
   contact mismatch fails closed.
2. Authenticate and establish **group-scoped** pairwise Signal sessions among
   noncontact members without creating or restoring accepted Contacts. New
   invitees must verify the current owner directly, existing roster bindings,
   and every current member's signed approval before installing the state.
3. Version and gate this mode. Old clients continue direct messaging, but must
   not silently accept a partially supported owner-introduced group. Existing
   groups retain their original trust mode until a separately verified upgrade.
4. Make the group receive and fanout paths accept authenticated group-scoped
   text, media, replies, controls, and governance traffic only for exact current
   roster members, epochs, and authority pins. Preserve the blocked-envelope
   model, ratchet/replay commits, Safe Exit, and noncontact profile isolation.
   Do not display a joined member before this delivery path is ready.
5. Define restart/offline recovery, duplicate controls, pending proposal retry,
   removal, and owner transfer. A new owner must be able to authenticate every
   member before taking over owner-only introduction.

The working-tree implementation uses a versioned owner introduction and
group-scoped Signal authorization. The five-account regression covers four
simultaneous previews, out-of-order acceptance with one unavailable invitee,
management activation, baseline and governed text, a photo descriptor and
local download eligibility for noncontact recipients,
reactions, edits, and governed removal. It verifies that invitees do not
become accepted direct contacts with one another. The regular messaging and
app unit suites and strict debug assembly have passed. In the five-emulator
replay, Pixel 10 Pro XL invited Simulators3–6 together, Simulator6 accepted
before Simulator3, and all five eventually displayed five members. A message
from Simulator4 resumed after a queued noncontact fanout fix, reached all four
recipients, and was visible on Simulators3, 5, and 6. Group management then
became READY on all five. The replay also found an upgrade crash from an old
pending-admission record; a canonical legacy on-disk decoder now preserves
that record, and Pixel XL reopened with existing data. The same replay exposed
an old direct-contact check in group attachment access. After correcting it,
a 26 KiB group photo sent by Simulator4 reached all four recipients, and
Simulators3 and 5 each tapped Load photo and rendered it. All five emulators
were updated in place, without clearing data. The relevant JVM suites and
strict debug build passed after this fix. This is not a claim
that document/voice downloads, complete restart/offline recovery, owner transfer, Block,
Safe Exit, or release assurance have passed for the new mode.

## Acceptance gate

Use five isolated accounts with only owner-to-invitee accepted contacts. Send
four previews together; accept #3 before #2; finish #3 without waiting for
#2. Accept the others in arbitrary order. Verify the exact same five-member
canonical roster on all devices, then send text/photo/document/voice between
two noncontacts, including offline/restart delivery. Verify no direct contact,
profile, Ghost Cloak ID UI, or direct notification appears between them.
Replay and key-change tests must fail closed; Block, removal, owner transfer,
and Safe Exit must preserve their prior security behavior. Repeat on Android
emulators before updating the physical-phone build.
