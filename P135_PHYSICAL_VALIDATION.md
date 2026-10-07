# P13.5 controlled physical A/B/C validation plan

Use existing in-place Android Studio builds and preserve all three identities and
app data. Record each device's Settings → Build information SHA and modified
status. A is Owner, B is Admin, C is Member. Confirm every device advertises
group controls before starting. Do not claim globally confirmed delivery from
a local “sent” indication.

1. C sends normal group text; A replies. Check the reference-only preview and
   sender label on A, B, and C.
2. B reacts 👍; C reacts, changes emoji, then clears their own reaction. Check
   counts and no duplicate chips after sync and restart.
3. A edits their own message; verify “Edited,” replacement text, and dynamic
   reply previews on all devices. Confirm the old text is not visible in search
   or the active message row.
4. A deletes their own message. All three show “This message was deleted”;
   reactions disappear and replies show “Original message deleted.”
5. C sends another message; B removes it as Admin. All three show the distinct
   “Message removed by an admin” notice. If a sender delete races with this
   removal, the admin notice must prevail everywhere.
6. A sends a message and briefly disconnects after editing it. Reconnect and
   sync; the edit applies once at the same head or is discarded after the head
   advances. No older text or duplicate control appears.
7. While C is offline, B reacts, edits their own delivered text, and deletes
   their own delivered text. Reconnect C and verify final state, no duplicates,
   and no plaintext resurrection.
8. Remove B from the group. B may read retained history but cannot issue new
   group reactions, edits, or deletes. Confirm direct A↔B and B↔C chats still
   work independently.
9. Restart one device with a queued control and sync. Confirm one logical
   reaction/edit/delete and correct tombstone precedence. Check mixed-version
   fallback in a separate development group: base text continues, controls
   remain unavailable until every current member updates.

No uninstall, Clear Data, identity recreation, or server changes are part of
this plan. The tests are manual and have not been executed by Codex.
