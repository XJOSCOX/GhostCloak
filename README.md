# Ghost Cloak

**Experimental. Ghost Cloak has not undergone an independent security audit and must not be relied upon for high-risk communications.**

Phase 1C.2 provides a Ktor/PostgreSQL foundation and Android HTTPS controls with Keystore-backed device authentication. Messages use the existing libsignal and durable-outbox boundaries. The owner reports successful staging deployment at api.ghostcloak.org. Ghost Cloak is not anonymous yet. See [deployment](infrastructure/DEPLOYMENT.md), [validation](infrastructure/VALIDATION.md) and [security review](SECURITY_REVIEW_PHASE_1C2.md).

**Phase 1D.1 prepares origin hiding only:** a private TLS Unix-socket ingress, outbound Cloudflare Tunnel configuration, service isolation, visitor-header filtering and an origin-rotation plan. No live configuration changed in this phase. See the [Tunnel plan](infrastructure/CLOUDFLARE_TUNNEL.md), [metadata visibility](protocol/METADATA_PRIVACY.md), [local tests](infrastructure/tunnel/tests/README.md) and [Phase 1D.1 security review](SECURITY_REVIEW_PHASE_1D1.md). Cloudflare can still observe client IP and HTTP metadata; Ghost Mode is not implemented.

## Local network backend

From `Android/`, run `.\gradlew.bat :backend:runLocal --dependency-verification strict`. It binds only `127.0.0.1:8787`, with ephemeral repository state. Do not use real accounts or expose this listener. The end-to-end JVM fixture starts its own server: `.\gradlew.bat :test-support:test --tests org.ghostcloak.testing.NetworkTest --dependency-verification strict`.

Read the [API contract](protocol/API_V1.md), [device authentication](protocol/AUTHENTICATION.md), [server metadata](protocol/SERVER_METADATA.md), [mailbox policy](protocol/MAILBOX.md), [outbox crash states](protocol/OUTBOX_STATE.md), and [Phase 1C.1 security review](SECURITY_REVIEW_PHASE_1C1.md). The backend cannot decrypt messages. It still sees routing, timing and source IPs at its transport. Outbox recovery deliberately fails ambiguous post-encryption states; PostgreSQL provides backend restart durability; remaining endpoint crash gaps are documented in the Phase 1C.2 review.

## Android experience

Choose a local username, exchange a public contact card, open a text conversation and compare safety numbers through Contact Security. Verification requires explicit confirmation. Identity changes block sending until explicitly reviewed. Contacts, trust and history survive reopening through SQLCipher and the existing Keystore wrapping. Settings can rename the username without changing keys or identifiers.

The dark charcoal/mint interface separates theme colors and typography, reusable components, navigation and individual screens. MainActivity contains only activity setup. Application orchestration lives in app/application, while domain validation and encrypted persistence live in the new messaging module. User launcher assets are preserved separately.

For a working local exchange, install a **debug** build and choose **Settings → Open local demo**. This opens isolated Alice/Bob encrypted endpoints, demonstrates ciphertext transport and shows Bob's simulated reply. Return through Settings → Return to my identity. Release builds contain no synthetic endpoint implementation. For network messaging, build with the reviewed HTTPS origin, connect in Settings and add each other by exact server username. Sync fetches messages and retries queued sends. Network controls are disabled until a server origin is compiled into the build.

## Modules

| Module | Responsibility |
|---|---|
| Android/app | Compose screens, separate theme/components/navigation, safe UI state and process-owned endpoint lifetime |
| Android/messaging | Contact cards/models, message validation, ConversationService and encrypted-record repository |
| Android/identity | Public identity/trust/event types and independent random IDs |
| Android/crypto | SecureSessionEngine facade and isolated libsignal integration |
| Android/storage | Room/SQLCipher endpoint store and Keystore-wrapped random database secret |
| Android/protocol | Bounded canonical envelopes and encrypted routing bindings |
| Android/transport | Ciphertext-only API and bounded in-process router |
| Android/test-support | JVM acceptance, hardening, application and architecture tests; not shipped |
| backend | Loopback HTTP adapter, challenge/auth sessions, public directory, transactional repository interfaces and opaque mailbox; no endpoint secrets or decryption API |
| protocol | Security architecture, threat, trust, lifecycle, privacy and phase decisions |

## Build and test

