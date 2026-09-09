# Server-visible metadata

TLS encrypts data in transit; **the server endpoint sees the source IP**. It also sees timing, request sizes, frequency, destination service and all control-plane fields. Neither opaque routing IDs nor TLS hides a contact graph from a backend that authenticates senders and routes recipients.

| Operation | Application-visible information in addition to source IP/time/size at transport |
|---|---|
| Challenge | proposed account/device IDs, purpose, registration digest, issued challenge and expiration |
| Registration | normalized username, independent account/device/routing IDs, auth public key, Signal identity, public signed/EC/PQ prekeys, possession proof |
| Verify | claimed account/device, challenge ID, signature, result, issued raw token transiently |
| Revoke | authenticated device, token transiently and its hash |
| Rename | authenticated account/device, new username |
| Lookup | authenticated requester, exact queried username, result/exhaustion and selected public bundle; therefore potential contact interest |
| Prekey upload | authenticated owner, device, counts, IDs and full public bundles |
| Send | authenticated sender, recipient routing ID, submission ID, outer envelope ID/sender/recipient/version/type, ciphertext bytes/size, server ID, receive/expiry times |
| Fetch | authenticated recipient, fetch time, batch contents, queue size inferable internally |
| ACK | authenticated recipient, acknowledged server IDs, time and deletion outcome |

The current tables persist accounts/usernames, device routing/auth/identity public keys, bounded prekey pools plus issued-ID/signed-key history, live challenges, session hashes/device/expiry, opaque mailbox envelopes/routing/timestamps and dedupe hashes/sender/submission IDs/server IDs/expiry. Account/device records last for the lifetime of the local backend. Challenge/session/message/dedupe lifetimes differ; see MAILBOX.md. No IP, model, phone/email, GPS, hardware identifiers or explicit contact-list table is stored. That does not eliminate inference of communication relationships from observed requests or retained dedupe metadata.

Application logging accepts only ServerOperation and ServerResult enums; default logger is silent. No request/response-body interceptor, dynamic exception log, tokens, challenge bytes, contact cards, prekeys, safety numbers, plaintext or ciphertext logging. There is no device fingerprinting or model-specific User-Agent; client sends `GhostCloak/1`. The OS/network stack still has transport characteristics; this is not an anonymity guarantee.

Development rate limiting uses an anonymous bucket or authenticated device ID, never invasive fingerprints/IP tracking. An attacker can exhaust shared anonymous limits or obtain more accounts. Destination/mailbox/submission quotas limit resource use but do not solve Sybil attacks, spam, enumeration or abuse. Exact-username lookup requires authentication and has rate hooks; it remains enumerable. No bulk/prefix/user-list endpoint exists.

Reverse proxies, load balancers and web servers can independently log IPs/paths/headers and must be configured/reviewed in future deployment work. No proxy is deployed here. Phase 1D must design relay/edge behavior and collusion assumptions before claiming reduced IP visibility. No claim that IPs are encrypted from the receiving server is made.
