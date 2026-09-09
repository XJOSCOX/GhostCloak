# HTTPS staging acceptance

The local `postgresTest` suite starts real Ktor, real PostgreSQL, and an isolated HTTPS ingress with a generated one-day localhost certificate. Only the test JVM trusts that certificate; it does not modify OS/Android trust. It exercises registration/login, libsignal delivery, restart/retry, dedupe, ownership, ACK and database row inspection. HTTP-only tests additionally exercise every outbox crash boundary. These do not prove the owner's DNS, public CA chain, Linux service unit, or firewall configuration.

After owner review and deployment of a separate staging database/service with a publicly trusted certificate, set GHOSTCLOAK_STAGING_ORIGIN to the staging HTTPS origin and GHOSTCLOAK_STAGING_CONFIRM to `isolated-staging`, then run from Android:

```sh
./gradlew :test-support:stagingTest
```

This deliberately creates three disposable accounts and sends the synthetic message `hello bob`. It uses default CA/hostname validation, contains no production credentials, checks Charlie cannot fetch/ACK Bob's delivery and revokes the generated sessions at completion. It leaves public test account metadata; retire it through the isolated staging database lifecycle. Never run against real user accounts. No remote invocation was performed during package preparation.

While a staging message is queued, the owner can privately export the relevant tables through `pg_dump` (credentials from a 0600 pgpass/secret file, never command arguments). Search raw and bytea/hex representations for the known plaintext and endpoint private-key bytes; neither may appear. Compare generated token values against stored token hashes without printing tokens to logs. Delete the temporary dump securely according to backup policy. The automated local suite performs row serialization of every application table before ACK and rejects known plaintext, raw tokens and serialized endpoint key material.

Build two Android test installs with the reviewed origin, create disposable identities, connect, add each other and verify safety numbers. Exercise offline send, app restart, reconnect/sync, receive/ACK, repeated sync, logout and key loss. Do not use trust-all debug configuration. Before promotion, run the external port/TLS checks in DEPLOYMENT.md and preserve the exact artifact/checksum and test report with the release.
