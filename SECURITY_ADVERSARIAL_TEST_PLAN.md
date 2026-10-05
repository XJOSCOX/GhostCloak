# Ghost Cloak controlled adversarial pre-production test plan

Status: **Adversarial testing paused after A1. A1 is complete (17 PASS, 0 FAIL, 1 BLOCKED); A2+ and T01–T24 remain NOT RUN.** Phase Q.4 itself executed no adversarial test. Q remediation and operational assurance are closed separately in [the final audit register](SECURITY_AUDIT_CURRENT.md). This plan authorizes no further live, Android, fuzzing or resource-exhaustion tests. A later phase requires new owner instruction. The rollout boundary is [infrastructure/DEPLOYMENT.md](infrastructure/DEPLOYMENT.md). Do not use either populated physical phone or production account for destructive cases. PostgreSQL behavior is tested **through the API only**, never by hostile direct database access.

## Boundaries and evidence

In scope after later authorization: Android clients, authenticated and preauth backend API, owned Cloudflare/nginx ingress, and PostgreSQL-facing effects observed through API responses plus operator-approved sanitized row counts/health. Capture test ID, exact artifact/version and migration checksum, configuration category, clock/source, request/result category, response code, bounded timing/size, and cleanup outcome. Never store real IDs, tokens, PINs, keys, safety numbers, message content, full headers, raw packet bodies, or copied user data in the ledger. Synthetic fixture bytes and hashes may be retained only in a protected test report. Verify fail-closed outcomes and availability recovery; an HTTP error alone is not proof of safety.

Out of scope unless later authorized: unrelated third parties, attacks on Cloudflare itself or hosting-provider infrastructure, public internet scanning, credential stuffing against real users, destructive database attacks, denial of service beyond controlled thresholds, physical-phone instrumentation, or changing production firewall/edge rules as part of a probe. Stop immediately on unexpected access to real data, shared-service degradation, unexpected identity/key change, an unbounded retry loop, or a failed backup/cleanup check. Reassess target authorization before resuming.

Severity if failed: **CRITICAL** = exploitable plaintext/private-key disclosure or unauthorized account control; **HIGH** = cross-account access, trust/identity substitution without warning, fail-open destruction, or durable recipient privacy breach; **MEDIUM** = bounded availability/resource, replay/idempotency, expiry or local-content hygiene defect; **LOW** = limited metadata or nonpersistent UI/privacy defect; **INFO** = expected observable metadata or test/documentation gap. Raise severity when scope or exploitability warrants it. `ACCEPTED` means an explicit owner decision for a documented residual limitation, never an automatic pass.

## Test cases

Each row supplies objective, precondition, action, expected secure behavior, observable evidence, stop condition, cleanup and provisional severity. Actions are future controlled tests against synthetic fixtures only. “Stop” means halt that case immediately and preserve sanitized evidence.

