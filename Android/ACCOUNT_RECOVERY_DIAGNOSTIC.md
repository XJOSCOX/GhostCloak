# Account reconnect regression investigation

Investigation of `2636d8f` (current main at investigation start), 2026-09-12. Documentation and sanitized DEBUG instrumentation only. No recovery behavior, credentials, identity, keys, ownership, backend or UI policy changed. No phone or server database was read or modified. The reported server counts are user-provided aggregate observations, not proof that a particular local credential matches a server device.

## Finding and confidence

**Phone A's root cause is not yet established.** Its exact last-working APK/commit, compiled API host, persisted network metadata and failing endpoint trace were not supplied. The immediate predecessor `4d62239` is the comparison baseline, not a verified last-working Phone A build. Older onboarding, credential and lifecycle commits were also audited.

Confirmed code-level failure branch: `NetworkController.connect` treats an absent host-specific `registered` marker as unfinished onboarding. It prepares a registration, tries login first, and on **any login ApiFailure 401** falls back to registration. If the server already owns the username, device, account, routing identifier or auth public key, `/v1/accounts` rejects that registration with 409. This branch predates the attachment release. A 401 proves failed authentication, not that the account never existed.

Confirmed through isolated emulator fixtures:

- Removing only the registration marker: existing credentials successfully log in, the marker is restored, no new registration occurs.
- Removing only the access token: explicit reconnect logs in to the existing account; no registration occurs.
- Removing the entire network metadata namespace while retaining Signal identity and Keystore: the client prepares new account/routing metadata, the existing account rejects login, and fallback registration conflicts. Signal identity/device and server account remain unchanged in the fixture.
- Removing the marker and injecting a rejected login: fallback registration conflicts even though the original account and authentication credential remain present.

These are reproductions of possible state transitions, **not evidence that an upgrade deleted Phone A's metadata**. Repeated reconnect can already have changed an originally empty namespace into newly prepared, unregistered metadata. Do not repeatedly reconnect, rename, clear data, reinstall by uninstalling, or attempt recovery scripts while collecting evidence.

## Persisted state and decisions

Source: [EndpointNetworkState / NetworkAccount](messaging/src/main/kotlin/org/ghostcloak/messaging/NetworkAccount.kt), [SignalProtocolEngine](crypto/src/main/kotlin/org/ghostcloak/crypto/SignalProtocolEngine.kt), [SignalStore](crypto/src/main/kotlin/org/ghostcloak/crypto/SignalStore.kt), [NetworkController](app/src/main/java/org/ghostcloak/app/application/NetworkController.kt).

All records below live inside the credential-encrypted SQLCipher endpoint database. `H` means SHA-256 of the **exact `URI(API_ORIGIN).host` UTF-8 string**, hexadecimal. It is not a username or server-assigned identifier. Never print actual record keys or values.

| Meaning | Storage | Decision / limitation |
|---|---|---|
| Local Signal identity | `local/key`, `local/device`, `local/user`, `local/username`, `local/registration` | Independent of server account registration. `local/registration` is Signal's numeric registration ID, not the network registered marker. `local/user` is not the network account ID. |
| Network account and routing | `network/H/account`, `network/H/routing` | Client-generated immutable identifiers used in registration; login requires stored account plus `local/device`. |
| Device authentication credential | `network/H/auth-alias`, `network/H/auth-public` plus non-exportable AndroidKeyStore EC P-256 private key | Separate from Signal keys and from the database wrapping key. Alias presence does not prove the private key is usable or matches the server. |
| Legacy software credential | `network/H/auth-private` | Older PKCS#8 format; Android's Keystore-backed path explicitly rejects it, without conversion/replacement. |
| Successful registration remembered | `network/H/registered` | Raw record existence, not a serialized Boolean. Written after successful Register response, or after successful login from unfinished onboarding. No separate device-registered flag. |
| Access session | `network/H/token` | Token only; client does not persist its expiry. Server expiry is learned as 401. Missing and expired are different states. |
| Automatic reconnect blocked | `app/renewal-blocked/<host>` | Raw presence; written on terminal 401 and explicit logout, removed after successful explicit connect. It does not distinguish logout from rejected renewal. |
| Routing cache | `network/H/route/...` | Contact routes, not registration authority. Contacts/history are stored separately under `app/...`. |

