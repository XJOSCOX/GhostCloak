# Phone A local-state regression — diagnostics only

## Follow-up: existing-store content inventory (2026-09-12)

### New physical evidence and what is still unknown

Phone A now reports the expected package, debug/staging/default slot, both files present, and `STORE_OPEN_RESULT=existing`. Its existing account diagnostic reports identity, registration, session and device credential all false. Phone B remains working. This rules out a missing selected file **at that observed open**; it does not prove that this database is the same historical logical state or that its identity records are intact.

**Phone A's new per-record/count inventory has not yet been captured.** No contact, message, partial-identity or corruption finding can yet be asserted. In particular, `IDENTITY_PRESENT=false` is a conjunction failing: it does not establish that both constituent records are absent. The earlier source-comparison findings below remain historical context, not a diagnosis of deletion.

### Read-only inventory contract

The debug runtime takes a record-name snapshot immediately after successful store open, before constructing the Signal engine, NetworkController, repository or attachment store and before normal reconciliation. `LocalStateDiagnostics.inventory` only calls `keys`, reads the current namespace's stored auth-alias if present, and checks that exact alias with Android Keystore `containsAlias`. It never invokes engine/service getters, registration, recovery, key generation, signing, record writes/removes or SQL updates. No alternative alias is derived, and no unrelated Keystore entries are enumerated. The temporary alias byte-array copy is cleared; no persisted value is changed.

The output uses `GhostCloakStore` and only the following fixed mappings. No record key, suffix, hostname digest or value is printed. A read transaction keeps the snapshot coherent. Normal runtime behavior remains unchanged after this observation; ordinary expiry/synchronization can still run later. The guarantee is that the **diagnostic itself** leaves all logical records unchanged, not that an otherwise running application or SQLite WAL never writes physical bytes.

| Diagnostic | Exact record or counted prefix |
| --- | --- |
| LOCAL_IDENTITY_KEY_PRESENT | `local/key` |
| LOCAL_DEVICE_PRESENT | `local/device` |
| LOCAL_USER_PRESENT | `local/user` |
| LOCAL_USERNAME_PRESENT | `local/username` |
| LOCAL_SIGNAL_REGISTRATION_PRESENT | `local/registration` |
| NETWORK_ACCOUNT_PRESENT | current network prefix + `account` |
| NETWORK_ROUTING_PRESENT | current network prefix + `routing` |
| NETWORK_REGISTERED_MARKER_PRESENT | current network prefix + `registered` |
| NETWORK_TOKEN_PRESENT | current network prefix + `token` |
| NETWORK_AUTH_ALIAS_RECORD_PRESENT | current network prefix + `auth-alias` |
| NETWORK_AUTH_PUBLIC_RECORD_PRESENT | current network prefix + `auth-public` |
| NETWORK_LEGACY_AUTH_PRIVATE_PRESENT | current network prefix + `auth-private` |
| CONTACT_RECORD_COUNT | `app/contact/` |
| MESSAGE_RECORD_COUNT | `app/message/` |
| VERIFICATION_RECORD_COUNT | `trust-state/` (all stored trust states, **not** a count of verified contacts) |
| OUTBOX_RECORD_COUNT | `outbox/` |
| DISAPPEARING_POLICY_COUNT | `app/disappearing/` |
| ATTACHMENT_RECORD_COUNT | sum of `app/attachment/`, `attachment/transfer/`, `attachment/delete/` records; not a distinct-media count |
| APP_LOCK_CONFIG_PRESENT | `app/access-lock` |
| TOTAL_RECORD_COUNT | all record names, including categories not separately counted |
| REFERENCED_AUTH_KEYSTORE_ENTRY_PRESENT | presence of the current namespace's stored alias; false if no alias record exists |

Network fields refer to the configured API hostname namespace only. Other namespaces contribute to total records but are not opened, validated or reported individually. Zero contact/history/outbox counts can be legitimate for a healthy new account. The inventory cannot distinguish missing records from invalid values solely by presence. It deliberately does not parse message/identity blobs or call APIs that might write migrations.

