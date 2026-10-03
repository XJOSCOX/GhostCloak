# Phase 1J correctness correction

Baseline: `89c4f26`. Corrects the findings in
[the historical diagnostic](PHASE_1J_FINAL_VALIDATION.md). No production connection,
timestamp adjustment, deployment, data reset or duration reduction is performed.

## Root causes

Android used `Delivery.receivedAt` (server enqueue) as the pending request origin.
An offline recipient lost part or all of its decision window. The old record also
stored an elapsed deadline relative to local receive/commit, but expiry preferred
the enqueue-derived server timestamp.

Backend SEND independently sampled the server clock for mailbox received time,
mailbox deadline and sender receipt deadline. An advancing clock violated exact
seven-day equality and briefly allowed payload removal before the receipt reported
expiry. Fixed-clock tests did not expose this.

## Secure commit boundary and local clock

The boundary is the endpoint transaction enclosing
`SignalProtocolEngine.decryptAndCommit` and its `ConversationService.acceptNetwork`
callback. Authentication and bound-envelope checks precede application parsing.
Signal/session changes, cryptographic replay evidence, application replay receipt,
encrypted message/descriptor and request record commit together. At the end of the
callback, `startRequest` samples one `ExpiryMoment` and stages the timer record.
It takes effect only when the enclosing transaction durably succeeds. Failure
rolls back all these writes; neither HTTP receipt nor attempted authentication
establishes a timer. This is a transaction linearization sample immediately before
durable finalization, not a separate post-commit timer transaction vulnerable to
process death. Nothing is exposed between staging and successful commit.

The persisted request record uses the existing fields plus `clockVersion=1`:

- `grace.elapsed = commitElapsed + 259200000`, with checked addition;
- `grace.boot = commitBoot`;
- historical field `acceptedAt = projectedServerTimeAtCommit` when available;
- `grace.wall` remains a compatibility field, **not** an expiry authority;
- `lastEnvelopeAt` may retain enqueue time but is not a clock/eligibility gate.

On the same boot, `nowElapsed >= grace.elapsed` expires the request. At
commit + 71:59:59.999 it remains pending; at exactly commit + 72h it expires.
Server time samples and client wall-clock changes cannot alter this elapsed
deadline. Process/runtime recreation does not reset it.

After reboot, a fresh authenticated server reference plus current elapsed time
is compared with `acceptedAt + 259200000`, using `>=`. While this reference is
unavailable, content reveal/Accept is withheld; cleanup resumes once reliable
time returns. The commit reference is preserved, never rebased to a new window.
An untimed request can bind its **remaining** elapsed lifetime to the first trusted
reference on the same boot. If it reboots before binding, it expires conservatively
rather than obtaining another 72 hours. Local wall time is never used to bridge
reboots. Server-clock honesty/stability is still assumed; malicious server time
and deferred Android execution are not solved by this retention mechanism.

Only an authenticated, previously unseen envelope can begin a fresh request after
Reject/expiry. Exact replay is caught before request creation. Additional messages
in a pending request never extend its deadline. No body retrieval is authorized
for unaccepted attachment requests. Expiry removes messages, descriptors, read and
notification ledger entries; AppRuntime reconciliation removes unreferenced
attachment caches while retaining required delivery/outbox references and replay
evidence. App lock still gates presentation and does not redefine time.

## Existing pending records

Historical CBOR records decode with `clockVersion=0`. Migration is performed once
inside the encrypted endpoint transaction without a Room/SQL migration:

1. Pending record, same boot: retain the already persisted commit-relative elapsed
   deadline and infer a commit reference from projected server time minus elapsed
   age. This gives the recoverable original commit window, not upgrade + 72h.
2. Pending record after reboot: retain its old server-enqueue reference and original
   elapsed/boot fields conservatively. A client wall timestamp cannot securely
   reconstruct the lost monotonic commit reference. Such old requests can retain
   the shorter old lifetime; this limitation is explicit and deterministic.
3. Terminal EXPIRED/REJECTED/BLOCKED/ACCEPTED records remain terminal. Lost/deleted
   content is not reconstructed or revealed. Very old retained contacts with no
   request record still receive a bounded hidden compatibility record on first
   access; an exact historic commit time cannot be recovered for that case.

Already-expired requests do not revive. Identity, device authentication, Signal
keys and accepted-contact membership are unchanged. No filesystem scanning or
identity recovery is introduced. Older strict Android decoders do not understand
the new serialized marker; rollback to those builds is unsupported after records
are upgraded. Do not clear data or regenerate identity as a rollback workaround.

## Mailbox invariant and races

New SEND captures one server-controlled `Clock.millis()` sample, default
`Clock.systemUTC()`, inside the database transaction:

```
receivedAt = serverClockMillis
expiresAt = Math.addExact(receivedAt, policy.mailboxTtl)
mailbox.expiresAt = expiresAt
submission.mailboxExpiresAt = expiresAt
```

Production TTL remains **604800000 milliseconds**. For every newly queued row,
`expiresAt - receivedAt == 604800000` exactly. Dedupe arithmetic is also checked.
Overflow fails before either row is written. Duplicate SEND returns the original
submission/server identifier without sampling a new retention deadline.
No sender/recipient timestamp or disappearing duration influences it.

