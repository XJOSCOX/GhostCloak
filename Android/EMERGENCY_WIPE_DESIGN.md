# Phase 1K — emergency wipe security audit

Audit date: 2026-10-03. Baseline: `9b09739`.
Status: **1K.1 audit/design checkpoint; stopped before implementation** under
section 39 of the complete Phase 1K request. Emergency wipe is NOT implemented or
enabled. The initial truncated request has been superseded by the complete request.
This document records findings and a proposed implementation, not an assertion
that the current app already has safe reset semantics.

## Implementation stop condition

Current startup cannot safely distinguish an interrupted destructive reset from
ordinary store availability/fresh installation: there is no durable reset journal
or mandatory startup gate. `GhostApplication` starts runtime initialization and
cleanup independently of Activity unlock. Attachment and debug demo owners can
remain live outside the runtime mutex. This meets the requested stop condition
`app cannot distinguish fresh install from interrupted wipe safely`; do not bolt
key deletion onto the PIN callback or make missing files imply a reset command.

The key hierarchy is compatible with a future coordinated cryptographic reset;
no fundamental need for a backend change was found. Before 1K.2/1K.3, implement
and validate the proposed startup/operation journal gate, cancellation/drain APIs,
demo-owner disposal, provider revocation and generation fencing. Those changes
must demonstrate that a partial reset cannot reopen/recreate usable old state.
This is a bounded prerequisite rework, not permission to weaken erasure, recovery
or authentication. No emergency configuration, credential or deletion path is
added in this checkpoint.

## Security boundary

The intended property is destruction of this installation's keys needed to decrypt
encrypted local Ghost Cloak state, followed by deletion of local artifacts.
No physical flash sanitization, forensic irrecoverability or guaranteed RAM erasure
is promised. Plaintext staging and external viewer copies are outside the database
key boundary. Deleting a wrapping key does not revoke an already open SQLCipher
connection or previously copied decrypted keys. Quiescence and revocation are
required as well as persistent key destruction.

Backend deployment, server migration and nginx changes: none expected. No server
wipe request, event, timestamp, telemetry or remote recovery is proposed.

## Actual key hierarchy

1. `EncryptedEndpointStore` creates a random 32-byte SQLCipher database secret.
2. A non-exportable AES-256 Android Keystore key named `ghost-cloak.db.<endpoint>`
   wraps that secret with AES-GCM. The alias is authenticated as AAD.
3. Credential-encrypted `noBackupFilesDir/<endpoint>.wrapped` stores the versioned
   wrapped secret (61 bytes). Android `AtomicFile` may also leave recovery artifacts.
4. Room/SQLCipher opens `<endpoint>.db` using the unwrapped secret. The Java secret
   array is cleared on store close; native SQLCipher/Signal/JVM copies are not
   covered by a guarantee of zeroization.
5. Signal private identity, private prekeys, session/ratchet records, messages,
   requests, contacts, blocks, attachment descriptors/keys and network records are
   values in the encrypted `endpoint_records` table. They have no separate
   per-record Keystore aliases in the audited code.
6. Device authentication additionally uses a non-exportable P-256 Keystore key.
   `EndpointNetworkState.registration()` creates an alias of the form
   `ghostcloak.auth.<audience digest>.<local device identifier>` and retains its
   reference/public key inside encrypted network records. Never log actual aliases
   or those suffixes. Legacy software `auth-private` is also an encrypted record.
7. App-lock PBKDF2 verifier/configuration is `app/access-lock` inside the same
   database, not a separate Keystore credential. BiometricPrompt is a UI unlock
   gate and does not supply the database decryption key.
8. Attachment keys are fresh per attachment, retained in encrypted descriptors and
   transfer records. Upload/download bodies are encrypted with those keys; losing
   the DB key removes access to those retained local descriptors after live owners
   are revoked. Other endpoints' descriptor/key copies remain independent.

Critical persistent boundary: all Ghost Cloak DB wrapping aliases AND device-auth
aliases are deleted and absence is verified, with old owners denied access and
closed. Key enumeration failure is not successful absence. Destroy wrapping keys
before attempting best-effort file removal. Do not falsely report completion if
any required key destruction is unverified.

## Actual storage inventory

