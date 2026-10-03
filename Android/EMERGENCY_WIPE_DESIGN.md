# Phase 1K — emergency wipe design and infrastructure

Current status: **1K.2C Safe Exit UX and protected Settings implemented; debug-only non-destructive arming, no destructive wipe engine**.

Audit date: 2026-10-03. Baseline: `9b09739`.
Historical status: **1K.1 audit/design checkpoint; stopped before implementation** under
section 39 of the complete Phase 1K request. Emergency wipe is NOT implemented or
enabled. The initial truncated request has been superseded by the complete request.
This document records findings and a proposed implementation, not an assertion
that the current app already has safe reset semantics.

## Phase 1K.2C — Safe Exit and protected Settings

Baseline: `51fba806412e31be301e9466d21ceb5ed83caa5f`. The user-facing name is
**Safe Exit**. Internal Emergency/Cryptographic Wipe terminology, classes, journal
and configuration names stay unchanged. The description and permanent-destruction
warning describe the intended future feature; the adjacent explicit debug-preview
warning states this build only permanently fences access and deletes nothing.
Destructive readiness stays false; release settings/arming remain unavailable.

### Settings boundary and lifetime

With App Lock enabled, an already-unlocked app session is insufficient to open
Settings. The route renders only “Authenticate to open Settings” until a normal
PIN or configured strong biometric succeeds for the distinct SETTINGS purpose.
A Safe Exit PIN or wrong PIN grants neither Settings nor wipe authority in this
challenge. Cancellation/back returns to the previous screen; no Settings subtree
is composed behind the challenge. App Lock OFF allows Settings without inventing
a credential. Unready/unavailable controllers never authorize protected content.
Production navigation always obtains the Application-owned controller through the
global AppLockGate; missing controller fails closed for protected routes.

A SETTINGS grant is memory-only, generation-bound and expires **three minutes from
authentication**, with no extension for taps/rows. A scope-owned timer removes the
protected subtree at expiry; predicates check monotonic time. It covers Settings,
Blocked Contacts, App Lock and Safe Exit routes. Traversing those rows/back does
not repeat Settings authentication while the grant remains valid. Leaving this
route family invalidates it **immediately** (a conservative meaningful-exit policy),
including bottom-tab changes, notification navigation or normal back navigation.
Any Activity stop, including rotation, clears authorization; background/lock,
process death and secure-operation quiesce also revoke it. Returning and satisfying
global App Lock never restores an old Settings grant. Secure window/Recents behavior
is unchanged and applies to these screens. Route cancellation also advances a
purpose-specific authorization epoch, rejecting late PIN/biometric completions
even when the global unlocked lifecycle generation has not changed.

### Separate critical-action authorization

SETTINGS authentication grants **no MANAGE or biometric ENROLL proof**. Opening
App Lock administration clears any previous management grant before exposing its
configuration; changing normal PIN or disabling App Lock requires a fresh configured
normal PIN/strong biometric. Safe Exit shows On/Disabled and action buttons only;
choosing enable/change/disable starts a new management challenge, clearing older
normal/administration proofs. Normal management lasts at most **60 seconds** from
authentication, is generation-bound, and does not survive background/disposal.

Initial enable requires PIN-capable App Lock, fresh normal management authentication,
explicit irreversible acknowledgement and matching 6–64-digit new Safe Exit PINs
that differ from the normal PIN. Change requires that fresh normal credential
**plus the current Safe Exit PIN** before exposing new PIN entry, then matching
new PINs/acknowledgement. Disable requires fresh normal authentication **plus the
current Safe Exit PIN plus explicit confirmation**. Normal PIN or biometric alone
can neither replace nor remove an existing Safe Exit verifier.

The explicit current-PIN administration check never unlocks or arms. It reserves
the existing shared durable failed-attempt/cooldown budget before KDF execution.
Successful verification creates a private, short-lived memory proof bound to the
normal management grant, lifecycle generation and exact current verifier object.
Proofs are cleared on a new management challenge, change/disable, screen disposal,
background, expiry or quiesce. Slow operations recheck generation and fresh normal
proof before commit. Direct controller administration APIs still require the same
credentials; there is no UI-only bypass. No failed-attempt destruction or permanent
credential lockout is introduced. Mutable credential arrays are cleared in finally;
UI PINs remain transient remember state, never saved/navigation/ViewModel state.

Existing PBKDF2, dual lock-screen verification, equality checks, SQLCipher record
format 2 and journal behavior are unchanged. PIN app lock cannot be removed while
Safe Exit is enabled (including switching to biometric-only). Final policy is
**Option B**: “Disable Safe Exit before turning off App Lock.” The user must first
disable Safe Exit using both credentials, then separately reauthenticate to turn
off App Lock. Equality still rejects with “Choose a different PIN.”

There is **no forgotten Safe Exit PIN reset** using a normal PIN, biometric, email,
server or third party. Losing it prevents ordinary change/disable, even if the app
can still be unlocked normally. A future explicit full local reset would need its
own reviewed design and cannot recover old identity/history or silently regenerate
keys. No such recovery/reset path is implemented here. Biometric has only normal
Settings/management/unlock roles; it cannot substitute for the Safe Exit secret.