Open Android/ in Android Studio. The wrapper pins Gradle 9.7.1 and its distribution checksum; the existing toolchain selects JDK 25 and targets Java 21 for libsignal. Install SDK platform 37 and configure Android/local.properties with sdk.dir. That file is excluded from Git. Initial resolution requires Google Maven, Maven Central and Signal artifact access; no GitHub credentials belong in Gradle configuration.

From PowerShell, with a dedicated test emulator running:

```powershell
cd Android
$env:ANDROID_SERIAL = 'emulator-5554' # Use your test emulator's actual serial.
.\gradlew.bat test :app:connectedDebugAndroidTest :storage:connectedDebugAndroidTest :app:assembleDebug :app:assembleRelease :app:lintDebug --dependency-verification strict
```

On Linux/macOS use bash ./gradlew with the equivalent environment variable. Do not run destructive instrumentation fixtures on a personal device. The maintained test set covers 65 tests: 47 JVM test-support, 1 app unit, 8 app instrumentation and 9 storage instrumentation. The JVM tests and API 37 emulator instrumentation passed; debug and unsigned release APKs assembled, and lint reported zero errors in the shared working tree. Real-hardware and other Android-version validation remain open.

Reports are under Android/test-support/build/reports/tests/test/ and each Android module's build/reports/androidTests/connected/debug/. APKs are Android/app/build/outputs/apk/debug/app-debug.apk and Android/app/build/outputs/apk/release/app-release-unsigned.apk. These builds are prototypes, not distribution approval.

Checksums for newly resolved UI/test artifacts extend the existing verification metadata. Crypto versions remain pinned. Do not regenerate checksums blindly to bypass a mismatch; independently attest baseline provenance before release. Espresso was updated only to fix its removed InputManager API use in API-37 UI tests.

Android Studio also downloads source attachments during Gradle sync. Phase 1C.2 increases the reviewed total to 533 attachments. The prior 312 source JARs (including 25 AndroidX samples) and 115 Javadoc JARs were checked against fresh downloads from Google Maven, Maven Central, the Gradle Plugin Portal and Signal's repository. These include Kotlin Gradle-plugin variant sources. AndroidX source variants are also resolved with variant reselection, which includes extra sample files and their published filename/URL mappings. The Gradle 9.7.1 source ZIP requested by the IDE is also pinned against Gradle's published SHA-256. The check also discovers sources from settings-plugin and project build-plugin classpaths, plus every Android and JVM compile/runtime classpath, including debug, release, unit-test and Android-test dependencies, so it can detect missing entries rather than only recheck known ones. To run it without opening the IDE, use Android/:

```powershell
.\gradlew.bat -I gradle/verify-ide-sources.init.gradle verifyIdeSources --dependency-verification strict --no-configuration-cache
```

This resolves the running Gradle version's source ZIP, discovers plugin and Android/JVM classpath sources and Javadocs and checks recorded IDE/build-tool attachments in their original repository scopes; it never adds hashes or disables verification. New dependency versions or additional IDE classifiers still require reviewed metadata additions. It does not execute Android Studio's full proprietary sync model. After updating, use Android Studio's Sync Project with Gradle Files.

## Security boundaries and limits

Sending always passes through SecureSessionEngine before ciphertext transport. Local history is plaintext only inside the encrypted endpoint database and endpoint/UI memory. No plaintext preferences, cache, index, logs or notifications are created. SQLCipher does not protect an unlocked/compromised process. Blocking is local; deleting a message does not delete the peer copy or guarantee physical erasure.

Independent identity verification, bounded prekey/replay state, crash-delivery gaps, clipboard/keyboard/screen exposure, native memory and authentic database rollback remain important constraints. A durable outbox is implemented, but rollback detection and guaranteed network delivery are not. Libsignal external use/API stability/native binaries and AGPL obligations require owner/legal and independent security review before distribution.

Read [Phase 1B architecture and walkthrough](protocol/PHASE_1B.md), [Phase 1B security review](SECURITY_REVIEW_PHASE_1B.md), [trust model](protocol/TRUST_MODEL.md), [rollback risks](protocol/ROLLBACK_RISK.md), [logging audit](protocol/LOGGING_AUDIT.md) and [dependency review](protocol/CRYPTO_DEPENDENCIES.md). The prior [Phase 1A/1A.1 review](SECURITY_REVIEW_PHASE_1A.md) remains the historical foundation review.