| Owner | Location / representation | Reset treatment |
|---|---|---|
| Main encrypted endpoint | `noBackupFilesDir/local.db`, `local.wrapped` | Destroy wrapping key, close owner, remove DB and wrapper artifacts |
| SQLCipher artifacts | DB `-wal`, `-shm`, `-journal`; wrapper AtomicFile recovery files | Remove with corresponding endpoint; inspect actual AtomicFile/runtime behavior in tests |
| Debug local demo | `demo-alice.db/.wrapped`, `demo-bob.db/.wrapped` | Separate wrapping keys and open stores; must close and reset cached demo owner |
| Additional endpoint fixtures/legacy files | Valid endpoint names accepted by `EncryptedEndpointStore` | Inventory matching app-owned DB/wrapper files and aliases without opening them; do not restrict erasure to `local` |
| All private records | SQLCipher `endpoint_records` | Whole encrypted store reset, not row-by-row deletion |
| Encrypted attachment caches | `<endpoint>-attachments/upload` and `/download` under noBackup | Delete after revoking/draining transfers |
| Decrypted attachment verification scratch | `<endpoint>-attachments/scratch` | Best-effort immediate cleanup after access is revoked; no physical erasure claim |
| Media preparation/presentation | `noBackupFilesDir/media-presentation` | Cancel source reads/normalization/preview jobs, revoke lease, remove files |
| Photos / previews | In-memory bitmap/InlinePhotos state | Revoke access, clear references and dispose private UI |
| External document presentation | `AttachmentViewerProvider` and short-lived URI/FD lease | Revoke URI permission and lease; already opened FD/external copy cannot reliably be recalled |
| App preferences | `appearance`, `notification-permission` SharedPreferences | Non-sensitive; define ordinary fresh-state preference reset explicitly |
| WorkManager | AndroidX-owned scheduler database/jobs | Cancel Ghost Cloak unique work, deny stale execution; never delete scheduler files underneath live WorkManager |
| Notifications | OS notification plus encrypted ledger | Cancel OS notifications and remove encrypted ledger with DB |
| Saved UI / process owners | Activity state, ViewModel, navigation state, runtime, controller, demo singleton | Invalidate generation and recreate clean state; old routes/notification intents must not reopen old private state |

The audited runtime always selects endpoint `local`; API origin only namespaces
network records, not DB filenames. This phase must not change normal update/store
selection. No backup/export DB writer was found in the audited production paths.
That does not prove that historical/rooted/OEM copies cannot exist.

Alias enumeration must use explicit validated Ghost Cloak namespaces, preserving
unrelated entries. DB aliases must match the store's validated endpoint grammar;
auth aliases must match the creator's full format. Never delete all Android
Keystore entries or use a loose arbitrary substring. Inventory can run without
decrypting stores and must still work after the main wrapping key is destroyed.

## Current concurrency gaps requiring implementation

`AppRuntime.use()` serializes core network/storage operations, but there is no
durable wipe fence. `GhostApplication.onCreate()` starts background initialization
and expiry cleanup before any reset state has been checked. WorkManager constructs
the shared runtime independently of Activity UI. `AppRuntime.close()` is not itself
mutex-protected and is not a wipe protocol.

Attachment upload/download work executes outside the runtime mutex under its own
transfer mutex. `AttachmentStore.invalidate()` cancels but does not await all
writers. `AttachmentPresentation.clear()` similarly cancels without joining jobs.
The viewer provider may obtain a lease outside conversation composition. The debug
demo singleton retains two open encrypted stores with no close/reset API.

Therefore a lock-screen callback which only deletes aliases/files is unsafe.
A coordinated reset barrier, cancel-and-drain APIs, debug-demo disposal and
startup/provider/worker guards must precede activation. Existing cooldown/session
generation fields are not an adequate durable wipe state machine.

## Proposed crash-safe state machine and order

Use a small versioned, credential-encrypted noBackup reset journal outside the DB
whose key will be destroyed. It contains fixed stages only, no identifiers, PINs,
secrets, timestamp or message count. Missing journal means ordinary startup;
malformed/unknown journal means fail-closed error, NEVER authorization to wipe.
Only successful exact emergency credential verification may create the intent.
The journal must be durably committed before destructive work. Atomic replacement,
file synchronization and crash behavior need explicit tests; an in-memory flag
or SharedPreferences.apply() alone is insufficient.

