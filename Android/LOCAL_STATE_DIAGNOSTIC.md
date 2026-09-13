# Phone A local-state regression — diagnostics only

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