| ID / objective | Precondition | Test action | Expected secure behavior | Observable evidence | Stop condition | Cleanup/reset | Severity if failed |
|---|---|---|---|---|---|---|---|
| T01 ingress isolation | Owned isolated ingress and external probe point | Send bounded requests to public hostname and direct origin/alternate routes, methods and Host/SNI combinations | Only reviewed hostname and route/method allowlist works; direct origin and alternate paths fail | Sanitized status matrix, listener/firewall category, health | Any direct-origin or unreviewed route success | Restore test config; confirm health | HIGH |
| T02 transport metadata | Synthetic requests through owned edge | Compare source-IP/header visibility at edge, nginx and backend without recording raw visitor identifiers | Cloudflare can see client IP; visitor headers do not become backend identity or application DB columns | Sanitized per-component field-presence matrix | Raw identifier reaches app log/DB or real traffic captured | Delete synthetic traces under short retention | MEDIUM |
| T03 auth and recovery | Two disposable accounts plus unknown public key | Replay, expire, misbind and modify challenges; test wrong signature and stale session | No ID-only recovery, uniform valid-shaped unknown failure, single-use challenge, no ownership change | Status/result categories; fixture account continuity | Unauthorized token/account mutation | Revoke fixture sessions; retire accounts | CRITICAL |
| T04 preauth abuse buckets | Isolated rate-limited backend | Exercise bounded known/unknown supplied-material buckets and global ceiling under owner-set ceiling | Per-material budget and broad cap apply without account-existence leak | 429 distribution, healthy cycles, no raw IP principal | Shared service impact or ceiling reached | Wait/reset test windows in isolated DB | MEDIUM |
| T05 directory enumeration | Synthetic known/unknown/exhausted IDs | Compare exact lookup shapes/timing within small approved sample; reject prefix/bulk requests | Generic unavailable response, bounded lookup, no contact name or consuming prekey | Response classes and prekey count delta zero | Unexpected data or prekey consumption | Restore fixture pools | MEDIUM |
| T06 allocation idempotency | Two fixture devices with prekeys | Retry same allocation ID after lost response; reuse ID for another target; vary caller | Same bound public bundle during 24h window, conflict on rebinding, no second consumption | Bundle digest equality and pool-count delta | Key reused across distinct allocation or unauthorized bundle | Expire fixtures; restore pools | HIGH |
| T07 allocation exhaustion | Isolated pool/clock with bounded requests | Reach per-pair, per-requester and global test limits; simulate response and tombstone expiry | Bounded 429/availability; response bytes removed at 24h, tombstone at 48h, ≤128 per pass | Sanitized counts and repeat-cleanup result | Health turns unavailable or limit exceeded | Restore fixture clock/pool and verify worker | MEDIUM |
| T08 prekey refill/identity | Disposable Android pair and backend | Deplete bounded pool, refill, submit duplicate/changed signed capability proof | Refill restores valid pool; identity pin unchanged; invalid capability rejected | Pool category, verified pin state, error category | Silent identity change or unusable key accepted | Recreate disposable accounts only | HIGH |
| T09 mailbox ownership | Three disposable devices | Send to B; attempt C fetch/ACK, wrong routing and tampered outer envelope | Only B accesses queue; tamper rejects without ACK or plaintext | Status categories, queue count and recipient history | Cross-account fetch/ACK | Revoke sessions and clear fixture queue | HIGH |
| T10 replay/dedupe | Fixture endpoints with accepted packet | Repeat submission ID with same/different bytes; replay accepted envelope after restart | Same submission returns same server ID; changed bytes conflict; plaintext appears once | Dedupe status and receiver message count | Duplicate plaintext or changed-payload acceptance | Clear fixture endpoints after evidence | HIGH |
| T11 message requests | Unaccepted synthetic sender/recipient | Send text, attachment, View Once and timer-bearing payloads before Accept/Reject | Hidden request reveals no body/metadata; Accept only reveals surviving content; Reject cannot resurrect old | UI accessibility snapshot without content, state category | Unaccepted content rendered or opened | Delete fixture request/history | HIGH |
| T12 block ACK privacy | Block a fixture contact | Send valid, invalid and offline envelopes; compare ACK/delivery paths | Valid blocked envelope authenticates/replays/ACKs without content or block-specific signal; offline makes no fake ACK | Sender delivery state and recipient generic state | Block state disclosed or content retained | Unblock/reset disposable relationship | HIGH |
| T13 attachments | Synthetic encrypted photo/document and capabilities | Attempt wrong owner, missing/reused capability, partial upload, stale URL and invalid digest | No plaintext at server; owner/quota/capability/digest enforced; partials do not become downloadable | API codes, encrypted object size/digest category | Cross-owner download or plaintext leak | Delete fixture blobs and scratch, verify count | HIGH |
| T14 bounded resources | Isolated service, explicit rate/concurrency ceiling | Submit bounded largest permitted bodies, parallel fetch/upload and retry load | Body/quota/concurrency limits hold and health recovers | Latency/health, queue/blob counts | Ceiling or degradation threshold reached | Stop load; wait for healthy cycle and clear fixtures | MEDIUM |
| T15 malformed protocol | Local service and synthetic corpus only | Feed finite malformed CBOR, unknown fields/versions, truncation and oversized frames | Reject without crash, commit, plaintext, or unbounded allocation | Fixed errors, unchanged state, healthy service | Crash, state mutation or memory threshold | Archive sanitized corpus ID; reset fixture | HIGH |
| T16 stale tokens/clock | Isolated sessions and controllable test clock | Use expired/revoked token, rollover, recovery re-login and rollback time | Expired/revoked access rejected; fresh proof required; no session resurrection | Auth codes and session-count category | Old token works | Revoke all fixture sessions | HIGH |
| T17 request expiry | Disposable endpoint + test time source | Advance same-boot 72h after authenticated local commit, reboot without trusted reference, then refresh trusted time | Expired request content stays hidden; uncertain reboot withholds reveal/Accept; no extension by later message | Encrypted-state category, generic UI | Old content revealed | Reset test clock/endpoint | HIGH |
| T18 mailbox expiry | Isolated backend test clock | Advance to exact seven-day boundary, then fetch and repeat cleanup/ACK | Expired ciphertext inaccessible at boundary; ACK remains idempotent | Queue count and status categories | Expired payload fetched | Delete fixture queue/receipts | MEDIUM |
| T19 identity change/safety number | Three disposable clients with distinct keys | Rotate synthetic peer key; navigate rapidly B↔C; attempt verify with wrong pair number | Changed trust warning, no stale/wrong-contact number, exact pair binding | UI state and pairwise match/distinct assertions without recording digits | Wrong-contact verification succeeds | Restore/recreate disposable identities | HIGH |
| T20 Safe Exit | Disposable AVD with synthetic account, offline | Enter exact/wrong/near PIN; kill/reboot at wipe journal states; tap Retry | Wrong PIN leaves data; exact PIN destroys keys/state, fail-closes until finalization, then onboarding; no old-account reconnect | Journal enum, fixed diagnostics, key/DB probes, zero-network assertion | Surviving old identity or fail-open UI | Recreate fixture identity only after proof | CRITICAL |
| T21 inactivity protection | Disposable AVD with lock configured | Test proven same-boot expiry and cross-boot TIME_UNCERTAIN; authenticate normally | Proven expiry triggers Safe Exit; uncertainty locks and successful normal auth starts fresh interval | Access-state enum and onboarding/lock UI | Wall-clock-only wipe or old content exposure | Reset AVD fixture | HIGH |
| T22 View Once | Disposable pair and media fixtures | Open once, crash at REVEALING, replay, block/remove/re-add, use external-open attempts | Durable consumption, no second reveal, no external photo handoff; already observed bytes not remotely erasable | Placeholder/state, no content logs | Reopened consumed content | Delete fixture media/history | HIGH |
| T23 local presentation | Disposable AVD, owned clipboard/viewer | Exercise copy, screenshot/screen recording, document viewer lease, lock/background | FLAG_SECURE where enforced, sensitive clip hint, generic notification, URI revoked and scratch deleted; external viewer may retain prior bytes | UI/permission categories and owned scratch count | New read after revoke or plaintext persistent scratch | Clear test clipboard, close viewer, delete fixtures | MEDIUM |
| T24 deployment/retention recovery | Owned isolated clone with backups and health | Rehearse V007 restore from matching backup; exercise V008 worker failure then successful cycle | Restore documented and isolated; worker healthy after successful later cycle; no checksum rewrite | Schema history, row counts, health transitions | Backup mismatch or unsafe live-target effect | Destroy clone after backup digest verification | HIGH |

