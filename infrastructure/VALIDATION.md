# Local Phase 1C.2 validation — 2026-09-09

Validated on Windows with Gradle 9.7.1/JDK 25, a disposable PostgreSQL 17.11 instance on loopback, and the Pixel 10 Pro XL API-37 emulator. The connected physical phone was not targeted. No VPS, DNS, firewall, cloud backup or public certificate configuration was changed.

| Check | Result |
|---|---|
| Existing JVM unit/acceptance suites | 48 distinct tests pass (app unit test also runs in both build variants) |
| PostgreSQL integration suite | 7 tests pass |
| Android app instrumentation | 9 tests pass |
| Android encrypted-storage instrumentation | 10 tests pass |
| Android debug and release assembly | Pass |
| Android debug lint | 0 errors, 23 existing warnings |
| Production backend installDist | Pass |
| Strict Gradle IDE verification | 533 source/Javadoc attachments plus discovered graphs and Gradle source ZIP pass |
| New dependency publisher audit | 223 artifact hashes compared; no baseline hash removed or changed |
| nginx 1.28.3 configuration syntax | Pass with isolated test certificate and Windows path/user substitutions |
| git diff whitespace check | Pass |

The seven PostgreSQL tests cover the application history/receipt boundary, HTTPS registration and delivery, before-forward/after-commit gateway failures, backend restart, every existing outbox crash point, concurrent challenge/prekey/submission handling, ciphertext-only dump inspection, persistent limits, scheduled cleanup, migration validation and unsafe configuration rejection. Android checks include non-exportable P-256 signing, reopening encrypted token/outbox state, explicit legacy rejection and failure on incomplete credential metadata.

Commands are documented in DEPLOYMENT.md and STAGING_TEST.md. Tests use disposable randomly generated keys/accounts, not owner credentials. Strict verification was never disabled; candidate metadata was independently checked before the final strict run. The IDE verification task approximates attachment resolution and does not control Android Studio's proprietary Sync action.

Not executed: public-CA staging harness, remote port/firewall probes, Linux systemd startup, sustained load/DoS testing or physical-device compatibility matrix. The production origin remains unset until the owner supplies/reviews a domain; network controls are therefore disabled in the default build. Review SECURITY_REVIEW_PHASE_1C2.md before staging. The repository preserves the owner's separate, uncommitted launcher-resource edits.
