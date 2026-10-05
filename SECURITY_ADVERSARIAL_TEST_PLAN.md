# Ghost Cloak controlled adversarial pre-production test plan

Status: **PLAN ONLY — NO TEST EXECUTED IN PHASE Q.4.** This is not authorization to attack a running service. The owner must name an isolated, owned target, approved client/build digests, test identities, change window, rate/concurrency ceilings, recovery contact and backup/restore point before a later execution phase. The source audit is [SECURITY_AUDIT_CURRENT.md](SECURITY_AUDIT_CURRENT.md); the current rollout boundary is [infrastructure/DEPLOYMENT.md](infrastructure/DEPLOYMENT.md). Do not use either populated physical phone or production account for destructive cases. Use disposable Android virtual devices and isolated PostgreSQL fixtures unless the owner separately authorizes another target. PostgreSQL behavior is tested **through the API only**, never by hostile direct database access.

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
