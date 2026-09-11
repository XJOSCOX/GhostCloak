# Security review — Phase 1E.0

Base: 5570a33. Scope: Android foreground UI/application wiring and local tests. This is a development review, not an independent audit.

## Behavior and security boundaries

- `AppRuntime.create` validates local/server-compatible username rules before creating an identity, then invokes existing registration/login when an API origin is configured. A failed registration leaves the encrypted identity intact and the main UI offers Connect/Sync. A retry opens that identity; it does not regenerate keys. Login-first recovery for a lost registration response remains in place.
- Android now exposes the last foreground network state: needs connection, connecting, connected, syncing, offline or error. It does not claim to monitor connectivity continuously. Reopening the process requires explicit reconnect/Sync, using persistent encrypted credentials and registration state.
- Username lookup is the primary network contact UI and invokes existing directory lookup/contact import/session establishment. Lookup can reconnect after token expiry. Local builds and the isolated debug demo retain contact cards.
- Real identities in a configured build do not attach the local router. Conversation sends still call the existing `AppRuntime.send` network branch. Failed uploads retain durable pending state; the UI clears a draft only after a saved message is returned and explains that Sync retries it. Server acceptance is labelled “Queued on server”, never recipient delivery/read receipt.
- Manual Sync explicitly authenticates, then uses existing outbox retry, fetch, decrypt/store and accepted-message ACK logic. No background polling, push, relay, VPN, permission or service is added.
- Production cryptography, protocol, prekey semantics, mailbox authorization, storage format and TLS validation remain unchanged. The transport injection seam is for trusted local tests; shipped construction continues to use Standard direct HTTPS.
- UI error messages do not include bearer tokens, account/device IDs or full server responses. Existing duplicate-name device-ID snippets were replaced with safety-number guidance. Network flows do not show the simulator's delivery error.

## Known limits

The service and Cloudflare still see the metadata documented in `protocol/METADATA_PRIVACY.md`. This phase does not implement anonymity. A connected label records a successful foreground operation, not a live link guarantee. Registration uses a unique normalized username; after registration, profile edits remain local display-name changes. Both users must add each other before receive tests. Unknown/blocked/changed contacts are not auto-trusted or ACKed. Existing bounded outbox/mailbox TTLs and previously documented endpoint crash gaps remain.

This work uses synthetic local endpoints and the existing encrypted Android storage/Keystore. Physical-phone cellular, OEM reboot behavior and the actual staging path require the operator's two-phone test. No Cloudflare/VPS configuration, live server operation or cryptographic redesign is part of this phase.

## Validation

Validated locally on 2026-09-11:

| Check | Result |
|---|---|
| JVM/unit suite under strict dependency verification | 55 test-support tests plus 1 app unit test passed; includes existing Alice/Bob/Charlie, origin-header and transport/privacy tests |
| Isolated PostgreSQL integration suite | All 8 passed; no staging/live backend request |
| Full Android suites with empty API origin on isolated API 37 emulator | All 28 passed: 18 app tests (including 9 new onboarding/UI tests) and 10 storage/Keystore tests |
| Live-origin build regression checks | All 3 network UI tests passed; first-frame network copy verified. All 6 onboarding tests passed again after testing a valid persisted-token send/republication across process reopen. Endpoints remain synthetic through injection |
| Actual emulator reboot | Explicit prepare → `adb -s emulator-5556 reboot` → verify passed. Same identity/public key, device/account, registration flag, token, contact and decrypted history persisted; opening performed no network request |
| Debug/release assembly | Both passed with `API_ORIGIN=https://api.ghostcloak.org`; generated BuildConfig checked. Debug APK is ready for operator testing |
| IDE dependency attachments | Strict verification passed for Gradle 9.7.1 source ZIP and 533 source/Javadoc/sample artifacts; verification metadata unchanged |
| Visual review | First-launch and connected-contacts screenshots inspected; existing dark/mint theme and separate screen/component organization retained |

The initial active-emulator run was externally force-stopped by Android Studio deployment. Subsequent validation used the independently created GhostCloakE0Test emulator on port 5556; the user's emulator and physical phone were not used. A new persistence fixture initially compared a cumulative request count to zero; it was corrected to compare the before/after count. Final checks are green. Existing Gradle/protobuf deprecation warnings remain non-failing.

Existing user launcher edits and the unrelated root Android build-file edit remain outside the phase commit; generated APKs include the current workspace artwork. The isolated PostgreSQL process and dedicated test emulator were stopped after validation. Two physical phones, cellular switching and the live Cloudflare path still require the operator procedure in `Android/TWO_PHONE_STAGING_TEST.md`; no successful live run is claimed here.