If record enumeration, alias decoding or Keystore access throws, the entire inventory block is omitted rather than fabricating false/zero fields. `DATABASE_INTEGRITY=NOT_CHECKED` is still emitted. An incomplete/missing block must not be interpreted as an empty database.

`DATABASE_INTEGRITY=NOT_CHECKED` is intentional: successful SQLCipher open/query is not an authenticated whole-database integrity assessment. The existing `EndpointRecords` read abstraction exposes no SQL integrity operation; this patch does not add raw SQL/PRAGMA access or claim that a particular check is safe and sufficient without validation. No database rows are dumped. Release has a no-op inventory implementation: no record reads, Keystore queries or logs.

### Exact local identity recognition path

There is no `SignalProtocolEngine.open()` method. `ConversationService.open()` calls `LocalRepository.hasIdentity()`, which tests only whether `local/device` has a non-null value. If absent, it returns null and the UI can show first-launch onboarding, even if other records survive.

If `local/device` exists, that method calls `engine.createIdentity("Local")`. Despite its name, the engine's existing-device branch does not generate keys: it reads the identity. Its `identity()` requires `local/user`, `local/username`, `local/device`, and a decodable Signal `IdentityKeyPair` in `local/key`. Missing required records or malformed key material raise a corrupt/storage failure rather than yielding a valid identity. Text retrieval uses UTF-8 decoding, not comprehensive identifier validation. `local/registration` is required and parsed as an integer by `SignalStore.getLocalRegistrationId()` for subsequent protocol operations; the initial identity-return path alone does not read it.

Only the explicit engine creation branch, with absent `local/device` **and an entirely empty record store**, generates a new identity. A nonempty store without a device record raises `CorruptEndpointState` on that branch. This diagnostic never invokes that method or any creation branch.

The older account log tests the presence of **both** `local/device` and `local/key`, without deserializing either. Thus malformed values can still yield true. The new five independent booleans distinguish absent/partial record sets; they cannot establish that present bytes are valid. No synthetic repair or malformed-key reconstruction is attempted.

### Health interpretation once Phone A's block arrives

- `TOTAL_RECORD_COUNT=0`: logically empty at inventory time. Does not explain when or why it became empty.
- Low total with all five local flags false: no records of the five known identity components; report the actual remaining category counts, not an assumed wipe.
- Contacts/messages positive with missing local components: evidence that those record categories remain; not proof they deserialize or that original cryptographic operation can resume.
- Some local flags true, others false: partial local identity. Do not use the presence of history to invent missing private keys or device identifiers.
- All local flags true with startup failures: invalid/deserialization state remains a candidate requiring separate evidence; these presence-only diagnostics do not establish category D (malformed records).
- Different historical state (category E) cannot be established with these non-identifying counts alone. No correlating hashes, Phone B database export or cross-phone key copy is proposed.

At present **there is no evidence establishing that Phone A's contact/history state is still recoverable**. Capture the new block after a cold-open using `tag:GhostCloakStore`, alongside `tag:GhostCloakAccount`. Do not select Create identity, recovery, logout or reset for this diagnostic.

### Healthy fixture / Phone B semantics

A registered modern fixture has all five local components; network account/routing/registered/token/auth-alias/auth-public; and the referenced Keystore entry. Legacy auth-private is absent. A logged-out/expired session can legitimately lack a token. Contacts, messages, verification, outbox, policy and attachment counts depend on use; app-lock config depends on setup. No fixed expected total is asserted because Signal prekey/session/replay and application bookkeeping add records. The instrumentation fixture creates test-only state in a random isolated endpoint, closes and reopens it, captures the inventory, and compares every logical record byte before/after. It never opens either physical phone's store or the normal `local` endpoint.

### Deletion/history audit