Audit of current Settings actions: there is no recovery-secret export, identity/key
replacement or full-reset action. Connection actions retain existing explicit
logout/credential ownership checks; ordinary display/request/notification controls
use the bounded Settings boundary. Any future account/device-protection weakening
or recovery action must require a separate fresh management proof, not SETTINGS.

### Validation and rollout

Unit coverage exercises already-unlocked attackers, wrong/Safe Exit Settings PINs,
normal/biometric Settings authentication, OFF mode, exact timeout, route exit,
background, process recreation, proof separation, both-credential change/disable,
forgotten-secret refusal, shared throttling and App Lock disable/equality policy.
Disposable AVD coverage verifies the absent private subtree, cancel/previous route,
row/back traversal, critical reauthentication, background/timeout, current-PIN-first
change and enable/disable confirmation. User-visible literals are audited for old
branding. Existing Phase 1E/1F/app-lock/arming tests continue to run.

No backend deployment, API/server DB change, Android SQL migration, new permission
or network event is required. The verifier/configuration format remains 2. No VPS
access or real-account phone tests; no destructive implementation or release
readiness change. Test counts are recorded in DEVELOPMENT.md.

## Phase 1K.2B — implemented PIN enrollment and safe arming (historical baseline)

Baseline: `9602111`. This is **not a usable destructive wipe release**. Settings →
Privacy & Security → Emergency Wipe is visible only in debug. Its default is
Disabled. The future-feature explanation is paired with an explicit preview
warning: an exact emergency PIN permanently fences local access in this debug
build and cannot restore access, **but deletes nothing**. Never test arming on an
account-bearing phone. The only implemented progression is ARMED → QUIESCING →
KEY_DESTRUCTION_PENDING. No reset/clear/completion UI was added.

### Credential configuration and persistence

The dedicated app-access configuration remains the encrypted key-value record
`app/access-lock` in the existing SQLCipher `noBackupFilesDir/local.db` (synthetic
instrumentation uses isolated endpoint names). Its existing DB secret is protected
by `local.wrapped` and Android Keystore; emergency PINs do not gate database
unwrapping. No additional plaintext preference/file is created. Binary configuration
format **2** adds only an emergency-verifier presence/enabled boolean, fixed KDF
iteration parameter, 16-byte salt and 32-byte verifier to the normal app-lock
configuration. Decoder accepts legacy format 1 with emergency disabled, validates
fixed costs/mode consistency/trailing bytes, and bounds the record to 192 bytes.
SQLCipher/Room schema stays unchanged; this is record versioning, not a SQL migration.

Enable/change/disable use the existing single encrypted-record transaction. This
keeps normal and emergency configuration consistent atomically. Database files and
wrapped keys remain in app-private no-backup storage, with existing manifest and
cloud/device-transfer exclusions. Credentials never enter server state, logs,
notifications, analytics, saved UI state or an exported component. No API request
is invoked by enrollment, credential change/disable, verification or arming.
Independent ordinary background sync can still run until the operation fence closes.

Both PIN types use the audited platform PBKDF2-HMAC-SHA256 primitive: **600,000
iterations**, independent CSPRNG **16-byte salts**, **256-bit derived verifiers**, and
`MessageDigest.isEqual`. No Signal or attachment key participates. No new weak-PIN
heuristic was invented: both require 6–64 ASCII digits and confirmation. Six digits
have low entropy against an offline attacker despite the KDF; longer PINs are better.

Mutable input arrays and PBEKeySpec password copies are cleared in finally blocks;
derived candidate bytes are zeroed after comparison. Change/disable drops and zeroes
the previous in-memory emergency verifier only after successful record commit.
Compose input Strings are transient `remember` state (never saveable/ViewModel);
submit, background and disposal clear references. JVM/IME String copies cannot be
promised erased. FLAG_SECURE stays in force. Removing an encrypted record field is
not a claim of physical SQLite/WAL/flash erasure; any old pages remain DB-encrypted.

### Management and equality safety

Enrollment requires PIN-capable app lock, an unlocked/visible UI, fresh normal
management authentication (the existing 60-second generation-bound grant), explicit
“I understand this cannot be undone” acknowledgement, matching valid new PINs, and
inequality with the normal verifier. Change additionally verifies the current
emergency PIN with the shared persisted attempt/cooldown budget. Disable requires
fresh normal management authentication and an explicit confirmation dialog, then
atomically removes the verifier without touching identity, messages or attachments.
An emergency PIN entered for MANAGE/ENROLL never grants access or arms anything.
Biometric management authentication is the existing configured strong biometric,
not device credential recovery or a new identity provider.

Changing the normal PIN checks the new candidate against the enabled emergency
verifier and rejects equality with “Choose a different PIN.” Conversely emergency
enrollment/change rejects a normal-PIN match. Removing the normal PIN unlock method
(OFF or biometric-only mode) requires disabling Emergency Wipe first, so the feature
cannot become inaccessible or share a credential accidentally. New normal PIN
configuration preserves the emergency verifier. Backgrounding invalidates management
grants; slow enrollment/change rechecks the generation and grant before commit.

### Dual verification and timing limits