Proposed stages: `REQUESTED -> KEYS_DESTROYED -> CLEANUP -> COMPLETE`.
Steps are idempotent and re-enumerate verified owned targets on recovery.

1. Accept the exact emergency verifier at normal UNLOCK entry, persist reset intent
   and set the process-wide access fence. Never temporarily grant normal unlock.
   Failure to persist authorization is not a successful reset.
2. Hide/dispose the private subtree and clear ViewModel/preview references. Deny
   new runtime/store/key creation, renewal, outbox, prekey publication, provider
   leases and notification publication. Invalidate generation and biometric tickets.
3. Initiate WorkManager cancellation, cancel live foreground/background/media work,
   revoke viewer leases and drain owners. Do not depend solely on asynchronous
   WorkManager cancellation. Stop writers before files can be removed/recreated.
4. Delete and verify absence of every inventoried wrapping and device-auth key.
   Continue independent critical deletions despite another deletion failure, but
   keep the journal/access fence if any required destruction cannot be verified.
5. Close stores and clear accessible secret buffers, including demo owners. Close
   failure must not expose old state; process termination/restart can release live
   handles, with startup fenced by the durable journal. Recovered startup must run
   reset before opening any old store or generating a new key.
6. Delete wrapper/DB artifacts, encrypted caches and all known plaintext staging,
   previews and journaled attachment files. Bound paths to verified app-private
   roots and do not follow symlinks. Keep failed targets pending for retry.
7. Confirm Ghost Cloak jobs are cancelled, revoke stale navigation grants, cancel
   notifications and remove old lock/emergency/network/account linkage with stores.
8. Reinitialize ordinary fresh/unlinked UI only with new owners/generation and no
   automatic account creation or reconnect. Keep cleanup state until checked
   deletion succeeds, or use an explicitly separate fresh namespace if allowing
   fresh use before encrypted-only cleanup completes. Never clear the journal and
   reopen `local` over undeleted encrypted DB/wrapper files.

Every app entry point checks the journal before normal initialization, including
Application, Activity, Worker, notification dismissal and viewer provider. An
interrupted reset retries offline and exposes no private content. A worker already
in flight at activation may have sent a request; this cannot be undone. The
fence prevents subsequent calls and stale completion/publication. No emergency
request is sent to the server.

## Credential construction and false-activation protection

Current normal PIN: 6–64 numeric characters; PBKDF2-HMAC-SHA256, 600,000 iterations,
16-byte random salt, 256-bit verifier, MessageDigest.isEqual comparison. Failed
attempt reservation is persisted before KDF execution; after three failures the
monotonic cooldown increases from 5 seconds to at most 5 minutes and is restored
across boot. Configuration version 1 has a strict 128-byte decode limit.

Proposed emergency verifier reuses PBKDF2-HMAC-SHA256 with the same work factor,
fresh random salt and an explicit emergency-specific salt domain. Do not alter
the legacy normal verifier derivation. Never persist a reversible/plaintext PIN.
Use a bounded new lock configuration version; read old version 1 as emergency Off.
Invalid/truncated/unknown config fails closed, never activates a reset. Schema
version 1 SQLCipher key-value storage can hold this without a Room migration.

Require matching confirmations, at least six digits, reject repeated single
digits and simple ascending/descending sequences. Compare the proposed emergency
PIN against the current normal verifier, and proposed normal PIN against the
current emergency verifier, so later normal-PIN changes cannot create equality.
Reuse authenticated sensitive-settings management grants for enable/change/disable.
Disabling removes only the verifier/config field and never invokes reset.

When enabled, check both verifiers on each eligible normal lock-screen attempt
without an early return after normal success. Share one persisted attempt budget
and generic error; do not charge twice. Never trigger on failed PIN, cooldown,
biometric callbacks, missing credentials, corruption, normal update or MANAGE/
ENROLL entry. Exact emergency success must not unlock. Recheck lifecycle/ticket
after expensive verification before consuming authorization. No constant-time
claim for Android/JVM/UI scheduling is appropriate.