Eligibility is `expiresAt > now`, equivalently expiry is `now >= expiresAt`.
FETCH/ACK require eligibility; cleanup and sender expired status use `<= now`.
At deadline minus 1 ms the row is eligible; at deadline it is unavailable.
Physical deletion is bounded, best effort: the existing 30-second worker and
opportunistic cleanup remove up to 128 expired rows per pass. FETCH cannot expose
an expired row left behind by the batch limit. The existing V005 expiry index and
transaction advisory lock are unchanged.

Transactions serialize concurrent repository operations:

- FETCH before expiry may return an eligible envelope. Normal ACK before expiry
  establishes Delivered; later cleanup cannot change that receipt to expired.
- FETCH alone is not delivery. If ACK arrives at/after expiry, it does not establish
  Delivered even if FETCH returned the envelope just before the deadline.
- At the exact boundary, cleanup/FETCH/ACK contention produces no envelope and an
  unacknowledged expired receipt, regardless of lock acquisition order.
- ACK and cleanup are idempotent. Resubmitting the identical expired envelope
  cannot restore its mailbox row.
- Local Accept before expiry commits accepted membership; later request cleanup
  leaves accepted history alone. At/after expiry evaluation, Accept fails and the
  expiry cleanup stays committed. Delete before/after expiry remains harmless and
  cannot reveal content or block the sender.

Sender authenticated reconciliation remains SERVER_ACCEPTED -> EXPIRED_UNDELIVERED
with **Expired before delivery**, retained history, no outgoing disappearing timer,
no fabricated Delivered and no reason disclosure. Blocked online recipients still
authenticate, durably commit ratchet/replay evidence, discard content and ACK
normally. A blocked offline recipient uses ordinary seven-day transport expiry;
the backend has no Block knowledge.

## Independent lifecycles

Enqueue at T0; recipient commits at T0 + six days; normal ACK removes the envelope.
Its local unaccepted request lasts until T0 + six days + 72 hours. The former
seven-day mailbox deadline does not cap local committed content.
Photo/document descriptors use the same request timer; blob retention is
independent and may make a later body unavailable. Incoming E2EE disappearing
expiry starts on secure commit; outgoing expiry starts on recipient ACK observed
by the sender. Local content follows the earlier applicable request/disappearing
deadline. The server never receives the disappearing duration.

## Deployment and rollback

**Backend release required: YES. DB migration: NO. nginx changes: NO.**
V006 remains the current schema; no V007 or change to V001–V006 is introduced.
Deploy the corrected backend release through the existing procedure, then update
Android in place with the existing package/signing/origin. The backend wire format
and ownership model are unchanged. This task does not connect to or deploy on the
VPS.

Already-persisted server deadlines are not rewritten. Those queued before this
release retain their original mailbox/receipt times, including any sampling skew;
they reconcile with their existing authoritative persisted deadline. The strict
new invariant applies to new enqueues. Reverting the backend binary does not
require reverting the schema, but reintroduces the bug for subsequent enqueues.
Prefer a forward fix. Android rollback constraints are described above.

## Validation

Host validation passed with strict dependency verification: 131 core JVM tests,
17 isolated loopback PostgreSQL tests, four attachment tests, 33 debug and 26
release unit tests, and both debug/release builds. The new PostgreSQL probe uses
an advancing clock, fresh schema, separate repository instances, exact -1/0/+1 ms
boundaries, concurrent FETCH/ACK/cleanup, staggered messages, checked-overflow
rollback and idempotent non-resurrection. Existing retention coverage includes
bounded batches/repeated cleanup and sender terminal reconciliation.

Ten request-privacy JVM tests include offline 60h then full 72h, exact boundaries,
restart/reboot, historical serialized-state migration, failed authenticated commit
rollback, acceptance/deletion ordering and replay/fresh request behavior. All six
blocked persistence JVM tests passed: no persisted plaintext/message, hidden
content, unread/notification entry or attachment descriptor/cache/body download.
Only the required security/replay/session and retained private relationship/trust
state remain. This does not promise guaranteed RAM erasure.

Android instrumentation passed on API 37: all 131 app tests and ten storage tests.
The new encrypted-store test covers six-day offline delivery of hidden photo and
document descriptors, reopen, reboot/reference expiry, cleanup, exact replay and
a fresh request without identity changes. Existing app-lock, foreground/background
sync and blocked-envelope device tests remain green. The first full run passed
130/131 and exposed an existing ErrorNoticeTest race: error clearing preceded
the final CONNECTED state publication. Its assertion now waits for both terminal
conditions without changing application error handling. Its three targeted tests
and the subsequent complete 131-test run passed. Strict verification also passed
for IDE-resolved source/documentation artifacts.

Only GhostCloak_Phase1J1_Disposable (`emulator-5566`) was used; physical phones,
account-bearing AVDs and the production database are not test targets. Local
unrelated launcher artwork and Gradle/AGP tooling edits are excluded from this
commit; validation uses the existing local Gradle 9.8.0/AGP 9.4.1 configuration.

## Physical retest after user-managed deployment

Update both phones in place; never uninstall, clear data or recreate identity.
Verify an ordinary hidden text/photo/document request, Accept, Reject/new request,
Block with normal Delivered ACK, and offline queued delivery. Confirm no attachment
body is fetched before acceptance. Test real-duration expiry only on identities
you designate for it; do not modify production timestamps or shorten durations.
The deterministic boundary/race tests above do not require physical waiting.