`canAutoSync = configured && !renewalBlocked && !loggingOut && registeredMarkerPresent && tokenPresent`.

Startup opens the existing `local.db`/`local.wrapped` endpoint and creates a controller whose initial visible status is NEEDS_CONNECT, even when records are valid. It does not register on open. Foreground polling runs only when eligible. A valid stored session leads to ordinary sync; an expired stored session leads to one serialized silent login and one retry. A missing token is not silently renewed by this eligibility predicate. Explicit connect with the marker present logs in without registration even when the token is missing or renewal is blocked.

`AppRuntime.networkRequiresConnect` is computed from `!canAutoSync`, not from cryptographic identity validity. `NetworkActions` displays both Connect and Sync when appropriate. **Current Sync does not call Connect or Register**: it enters `NetworkController.sync`, which rejects ineligible state with a local 401. A reported “Sync/reconnect 409” must therefore be separated into the actual button/path or a queued operation performed during Sync.

## Exact problematic transition

```text
Signal identity exists
current-host network registered marker absent
explicit Connect
  -> registration(username, public bundle)
     -> existing complete auth metadata: reuse it
     -> completely absent metadata: prepare account/routing IDs and auth metadata
  -> login challenge + proof for the prepared/stored account and local device
  -> login ApiFailure 401
  -> register challenge + signed registration
  -> POST /v1/accounts
  -> server uniqueness check rejects existing information with 409
  -> NetworkStatus.ERROR; marker still absent; canAutoSync false
  -> Needs attention / Connect / Sync
```

For complete metadata loss in the **same host namespace**, the alias is deterministically derived from host hash and the preserved local device. If that Keystore alias still exists, `KeystoreDeviceAuth.publicKey(create=true)` reuses its key, but `registration()` still generates new account/routing IDs because the database metadata is absent. Thus even a preserved authentication private key can be paired with the wrong newly prepared account ID. The server correctly rejects login for that tuple. This is not a reason to change server ownership.

For a different host namespace, the client can prepare a different auth alias and new metadata while preserving Signal identity. If both hosts route to the same account service, registration can conflict. A server audience mismatch normally fails the local challenge-binding check (400), rather than proving this entire 409 chain. Host drift must be checked, not assumed. Scheme/port are not in this namespace hash; changing only those does not make the metadata absent.

The registration preparer is not a read-only probe: it can persist candidate network metadata before the first successful server response and `engine.publicBundle()` can publish new prekey material. No such operation was performed on Phone A during this investigation.

## Candidate ranking and Phone B

1. **Missing or differently selected network namespace, with Signal state intact.** This fits the reproduced conflict branch. Possible antecedents include different historical API-host build configuration or selectively missing/restored records. No production record-deletion path causing this was found. Phone B may retain the correct namespace and marker while A does not.
2. **Absent marker plus rejected existing-device login.** Missing marker alone recovers. Additional rejection can arise from wrong account/device mapping, wrong/unusable device credential, stale/expired challenge, or server data inconsistent with that device. The “any 401 means try registration” fallback makes these states misleading. The ordinary 401 session-expiration path does not remove the marker.
3. **Wrong endpoint interpretation.** The 409 text is a catch-all for several endpoints. Until the endpoint category is captured, a prekey/delivery conflict must not be labeled an account-registration conflict.
4. **Legacy software credential.** A state-dependent historical incompatibility exists, but produces the distinct local `legacy_auth_requires_reset` message in current code. The reported generic wording makes this a weaker fit. It need not involve an HTTP 409 at all.
5. **Keystore loss with intact registration metadata.** Normally produces storage/credential failure, not new registration. It can coexist with metadata loss, but Signal-key preservation alone says nothing about this separate key. No evidence yet shows loss on Phone A.

