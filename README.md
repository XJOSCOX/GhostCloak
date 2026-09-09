# Ghost Cloak

**Experimental. Ghost Cloak has not undergone an independent security audit and must not be relied upon for high-risk communications.**

Phase 1B adds an Android identity, contacts and encrypted text experience on top of the completed Phase 0/1A/1A.1 foundation. Messaging is local and simulated. There is no production networking, backend deployment, account signup, notification service or Ghost Mode. Phase 1C has not begun.

## Android experience

Choose a local username, exchange a public contact card, open a text conversation and compare safety numbers through Contact Security. Verification requires explicit confirmation. Identity changes block sending until explicitly reviewed. Contacts, trust and history survive reopening through SQLCipher and the existing Keystore wrapping. Settings can rename the username without changing keys or identifiers.

The dark charcoal/mint interface separates theme colors and typography, reusable components, navigation and individual screens. MainActivity contains only activity setup. Application orchestration lives in app/application, while domain validation and encrypted persistence live in the new messaging module. User launcher assets are preserved separately.

For a working local exchange, install a **debug** build and choose **Settings → Open local demo**. This opens isolated Alice/Bob encrypted endpoints, demonstrates ciphertext transport and shows Bob's simulated reply. Return through Settings → Return to my identity. Release builds contain no synthetic endpoint implementation. Importing a card from another device does not establish a network connection; normal-device delivery is unavailable in this phase.

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
| backend | Existing opaque mock mailbox; no endpoint secrets or decryption API |
| protocol | Security architecture, threat, trust, lifecycle, privacy and phase decisions |

## Build and test

Open Android/ in Android Studio. The wrapper pins Gradle 9.6.0 and its distribution checksum; the existing toolchain selects JDK 25 and targets Java 21 for libsignal. Install SDK platform 37 and configure Android/local.properties with sdk.dir. That file is excluded from Git. Initial resolution requires Google Maven, Maven Central and Signal artifact access; no GitHub credentials belong in Gradle configuration.

From PowerShell, with a dedicated test emulator running:

```powershell
cd Android
$env:ANDROID_SERIAL = 'emulator-5554' # Use your test emulator's actual serial.
.\gradlew.bat test :app:connectedDebugAndroidTest :storage:connectedDebugAndroidTest :app:assembleDebug :app:assembleRelease :app:lintDebug --dependency-verification strict
```

On Linux/macOS use bash ./gradlew with the equivalent environment variable. Do not run destructive instrumentation fixtures on a personal device. The maintained test set covers 55 tests: 38 JVM test-support, 1 app unit, 7 app instrumentation and 9 storage instrumentation. All passed on the API 37 emulator; debug and unsigned release APKs assembled, and lint reported zero errors and 23 dependency-age/starter-resource warnings in the shared working tree. Real-hardware and other Android-version validation remain open.

Reports are under Android/test-support/build/reports/tests/test/ and each Android module's build/reports/androidTests/connected/debug/. APKs are Android/app/build/outputs/apk/debug/app-debug.apk and Android/app/build/outputs/apk/release/app-release-unsigned.apk. These builds are prototypes, not distribution approval.

Checksums for newly resolved UI/test artifacts extend the existing verification metadata. Crypto versions remain pinned. Do not regenerate checksums blindly to bypass a mismatch; independently attest baseline provenance before release. Espresso was updated only to fix its removed InputManager API use in API-37 UI tests.

## Security boundaries and limits

Sending always passes through SecureSessionEngine before ciphertext transport. Local history is plaintext only inside the encrypted endpoint database and endpoint/UI memory. No plaintext preferences, cache, index, logs or notifications are created. SQLCipher does not protect an unlocked/compromised process. Blocking is local; deleting a message does not delete the peer copy or guarantee physical erasure.

Independent identity verification, bounded prekey/replay state, crash-delivery gaps, clipboard/keyboard/screen exposure, native memory and authentic database rollback remain important constraints. No durable outbox, rollback detection or network delivery guarantee is implemented. Libsignal external use/API stability/native binaries and AGPL obligations require owner/legal and independent security review before distribution.

Read [Phase 1B architecture and walkthrough](protocol/PHASE_1B.md), [Phase 1B security review](SECURITY_REVIEW_PHASE_1B.md), [trust model](protocol/TRUST_MODEL.md), [rollback risks](protocol/ROLLBACK_RISK.md), [logging audit](protocol/LOGGING_AUDIT.md) and [dependency review](protocol/CRYPTO_DEPENDENCIES.md). The prior [Phase 1A/1A.1 review](SECURITY_REVIEW_PHASE_1A.md) remains the historical foundation review.
