# Sealed-sender direction — design only

Phase 1D.3 changes no authentication, cryptography or mailbox schema. A privacy relay and sender-metadata reduction solve different problems. Replacing the network source does not remove authenticated sender identity at the service.

## Current service visibility

Reviewed `NetworkV1.kt`, `Envelope.kt`, `MailboxService.kt` and repository rows:

| Operation/field | What the service learns now | Persistence/limitation |
|---|---|---|
| Challenge/register/verify | Account ID, device ID, username at registration, authentication public key, signed challenge bindings; issues bearer token | Account/device/public-key rows and bounded challenge/session state; session stores token hash |
| Authenticated send | Session maps to sender device/account | Sender-scoped deduplication submission row and digest; no anonymous send |
| Destination | `recipientRoutingId` resolves recipient device/account | Mailbox routing plus directory/account mappings |
| Outer envelope | Sender/recipient device IDs, envelope ID, protocol version, message type, ciphertext length | Entire encoded envelope stored; E2EE applies to payload, not these outer fields |
| Send time | Server receipt time, retry arrival times, expiry | `receivedAt`/`expiresAt` in mailbox; no explicit client-authored send timestamp in envelope; real composition time not known exactly |
| Fetch | Authenticated recipient, polling timing, returned message IDs/counts | Observable during execution; no dedicated per-fetch audit timestamp row; repeated fetch leaves mailbox intact |
| ACK | Authenticated recipient and acknowledged server IDs, arrival time | Mailbox rows deleted; no dedicated ACK-history timestamp row; DB activity still observable |
| Retry | Sender/submission ID and digest equality; server ID reused | Deduplication retention outlives mailbox ACK; same bytes do not recreate acknowledged messages |

Cloudflare sees these HTTP bodies/headers at application TLS termination. A forwarding relay that terminates HTTP can see them too; an inner-TLS CONNECT relay cannot. The backend requires outer sender to match authenticated device and recipient to match destination. Removing just Authorization would break this security check; removing just the outer sender leaves token correlation intact. Public-key bundles and directory lookups also expose relationships.

## Future direction and review gates

The desired direction is recipient-authorized delivery without an account-identifying sender credential on each mailbox send. Sender identity would be authenticated and bound inside the recipient-decryptable envelope, while an independently reviewed delivery authorization mechanism controls unsolicited traffic. Recipient fetch/ACK still need authorization and may remain linkable. Enrollment, key distribution, prekey consumption, directory lookup and credential issuance must be included in the analysis, not only the send endpoint.

[Signal's sealed-sender design discussion](https://signal.org/blog/sealed-sender/) is an example of moving sender identity into protected delivery content alongside abuse controls. It is not a specification to copy into Ghost Cloak. No custom cryptography or Signal protocol fork is proposed. Before implementation, select mature reviewed primitives/libraries and obtain a separate protocol/security review covering impersonation, replay, identity binding, consent/revocation, spam, mailbox capacity, deduplication without a stable sender key, offline delivery and migration/version negotiation.

Future acceptance must show that outer sender fields, application tokens, issuance identifiers and deduplication handles cannot trivially restore sender linkage, while recipient authorization and endpoint verification still work. Preserve retry safety and bounded storage; do not re-encrypt a persisted submission to retry it. Existing endpoint crash/receipt gaps documented in Phase 1C.2 remain unresolved by this transport work.

Even a successful future design would leave destination delivery, timing, sizes and potentially directory/issuance correlation. Relay/service collusion and global traffic analysis remain threats. Sealed sender, anonymous credentials, Privacy Pass and cover traffic are explicitly not implemented in this phase.
