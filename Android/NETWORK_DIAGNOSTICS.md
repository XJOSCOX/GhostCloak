# Phase 1E.4: confirmed FETCH rate-limit collision

Physical-device Logcat supplied by the user confirmed repeated HTTP 429s on FETCH, followed by recovery at the rate-limit window boundary. The former inbox and RECEIPT_STATUS calls shared the existing 60/minute per-device bucket. This update fixes client request amplification; server rate limits and backend protocol are unchanged.

## Current behavior

- The first inbox Fetch includes up to `NetworkLimits.BATCH` (8) rotating queued submission IDs. One response supplies deliveries and statuses. Normal idle/light cycles perform **one FETCH**, including when delivery receipts are pending.
- Full inbox batches paginate, bounded to the existing 16 pages. Subsequent pages carry skip IDs but no receipt IDs. Messages are still decrypted/committed before ACK. Status IDs/counts are validated before updating outgoing messages; only recorded recipient ACK makes a message Delivered. Inbox commits survive a subsequent invalid receipt response.
- An expired/unknown receipt can cause the existing server to reject the entire combined request with 404. Only that case falls back to an inbox-only Fetch, preserving retrieval and the existing seven-day receipt retention behavior. It does not mark expired receipts Delivered.
- Foreground entry syncs immediately. After completion, active/pending-receipt cycles wait 2 seconds (at most about 30 normal FETCH/minute before latency). Consecutive unchanged cycles relax to 3 seconds from the fourth and 5 seconds from the eighth. Sending resets the fast policy and wakes an idle wait, while maintaining at least 2 seconds after cycle completion. Resume resets the policy. Lifecycle STOP cancels polling and in-flight coroutine work; a mutex and the serialized runtime prevent overlapping loops/operations.
- HTTP 429 maps to **RATE_LIMITED**, with a subtle “Sync temporarily delayed” label. It never clears credentials or initiates auth renewal. Both manual and automatic Fetch calls honor a shared monotonic cooldown. `Retry-After` accepts seconds or HTTP-date; absent/invalid values use 15, 30, then at most 60 seconds. A minimum 2-second wait avoids a zero-delay loop. Successful Fetch resets fallback backoff. Other HTTP errors and the one-retry 401 renewal policy retain their behavior.
- Cooldown headers are local transport metadata, not a change to serialized request/response fields. No error body, header value, identifier, token or URL is logged. Combined requests are categorized as FETCH. Release diagnostics remain disabled.

A typical active debug cycle now shows:

```text
SYNC START cycle=...
FETCH START elapsed=0ms
FETCH HTTP elapsed=... http=200
FETCH END elapsed=... http=200
status SYNCING -> CONNECTED
SYNC END cycle=... elapsed=...ms
```

Incoming messages may add ACK calls and full inboxes may add FETCH pages. Manual Sync, pagination, expired-receipt fallback, and a one-time 401 retry can add requests beyond the normal polling budget; the server limit and explicit 429 cooldown still apply. No physical-phone run is claimed by automated emulator tests.

`CombinedSyncTest` covers combined replies/receipts, accurate ACK state, rotation, pagination, invalid receipts, retention fallback and 429 recovery. `ForegroundSyncTest` exercises real STARTED/STOPPED/resumed lifecycles, automatic renewal and cooldown recovery. `ForegroundBudgetTest` applies the actual cadence policy to the unchanged server limiter over ten simulated minutes and verifies Retry-After through a real loopback HTTP server. Existing JVM/PostgreSQL, privacy, session and Android tests remain regression coverage.

## Historical Phase 1E diagnostic (before this fix)

The following audit records the earlier behavior and candidate analysis, superseded by the confirmed finding and implementation above.

Baseline: `2580573`. This commit instruments the client; it does not establish the cause of the recurring physical-device failure. Capture a complete occurrence on the affected debug phones before changing timing or adding UI debounce. Healthy server processes and tunnel counters do not exclude DNS, TLS, mobile routing, response validation, or endpoint-local failures.

## Strong candidate: client polling reaches the existing FETCH rate limit

A read-only audit found that production wiring uses `PostgresRateLimiter` with its default **60 requests per minute per operation/principal**, using fixed wall-clock minute windows. MailboxService maps both inbox Fetch and submission-status Fetch to the **same FETCH operation**, with a per-device principal for a recognized session. Thus two phones do not share a single authenticated bucket, but two FETCH calls per cycle on either phone do.

