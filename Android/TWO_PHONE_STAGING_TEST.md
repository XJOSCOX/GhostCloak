# Two-phone staging test — Phase 1E.0

Use two test phones and synthetic messages only. Staging must already be deployed; this test does not change Cloudflare, the VPS, protocol or cryptography. No same-LAN requirement, background service or push setup is needed.

## Build and install

From the repository root, build the network-configured APK:

```powershell
.\Android\gradlew.bat -p Android :app:assembleDebug '-PghostcloakApiOrigin=https://api.ghostcloak.org' --dependency-verification strict
```

Install `Android/app/build/outputs/apk/debug/app-debug.apk` on both test phones. A debug APK includes the existing developer tools; keep both phones in their real identity, outside the local demo. A build with an empty origin is intentionally local-only. The network build's first launch must say **Encrypted messaging via Ghost Cloak staging** and **Create identity**.

For a clean test, use fresh test installations without identities you need to retain. Uninstall/clear-data removes local identity and encrypted history; do not do this to repair a failed registration. Existing local identities can instead use **Connect to Ghost Cloak** without key replacement.

## Create identities and connect

1. Phone A: enter a unique username, for example `alice_e0_4821`, then tap **Create identity**.
2. Phone B: enter a different unique username, for example `bob_e0_4821`, then tap **Create identity**.
3. Wait for **Connected** on both. Use 3–24 letters, numbers or underscores, beginning with a letter or number. Names are normalized to lowercase; some names are reserved.
4. If creation cannot reach staging, the local identity remains. Restore connectivity and tap **Connect to Ghost Cloak** or **Sync**. Do not reinstall. If the username is taken, choose another in Settings before reconnecting; keys stay unchanged. After successful registration, Settings changes only the local display name, not the server username.

“Connected” reflects the last successful authentication/operation, not a continuously monitored connection. **Sync** is an explicit foreground action that renews authentication, retries pending sends and fetches messages. Offline/error text asks you to retry; no automatic background polling occurs.

## Exchange messages

1. Alice taps **Add a contact**, enters Bob's exact registered username and taps **Find and add contact**.
2. Bob adds Alice by her registered username. Both must add each other before testing receive; unrecognized/blocked/changed identities are not automatically accepted or ACKed.
3. Open Contact Security and compare safety numbers through a separate trusted channel. Mark verified only after comparison.
4. Alice opens Bob's conversation and sends `Synthetic test A1`.
5. Alice should see **Queued on server** after acceptance. This is not a delivery or read receipt.
6. Bob taps **Sync** in contacts or the conversation. Check `Synthetic test A1` appears as **Received**.
7. Bob replies `Synthetic test B1`; Alice taps **Sync** and verifies receipt.
8. If a send remains **Pending**, use Sync to retry that saved submission. The composer clears once the pending message is saved, so do not retype/send the same message to retry it. A rejected/expired/ambiguous submission must not be labelled delivered.

## Persistence and network changes

1. Force-stop both apps through Android Settings and reopen. Confirm usernames, contacts, safety numbers and received history remain. Tap Sync; **Connect to start messaging** after a process restart is expected and does not mean the identity was replaced.
2. Put Bob offline or close his app. Alice sends `Synthetic offline A2`; after Bob returns online and taps Sync, verify receipt. Keep the test within the existing mailbox/outbox TTL (24 hours); this is not indefinite offline storage.
3. Put Alice offline and attempt `Synthetic pending A3`. Check Pending/error feedback, restore connectivity and Sync. Verify Bob receives one copy after his Sync.
4. Disable Wi-Fi on one phone and use cellular data. Repeat send/Sync both ways. Keep the other phone on Wi-Fi; no common LAN is required.
5. Reboot one phone, unlock it and manually launch Ghost Cloak. Confirm the same identity/safety numbers, contacts and history. Tap Sync and exchange `Synthetic reboot B2` in both directions.
6. Record pass/fail, app build/commit, Android versions and which step failed. Do not include keys, bearer tokens, plaintext database exports or full server responses. The local error text is sufficient for an initial report.

## Boundaries

Messages remain encrypted through the existing Signal engine; accepted plaintext/history stays in SQLCipher, with existing Keystore identity protection. Only successfully accepted messages are ACKed. Cloudflare and the service retain the metadata visibility documented in `protocol/METADATA_PRIVACY.md`; this is not anonymous messaging. No relay, Ghost Mode, push, read receipts, typing indicators or background sync are enabled.

Developer contact-card/demo flows remain available in local builds and the isolated debug demo. They are not the primary staging contact flow. Manually imported contacts without a stored network route cannot silently use the simulator in a network-configured real identity.
