# Ghost Cloak

**Ghost Cloak is experimental and has NOT undergone an independent security audit. It must not yet be relied upon for high-risk communications.**

Phase 0 security architecture, Phase 1A local identity/cryptographic foundation and Phase 1A.1 security hardening. No chat UI, production backend, account signup, networking or Ghost Mode is implemented. The repository preserves the existing `Android/` directory capitalization.

## Modules

| Module | Responsibility |
|---|---|
| Android/app | Compose foundation status; application service initializes a persistent local identity off-main |
| Android/identity | Public identity types, random IDs, security events and fixed-code logging |
| Android/crypto | SecureSessionEngine, SignalProtocolEngine, endpoint-record contract and vendor-specific store adapter |
| Android/storage | Room/SQLCipher endpoint database; Keystore-wrapped random database secret |
| Android/protocol | Bounded versioned CBOR envelopes and encrypted routing bindings |
| Android/transport | Ciphertext-only interface and bounded local mock |
| Android/test-support | JVM-only Alice/Bob/Charlie fixtures and attack tests; never shipped in app |
| backend | Bounded opaque mailbox and future public-directory/auth boundaries; no crypto dependency |
| protocol | Threat, privacy, protocol, lifecycle and dependency decisions |

## Build and reproduce

Open `Android/` in Android Studio. Install Android SDK platform 37 and an API 30+ emulator/device; the validated emulator is API 37. The supplied wrapper pins Gradle 9.6.0 with a distribution checksum. The existing daemon configuration selects JDK 25; modules target Java 21 to satisfy libsignal. Configure `Android/local.properties` with your local `sdk.dir` (excluded from Git). Initial builds require Google/Maven Central/Signal artifact access. No GitHub credentials belong in Gradle files.

From PowerShell:

```powershell
cd Android
$env:ANDROID_SERIAL = 'emulator-5554' # Select your test emulator's actual serial.
.\gradlew.bat test :app:connectedDebugAndroidTest :storage:connectedDebugAndroidTest :app:assembleDebug :app:assembleRelease :app:lintDebug --dependency-verification strict
```

On Linux/macOS use `bash ./gradlew` with the same tasks. JVM native artifacts support upstream's documented platforms; unsupported hosts must not substitute cryptography. Instrumentation tests require a running emulator or attached test device.

To rerun the synthetic acceptance demo even when Gradle considers it current:

```powershell
.\gradlew.bat :test-support:test --tests "org.ghostcloak.testing.AcceptanceTest.aliceBobCharlieDemo" --rerun-tasks
```

Expected output includes Alice plaintext `hello bob`, `EncryptedEnvelope(payload=<opaque ciphertext>)`, ciphertext size, Bob plaintext `hello bob`, and a matching 60-digit security number. Ciphertext and numbers change every run. Only fixed synthetic test plaintext is printed, never application messages. The mock transport and backend receive encoded ciphertext only. The backend module has no crypto/storage dependency or decryption API.

Test reports: `Android/test-support/build/reports/tests/test/index.html` and `Android/storage/build/reports/androidTests/connected/debug/index.html`. Debug APK: `Android/app/build/outputs/apk/debug/app-debug.apk`. Read `SECURITY_REVIEW_PHASE_1A.md` for verification results and limitations.

## Decisions and limits

Use maintained libsignal 0.102.1 behind a vendor-independent interface, not homemade messaging cryptography. SQLCipher 4.19.0 uses Room 2.8.4. Review AGPL obligations, unsupported external libsignal use, API instability, native binaries and dependency provenance before distribution. See [dependency review](protocol/CRYPTO_DEPENDENCIES.md).

First contact is explicitly UNVERIFIED until independently verified. Changed pins require explicit fingerprint approval and do not regain VERIFIED status automatically. Destroyed sessions reject old packets; explicit identity-validated reconnection is available through the domain APIs. PreKeyManager provides bounded local maintenance without a publication service. See [trust model](protocol/TRUST_MODEL.md), [protocol and reconnection](protocol/PROTOCOL.md), [key lifecycle](protocol/KEY_LIFECYCLE.md) and [logging audit](protocol/LOGGING_AUDIT.md).

All 40 available tests passed in the hardening review, along with debug/release assembly and lint (zero errors, 22 warnings in the shared working tree). Committed SHA-256 verification covers resolved dependencies; the bootstrapped baseline still needs independent provenance review. Endpoint compromise, [database rollback](protocol/ROLLBACK_RISK.md), traffic analysis and perfect memory/flash erasure are not solved. AGPL review by the owner/legal reviewer is a release blocker. No Phase 1B work is authorized by this implementation.