With pending delivery receipts, assume two 225 ms requests plus the one-second pause: a cycle takes 1.45 seconds and performs two FETCH requests, approximately 83/minute. Thirty cycles consume 60 requests at about 43.5 seconds into a window; subsequent attempts receive 429 until the next minute, leaving approximately **16.5 seconds**. Actual timing depends on when receipt polling begins, latency, and other FETCH calls. A rejected first FETCH short-circuits the cycle, but retrying once per second cannot clear a fixed-window limit before rollover. The next permitted request recovers automatically.

This matches the reported ERROR label, recovery interval and healthy services better than assuming an arbitrary 15-second socket timeout. It remains a **candidate**, not confirmation of the deployed configuration or captured incident. Confirm `http=429 apiStatus=429 apiCode=server_rejected` during the warning, short individual request durations, RECEIPT_STATUS activity beforehand, and recovery near a wall-clock minute boundary. The HTTP client intentionally reports a generic server_rejected code, not the server's internal rate_limited code. If logs instead show 502, an exception, or a single long request, follow that evidence.

No backend or limiter changes were made. `PollingLoadDiagnosticTest` demonstrates the 16.5-second fixed-window mechanism using the existing development limiter's matching 60/minute policy, and shows that one idle FETCH/second fits. It is a deterministic load model, not a physical-device reproduction or live PostgreSQL measurement.

## What the visible warning means

`Needs attention · Try Sync` is **ERROR**, not NEEDS_CONNECT. `NetworkController.networkFailure` maps final ApiFailure 401 to NEEDS_CONNECT (and disables auto-renewal for this controller), 503 to OFFLINE, and other statuses to ERROR. An IOException in the HTTP client becomes ApiFailure 503/network_unavailable. Other exceptions reaching the controller become ERROR. A pending send with a local failure before a request also becomes ERROR. Cancellation is rethrown and is not mapped to a disconnect.

Silent renewal consumes the first authenticated 401 before the final failure mapping. A rejected/unavailable credential or repeated 401 after renewal becomes NEEDS_CONNECT. Missing stored access state requires explicit Connect. A network outage during renewal remains OFFLINE and eligible for another foreground attempt. Explicit logout removes the stored token and disables automatic reconnect, including across restart.

The old NetworkActions component displayed Connect for **every** non-CONNECTED state, including OFFLINE and ERROR. This was an incorrect presentation of authentication availability. Connect now depends on explicit `networkRequiresConnect`, derived from stored registration/session availability and the controller's renewal gate. The warning and manual Sync remain; no debounce or hidden error was added. An initial registration outage can still offer Connect because usable authentication state is actually unavailable.

## Timing audit

| Layer | Current behavior |
|---|---|
| DirectHttpsTransport | HttpURLConnection, connect timeout 5,000 ms and read timeout 5,000 ms. Fixed-length POST; redirects disabled; platform TLS/hostname verification. |
| Whole-call deadline | None. The two timeouts do not bound the complete HTTP operation to five seconds. |
| DNS | Platform resolver; no separately configured application DNS timeout or resolver retry policy. DNS time is included in observed request duration. |
| Multiple addresses | Android documents trying resolved addresses in order. Repeated connection attempts can exceed a single connect timeout. Exact behavior depends on the device platform/network. |
| TLS | Platform HttpsURLConnection establishment; no separate TLS deadline configured by Ghost Cloak. Its timing is included in the HTTP attempt. |
| Reads/writes | Read timeout limits waiting for input, not total response download duration. No separate application write timeout. |
| HTTP/session retry | No application transport retry/backoff for an IOException/503. An authenticated 401 can trigger two sequential AUTH requests (challenge and verification), then exactly one retry of the original request. No deliberate renewal delay. |
| Foreground cadence | Immediate STARTED entry, then 1,000 ms **after** the completed cycle. Serialized AppRuntime work may add waiting time. No exponential backoff exists in this loop. |
| Other receive loop | NetworkMailboxTransport.receive has a 2,000 ms delay; Android foreground sync uses explicit fetch/exchange instead, so that flow does not add delay here. |
| Cancellation | repeatOnLifecycle cancels the loop below STARTED. The platform connection is blocking IO, not a cancellable HTTP call; an already-running attempt may finish/timeout before releasing runtime serialization. No new polling loop or cancellation behavior was introduced. |

