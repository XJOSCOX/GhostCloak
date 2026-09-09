# Durable outgoing state

`DurableOutbox` is a process-owned endpoint worker over encrypted EndpointRecords and SecureSessionEngine. Network submission happens only through IdempotentMessageTransport, which extends the existing ciphertext transport boundary. It does not teach SecureSessionEngine about HTTP. The Phase 1B ConversationService/local UI remains available; network controllers must use this worker rather than the earlier demo send path. UI network onboarding is not added in this backend-contract phase.

| Durable state | Stored material | Recovery |
|---|---|---|
| LOCAL | random submission ID, destination device, plaintext, creation time | Safe to begin one encryption attempt |
| ENCRYPTION_PENDING | ID/destination/time, no plaintext | Ambiguous after recovery: mark FAILED; never re-encrypt |
| CIPHERTEXT_READY | ID/destination/time, exact encoded ciphertext | Upload identical bytes |
| UPLOAD_PENDING | same exact ciphertext and submission ID | Retry identical submission within age bound |
| SERVER_ACCEPTED | ID/destination/time/server ID, no plaintext/ciphertext | Terminal; no resend |
| FAILED | ID/destination/time, no secret payload | Terminal; no auto-resend or crypto rollback |

Before calling encrypt, commit ENCRYPTION_PENDING and erase the durable plaintext. Engine encryption commits its own ratchet transaction. Persist ciphertext only after encrypt returns. These are separate transactions: the design deliberately treats the interval between the marker and ciphertext commit as ambiguous. It can lose one unsent logical message, but cannot advance the ratchet twice by retrying it. A user could later compose a genuinely new logical message; this phase does not silently reinterpret a failed item as new. No rollback of cryptographic state is attempted.

Crash cases tested:

* after local plaintext commit: reopen LOCAL and encrypt once;
* after encryption/ratchet commit, before ciphertext commit: reopen marker and fail closed, no upload/re-encryption;
* after ciphertext commit: reopen and submit identical bytes;
* after server acceptance, before local acceptance commit: resend identical submission ID/ciphertext, receive same server ID, keep one mailbox row.

These tests reconstruct an engine/worker over the same persisted fixture records. Android instrumentation separately reopens actual SQLCipher storage to verify LOCAL outbox/auth/token persistence and encrypted database contents. The worker serializes enqueue/process with a mutex; as with existing endpoint orchestration, there must be only one process-owned worker for an endpoint. Multiple independent processes/workers require durable leases before production. Each endpoint queue has 128 entries; removeFinished reclaims terminal rows. pendingIds permits recovery enumeration. Plaintext size is 1–16 KiB. Automatic retry stops at 24 hours; this is shorter than server dedupe retention. A network failure leaves UPLOAD_PENDING.

SERVER_ACCEPTED means queued by an untrusted server, **not delivered or read by Bob**. Receiving endpoints must durably accept processing before ACK. The local integration fixture ACKs only after successful libsignal decryption. Existing Phase 1B history persistence is a separate transaction from decrypt; a crash after decrypt but before history commit may produce a replay rejection on retry. No automatic ACK of a replay is implemented, because a durable accepted-history receipt has not been proven. A production network UI must address that inbound history/receipt window before claiming exactly-once visible delivery. This phase makes no such claim.
