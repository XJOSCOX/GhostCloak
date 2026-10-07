# P13.4B controlled A/B/C group-management validation

This is a manual test plan, not an instruction to reset any device. Update A, B,
and C in place with Android Studio Run. Do not uninstall Ghost Cloak or clear app
data. Record Settings → Build information (source SHA and modified status) on all
three clients before testing. Development builds are suitable for this controlled
test; final release signoff still requires a clean reviewed build.

Use A as Owner, B as Member, and C as Member in a fresh P13.3 group. Confirm
all three have accepted direct contact relationships and current group support.

1. A opens Group Info and enables group management. With one client offline,
   confirm setup says it is waiting. Reconnect all clients and confirm all three
   reach ready before any management control becomes available.
2. Confirm A is Owner and B/C are Members. A selects Admins only. B/C cannot
   send; A can. A returns to Everyone and B/C can send again.
3. A promotes B. B sees Admin and can change posting permissions. B restricts
   C; C cannot send but still receives. B allows C to send again; C succeeds.
   B cannot change A's role, remove A, or transfer ownership.
4. A demotes B. B loses admin controls. A promotes B again, then removes C.
   C becomes read-only; A/B can still send. Already stored C messages remain.
   Direct A↔C and B↔C conversations are unaffected. Check that C receives no
   subsequent group text or governance entries.
   In a separate group with a fourth mutually accepted contact D, B as a
   non-coordinator Admin invites D. Confirm D receives one invitation, joins
   only after all existing members approve, and a Member cannot invite D.
5. In a separate group, B leaves as Member. In another separate group, B leaves
   as Admin when B is not coordinator. Both are read-only for B afterward. Owner
   leave and coordinator leave are unavailable with an explanation.
6. In a separate group, A requests ownership transfer to B. B first declines;
   no role changes. A requests again, B accepts, B becomes the only Owner, and
   A becomes Admin. B ends the group; all three see read-only history and cannot
   send. No prior messages are erased.
7. Restart clients during pending restriction, promotion, and removal in
   separate test groups. Confirm each accepted action is applied once and no
   duplicate group messages appear. Repeat with one offline member and ensure
   the next management action waits for the required acknowledgments.
8. Check that a forked or legacy-incomplete development group remains
   read-only and offers new-group recovery, with no state reconstruction.

For every observation, record the device, source SHA, dirty/clean status,
group's local state, action, expected result, actual result, and relevant
privacy-safe diagnostic category. Do not paste member IDs or cryptographic
material into the test report. Admin deletion of another member's message is
outside P13.4B and remains deferred.