## Test-state ledger

Allowed status values: **NOT RUN / PASS / FAIL / BLOCKED / ACCEPTED**. A pass requires saved evidence and cleanup verification; a blocked case records its external prerequisite. An accepted result records the owner decision, reason, expiry/review date and compensating controls. Initial state for every case is NOT RUN.

| Test IDs | Status | Evidence reference | Owner / target / date | Cleanup confirmed | Notes |
|---|---|---|---|---|---|
| T01 | NOT RUN | — | — | — | Q.4 planning only. |
| T02 | NOT RUN | — | — | — | Q.4 planning only. |
| T03 | NOT RUN | — | — | — | Q.4 planning only. |
| T04 | NOT RUN | — | — | — | Q.4 planning only. |
| T05 | NOT RUN | — | — | — | Q.4 planning only. |
| T06 | NOT RUN | — | — | — | Q.4 planning only. |
| T07 | NOT RUN | — | — | — | Q.4 planning only. |
| T08 | NOT RUN | — | — | — | Q.4 planning only. |
| T09 | NOT RUN | — | — | — | Q.4 planning only. |
| T10 | NOT RUN | — | — | — | Q.4 planning only. |
| T11 | NOT RUN | — | — | — | Q.4 planning only. |
| T12 | NOT RUN | — | — | — | Q.4 planning only. |
| T13 | NOT RUN | — | — | — | Q.4 planning only. |
| T14 | NOT RUN | — | — | — | Q.4 planning only. |
| T15 | NOT RUN | — | — | — | Q.4 planning only. |
| T16 | NOT RUN | — | — | — | Q.4 planning only. |
| T17 | NOT RUN | — | — | — | Q.4 planning only. |
| T18 | NOT RUN | — | — | — | Q.4 planning only. |
| T19 | NOT RUN | — | — | — | Q.4 planning only. |
| T20 | NOT RUN | — | — | — | Q.4 planning only. |
| T21 | NOT RUN | — | — | — | Q.4 planning only. |
| T22 | NOT RUN | — | — | — | Q.4 planning only. |
| T23 | NOT RUN | — | — | — | Q.4 planning only. |
| T24 | NOT RUN | — | — | — | Q.4 planning only. |

