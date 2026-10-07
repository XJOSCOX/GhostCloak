# P13.6 controlled A/B/C media validation

Do this only after strict JVM, instrumentation, lint, and debug-build gates pass. Update A (Owner), B (Admin), and C (Member) in place through Android Studio Run. Do not uninstall, clear data, or replace identities. Record each device's Settings → Build information SHA and dirty status.

1. Confirm group governance is ready and all three advertise group media support. Verify an older client disables only media, while text still works.
2. A sends a prepared photo with caption. B and C see the same logical message once, attributed to A, and can display the verified local photo.
3. B sends a document. A and C see a sanitized filename and size and can use the protected viewer.
4. C sends an original voice note, then a masked voice note. A and B play both. Check a failed mask never sends original audio.
5. Take C offline. A sends a photo, document, and voice note; B receives them. Reconnect C and verify one descriptor per message and successful downloads. Restart A during a separate partial fanout and check no duplicate.
6. Restrict C; media send must fail while receive still works. Unrestrict C and confirm send resumes.
7. Remove C while a descriptor remains unsent. Verify C receives no new descriptor or capability after removal; old received media may remain. Verify direct A↔C and B↔C media are unaffected.
8. Block a member locally and send group media from that member. The blocked endpoint must show no media, caption, notification, thumbnail, or blob fetch; normal authenticated maintenance continues.
9. Admin-moderate a photo, then sender-delete another media item. Confirm tombstones, no late resurrection, no active cache/viewer reference, and no misleading erasure promise.
10. Verify reply and reaction to visible media, no media edit or group View Once option, and unchanged one-to-one media/View Once.

The existing blob GET sends the capability to the backend. A shared blob can let the backend correlate which authenticated accounts download it. Do not interpret this test as proving recipient anonymity from the storage service.
