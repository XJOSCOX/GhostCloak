# API boundary

Phase 1L implements a version-2 account/mailbox contract; see
[the anonymous identity design](../../Android/ANONYMOUS_IDENTITY_DESIGN.md).
Registration omits a human name and receives a server-assigned random Ghost Cloak ID.
Directory lookup uses an authenticated exact ID. Capability and sender profiles
carry no plaintext name. The v1 username API and rename route are removed. The
separate encrypted attachment blob endpoint remains v1. An ID is public metadata,
never login/recovery proof; device-auth signatures retain that role. Deploy the new
backend and destructive pre-release V007 together before using Phase 1L clients.

Only the OpaqueEnvelopeStorage contract is implemented. No plaintext send API or decryption capability. Production TLS, authorization, quotas and retention are blocked pending review.