The execution report must list every deviation, discovered defect and retest separately. No production readiness conclusion follows from writing this plan.

## Phase A1 execution ledger — 2026-10-04

The owner authorized only `https://api.ghostcloak.org` for live A1 auth/recovery/session testing. The reviewed source checkout was `53d5c64d4771458291c68883af6ef64b87483242`; the owner-reported live backend release was `900a9cc` (the deployed jar was not independently inspected in A1). All live identities and signing keys were generated for this phase. No A/B/C identity, physical device, VPS shell, database connection, mailbox, attachment, or prekey allocation was used. The guarded harness is `Android/test-support/src/test/kotlin/org/ghostcloak/testing/LiveAdversarialA1Test.kt`: it targets a hard-coded HTTPS origin, requires `GHOSTCLOAK_ADVERSARIAL_LIVE=true`, caps each run at 90 requests, and emits only fixed test labels, statuses, response-size comparisons, and coarse timing values. Normal test runs skip its live case.

The final live run used **89 requests**, including health checks after registration and each major batch; its last `/health` response was HTTP 200 with `{"status":"ok"}`. Earlier harness runs stopped on two *harness expectation errors*: exceeding D's documented 10-per-minute challenge bucket (HTTP 429), and treating safe HTTP 400 ingress rejections as if HTTP 401/404 were the only acceptable statuses. Both were corrected before the final run. Neither was recorded as a product vulnerability. Relevant synthetic `RecoveryTest`, `AbuseResistanceTest`, and `NetworkTest` checks passed under strict dependency verification. A1 does not establish production release signoff.

