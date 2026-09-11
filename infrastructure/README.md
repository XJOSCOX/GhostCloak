# Infrastructure

The owner reports Phase 1C.2 deployed and validated on staging. Phase 1D.1 now prepares [Cloudflare Tunnel](CLOUDFLARE_TUNNEL.md), [fresh-origin rotation](ORIGIN_ROTATION.md) and a [metadata threat model](../protocol/METADATA_PRIVACY.md), with a [security review](../SECURITY_REVIEW_PHASE_1D1.md). No remote resources were modified during this preparation; the tunnel is not enabled by this repository change.

Start with [deployment](DEPLOYMENT.md), [local validation](VALIDATION.md), [TLS](TLS.md), [VPS hardening](VPS_HARDENING.md), [logging](LOGGING_POLICY.md), [backups](BACKUP_POLICY.md) and [dependency review](DEPENDENCIES.md). The [security review](../SECURITY_REVIEW_PHASE_1C2.md) records remaining endpoint crash gaps and IP visibility. Ghost Cloak is not anonymous yet.