After reserving the existing durable shared failed-attempt budget, an enabled
configuration checks the normal verifier **and then the emergency verifier**, always
using two equivalent-cost primitives for every valid-format attempt. Normal success
never skips the second check. Only after both comparisons does the controller select
normal unlock, exact emergency arming (UNLOCK purpose only), or failure. Format-invalid
inputs and cooldowns skip both KDFs. Biometric success follows only the normal grant
path and cannot arm. No prefix/fuzzy/attempt-threshold/timeout trigger exists.

This removes a gross *normal success versus emergency verification* cost split;
it is **not constant-time Android execution**. Scheduling, JIT/provider behavior,
persistence and post-authentication actions differ. Enabled configurations perform
two KDFs versus one when disabled, so a local actor able to benchmark sufficiently
can potentially infer enrollment; an authenticated user can already see it in
Settings. Do not claim the existence of a wipe PIN is unobservable, or that remote
attackers can measure this local-only path. PIN entropy/throttling still matter.
Tests count primitive invocations for wrong/normal/emergency input rather than
asserting flaky wall-clock thresholds or weakening the KDF for speed.

### Durable arming, failure and restart

Exact emergency success first invalidates normal access/grants and never calls
normal `grant`. It then writes NONE → ARMED through the existing gate on IO.
The gate closes before writing. AtomicFile write plus explicit checked file fsync,
rename, parent-directory fsync and canonical readback must succeed before publishing
ARMED. The independent recovery scope observes **only known committed non-NONE
states**, not the provisional/failure CORRUPT state. This avoids a write-failure
handoff and avoids draining the same coroutine/mutex that performed verification.
The coordinator cancels/joins existing owners and stops at KEY_DESTRUCTION_PENDING.
The Activity root renders generic maintenance text, never the private route.

On write/file-sync/directory-sync/readback failure, current-process state stays
CORRUPT/unavailable, no normal unlock and **no coordinator handoff**. No account/key
reset is attempted. If a late failure left a canonical ARMED file, a fresh process
can validate that surviving journal and resume; if an uncommitted first write leaves
no artifact, it is not durable arming, and restart still uses normal app-lock policy.
Unreadable/partial/unknown files remain fenced without invented authorization.
AtomicFile/device fsync cannot guarantee behavior of broken hardware.

Process death after committed ARMED cannot reopen runtime/store/media/lock owners:
startup reads the journal first, resumes non-deleting shutdown, and remains fenced.
Biometric success is never cached across process death. There is no timer/cancel
button that returns an armed journal to NONE.

### Release safety, compatibility and validation

`BuildConfig.EMERGENCY_PIN_ARMING_ENABLED` is **true only in debug, false in release**.
`EMERGENCY_WIPE_DESTRUCTIVE_READY` is **false in both variants**. Release has no
settings entry and ignores an emergency match as an unlock credential, even if a
same-package debug upgrade previously enrolled it. The normal PIN remains usable.
Release retains configuration/equality protection; it does not erase enrollment.
A durable pre-existing armed journal still gates release startup; never transfer
an armed disposable-debug install into normal use. No property/intent/exported
component enables release arming. Phase 1K.3 must separately review release enablement.

Format-2 writes cannot be read by older format-1-only app-lock code; downgrade to
those binaries fails closed. Do not clear data or silently downgrade config to
work around that. No backend deployment, server migration or Android SQL migration
is required. Destructive action, key/data deletion, account replacement, server
wipe and identity recovery are still absent.

Validation uses equivalent-primitive host tests in debug/release, isolated encrypted
store/Compose/journal tests on the disposable AVD, and existing foreground/background,
renewal, notifications, local deletion, attachments and app-lock tests. Fault
injection covers write, file fsync, directory fsync and readback independently.
Existing startup probes check real-process journal recovery separately. Results
and exact commands are recorded in DEVELOPMENT.md.

## Phase 1K.2A — implemented non-destructive prerequisites

Baseline for this slice: `55f3269`. There is no emergency PIN, wipe engine, key
deletion, database/cache deletion, account reset, logout, configuration clearing,
server request or fresh-install finalization in this implementation. Existing
normal scratch rollback/expiry behavior is unchanged; it is not repurposed as wipe
cleanup. The previous stop condition below describes the 1K.1 baseline.

### Journal and state machine

`DurableLocalOperationJournal` uses credential-encrypted app-private
`noBackupFilesDir/local-operation.v1`, outside SQLCipher and independent of keys.
Only the fixed ASCII version prefix `GCLO1:`, enum name and newline are stored.
There are no identifiers, credentials, timestamps, counters or content. Missing
base **and** missing `.new`/`.bak` mean NONE. A populated NONE is invalid. Reads are
bounded to 65 bytes and require exact canonical bytes. Unreadable, truncated,
oversized, unknown-version/state or incomplete-first-write artifacts are CORRUPT.

State enum: NONE, ARMED, QUIESCING, KEY_DESTRUCTION_PENDING,
KEY_DESTRUCTION_COMPLETE, STORAGE_CLEANUP_PENDING, FINALIZING, COMPLETE, CORRUPT.
This slice can simulate ARMED through the debug-source in-process test hook;
the coordinator advances ARMED -> QUIESCING -> KEY_DESTRUCTION_PENDING and stops.
No later destructive/finalization transition is executable. **Every state except
NONE, including COMPLETE, blocks normal access.** Corruption does not grant wipe
authorization and is never converted to a destruction checkpoint.