Current production/debug sources and Git history searches for local/network broad-prefix removal and endpoint table clearing found no path selectively deleting the local identity set. This is a bounded source audit, not forensic proof of what ran on Phone A.

- Logout sets a durable block/logged-out marker and removes the current token; it preserves identity, account/routing and device credentials. Renewal/recovery removes only explicit pending/recovery/logged-out/renewal marker keys after successful operations, not a whole network prefix.
- Initial onboarding writes identity only via explicit creation. Startup and exception handling do not clear the records table or recreate unreadable state. The DAO's delete statement has an exact `WHERE name = :name`; no destructive Room migration was found.
- Conversation clear/local delete/expiry operate on `app/message/<conversation>/`, `app/attachment/<conversation>/`, read/unread and notification records. Notification clearing uses its own fixed prefix. The broad empty-prefix reads found are enumeration/creation guards, not deletes.
- Attachment cleanup targets `attachment/transfer/`, `attachment/delete/` and attachment-owned files. It does not select endpoint database files or local/network records.
- Signal session reset deletes session records; prekey consumption/retention removes prekey records. Trust replacement removes candidate/previous remote-trust records. The lazy legacy trust/lifecycle migrations can write remote trust/lifecycle records, which is why this inventory never calls those getters. None removes local identity components.
- Debug simulator creation uses separate fixed endpoints and explicit entry. Test helpers live in test source sets; no production path was found that invokes test cleanup or a full-record reset. History matches include the original exact-key DAO delete, initial network implementation and `7776d2b` recovery marker operations; none supplies evidence of the observed Phone A identity loss.

### Recovery boundary

The V004 Recover account flow restores server account/routing/session bindings after proving possession of the existing registered device authentication key. It requires the original `local/device`, stored auth-alias/auth-public, a matching accessible Keystore key, and a matching server binding; it is not a Signal identity-restoration mechanism. The current composite false flags do not show these prerequisites are available. If the new inventory confirms missing `local/device` or the original auth credential, this flow cannot be used as currently implemented. Even successful binding recovery would not reconstruct a missing Signal private identity key.

Preserved exact original key material might support a separately designed, explicitly authorized restoration investigation. Missing private keys cannot be reconstructed from a username, public key, account row, contact history or Phone B's identity. Generating replacement keys would change identity, not recover it, and must not be attempted here. No recovery or restoration implementation is included.

### Follow-up validation

The existing storage emulator suite also passed: 10 tests, zero failures.

Passed with strict dependency verification: 142 JVM tests (`:test-support:test`, both app unit-test variants), debug and release builds, the new reopened-store inventory emulator test (1), and existing network persistence emulator tests (2). New tests include empty/partial stores, category mappings, current-host isolation, absent/missing/inaccessible referenced keys, byte preservation, release no-read behavior and a reopened encrypted healthy fixture. The first inventory emulator invocation reported zero tests; it was not counted as validation, and the rerun's XML confirms one test executed with zero failures. No live recovery or registration request is sent by the diagnostic.

## Finding and limits (2026-09-12)

Source comparison: `f08fcf2` -> `7776d2b` (one commit). There is **no change to package, API-origin defaults, endpoint selection, database naming, wrapping-key naming, storage schema, or normal Application construction** in that interval. The earlier report of identity present is not accompanied by a verified APK/build fingerprint, so `f08fcf2` is a comparison baseline, not proof of the exact installed earlier binary. No physical Phone A data or Keystore has been inspected. Phone B being healthy argues for investigating per-install state and build/environment differences; it does not prove Phone A corruption.

The strongest unresolved possibilities are a different Android application sandbox/profile or previously installed artifact/configuration, or changed/empty contents in the selected database. Current source does not establish which happened. Loss of all four reported booleans is **not explained by an API-host change alone**. These booleans are record-presence observations, not a physical backup inventory or proof that keys have been erased.

## Exact storage and startup path