For an initial mode policy, require normal PIN/combined lock before enabling so
the ordinary PIN surface can be reused without a special visible emergency button.
Biometric-only/OFF compatibility needs an explicit policy: adding a PIN surface
only when emergency is enabled would reveal feature configuration. Never silently
discard emergency configuration when changing normal lock modes/timing. This
decision must be resolved in the complete implementation instructions.

## Recovery, backups and limits

Existing account recovery proves possession of the retained device-auth Keystore
key and encrypted local binding; username is not sufficient. Destroying that key
and the DB must disable this route. Do not retain a hidden recovery token/key or
automatically regenerate an old identity. Separately held future recovery proof
would not restore wiped Signal/session/history without a separately designed
explicit encrypted backup. Normal logout remains distinct and keeps identity.

Manifest sets allowBackup=false and references exclusions for root/files/database/
preferences/external in both legacy backup rules and Android 12+ cloud/device
transfer rules. Sensitive production stores are in noBackupFilesDir. The reset
journal must also be there. These controls do not prove eradication of privileged
OEM/root/system snapshots or copies another endpoint/application already holds.

Recipient messages, exported files, existing external FDs, previously captured
plaintext and server public/encrypted records are outside the local reset boundary.
Server mailbox/blob retention remains unchanged. UI wording must distinguish local
key/data destruction from account deletion, with no wipe-completed announcement on
the fresh-state screen. Production logging/telemetry: none.

## Validation required before shipping

Disposable AVD only, no physical account resets and no VPS access. Test normal/wrong
PIN and all biometric success/failure paths never reset; exact emergency PIN resets
offline; unequal PIN policy in both directions; enable/change/disable management
authentication; one durable shared attempt budget; old configuration/update survives
without activation; no normal-PIN/biometric success cache after process death.

Seed real encrypted DBs and alias-backed credentials, Signal sessions, contact/block/
request/outbox/replay/notification/attachment state and all known caches/scratch.
Test alias isolation, unreadability with retained encrypted bytes after deletion,
no silent key regeneration, viewer lease revocation, notification navigation and
stale worker/transfer/renewal completion after the reset fence.

Inject failure/process restart before and during each critical stage: journal
commit, alias deletion/enumeration, DB close, DB/wrapper/WAL cleanup, attachment
cleanup, job cancellation, notification cancellation and fresh UI reset. No
interrupted state may open old content or reconnect. Test marker corruption fails
closed without authorizing erasure, absent marker means ordinary update, and fresh
state cannot reopen or collide with undeleted old files. Document residual encrypted
bytes and plaintext/external-copy limitations without physical erasure claims.

Run JVM app-lock/identity tests, disposable Android app-lock/storage/background/
notification/attachment tests, debug/release builds and strict verification after
implementation. Existing tests validate baseline guards but cannot prove emergency
wipe safety until the new reset-specific tests exist.

### Audit checkpoint validation results

Existing baseline passed: 135 JVM messaging tests, four attachment JVM tests,
33 debug and 26 release app unit tests, debug/release builds and strict dependency
verification. Dedicated disposable API 37 AVD: 10 encrypted-storage tests and the
one AppLockStorageTest passed. The storage suite includes missing-key refusal,
tampered-wrapped-key rejection and prevention of silent identity replacement.
The merged debug manifest retains allowBackup=false and both exclusion resources.

No emergency/destructive wipe tests ran: no reset engine or emergency verifier was
implemented because of the section 39 stop condition. No real-phone tests, VPS
connections, account resets, key deletions outside synthetic test fixtures or
backend deployments were performed. Unrelated local tooling and launcher-artwork
edits remain outside this documentation commit.

## References

Code evidence: EncryptedEndpointStore.kt, KeystoreDeviceAuth.kt, NetworkAccount.kt,
AppRuntime.kt, GhostApplication.kt, BackgroundSync.kt, LockConfiguration.kt,
AppLockController.kt, LockScreens.kt, DeveloperMode.kt, AttachmentStore.kt,
AttachmentPresentation.kt, AttachmentViewerProvider.kt and Android backup XML.

- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)
- [Android backup overview](https://developer.android.com/identity/data/backup)
- [WorkManager cancellation](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/manage-work)

Keystore non-exportability is not a promise that cached unwrapped data is revoked.
WorkManager cancellation is cooperative, so a runtime generation/fence is required.