Writes use Android AtomicFile startWrite/finishWrite/failWrite, followed by an
explicit checked output FileDescriptor.sync, parent-directory Os.fsync and canonical readback before success. One
process-wide monitor serializes transitions, opens and synchronous record/key
access. Android AtomicFile provides no locking itself. The in-memory fence closes
before writing; any write failure keeps this process fail-closed. A successful
arming is acknowledged only after durable write/sync/readback. An uncommitted first
write with no surviving artifact is not a durable arming; this phase does no
destruction before or after it. Power-loss guarantees still depend on the OS/device
honoring fsync. No absolute hardware-erasure/durability claim is made.

The journal is excluded from backup by noBackupFilesDir and the unchanged manifest
allowBackup=false plus all cloud/device-transfer exclusions. It is not stored in
device-protected storage. No Direct Boot path is added; components remain in the
credential-unlocked startup regime. The journal is not a forensic-authenticity
primitive against a rooted actor who can alter app files.

### Startup, operation gates and restart

`GhostApplication.onCreate` reads the gate before starting app-lock collection,
runtime initialization or expiry work. With a known non-NONE journal it starts only the
local recovery coordinator; CORRUPT remains fenced without coordinator authorization. It never initializes runtime/store/engine/media/lock
owners just to shut them down. MainActivity retains FLAG_SECURE but skips lock and
media initialization and renders only generic maintenance text. The private Compose
subtree is absent, including notification-requested navigation. ViewModels are not
created in that branch. Existing ViewModels are registered for cancellation,
awaited termination and snapshot/reference removal during a live simulation.

`BackgroundSyncWorker` checks the gate before even resolving its lazy shared runtime.
The sole unique work is `ghostcloak-background-sync`; its automatic class-name tag
contains no private input. Scheduling admission shares the fence monitor.
Cancellation awaits WorkManager's Operation result and verifies all matching work
rows are terminal. This is a scheduler acknowledgement, not proof that a running
HTTP call has finished: runtime operation leases are separately cancelled/joined.
No other Ghost Cloak work name/tag exists in the current code.

AppRuntime.use and transfer operations are coroutine leases admitted only in NONE.
Polling stops through the ViewModel scope and checks the runtime fence each cycle.
NetworkController checks the fence at operation and actual transport entry;
StreamingBlobClient uses the existing eligibility/checkpoint callbacks. Thus sends,
FETCH/ACK/status, login/renewal, lookup, capability/prekey publication and transfers
cannot be newly admitted after arming. Work already admitted before arming is
cancelled and fully awaited; a request already on the wire cannot be recalled.
Existing connection timeouts bound blocking control HTTP. Blob sockets disconnect
on cancellation and their watchdog is now cancelled **and joined**.

`EncryptedEndpointStore.open` obtains the Application's LocalStateAccess fence
before key unwrap/create or SQLCipher/Room opening. Its record methods and
transactions also use the fence. An in-progress synchronous open/transaction must
finish before arming can commit; later attempts fail. Closing is exempt so existing
handles can be released. Signal uses these fenced records: identity loading, prekey
use and session-store reads cannot resume through a stale service. Device-auth
Keystore publicKey/sign are fenced separately. No crypto format or key deletion
behavior changed.

Media eligibility, picker work, document preparation and provider entry points are
fenced. New decoding/rendering/transfer work is denied; live jobs are cancelled and
awaited. Ghost Cloak-owned source streams and provider descriptors are explicitly
closed. Viewer grants are revoked; files are left in place by quiesce. Already
duplicated external file descriptors/copies are outside Ghost Cloak's revocation
boundary, not evidence that our owned descriptors are still open.

On process death all volatile owners/completion evidence disappear. Relaunch reads
the durable checkpoint, repeats idempotent non-deleting shutdown and remains gated.
ARMED/QUIESCING resume to KEY_DESTRUCTION_PENDING after success; later checkpoints
and corruption remain unchanged. Any failed owner or journal write leaves the
maintenance screen and completion false. There is no retry timer that reopens state.
Corrupt journals require explicit future reviewed recovery; current code never
repairs, deletes or treats them as NONE. No Cancel/reset control is exposed.

### Complete ownership and shutdown inventory