- `GhostApplication` owns one lazy `AppRuntime(this, ...)`, without an endpoint override. Activity, app lock and WorkManager share it. `AppRuntime` defaults to endpoint `local` and `BuildConfig.API_ORIGIN`.
- The selected files are `local.db` and `local.wrapped` under the Application's credential-protected `noBackupFilesDir`. Room uses the explicit database file; it does not select a database from an account or API hostname. SQLCipher uses a random secret wrapped by an Android Keystore key in an endpoint-derived namespace. The wrapping record format and Room schema version 1 are unchanged.
- Foreground `AppRuntime.use` opens the selected endpoint when no in-memory service exists. `EncryptedEndpointStore.open` can initialize an empty store if both selected files **and** its wrapping alias are absent. This creates storage, not a Signal/account identity. This existing behavior is unchanged by this diagnostic patch.
- Existing alias plus either missing file fails closed. Missing alias plus either existing file fails closed. Unwrap/database errors propagate; there is no destructive Room migration/fallback. Existing readable but empty records can open successfully and produce first-launch UI.
- Background initialization/sync checks user-unlocked state and both files before opening. App-lock read returns no policy when both files are absent; it does not pick another endpoint. Logout operates on network state; no compared change deletes the local identity records. Attachment state uses the same endpoint records and an endpoint-named attachment directory, not a replacement endpoint database.
- `local/key` and `local/device` drive the existing identity-presence diagnostic. Network registration, token and authentication credential records are separately namespaced by a digest of the API hostname *inside the same database*. Missing that namespace can explain registration/session/credential absence with identity still present. It cannot remove `local/key` or `local/device`.
- `7776d2b` adds new-account intent after explicit identity creation and account-recovery logic. Neither invokes identity creation during ordinary open nor changes the selected files. The recovery status change cannot itself explain `IDENTITY_PRESENT=false`.

## Answers to the six investigation questions

1. **Package changed?** No source change: both variants use application ID and namespace `org.ghostcloak.app`; no suffix or product flavors. Debug/release can still differ in signing and configuration. A differently signed APK cannot normally replace an installed package in place. This is not evidence the user uninstalled it.
2. **Origin changed?** No default change between compared commits. Debug defaults to staging `https://api.ghostcloak.org`, optionally overridden by `ghostcloakApiOrigin`. Release defaults empty, with a separate explicit `ghostcloakReleaseApiOrigin`; staging is rejected for release. These rules date to `bd9886d`. Actual Phone A build-time overrides are unknown. Production has no designated fixed hostname in source; diagnostics classify non-staging, nonempty origins as `other`, never guess production.
3. **Endpoint/profile changed?** No: normal endpoint remains `local`. No account chooser or origin-based endpoint selection exists in normal startup. `PROFILE_SLOT` below refers to this application endpoint category, not an Android user/profile identifier.
4. **Can old/new stores coexist?** Yes with distinct explicitly supplied endpoint names: the debug simulator uses two fixed isolated endpoints; instrumented tests also use isolated names. Each has separate database/wrapped files and wrapping namespace. Changing the Android OS user/work/clone profile or package also changes accessible app data and Keystore scope. No normal endpoint switch was found between these commits, and no alternate endpoint was inspected on Phone A.
5. **Safe detection possible?** A future authorized read-only inventory can check existence of known historical filenames without opening SQLCipher, unwrapping or changing them. Existence would not prove integrity, ownership or recoverability. This patch only checks the selected filenames; it implements no alternate-store enumeration, fallback, migration or copy.
6. **Original wrapping alias still present?** Unknown. An alternate endpoint namespace could leave it present but unused; another OS application sandbox could make it inaccessible. With the *same* selected namespace, an existing wrapping alias plus absent files causes an error, not successful empty initialization. New empty initialization therefore implies absence of that selected alias too. No alias inventory or key operation is added by diagnostics.

