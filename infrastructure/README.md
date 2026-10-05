# Infrastructure

The owner reports staged deployment and a later Cloudflare Tunnel cutover; this repository does not independently attest to current live DNS, firewall or provider settings. Use the [current v2/V007–V008 deployment runbook](DEPLOYMENT.md), [tunnel design](CLOUDFLARE_TUNNEL.md), [origin rotation](ORIGIN_ROTATION.md) and [metadata threat model](../protocol/METADATA_PRIVACY.md). The Phase 1C/1D review documents are historical snapshots, not current commands.

Start with [deployment](DEPLOYMENT.md), [local validation](VALIDATION.md), [TLS](TLS.md), [VPS hardening](VPS_HARDENING.md), [logging](LOGGING_POLICY.md), [backups](BACKUP_POLICY.md) and [dependency review](DEPENDENCIES.md). Public IDs reduce direct identity disclosure; Cloudflare and the backend still observe transport and routing metadata.