| ID / objective | Status | Evidence summary | Severity if failed | Source commit | Mode / limitation |
|---|---|---|---|---|---|
| A1.1 challenge replay | PASS | First signed login proof 200; identical replay 401; no second grant. | HIGH | `53d5c64` | Live |
| A1.2 expired challenge | PASS | Controllable-clock `NetworkTest.authenticationReplayBindingsExpiryAndRevocation` rejects signed proof after 60,001 ms with 401. | HIGH | `53d5c64` | Synthetic; production clock unchanged |
| A1.3 wrong signing key | PASS | D login challenge signed by E's private key rejected 401. | CRITICAL | `53d5c64` | Live |
| A1.4 modified challenge | PASS | Seven individually changed transcript fields—ID, random, account, device, audience, purpose, registration hash—rejected 401. | HIGH | `53d5c64` | Live; original server challenge consumed on each attempt |
| A1.5 cross-account/device proof | PASS | D challenge with E account/device 401; D recovery challenge with E credential/device 401; registration possession/cross-binding rejected by `NetworkTest`. | CRITICAL | `53d5c64` | Live + synthetic registration case |
| A1.6 challenge purpose confusion | PASS | Register↔login and recovery↔ordinary proof use rejected 401. | HIGH | `53d5c64` | Live |
| A1.7 audience binding | PASS | Signature over another audience rejected 401; no request sent to another domain. | HIGH | `53d5c64` | Live |
| A1.8 token format/guess | PASS | Empty, malformed, random-looking, truncated, extended, and one-character-altered bearer values rejected 401; duplicate header covered by A1.16. | HIGH | `53d5c64` | Live; no brute force or timing equivalence claim |
| A1.9 revoke | PASS | Revoke 200, immediate reuse 401. | HIGH | `53d5c64` | Live; no client restart needed for server-side invalidation |
| A1.10 session expiry | PASS | Controllable-clock `NetworkTest.authenticationReplayBindingsExpiryAndRevocation` rejects token after 300,001 ms with 401. | HIGH | `53d5c64` | Synthetic; production clock unchanged |
| A1.11 bearer cross-device claim | BLOCKED | Auth/recovery/revoke requests expose no caller-device claim under a bearer session; sending D's token to a public directory endpoint would test a different phase. | HIGH | `53d5c64` | No valid in-scope device-claim operation; requires scoped endpoint/fixture design |
| A1.12 recovery known/unknown shape | PASS | Three known/unknown issue pairs each returned 200 and equal encoded length; bad proofs both 401 with equal encoded error and code. Observed round-trip ranges were 127–137 ms and 126–140 ms. | MEDIUM/HIGH | `53d5c64` | Live; sample does not prove timing equivalence |
| A1.13 Q-02 bucket isolation | PASS | One fresh recovery material: 10×200 then 429; another fresh material still 200. Global 600 ceiling covered by synthetic `AbuseResistanceTest`, not live traffic. | MEDIUM | `53d5c64` | Live + synthetic global ceiling |
| A1.14 window edge | PASS | New synthetic fixed-window test checks final millisecond, next-window first millisecond, same-material cap, and other-material independence. | MEDIUM | `53d5c64` | Synthetic; fixed-window design permits bounded boundary burst, not unlimited use |
| A1.15 malformed auth input | PASS | Live empty/one-byte/invalid/truncated/trailing CBOR, wrong content type/method/query/route/type received safe 4xx; synthetic `NetworkTest.httpRejectsOversizeSchemaVersionContentTypeAndCleartext` covers over-limit body. | HIGH | `53d5c64` | Live + synthetic; oversized *declared length without body* not probed live |
| A1.16 conflicting headers | PASS | Conflicting Authorization, malformed prefix/spacing, and duplicate conflicting Content-Type rejected 4xx; valid bearer remained usable until explicit revoke. | HIGH | `53d5c64` | Live through public ingress; ingress and app rejection are not distinguished |
| A1.17 rejected-response leakage | PASS | Inspected live rejected response bytes for exception, JDBC, stack-trace and deployment-path markers; none found. | HIGH | `53d5c64` | Live; bounded marker check, not exhaustive information-flow proof |
| A1.18 server survival | PASS | `/health` remained HTTP 200 and `{"status":"ok"}` after each major batch and at final completion. | HIGH | `53d5c64` | Live |

**A1 totals:** 17 PASS, 0 FAIL, 1 BLOCKED, 0 ACCEPTED. No HIGH or CRITICAL finding was observed. The known/unknown timing sample and blocked A1.11 should not be represented as complete assurance. **A2+ are NOT RUN; stop adversarial testing here.** Their execution requires a new owner instruction and is not part of Q closure.

**Disposable state:** Five guarded development runs created ten disposable server accounts (two each). The service has no public account-deletion operation, so those account/device/prekey records remain for owner-managed cleanup. Challenge records expire after 60 seconds; sessions left by interrupted harness runs expire after five minutes. The final run revoked both active D/E sessions. No existing A/B/C identity was addressed by the harness.
