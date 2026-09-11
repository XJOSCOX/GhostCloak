# Two-phone staging test — Phase 1E.1

Use two test phones and synthetic messages only. Deploy the matching Phase 1E.1 backend and additive V002 migration first; see [rollout notes](../SECURITY_REVIEW_PHASE_1E1.md). This test does not deploy or change live infrastructure. No same-LAN requirement, background service or push setup is needed.

## Run from Android Studio

Pull the latest source, open **Android/** in Android Studio and let Gradle sync. Select the **app** run configuration and **debug** variant, connect/select Phone A and click **Run app**. Repeat with Phone B selected. Debug automatically uses `https://api.ghostcloak.org`; no property or manual APK installation is needed. See [Android development](DEVELOPMENT.md) for device setup, command-line alternatives and separate release configuration.

Keep both phones in their real identity, outside the local demo. First launch must say **Encrypted messaging via Ghost Cloak staging** and **Create identity**. An explicitly empty debug origin is local-only; release is network-disabled unless given its separate release origin.

For a clean test, use fresh test installations without identities you need to retain. Uninstall/clear-data removes local identity and encrypted history; do not do this to repair a failed registration. Existing local identities can instead use **Connect to Ghost Cloak** without key replacement.

## Create identities and connect

1. Phone A: enter a unique username, for example `alice_e0_4821`, then tap **Create identity**.
2. Phone B: enter a different unique username, for example `bob_e0_4821`, then tap **Create identity**.
3. Wait for **Connected** on both. Use 3–24 letters, numbers or underscores, beginning with a letter or number. Names are normalized to lowercase; some names are reserved.
4. If creation cannot reach staging, the local identity remains. Restore connectivity and tap **Connect to Ghost Cloak** or **Sync**. Do not reinstall. If the username is taken, choose another in Settings before reconnecting; keys stay unchanged. After successful registration, Settings changes only the local display name, not the server username.

“Connected” reflects this app's last successful operation, never another person's presence. While the app is STARTED, automatic sync runs immediately on resume and then waits approximately one second after each completed cycle. Cycles do not overlap. It reuses authentication, renews on expiry, retries pending sends and fetches messages/receipts. **Sync** remains available for recovery. Backgrounding cancels polling completely; logout disables automatic reconnection until explicitly connected again.

## Exchange messages

1. Alice taps **+ (New chat)**, enters Bob's exact registered username and taps **Find and add contact**. **Bob does not add Alice.**
2. Do not verify safety numbers yet. Alice opens Bob's conversation and sends `Synthetic one-way A1`.
3. Alice sees **Queued on server** after upload. Keep Bob's app open; within roughly one second plus network time, Alice appears as a message request on Bob's phone. The **Requests** filter isolates pending requests.
4. Bob opens the request. The message is visible and the sender is **Unverified**. No manual Sync or mutual verification is needed. Reply stays disabled until **Accept**.
5. After Bob's client decrypts, stores and ACKs the message, Alice's next foreground cycle shows **Delivered**. This can happen before Bob taps Accept: Delivered means saved on the receiving endpoint, not human acceptance or reading.
6. Bob taps **Accept**; Alice becomes a normal contact but remains Unverified. Bob replies `Synthetic reply B1`. Alice receives automatically while foregrounded.
7. Optionally compare safety numbers through a separate trusted channel and explicitly verify. Confirm messaging worked before verification. Unexpected key changes must still require security review/re-approval.
8. With another synthetic sender, exercise **Block & delete**. Request history disappears and a blocked identity tombstone prevents recreation. Later blocked messages are not decrypted/ACKed. Local deletion cannot undo a prior Delivered receipt.
9. Pending messages retry automatically while foregrounded. The composer clears after local saving; do not retype the same message to retry. Manual Sync remains in Settings and the conversation options menu. Rejection, expiry or missing receipts must never become Delivered.

## Persistence and network changes

1. Force-stop/reopen both apps. Confirm usernames, contacts, safety numbers and history remain, and sync resumes automatically after opening. No identity replacement is needed.
2. Put Bob offline or background/close his app. Alice sends `Synthetic offline A2`; it stays Queued. Restore Bob's connection and foreground Ghost Cloak: it receives/stores/ACKs automatically, then Alice's next cycle shows Delivered. Keep this within the mailbox/outbox TTL (24 hours).
3. Put Alice offline and attempt `Synthetic pending A3`. Restore connectivity while foregrounded. Verify Bob receives one copy automatically.
4. Background both apps for at least ten seconds: recurring polling must stop. Foreground them and repeat. Also repeat with one phone on cellular and one on Wi-Fi.
5. Reboot one phone, unlock it and open Ghost Cloak. Confirm the same identity, contacts, safety numbers and history. Exchange `Synthetic reboot B2` automatically. Reboot alone does not start polling.
6. Record pass/fail, app build/commit, Android versions and which step failed. Do not include keys, bearer tokens, plaintext database exports or full server responses. The local error text is sufficient for an initial report.

## Boundaries

Messages remain encrypted through the existing Signal engine; accepted plaintext/history stays in SQLCipher with Keystore protection. Acceptance and ratchet updates commit together before ACK. Receipts share the existing seven-day submission retention. Old unconfirmed messages can remain Queued because their final status is unavailable; expiry never proves delivery. Cloudflare/service visibility remains documented in `protocol/METADATA_PRIVACY.md`; this is not anonymous messaging. No relay, Ghost Mode, push, read receipts, typing indicators, new permissions or background service are enabled. A bounded HTTP call already executing during cancellation may finish, but no new background polling cycle starts.

Developer contact-card/demo flows remain available in local builds and the isolated debug demo. They are not the primary staging contact flow. Manually imported contacts without a stored network route cannot silently use the simulator in a network-configured real identity.