Phone B surviving rules out neither state-dependent behavior nor build-history differences. It argues against assuming universal database corruption. Server totals (11 accounts/devices, empty mailbox, retained dedup rows) do not verify Phone A's particular ownership/key tuple, and an empty mailbox does not imply an account was deleted.

## Recent history / migration audit

| Commit / area | Finding |
|---|---|
| `2636d8f` attachments vs `4d62239` | Added separate `attachment/...` journal and `app/attachment/...` descriptors. Cleanup targets those prefixes/files, not `network/...` or `local/...`. Controller adds volatile visibility and internal blob entry points; connect/registration decisions unchanged. Shared authenticated retry was extracted, preserving one retry; it does not call registration. |
| Backend V003 | Adds blob/budget tables only; no account/device ownership or auth-key rewrite. This is not an Android state migration. Session check extraction retains the existing token/device checks. |
| `aba3ac5`, `5b793f5`, `0addc35` expiry/delete | Touch local message/policy/read/notification/attachment lifetimes. No network registered-flag migration. |
| `a687eea`, `34969a1` app lock | Separate `app/access-lock` and UI lifecycle. Does not convert auth keys or set network registration false. |
| `a448564`, `3c7619d` background/notifications | Shared runtime, existing-identity/eligibility gates; no background registration. Persistent renewal-block flag can explain manual-connect requirement, but does not remove marker/account/credential. |
| `2580573` renewal | Centralized one authenticated retry and login-only renewal. Explicit Connect's unmarked/login-first/401-registration fallback already existed. Transient failure preserves session/identity; terminal 401 blocks auto sync. |
| `60849e1` onboarding | Current login-first recovery for a lost registration response originates here. `NetworkOnboardingTest` is a test class, not a separate production onboarding engine. |
| `f26461e` network state | Only added sender-route remembering; no registration serialization change. No later change to `EndpointNetworkState` before this diagnostic. |
| `bd9886d` build configuration | Debug default changed from empty/explicit override to staging; release origin separate and empty by default. Historical host/config differences are worth checking for A vs B. No recent attachment-origin change. |
| `c5d46ff` historical auth | Introduced Android Keystore credential adapter and record-existence registered marker; deliberately rejects older software-private-key state. There is no automatic migration to Keystore. |
| EncryptedEndpointStore | Room schema remains version 1 (single key/value records table); no new-field default `registered=false`, no destructive migration, no fallback-to-empty on decryption failure. Missing wrapped/database/key combinations fail closed. Storage opener unchanged since `5fea4ee`. |

Consequently, **no new serialized `registered` field or recent schema migration explaining Phone A was found**. SQLCipher payload evolution for messages is unrelated to the raw registration marker. This does not prove the contents on Phone A are intact; that requires diagnostics.

## 401 and 409 attribution

Normal expiry with registered marker + token + usable auth credential:

```text
authenticated request -> 401
renewal mutex -> login(existing account, existing local device)
challenge/proof using existing device auth private key
new token persisted -> original request retried once
```

No username or Register request is involved. Concurrent send/sync coalesces renewal; repeated 401 blocks automatic renewal. Explicit logout clears only token and sets the persistent block. It must remain explicit; no recovery should defeat it.

Server source: [MailboxService](../backend/src/main/kotlin/org/ghostcloak/backend/MailboxService.kt). Client routes: [ApiRoutes](protocol/src/main/kotlin/org/ghostcloak/protocol/NetworkV1.kt). UI: [GhostViewModel](app/src/main/java/org/ghostcloak/app/application/GhostViewModel.kt).

