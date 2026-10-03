# Phase 1J final validation — stopped at backend correctness gate

Audit baseline: main `a835c606a24cd73e3e9c2004aa4ee130563fcb3a`.
Date: 2026-10-02. This is a diagnostic report, not a completed validation sign-off.
No runtime, backend, migration, policy duration or production data was changed.

## Blocking findings

### Request lifetime starts at the wrong event for the latest requirement

Required T0 is recipient authenticated receive/durable commit. Current
`ConversationService.acceptNetwork` passes the mailbox `Delivery.receivedAt` to
`LocalRepository.startRequest`. That value is persisted as `RequestRecord.acceptedAt`.
`requestExpired` compares the projected server time against that timestamp.
The existing `offlineRequestExpiresOnArrivalAndReplayCannotRestoreItsAttachment`
test explicitly proves a request queued for 72 hours expires on arrival.
It passed, which confirms existing behavior but **fails the newly requested
recipient-commit semantics**. A recipient downloading a two-day-old envelope
currently has about one day left, rather than a fresh 72 hours.

No correction was implemented. A follow-up Android correction must capture T0
inside the authenticated commit transaction, preserve the fixed deadline across
restart, and define treatment of already-persisted pending requests without
silently extending old deadlines. Subsequent messages must not extend a pending
request. Exact replay must retain its current durable rejection; genuinely new
envelopes must be eligible for a new recipient-side window independent of their
server queue age. Reboot without trusted time must remain fail closed for reveal.

### Server enqueue samples more than one timestamp

`MailboxService.send` currently uses independent `now()` calls for:

- `MailboxRow.receivedAt`;
- `MailboxRow.expiresAt = now() + policy.mailboxTtl`;
- `SubmissionRow.mailboxExpiresAt = now() + policy.mailboxTtl`.

Production `now()` uses the injected `java.time.Clock`, default `Clock.systemUTC()`.
The default mailbox TTL remains 604800000 ms. Neither client's wall clock controls
these fields. V005 backfills existing queued rows with the exact expression
`received_at + 604800000` and copies that deadline to associated submission rows;
the new-enqueue code does not necessarily preserve that equality.

A temporary isolated JVM probe used the production service and memory repository,
the unchanged seven-day duration, and a server clock advancing 1 ms on each read
only during SEND. It confirmed all of the following:

1. Mailbox deadline was `receivedAt + 604800000 + 1` ms.
2. Sender receipt deadline was another 2 ms later than the mailbox deadline.
3. At exactly `receivedAt + 604800000`, recipient FETCH still returned the envelope.
4. At the mailbox deadline, cleanup removed the payload and recipient FETCH was
   empty, but sender status was still neither acknowledged nor expired.
5. At the later receipt deadline, sender status finally became expired.

The 1/2 ms figures are deterministic probe results, not a bound on production
skew. Scheduling stalls or wall-clock adjustments between reads can widen it.
This is an exact-boundary correctness failure, not evidence of premature delivery
or content disclosure. Fixed-clock tests hide it because every read returns the
same value.

The requested backend-change gate therefore applies: **STOP before implementing**.
Proposed follow-up: capture one server acceptance timestamp inside the SEND
transaction, compute one mailbox deadline from it, and persist that exact deadline
in both mailbox and receipt rows. Do not recompute either on idempotent resend.
This would require a backend release. No new column or V007 appears necessary;
handling already-persisted mismatched receipt deadlines needs explicit review
before any data correction. No production row inspection or correction occurred.

## Other audit results and limits

Cleanup uses PostgreSQL `expires_at <= now`, ordered by `(expires_at, id)`, with
at most 128 rows per deletion pass. V005 supplies `mailbox_expiry_cleanup_idx`.
The service and retention worker use the same transactional deletion; the worker
runs with a 30-second fixed delay. FETCH excludes expired rows even if a bounded
cleanup pass has not physically deleted every row. Physical deletion exactly at
the deadline is therefore not guaranteed. Index existence/query alignment were
audited; actual EXPLAIN/index utilization was not measured in this stopped run.

Repository operations serialize through the PostgreSQL transaction advisory lock.
ACK checks an owned, unexpired mailbox row before marking the receipt acknowledged.
The existing fixed-clock retention test passed its boundary ACK, repeated cleanup,
service restart, bounded-batch and non-resurrection checks. It does not establish
the full concurrent race matrix requested here, or resolve the clock mismatch.

Sender reconciliation changes only SERVER_ACCEPTED to EXPIRED_UNDELIVERED from an
authenticated expired status. The tested local history remains visible, its
disappearing deadline remains unset, and it leaves queued receipt polling.
The UI label is `Expired before delivery`. Expiry does not fabricate Delivered or
disclose a reason. Comprehensive leftover-outbox/restart coverage remains pending.

Blocked-envelope source audit and six existing JVM tests passed: authenticated
blocked processing persists the ratchet/security and replay receipt, bypasses
payload parsing, and does not persist message content, hidden request content,
attachment descriptor, read/unread or notification entries. Photo/document tests
also confirm no downloaded blob or scratch content; the normal server blob remains
independent. Replay, rollback/retry and unauthenticated-envelope rejection passed.
Existing private relationship/block/trust records remain intentionally; there is
no server Block state. The real mailbox fixture confirms normal ACK and Delivered
after successful blocked processing. This is a persistence/API assertion, not a
guarantee of RAM erasure, and not a new physical-device audit.

The three clocks remain distinct:

| Lifetime | Current source | Required outcome |
| --- | --- | --- |
| Unaccepted request, 72h | Server enqueue timestamp projected using persisted server reference plus same-boot elapsed realtime; bounded legacy grace | Must change to recipient authenticated commit T0 |
| Undelivered mailbox, 7d | Server clock persisted as received_at/expires_at and receipt deadline | One acceptance sample/shared deadline needed for exact equality |
| E2EE disappearing message | Incoming durable commit; outgoing observation of recipient delivery ACK | Unchanged; duration remains encrypted |

Local content is removed at the first applicable local deadline; Accept must not
reset either timer. Transport expiry independently makes an undelivered envelope
unavailable. Blob retention is independent of mailbox and disappearing policy.
Delivered proves recipient device processing/ACK, not reading, request acceptance,
attachment download or playback.

## Executed checks and intentionally outstanding work

Strict dependency verification passed for the focused JVM execution: six
RequestPrivacyTest tests, six BlockedEnvelopeTest tests, one MailboxRetentionTest,
and the temporary diagnostic probe (14 total, zero failures). The probe asserted
the observed discrepancy; its passing result is **not** a TTL correctness pass.
The probe source and Gradle log are retained only in ignored `.research/`; no
production diagnostic or intentionally failing regression test was added.

The backend gate stopped further implementation and final validation. Isolated
PostgreSQL/repository reopen/EXPLAIN, disposable AVD, complete JVM suites, and
debug/release builds were **not run for this change**. No VPS/SSH/production backend
connection or physical-phone instrumentation was performed. Existing unrelated
launcher artwork and local Gradle/tooling edits are excluded from this commit;
the focused execution used the local Gradle 9.8.0/tooling configuration.

Before sign-off, an explicitly approved follow-up must fix the shared server
deadline and recipient T0, then run the complete requested boundary, reboot,
lock/notification, attachment cleanup, Accept/Delete race, staggered/batched
PostgreSQL cleanup/FETCH/ACK race, outbox and restart matrix. Do not describe
either latest 72h semantics or exact 7d semantics as passed yet.
