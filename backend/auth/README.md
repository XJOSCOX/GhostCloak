# Authentication boundary

**CURRENT source:** `MailboxService` implements device-auth challenge/verify,
session renewal/revocation and explicit proof-based account recovery. The v2
account has a server-assigned Ghost Cloak ID; the ID alone is not an
authentication or recovery credential. The database stores the public
device-auth key and session state, while the signing key remains local to the
client. See [anonymous identity](../../Android/ANONYMOUS_IDENTITY_DESIGN.md)
and the [current deployment runbook](../../infrastructure/DEPLOYMENT.md).

Authentication authorizes mailbox operations; it never decrypts messages or
authorizes replacement of a pinned contact identity. Phone-number signup is
not part of this protocol.
