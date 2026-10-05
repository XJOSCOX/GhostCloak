# API boundary

Phase 1L implements a version-2 account/mailbox contract; see
[the anonymous identity design](../../Android/ANONYMOUS_IDENTITY_DESIGN.md).
Registration omits a human name and receives a server-assigned random Ghost Cloak ID.
Directory lookup uses an authenticated exact ID. Capability and sender profiles
carry no plaintext name. The v1 username API and rename route are removed. The
separate encrypted attachment blob endpoint remains v1. An ID is public metadata,
never login/recovery proof; device-auth signatures retain that role. Deploy the new
backend and destructive pre-release V007 together before using Phase 1L clients.

**CURRENT source:** `ProductionHttpServer` serves the v2 account, authentication,
directory, prekey and mailbox API. `MailboxService` enforces authorization and
bounded request policies; `RetentionWorker` performs cleanup. The service binds
to loopback and production requests require HTTPS asserted by the reviewed
origin proxy. Deploy with the [current runbook](../../infrastructure/DEPLOYMENT.md)
and [origin allowlist](../../infrastructure/tunnel/nginx-origin.conf.template).
The attachment blob API remains separately versioned at `/v1/attachments` and
requires the [attachment route fragment](../../infrastructure/attachments.nginx.conf.fragment).
The backend never exposes a plaintext message-send or decryption API.

**HISTORICAL:** `OpaqueEnvelopeStorage`/`OpaqueMailbox` is an in-memory local
fixture retained for tests; its earlier prototype-only description does not
describe the current production backend.
