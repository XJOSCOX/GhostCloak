# P13.4C controlled A/B/C validation plan

Do not run this plan as part of the implementation task. Keep each existing
installation, account, direct chat, and app data. Use Android Studio Run on the
existing A/B/C development devices, then record Settings → Build information
for every device (source SHA and modified/clean status). All three need the
updated moderation capability before the action appears. Use a fresh,
governance-READY three-member group; older incomplete groups are not a valid
moderation test.

1. Confirm A is Owner, B is Admin, and C is Member. Open each app and Sync.
2. C sends `moderate me`. Long-press it on B, choose **Remove message**, read
   the confirmation, and confirm. A/B/C should each show one “Message removed
   by an admin” notice. The original body should no longer appear in the
   active chat. Check that C's outgoing delivery summary does not imply recall.
3. A sends a second unique line. B removes it. Verify the same result on all
   three devices. A repeated attempt must not add another journal entry.
4. A demotes B. B must lose the action; an already open action sheet must not
   authorize a new removal. Promote B again for the remaining cases.
5. Put C temporarily offline. A/B exchange a message, A moderates it, then C
   reconnects and Syncs. C must catch up through the signed governance chain
   and show one tombstone without showing plaintext after moderation. If
   practical, arrange delivery of moderation before the target ciphertext.
6. Restart one updated client during an outstanding moderation and Sync all
   devices. Check for exactly one journal advancement and one tombstone. A
   second mutation should remain blocked until the first action is complete.
7. Verify group text can still be sent at the resulting current head. Verify
   a direct one-to-one delete-for-everyone action independently; group
   moderation must not create a direct delete control.

Record each device's source SHA, dirty flag, group role, starting and ending
governance sequence, whether its notification changed, and any discrepancy.
The notice is logical moderation: screenshots, copied text, notification
history, and already viewed content cannot be recalled. Do not uninstall or
clear data. Development-build observations are not final release assurance;
repeat signoff on a clean reviewed build later.