| Category / endpoint | Relevant outcome |
|---|---|
| AUTH_CHALLENGE `/v1/auth/challenge` | Real backend issues login challenges without disclosing account existence; ordinary unknown-account failure is later at Verify. |
| AUTH_VERIFY `/v1/auth/verify` | 401 for missing device, wrong account binding, invalid/expired proof. No normal 409 branch. |
| ACCOUNT_REGISTER `/v1/accounts` | 409 `conflict` for any existing account/name/device/routing/auth key. Does not disclose which condition. Bundle validation can also reject with 409. |
| ACCOUNT_RENAME `/v1/accounts/username` | Username conflict 409. |
| LOOKUP `/v1/directory/lookup` | Exhausted prekeys 409. |
| PUBLISH `/v1/devices/prekeys` | Duplicate prekey or signed-key conflict 409. |
| SEND `/v1/messages` | Idempotency/ciphertext conflict 409. |
| Blob reservation | Conflicting immutable reservation 409; no user-facing attachment entry point in 1I.2. |

`HttpGhostClient` validates CBOR but maps non-2xx responses to status + `server_rejected`; the generic UI 409 advice does not preserve endpoint attribution. `legacy_auth_requires_reset` is separately recognized when raised locally. Therefore **the actual Phone A endpoint cannot be determined from the supplied message alone**. If the trace shows ACCOUNT_REGISTER/409 after AUTH_VERIFY/401, the fallback chain is confirmed. If it shows PUBLISH or SEND, investigate that operation instead. Do not instruct an existing user to rename based on the generic message.

## Secure recovery feasibility — no implementation

- Correct original account ID + local device ID + usable private key matching the server's device auth public key: existing challenge-response login is sufficient. Username is neither proof nor necessary for login. A lost token/marker alone is recoverable with existing mechanisms.
- Intact original credential in another host namespace: potentially recoverable only after explicit, validated audience/endpoint mapping and proof against the correct server device. Never blindly copy across hosts or derive ownership from username.
- Original auth private key survives but account metadata is lost/replaced: cryptographic possession may be sufficient in principle, but the current login API requires the correct account/device tuple. The app has no approved recovery/discovery flow for missing account metadata. A future procedure must obtain the original binding from a trustworthy source and prove possession against the server's already-stored auth key before committing anything. Do not replace the server key or use the newly prepared account ID.
- Only Signal keys survive, or auth private key is missing/unusable: **current authentication cannot securely reattach**. Signal keys are distinct and not accepted by DeviceAuth login. Public keys, username, contacts, device ID or database account counts alone are insufficient. No “reset keys,” takeover or username-based recovery is acceptable.
- Legacy private-key bytes: may still mathematically prove ownership if they match the stored device credential, but current Android deliberately refuses that path. Any supported migration must be separately threat-modeled and authorized; this investigation does not activate it.

`DEVICE_CREDENTIAL_PRESENT=true` below reports record/Keystore-entry presence only. It is not proof of successful signing, correct public-key binding, account ownership, or recovery success.

## Proposed correction for a later authorized change

Separate genuine first registration, prepared-but-unconfirmed registration, known existing-account login, metadata-incomplete recovery-required, and explicit logout states. Do not infer new-account eligibility merely from a missing marker or a failed login. Existing-state evidence must prevent fallback registration/key preparation; one terminal 401 must not authorize new ownership. Store enough durable registration intent/completion information to distinguish a first-attempt crash from lost existing-account metadata.

Treat token absence separately from registration absence, preserve explicit logout, and classify the 409 UI by operation. Preserve login-first handling for a truly lost registration response, but authenticate the original tuple without preparing replacement metadata. Any migration must be transactional and idempotent, retain all existing IDs/keys/outbox/history, and stop on ambiguity. Design missing-metadata recovery only after proving which original auth credential is still available. **None of these changes is implemented here.**

## Safe physical evidence collection

Install a DEBUG update normally over the existing app; do not uninstall or clear data. In Android Studio Logcat filter `tag:GhostCloakAccount`. Keep the existing generic `GhostCloakNet` trace if needed, but share only sanitized categories. No database export, key dump, username, token or full URL is requested.

On open and explicit connect/renewal, the new debug-only observer emits only:

```text
STARTUP_PATH=OPEN
IDENTITY_PRESENT=true
REGISTRATION_PRESENT=false
SESSION_PRESENT=false
DEVICE_CREDENTIAL_PRESENT=true
STARTUP_PATH=CONNECT_UNMARKED_LOGIN_FIRST
ENDPOINT_CATEGORY=AUTH_CHALLENGE HTTP_STATUS=200
ENDPOINT_CATEGORY=AUTH_VERIFY HTTP_STATUS=401
STARTUP_PATH=CONNECT_REGISTER_AFTER_401
ENDPOINT_CATEGORY=ACCOUNT_REGISTER HTTP_STATUS=409
```

This is an illustrative trace, not Phone A's captured output. Additional fixed STARTUP_PATH categories identify another namespace, incomplete metadata, legacy credentials, renewal blocking or diagnostic state-read failure. No hashes/aliases/identifiers or exception messages are emitted. Empty/unreadable/invalid fields must not be interpreted as proven recoverability. A session presence line says a token record exists, not that the token is valid.

Capture startup first; the instrumentation itself never prepares/registers/logs in, writes records, generates/signs with keys or alters decisions. If a reconnect trace is necessary, the existing Connect button still has the risky behavior described above; do not perform repeated attempts or assume this diagnostic build fixes it. Compare the old/new APK source provenance and API host locally for both phones without including URLs in the diagnostic log. The user can provide the failing endpoint category and Boolean/category trace to resolve the remaining uncertainty.

Release's observer is a no-op: no extra state reads, no transport wrapper and no logs. Debug passes the same body bytes, endpoint and authorization to the existing transport, preserves response/exception behavior and retry count, and catches logging failures. HTTP_STATUS is emitted only from an actual transport response/status callback, not fabricated from a local ApiFailure.

## Validation and required regression coverage

Six temporary isolated emulator investigation tests cover the four state combinations above, byte/response-preserving diagnostics with a throwing sink, and read-only namespace snapshots. Fixtures use fresh test endpoints and synthetic accounts only; no Phone A/B data or live service. Their source is kept in ignored local investigation artifacts rather than committing recovery/test behavior in this documentation/diagnostics-only slice. Initial fixture endpoint names exceeded the existing 40-character limit; shortening the test-only prefix resolved that harness failure.

The existing onboarding tests include lost registration response, encrypted reopen, local-only identity then registration, and account/history preservation. The synthetic Android backend rejects an unknown login at Challenge (unlike production's Verify), so endpoint-stage conclusions come from real backend source, not that fixture alone. Session-renewal tests cover one retry, concurrent send/sync, explicit logout across reopen, transient failures and rejected credentials without registration. NetworkStorage tests cover Keystore persistence and legacy refusal. Full test results are recorded below after completion.

Before any recovery fix ships, permanent tests must additionally cover: upgrade from each supported historical namespace/credential format; unchanged namespace with missing marker/token separately; missing account/routing metadata with intact Keystore; absent alias/public record/private key separately; wrong key versus server; expired login challenge with marker absent; complete namespace absence with existing identity; no writes/new keys or registration after ambiguous 401; explicit logout and blocked-renewal differentiation; crash at each durable onboarding boundary; secure binding proof before restoration; repeated recovery idempotence; A failing while B remains operational; and unchanged Signal/session/outbox/contact/history/replay/lock/notification/background behavior. No physical recovery is claimed.

Completed validation: **128 JVM tests** (82 test-support, 23 per app variant including the temporary release no-read/no-wrapper check), **94 emulator tests** (84 app including six investigation fixtures, 10 storage), and debug/release builds passed with strict dependency verification. Full emulator coverage includes onboarding, silent renewal, app lock, foreground/background sync, notifications, deletion, expiry and attachment storage. Backend protocol/auth JVM tests passed; PostgreSQL was not rerun because this diagnostic changes no backend/database code. Temporary investigation test sources were moved to ignored `.research/` after the run; only this report and instrumentation are committed. No physical-device migration or recovery result is claimed.
