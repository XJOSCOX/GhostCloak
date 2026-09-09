# Owner-operated VPS hardening

Nothing here has been executed on the owner's VPS. Use a supported Linux baseline such as Ubuntu 24.04 LTS, distribution-maintained nginx/PostgreSQL, and a supported JDK 21 or newer compatible with JVM 21 bytecode. Apply security updates, review unattended-update policy, monitor required reboots and rehearse service restarts. Keep JVM and PostgreSQL minor security releases current after staging verification.

Create a separate sudo administrator and prove SSH-key login in a SECOND session before changing SSH settings. Keep the first session and provider recovery console open. Run `sshd -t` before reload. Only after verified access, set `PasswordAuthentication no`, `KbdInteractiveAuthentication no` and `PermitRootLogin no`; consider restricted source networks and optional fail2ban with short retention. These are staged instructions, not an auto-executed hardening script.

Review current rules and IPv6 first. With the actual trusted management CIDR substituted, the owner can prepare UFW rules individually:

```sh
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow from MANAGEMENT_CIDR to any port 22 proto tcp
sudo ufw allow 443/tcp
sudo ufw status verbose
```

Do not enable UFW until the SSH rule matches the current connection, recovery-console access works, and a second-session test plan is ready. Then the owner may enable it and immediately re-test SSH. Add 80 only if deliberately using ACME HTTP challenge. Remove pre-existing broad allow rules for 8787/5432 and unnecessary services after inspection. Review provider firewall separately and include IPv6; UFW alone does not undo cloud/container port publishing.

Verify `ss -lntp`: application and PostgreSQL on loopback only. From an external test host, verify 8787 and 5432 are unreachable; 443 works; SSH is limited as intended. Root/service files must not be writable by the JVM account. Do not share the host with untrusted shell users: loopback is a trust boundary, not authentication against a compromised local process.
