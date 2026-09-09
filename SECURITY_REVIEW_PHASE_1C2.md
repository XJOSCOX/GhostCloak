# Phase 1C.2 security review

Ghost Cloak is not anonymous yet. This is a locally validated production foundation, not an independent audit or evidence of a deployed public service. No owner VPS, DNS, firewall or certificate authority was contacted for deployment. Phase 1D has not begun.

## Database and endpoint secrets

PostgreSQL stores normalized accounts/devices, public auth and Signal keys, opaque routing IDs, public prekey bundles/history, challenge bytes/bindings, SHA-256 access-token hashes, ciphertext envelopes, receipt timestamps/expiry, dedupe hashes/IDs and rate buckets. It does not store plaintext, raw bearer tokens, endpoint DB keys or Signal/auth private keys. The integration suite serializes every application table before ACK and checks known plaintext/token/endpoint-secret bytes are absent. This tests known fixtures, not every possible side channel.

PostgreSQL is trusted for authorization state, ordering, availability and metadata integrity, but not with message decryption. A compromised DB/operator can observe relationships and traffic volumes, delete/withhold/replay ciphertext, change public first-contact directory data and disrupt authentication. Existing Signal pins and out-of-band safety-number comparison are still essential. Compromised endpoints remain able to read local plaintext.

Android generates P-256 auth keys in AndroidKeyStore and signs through the private-key handle; exportability is checked and no PKCS8 is stored on this path. SQLCipher stores alias/public key/metadata plus short-lived tokens. The original software credential path remains explicitly a JVM/development fixture; production AppRuntime always supplies KeystoreDeviceAuth. Missing registered keys fail closed. Legacy exported credentials are refused without silent replacement. See DEPLOYMENT.md for the destructive, explicit disposable-development reset strategy. Hardware security level depends on the device; emulator tests prove API behavior, not StrongBox protection on every physical model.

## Authentication and relational consistency

The existing P-256 challenge binding, one-use verification and access-token model remain intact. Default challenge TTL is 60 seconds, session TTL five minutes, mailbox TTL one day and dedupe TTL seven days. Verification burns the challenge in its own committed transaction before proof validation. Registration uniqueness, prekey consumption, session replacement, send/dedupe, fetch ownership and ACK operate inside database transactions.

An advisory transaction lock serializes all domain operations across instances before READ COMMITTED reads. Unique/FK/check constraints provide additional protection. This preserves correctness with the existing repository contract but limits throughput; every writer must use this boundary. DBA writes bypass it. Lock/statement timeouts and an eight-connection semaphore bound requests. Normal startup validates the immutable migration checksum/version and never chooses memory or recreates a schema. Runtime credentials have DML only, plus SELECT on schema history; migration uses a separate owner role. The application rejects superuser/CREATEDB/CREATEROLE roles at startup; deployment must also enforce grants and private network binding.

The worker cleans expired mailboxes, sessions, challenges, dedupe and rate buckets every 30 seconds, independent of traffic. Consumed EC/PQ public bytes are discarded; historical IDs and signed public-key fingerprints/material remain to prevent reused IDs and equivocation, subject to existing protocol bounds. It does not remove endpoint delayed-decryption private keys. Live deletion is not physical erasure of WAL/backups.

## TLS, ingress and logging

Platform CA/hostname validation is used without hard certificate pinning. Production ingress requires a standard trusted certificate and TLS 1.2/1.3. Only nginx is public; JVM and PostgreSQL bind loopback. A local malicious process can bypass the proxy's asserted HTTPS header, so this assumes a dedicated trusted host. Compile-time Android origin is immutable to release users and no global cleartext setting is enabled.

The VPS, proxy, kernel and hosting provider see source IP addresses, timings and sizes. TLS ingress can see protocol bodies including tokens/public bundles/ciphertext. Application operators see account/routing metadata. There is no relay, origin hiding, anonymous transport or Ghost Mode. Disabled access/error/request logging reduces retained data, not observation capability. See LOGGING_POLICY.md for diagnostic tradeoffs and host/provider audit-log limitations.

Ingress bounds bodies/headers/connections and has volatile IP rate buckets. Backend bounds decoding, deadlines and concurrent handlers. Persistent rate buckets contain a device/anonymous principal hash, operation, count and minute; a 2,048-row bound and scheduled expiry limit retention. Hashes are linkable metadata, not anonymity. Shared anonymous quotas can cause denial of service for legitimate registrations; per-IP controls are imperfect behind NAT and distributed attackers can evade them. This is not DDoS protection.

## Crash consistency and remaining limitations

Tests exercise HTTPS registration/delivery, PostgreSQL-backed retries, gateway timeout before forwarding/after commit, server restart after commit, endpoint-engine recreation, simultaneous challenge/prekey use, conflicting/duplicate sends and duplicate/cross-user ACK. The durable outbox reuses persisted ciphertext; ambiguous post-encryption states deliberately fail without re-encrypting. Its 24-hour retry bound remains shorter than dedupe retention.

Android now queues through DurableOutbox, imports network public bundles through ConversationService/engine trust, and persists received history plus an exact envelope-hash receipt before ACK. A repeated fetch after that receipt is committed does not decrypt/display a second plaintext, including after reopen. However, engine replay-state commit and application history commit are still separate: a crash between them fails closed on replay and can leave an undeliverable mailbox entry until TTL. No unauthenticated workaround ACK is provided. Outbox enqueue/history persistence is also separate; a storage failure in between can leave a recoverable queued item without a history row. Resolving these gaps needs a reviewed endpoint transaction design rather than silently changing SecureSessionEngine.

Sync is user initiated, tokens expire quickly, prekey replenishment is explicit, unknown/blocked inbound contacts are not auto-accepted, and a failed item can stop a sync batch. Contact receipts are bounded (10,000), history 5,000; reaching capacity fails closed. Network account usernames are distinct from the local display-name setting. Public CA staging, load/soak tests, physical-device compatibility beyond the tested API, Linux systemd execution and external firewall probes remain owner staging gates. Backups can resurrect metadata/ciphertext/session state; restoration must remain private through cleanup and session revocation.

## Local validation and Phase 1D boundary

See infrastructure/VALIDATION.md for executed checks. nginx syntax was validated locally using an isolated generated certificate and substituted Windows-only paths; the Linux unit remains a template requiring `systemd-analyze verify` on the target. The deployable staging harness is opt-in and was not run remotely. Strict checksums include fresh publisher verification for every new artifact.

Before any anonymity claim, Phase 1D requires a separately approved transport/privacy architecture, threat review of provider/proxy metadata, abuse/availability controls and tests. This phase implements none of those features. Independent security review, endpoint crash-gap treatment and owner-operated HTTPS staging acceptance remain required before relying on this prototype for sensitive communications.
