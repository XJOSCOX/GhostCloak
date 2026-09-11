# Owner-operated VPS hardening

Phase 1D.1 preparation only: nothing here has been executed on the owner's VPS. The owner reports current staging exposes 443 and SSH **5683**. Keep the existing working SSH port; do not open or switch to 22. Use a supported Linux baseline such as Ubuntu 24.04 LTS, distribution-maintained nginx/PostgreSQL, and a supported JVM. Apply security updates, review unattended-update policy and required reboots, and rehearse service restarts.

Create a separate sudo administrator and prove SSH-key login in a SECOND session before changing SSH settings. Keep the first session and provider recovery console open. Run `sshd -t` before reload. Only after verified access, set `PasswordAuthentication no`, `KbdInteractiveAuthentication no` and `PermitRootLogin no`; consider restricted source networks and optional fail2ban with short retention. These are staged instructions, not an auto-executed hardening script.

After the owner later authorizes cutover and validates Tunnel, the target exposes externally **only 5683/tcp** for SSH, ideally restricted to the real management CIDR. Review rules in both the provider firewall and host firewall, including IPv6 and profile/broad subnet allows. Preserve recovery-console access and a second working SSH session. The following is a staged manual transition, not an executable hardening script:

```sh
sudo ufw status numbered
sudo ufw allow from MANAGEMENT_CIDR to any port 5683 proto tcp
sudo ufw default deny incoming
# Only after private ingress/Tunnel and a SECOND SSH session are validated:
sudo ufw delete allow 443/tcp
# Remove 80/8787/5432 allows too if present, and any Nginx Full/HTTPS profile.
# For non-simple rules use: sudo ufw delete RULE_NUMBER
# Re-list after each deletion: rule numbers change.
sudo ufw deny 80/tcp
sudo ufw deny 443/tcp
sudo ufw deny 8787/tcp
sudo ufw deny 5432/tcp
sudo ufw status verbose
```

Substitute actual values manually, not the literal MANAGEMENT_CIDR/RULE_NUMBER. An earlier matching allow can defeat a later deny; remove every overlapping allow and provider exception. Do not blindly enable/reset UFW or flush nftables. If it was not already active, enable only after independently verifying SSH/recovery access. Leave outbound connectivity working: the selected connector needs TCP 7844, DNS and separately reviewed renewal/update traffic. Certbot DNS-01 requires no inbound 80. Do not publish container ports or a fallback direct API listener.

Install the reviewed `tunnel/origin-guard.nft` rule separately from inbound filtering. Its OUTPUT hook admits only the dedicated ghostcloak-origin UID to 127.0.0.1:8787, closing the arbitrary-local-client bypass. It does not touch SSH/INPUT. Validate with `nft -c -f`, load the dedicated table without flushing other rules, and enable its ordered oneshot unit only during authorized deployment. Reconcile this with the host's existing firewall manager; recheck the chain after reload/reboot. Do not reuse a shared service UID, grant shell access to it, or remove the rule on tunnel failure.

Verify `ss -lntp`: backend 127.0.0.1:8787, PostgreSQL 127.0.0.1:5432, metrics 127.0.0.1:20241, no nginx TCP listener. `ss -lx` should show the private TLS Unix socket. Confirm its parent is root:ghostcloak-tunnel 0750 and only cloudflared belongs to that group. Test an unrelated local user cannot connect to the socket or backend, and the intended service path works. Check root-owned binaries/configuration cannot be modified by service users.

From an external machine, test the actual origin's IPv4 AND IPv6: 80,443,8787,5432 must be closed/filtered, including direct 443 with api.ghostcloak.org Host/SNI. SSH 5683 must work only from the intended management network. Public hostname TLS/health should work through Cloudflare. Keep management-origin addresses out of public monitoring output. Record these future checks privately; none ran against the live VPS during this phase.

Do not configure automatic DNS fallback, public-port reopening or an emergency public nginx unit on outages. Explicit rollback to direct staging requires a separate privacy-downgrade decision; see CLOUDFLARE_TUNNEL.md. Root/kernel, hosting provider and Cloudflare remain trusted actors with observation or bypass capabilities. This does not make the historically exposed staging IP secret.