The current local, untracked Android Studio configuration selects module `GhostCloak.app`, ordinary APK deployment, no custom install options, and `CLEAR_APP_STORAGE=false`. This describes this workstation's current configuration only, not the historic Phone A installation. No Run configuration is committed. The app disables backup and uses no device-protected storage or Direct Boot path. A restored/reinstalled environment therefore cannot be assumed to carry the original private files/Keystore.

## Debug diagnostics and interpretation

Filter Android Studio Logcat with `tag:GhostCloakStore`, select the Ghost Cloak process, then cold-open the existing installation. Capture the first block and the existing `GhostCloakAccount` presence block. Do not press Create identity, clear storage, uninstall, rename an account or run recovery to gather this evidence.

`LocalStateDiagnostics` surrounds the existing selected-store open exactly once per runtime initialization attempt, under the existing runtime mutex. It observes filenames before the opener runs; it neither opens nor creates files itself. Fields:

| Field | Meaning |
| --- | --- |
| PACKAGE_NAME / BUILD_TYPE | Compile-time application ID and build type |
| API_ORIGIN_CATEGORY | `staging`, `empty`, or `other`; no URL or host logged |
| PROFILE_SLOT | `default` for the fixed normal endpoint, otherwise `nondefault`; no name logged |
| DATABASE_EXISTS / WRAPPED_KEY_EXISTS | Selected file existence immediately before open |
| ENDPOINT_STORE_EXISTS | Both selected files exist; not a claim of readability/identity |
| STORE_OPEN_RESULT=missing | Pre-open observation: one or both selected files absent |
| STORE_OPEN_RESULT=existing | Existing opener returned successfully with both files previously present |
| STORE_OPEN_RESULT=new | Existing opener returned successfully with both files previously absent |
| STORE_OPEN_RESULT=error | Presence observation failed, open threw, or successful open followed an inconsistent partial-file observation |

`missing` can be followed by `new` or `error`; it is not a request to create anything. A prior attempt may already have initialized an empty store, so `existing` plus identity false does not prove historical identity deletion. An open failure is rethrown unchanged; diagnostic sink failures are swallowed. No paths, raw endpoint names, aliases, IDs, values, hashes or exception text are emitted. Release compiles a separate pass-through implementation with no logging or filesystem probes. No automatic scanning or recovery has been added.

## Next decision, not an implemented fix

First compare the sanitized blocks from Phone A and B and verify the earlier installed build/variant if available. Same package/default slot/staging with existing readable files and absent identity shifts the investigation to historical file/record state rather than network registration. Missing/new selected files shifts it to sandbox/installation or earlier endpoint provenance. A partial/missing-key error requires preservation and explicit investigation, never replacement. These observations alone cannot justify a safe migration or identity recovery. Retain current files and credentials unchanged until the original store and its authenticated key binding can be established.

## Validation

Unit tests cover selected-file presence without file creation, byte preservation, no alternate selection, sanitized output, one invocation, unchanged failures, broken logging sinks, and release pass-through without file probes. The physical Phone A regression is not reproduced merely by these synthetic tests. Backend/PostgreSQL behavior is unchanged and is outside this diagnostic patch.

Executed with `--dependency-verification strict`:

- `:test-support:test :app:testDebugUnitTest :app:testReleaseUnitTest`: 136 tests, zero failures, including five new diagnostic tests.
- `:app:assembleDebug :app:assembleRelease`: passed.
- API 37 emulator `AccountRecoveryTest`: 3 tests passed; `EndpointStorageTest`: 10 tests passed; `NetworkStorageTest`: passed. These cover original credential/identity preservation, encrypted database reopening and network namespace separation.
- An initial combined emulator invocation incorrectly applied the app-only class filter to the storage module and failed there. Storage was rerun unfiltered successfully; this was a test invocation error, not an application failure.

No physical phone state was opened or changed, no recovery endpoint was invoked on staging, and no backend deployment was performed. Existing unrelated launcher assets and root Gradle comment edits are excluded from this commit.