Sources for platform semantics: [Android URLConnection](https://developer.android.com/reference/java/net/URLConnection) and [HttpURLConnection](https://developer.android.com/reference/java/net/HttpURLConnection). The remaining findings above are from repository code in `GhostCloakTransport.kt`, `NetworkMailboxTransport.kt`, `NetworkController.kt`, `AppRuntime.kt`, and `GhostViewModel.kt`.

There is **no configured 15-second client retry or backoff**. The fixed-window rate limit above supplies a concrete alternative explanation. Three approximately five-second waits, multiple address attempts, accumulated connect/TLS/read time, or serialized work followed by the one-second delay could also plausibly yield 15–17 seconds. These are hypotheses, not a measured explanation. The reported ERROR label makes a plain IOException timeout alone insufficient as an explanation: that maps to OFFLINE. A 429/502 or other API status must be distinguished from a non-IO exception using the new logs.

Measure the failed attempt separately from the warning's recovery interval. `HTTP` records the actual response code before content-type/body validation, so an unexpected non-protocol response can be distinguished from the resulting ApiFailure 502. `TRANSPORT_FAILURE` preserves the exception class before IOException is converted to 503. If all requests are short but SYNC duration is long, investigate serialization/local processing rather than assuming a network timeout. SYNC END on lifecycle cancellation marks loop cancellation, not necessarily completion of blocking platform IO.

## Polling load

An idle registered client with an empty inbox/outbox and no SERVER_ACCEPTED submissions makes exactly **one FETCH** per cycle. For a mean idle round-trip of `t` seconds, the expected rate is `60 / (1 + t)` HTTP requests/minute: at 200 ms, approximately 50/minute/client or 100/minute for two phones. With near-zero latency, the upper approximation is 60/minute/client. Tests measure three idle cycles producing exactly three requests and no AUTH, SEND, ACK, or RECEIPT_STATUS calls.

Queued submissions add one RECEIPT_STATUS request per cycle, selecting up to eight IDs in a rotating batch. That request is also ApiRequest.Fetch and its default response can include inbox deliveries; this status path consumes only statuses. A pending outbox adds SEND attempts. Incoming accepted deliveries add ACK calls; a full batch can cause another inbox fetch (up to sixteen pages). A renewal adds two AUTH requests and one repeated original request. Counts are therefore workload-dependent; there is no unconditional extra idle receipt query.

An adaptive strategy worth evaluating after diagnosis: keep one-second polling during active conversation traffic, increase idle intervals after consecutive empty cycles (for example 2/4/8 seconds with jitter), and reset immediately on resume, user send, or manual Sync. Receipt checks could have an independent slower schedule when no new submission was added, preserving delivery semantics. This is a proposal only; cadence, request selection, timeouts and delivery behavior are unchanged in this commit.

If 429 confirms the rate-limit mechanism, first consider combining the rotating receipt IDs with the existing inbox FETCH so one response supplies both deliveries and statuses, then apply a request budget below 60/minute with headroom for pagination/manual work. A separate slower receipt query still adds to the same bucket, so it needs a combined budget. No such optimization is implemented in this diagnostic commit.

## Diagnostic privacy and interpretation

Only debug builds supply a diagnostic observer and a `GhostCloakNet` Logcat sink. Release's source-set implementation has no Android Log call and returns a null observer. Metadata includes operation category, START/END, elapsed monotonic milliseconds, available HTTP/ApiFailure statuses, allowlisted local error codes, exception classes, status transitions, renewal outcome/retry, and foreground cycle number/duration. Unknown error codes are replaced with REDACTED; exception metadata accepts a Class rather than any throwable message. AUTH covers login, registration and explicit revocation; no auth data is included.

No bodies, identifiers, URLs, usernames, tokens, plaintext, ciphertext, keys, safety numbers, throwable messages or stack traces are passed to the logger. Sink failures are isolated. The same failure can appear at request and enclosing operation boundaries; these are not additional HTTP requests. Count START entries by request category. RENEWAL_SUCCEEDED may also mean that another serialized renewal already supplied a fresh token; count AUTH START pairs for actual login traffic.

Regression coverage includes exact request bytes and authorization/retry behavior with disabled, recording and throwing diagnostic sinks; redaction; raw HTTP status plus exception class; unchanged identity/session/message state during renewal; transient 503 versus repeated 401; release no-op behavior; and existing foreground lifecycle cancellation tests with diagnostics enabled.