| Subsystem | Owner/class and retained state | Stop and completion evidence |
|---|---|---|
| UI/polling/manual API operations | GhostViewModel viewModelScope; snapshots, selected conversation, drafts in private Compose | Root gate removes private subtree; registered stop callback cancels/joins entire ViewModel scope, drops snapshots/selection; foreground polling exits and releases mutex; onCleared unregisters completed UI owners |
| Activity initialization/biometric UI | MainActivity lifecycleScope; AppLockGate/UnlockControls | Initialization is a leased operation; subtree disposal cancels biometric UI and composition jobs; gated lifecycle skips normal start/stop restoration; gate drain joins runtime initialization |
| App-lock controller | GhostApplication lockScope, AppLockController timer/config/grants | cancelAndJoin scope; acquire operations mutex after outstanding PIN/KDF operation completes; drop config/grants/prompt and publish unavailable state; no persistence write |
| Background startup/expiry/notification dismissal | GhostApplication backgroundScope/lifecycleScope | cancelAndJoin both scopes; notification receiver checks gate before runtime; expiry and notification reconciliation skip fenced work |
| Scheduled mailbox work | BackgroundSyncSchedule/BackgroundSyncWorker, WorkManager | cancel unique work; await Operation and query terminal rows; lazy worker gate; separately drain runtime leases for physical execution completion |
| Control HTTP, renewal, capabilities, prekeys, outbox, receipt loops | NetworkController and clients, owned by AppRuntime | Gate admission; cancellation/join of runtime leases; transport finally disconnects before lease completion; controller reference removed; no logout or auth-state write |
| Attachment transfers/verified staging | AttachmentStore through leased AppRuntime calls; StreamingBlobClient watchdog; verified attachments held inside presentation coroutines | Deny eligibility; cancel/join whole presentation scope and runtime leases, including watchdog; existing use/finally closes owned streams; no invalidate/reconcile wipe cleanup |
| Photo decode/picker preparation/preview jobs | AttachmentPresentation scope, source CancellationSignal/InputStream, InlinePhotos jobs/Bitmaps | Revoke visibility/epoch; clear snapshots/thumbnails; cancel source signal, close active input; cancelAndJoin entire parent scope, including jobs previously removed from child maps |
| Document provider/handoff | AttachmentPresentation lease, owned ParcelFileDescriptors, AttachmentViewerProvider | Gate provider access and register open under same fence; revoke URI grants; close every retained owned descriptor; failures prevent completion; external duplicates/copies cannot be recalled |
| DB/repositories/notification ledger/Signal | AppRuntime mutex, EncryptedEndpointStore SQLCipher handle/DB secret, ConversationService/SignalProtocolEngine/SignalStore/repository/router | Drain leases, acquire runtime mutex, close Room/SQLCipher (existing close zeroes in-memory wrapper secret), null engine/service/store/ledger/attachment references; router.close; no file/key deletion; no promise of universal JVM/native RAM erasure |
| Debug simulator | DeveloperMode singleton, DemoSession, two encrypted stores/engines/router and initialization ownership set | Runtime operations are drained first; new DeveloperMode.quiesce closes router and both stores, then clears singleton and runtime demo reference; failed initialization/close retains store ownership for retry; absent simulator implementation in release |
| Recovery coordination | GhostApplication recoveryScope, LocalOperationCoordinator | Dedicated scope never touches normal content/credentials and remains for maintenance; mutex coalesces repeated requests; completion flag only after all stop callbacks, drain and journal checkpoint succeed |

The coordinator must run outside an admitted state lease; self-drain is rejected
instead of deadlocking. Completion is process-local evidence of **owned subsystem
quiescence**, not key destruction or final reset. No timeout/sleep is used to claim
completion. Unknown/new owners must be added to this inventory and tests before a
future destructive slice. A blocked/hung close cannot be skipped to report success.

### Future COMPLETE policy and release safety

Future destruction must revalidate journal authorization, all owners and the
key/file inventory before advancing. Only after key destruction, cleanup and a
verified fresh namespace/state commit may COMPLETE be cleared to NONE. This slice
has **no production clear/reset API**, so even a test-installed COMPLETE stays
gated. A debug-source `LocalOperationTestHooks` permits in-process arming only; no
UI, exported receiver/activity/provider, hidden gesture or intent arm handler was
added. The hook class is absent from release. No release user-accessible arming
mechanism exists. No Emergency Wipe PIN/configuration has been added.

Quiesced scopes and owners intentionally cannot restart in this slice. Future
finalization must establish new owners/generation (or a fresh process) before
allowing NONE; reusing a cancelled scope or retained lazy owner is not finalization.
Stream/grant/DB close failure retains the owned resource for retry rather than
discarding its reference and falsely declaring success.

No backend deployment, server DB migration or Android DB migration is required.
Existing Room schema and encrypted record format are unchanged.

Validation passed on 2026-10-03:

- 222 JVM/app unit tests: 135 test-support, 4 attachments, 45 debug app, 38 release app.
- Full offline app AVD suite: 149 reported (147 passed, 2 opt-in probes deliberately
  skipped in the ordinary run). Final focused owner run: 14 passed, including all
  13 infrastructure cases plus LocalDemoTest and the deterministic record/device-
  credential lock-order race added after the full run.
- Final 17 lifecycle/owner tests also passed after ordinary ViewModel disposal was
  made to unregister its owner callback.
- 37 focused app-lock/foreground/background/session/notification/infrastructure
  regression tests passed; 10 separate encrypted-storage instrumentation tests passed.
- Explicit live media close-failure/retry probe passed. Five startup probe executions
  passed: ARMED, QUIESCING, corrupt, reboot with corrupt state, and final
  KEY_DESTRUCTION_PENDING relaunch. Application/Activity/Worker kept sensitive owners
  uninitialized. The live probe proved input close retries, parent-scope join and
  preservation of the synthetic plaintext staging file.
- Debug/release builds and strict dependency verification passed with both normal
  build configuration and the offline empty-origin test build. Release compile
  artifact excludes LocalOperationTestHooks; merged manifest retains allowBackup=false,
  cloud/device-transfer exclusions and no new exported arm component.

No wipe-specific destruction tests ran because no destruction exists. Existing
storage tests use their own synthetic key fixtures only. No physical phone, VPS,
account reset or server wipe call was used. Existing uncommitted launcher/tooling
changes were preserved and excluded from the infrastructure commit.
The instrumentation harness uses explicit adb serial selection of a disposable AVD
with an empty API origin; no real phones or VPS are touched. Process/relaunch probes
install only synthetic journal bytes, never reset account data or keys.

References for durability APIs: [AtomicFile](https://developer.android.com/reference/android/util/AtomicFile),
[Os.fsync](https://developer.android.com/reference/android/system/Os#fsync(java.io.FileDescriptor)).

## Historical 1K.1 implementation stop condition

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

## Credential construction and false-activation protection (1K.1 proposal, updated for 1K.2B)

Current normal PIN: 6–64 numeric characters; PBKDF2-HMAC-SHA256, 600,000 iterations,
16-byte random salt, 256-bit verifier, MessageDigest.isEqual comparison. Failed
attempt reservation is persisted before KDF execution; after three failures the
monotonic cooldown increases from 5 seconds to at most 5 minutes and is restored
across boot. Historical configuration version 1 has a strict 128-byte decode limit;
1K.2B accepts it and writes bounded version 2 (192 bytes maximum).

Implemented emergency verifier reuses PBKDF2-HMAC-SHA256 with the same work factor
and an independent fresh random salt. The proposed extra salt-domain prefix was
not needed: these independent verifiers are never used as encryption keys. Legacy
normal derivation stays unchanged. Never persist a reversible/plaintext PIN.
Version 2 reads old version 1 as emergency Off.
Invalid/truncated/unknown config fails closed, never activates a reset. Schema
version 1 SQLCipher key-value storage can hold this without a Room migration.

Require matching confirmations and at least six digits. The earlier proposed
sequence/repeated-digit heuristic is superseded: no vetted existing weak-PIN checker
exists, so 1K.2B does not invent one. Compare the proposed emergency
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
decision is resolved in 1K.2B: disabling PIN app lock requires disabling Emergency
Wipe first. Biometric remains a separate normal-unlock path.

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

- [Android AtomicFile source](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/core/java/android/util/AtomicFile.java)
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)
- [Android backup overview](https://developer.android.com/identity/data/backup)
- [WorkManager cancellation](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/manage-work)

Keystore non-exportability is not a promise that cached unwrapped data is revoked.
WorkManager cancellation is cooperative, so a runtime generation/fence is required.


## Phase 1K.3 implemented engine (readiness remains FALSE)

This section supersedes earlier proposed-engine/audit-only statements. The engine is
implemented and exercised by isolated instrumentation fixtures. Both debug and release
`EMERGENCY_WIPE_DESTRUCTIVE_READY` remain false. Ordinary debug Safe Exit remains the
non-destructive journal/quiesce preview; release cannot arm. Phase 1K.4 activation is
separate review. No exported component, adb production arming endpoint or automatic
recovery bypass was added.

### Boundary and durable sequence

`LocalOperationCoordinator` alone executes the engine. Durable ARMED closes the shared
access gate. Owners cancel/join, leased operations drain, databases close and sensitive
references are dropped before QUIESCING -> KEY_DESTRUCTION_PENDING is written,
fsynced and readback verified. **That durable transition is the irreversible boundary.**
No cancel, unlock, rollback or old-account reopen is available afterward.

KEY_DESTRUCTION_PENDING deletes/verifies keys before committing KEY_DESTRUCTION_COMPLETE.
Then STORAGE_CLEANUP_PENDING removes artifacts; FINALIZING verifies the fresh baseline;
COMPLETE is durable and baseline verification is repeated. Only then are fresh lazy
owners prepared and the journal's `.new`, `.bak` and base removed with directory fsync.
Verified absence reopens NONE. Fresh owners do not create identity or register accounts.
The Activity uses a fresh generation and ViewModel/navigation composition; onboarding
requires explicit later identity creation. No completion announcement or prior username.
The journal stays outside SQLCipher in credential-encrypted noBackupFilesDir.

### Exact cryptographic inventory and order

`AndroidLocalDestruction` enumerates AndroidKeyStore in this application's UID only:

1. `ghost-cloak.db.<endpoint>`: endpoint matches `[a-z0-9-]{1,40}`. Includes `local`,
   debug demo endpoints and other valid legacy endpoint slots. EncryptedEndpointStore
   wraps its random 32-byte SQLCipher secret with this non-exportable AES-256 key.
   Close zeroes its retained mutable secret; deleteEntry removes the wrapping key.
2. `ghostcloak.auth.<audience-digest>.<device-uuid>`: 64 lowercase hex audience digest
   and canonical lowercase UUID. KeystoreDeviceAuth's non-exportable P-256 signing key.

Each deletion checks containsAlias/getKey/getCertificate absence. An already absent
entry is idempotent success; provider/enumeration errors are failures, never absence.
Malformed entries under either owned prefix fail closed as unclassified. Other aliases
are preserved. A final rescan must contain no owned keys before KEY_DESTRUCTION_COMPLETE.
No alias/key/token/filename diagnostics are emitted by this engine in any build.

Signal identity, signed/one-time/PQ prekeys, ratchets/sessions, legacy software auth
private material, bearer sessions, registration/routing metadata, recovery state,
contacts/blocks/requests, replay/outbox/messages, notification ledger, app-lock and
Safe Exit salted KDF verifier/configuration are SQLCipher records. There is no separate
Signal or media Keystore alias to delete. AttachmentStore's per-object AEAD keys,
capabilities and descriptors/transfer entries are also SQLCipher records. Removing all
DB wrapping keys therefore removes the surviving installation's access to these
secrets before file deletion; no per-row DELETE or replacement key generation.

A retained DB plus `.wrapped` cannot reopen after its alias is removed:
EncryptedEndpointStore refuses missing-key existing files. Old device-auth signing
also fails. Encrypted attachment files alone cannot recover their descriptor key.
This assumes quiesce succeeded and an attacker did not already copy plaintext keys;
it does not revoke secrets previously stolen or copied to another endpoint.

### Exact file ownership and cleanup

The engine removes all children of app-private credential-encrypted noBackupFilesDir
except `local-operation.v1{,.bak,.new}` and `androidx.work.workdb{,-wal,-shm,-journal}`.
This includes every endpoint `.db`, `.wrapped`, SQLite WAL/SHM/journal and AtomicFile
recovery remnant, `<endpoint>-attachments/{upload,download,scratch}`, media-presentation
plaintext/viewer copies, thumbnails/previews and legacy private leftovers. It also
removes app-private filesDir/cacheDir children and application database-directory
children except those WorkManager files. Code/native libraries and external/public
storage are not cleanup targets. Symlinks are unlinked, never traversed.
Known appearance and notification-permission SharedPreferences are cleared with checked
commit; neither contains credentials. Current nonsensitive theme may persist in memory
until Activity recreation. Storage and settings absence are rechecked at finalization.

SQLCipher/auth/Signal/attachment keys are critical. Plaintext staging, presentation and
old identity stores must disappear before onboarding. Encrypted ciphertext and decode
cache are noncritical to cryptographic erasure, but this initial implementation chooses
conservative finalization: attempt other deletions after a file failure, retain
STORAGE_CLEANUP_PENDING and the closed gate until retry removes *all* owned targets.
No failed cache deletion is silently labelled success. Plaintext remnants after a
failed unlink may remain on disk, inaccessible through Ghost Cloak but not promised
forensically inaccessible to a privileged attacker. There is no secure-overwrite claim.

### Jobs, UI, media and network

Existing shared fence prevents new DB/network/crypto/media leases. GhostApplication
cancels and verifies its unique periodic mailbox work, stops/join UI owners, app-lock,
media, background and lifecycle scopes and closes runtime. WorkManager's own scheduler
DB is preserved; unrelated jobs are not cancelled. URI leases are revoked and owned
streams/ParcelFileDescriptors closed by media quiesce; cleanup repeats root read-grant
revocation. All package notifications are cancelled and the encrypted ledger disappears.
A stale notification can only enter the gated screen or fresh onboarding.

No engine path calls auth/logout/revoke/mailbox/directory/capability/blob/telemetry or
notifies peers. Existing pre-arm in-flight requests may already have reached the server;
cancellation cannot recall them. Remote account, public keys, queued ciphertext and
blobs retain existing server lifecycle. Intentional loss of local recovery proof can
make old-account recovery impossible from this installation; no username-only bypass.

Mutable PIN/DB-key buffers retain existing best-effort clearing. Crypto/network owners,
plaintext UI/media caches and references are closed/dropped. JVM/ART immutable copies,
GC timing, process memory and flash wear-leveling do not permit perfect erasure claims.
External viewers/peers that copied plaintext retain their independent copies.

### Interruption and failure policy

Every resumed non-NONE state first re-quiesces in the new process. At ARMED/QUIESCING
resume drain then enter boundary. At KEY_DESTRUCTION_PENDING re-enumerate and delete
remaining keys. At KEY_DESTRUCTION_COMPLETE advance to cleanup; at STORAGE_CLEANUP_PENDING
retry leftovers; at FINALIZING verify; at COMPLETE reverify before reset. Persisted
fault-injection tests cover every durable stage, partial alias deletion and retained
ciphertext. A two-invocation probe also crosses actual disposable-AVD reboot with
KEY_DESTRUCTION_PENDING and one remaining auth alias.

Keystore failure stays KEY_DESTRUCTION_PENDING, locked, with local retry; cleanup
failure stays STORAGE_CLEANUP_PENDING. Journal write/reset failure keeps the current
process blocked. CORRUPT quiesces but never authorizes deletion or guesses NONE;
there is no automated corrupt-journal recovery. Retry UI reveals no private details.

### Backup and validation scope

Manifest allowBackup=false remains unchanged. Legacy backup_rules and modern
cloud-backup/device-transfer rules exclude root/file/database/sharedpref/external
entirely. Sensitive DB, wrappers, attachment caches and journal remain noBackup and
credential encrypted; no Direct Boot. Deleted Keystore keys are not regenerated for
restored existing ciphertext. Source/resource/merged-manifest policy plus retained-DB
unreadability are validated; no real cloud-provider/OEM restore or physical-phone
uninstall was performed. Privileged/OEM snapshots and external copies are outside the
claimed boundary. No Android SQL schema migration, backend deployment, server schema,
Nginx or Cloudflare change is required.

Validation results are recorded in DEVELOPMENT.md. All destructive tests require a
debug empty-origin ranchu emulator and explicit scoped synthetic roots/aliases; the
Application's normal key/store namespaces are never destructively armed by tests.


## Phase 1K.4 release activation review — NOT READY (2026-10-03)

Reviewed baseline `475b31bd78d54a403792ff6a90cb20792910b890`. Release
activation is withheld. This review changes documentation only; no protection,
production code, permission, schema or flag is changed.

### Blocking finding: contradictory destructive consent

`app/src/main/java/org/ghostcloak/app/access/EmergencyWipeSettingsScreen.kt:40`
unconditionally says this is a non-destructive debug preview that “stops before
deleting keys or data.” Line 70 calls destruction “Planned destructive behavior.”
The first statement has no destructive-readiness/build-type condition. Enabling
release arming and readiness alone would expose that false promise while deleting
keys/data. Current release hides this screen behind its disabled arming gate;
this is an activation blocker, not evidence that current release deletes data.
The destructive consent must accurately describe the actual selected behavior
before activation. Do not turn either release flag on in this review.

Exact unchanged configuration:

| Variant | EMERGENCY_PIN_ARMING_ENABLED | EMERGENCY_WIPE_DESTRUCTIVE_READY |
| --- | --- | --- |
| debug | true | false |
| release | false | false |

Generated release BuildConfig confirms DEBUG=false and both flags=false.
The actual release destructive end-to-end test was NOT run: no activated release
was installed. Fixture success must not be represented as release acceptance.

### Security review and evidence boundaries

Normal PIN and biometric unlock remain distinct from Safe Exit; exact Safe Exit
matching can arm only from locked UNLOCK purpose. Wrong/near-match PIN does not
arm. Settings requires fresh normal authentication; Safe Exit alone cannot grant
management. Change/disable requires both credentials and explicit confirmation;
App Lock disable/non-PIN mode remains blocked while Safe Exit is enabled. Existing
controller and settings tests passed. This does not claim a real release system
biometric prompt was exercised.

The coordinator quiesces owners before the durable key-destruction boundary,
deletes and verifies owned DB-wrap and device-auth Keystore keys, then removes
private storage and verifies fresh state before resetting the journal. Signal
private state and attachment decryption material depend on the encrypted DB.
The rerun scoped destruction fixtures cover retained copied-DB unreadability,
old device-auth signing failure, old Signal/attachment inaccessibility, fresh
state, idempotent durable-stage recreation, partial key failure and cleanup
failure. These tests use synthetic roots/aliases, not the release Application's
normal populated store. Critical failure remains locked/incomplete; cleanup
failure retries without granting old content or premature onboarding.

Offline fixture execution and source review show no wipe-initiated auth, logout,
revocation, Fetch, ACK, directory, capability, attachment or telemetry call.
No release-path network-spy end-to-end assertion was performed. An already
in-flight pre-arm request may have reached the server; erasure is local-only.
Prior Phase 1K.3 separate reboot probes passed across an actual disposable AVD
reboot. This review reran durable-stage recreation fixtures but did not rerun
those opt-in reboot probes or post-wipe actual-release reboot/reconnect tests.

Release merged-manifest review: launcher MainActivity is exported but does not
accept an external arming/journal mutation/auth-bypass command. WorkManager's
exported job service requires BIND_JOB_SERVICE; ProfileInstaller's receiver
requires DUMP. Notification receiver, attachment viewer provider and startup
provider are non-exported; viewer access uses scoped read grants and local gating.
No release test/debug/arming component or instrumentation declaration was found.
Startup may resume an already authorized journal, never arm from an external
intent. No new components or permissions were added.

allowBackup=false and legacy/modern cloud/device-transfer exclusions cover all
root/file/database/sharedpref/external domains. Sensitive stores and journal
remain credential-encrypted noBackup state. No real OEM/cloud restore was tested.
Release diagnostic implementations are no-ops; review found no Safe Exit private
logging or crash-reporting/analytics SDK/event.

### Required follow-up before activation

Correct preview/consent copy to match readiness in every enable/change branch,
with regression checks that destructive-ready UI never promises no deletion.
Preserve exact PIN matching, dual credentials, throttling and fail-closed journal.
Then repeat preflight and install the actual release on a disposable AVD only.
Populate all requested identity/contact/request/message/disappearing/attachment/
outbox/notification/capability state; exercise normal/wrong/near-match PIN,
biometric and Settings, then exact Safe Exit entirely offline. Verify no content
flash, all old-state probes, zero newly initiated network calls, crash/failure
matrix, verified onboarding, kill/restart, reboot and network reconnect without
old-account resurrection. Do not substitute scoped fixture results for this gate.
No fix or automatic activation is included here.

Residual limits remain: external/peer copies, privileged/OEM snapshots, flash
remanence and JVM/ART immutable-memory/GC behavior prevent perfect physical or
memory-erasure claims. No backend deployment, server/Android DB migration, Nginx,
Cloudflare or VPS change is required. Physical phones were not tested.
