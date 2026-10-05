# Ghost Cloak

**Experimental. Ghost Cloak has not undergone an independent security audit and must not be relied upon for high-risk communications.** The current source has an [audit register](SECURITY_AUDIT_CURRENT.md); it is not a certification or a claim that live infrastructure was independently inspected.

Ghost Cloak is an Android end-to-end encrypted messaging prototype with a Ktor/PostgreSQL mailbox backend. Each account receives a random, public, 12-character **Ghost Cloak ID**. A contact can find an account by its exact ID or versioned QR payload; an ID alone cannot authenticate, recover, or replace an account. Display names are local and shared only in encrypted messages. Safety-number comparison and explicit verification remain the way to confirm the person behind an ID. The backend has no server username, display-name, alias, contact-acceptance, block, or View Once column in the current V008 source schema.

This is **pseudonymous addressing, not network anonymity**. The server observes account/device/routing relationships, request and mailbox timing, sizes and attachment ownership. Cloudflare can observe client IP and HTTP metadata at its edge. There is no relay or sealed-sender/Ghost Mode implementation; see [metadata privacy](protocol/METADATA_PRIVACY.md) and [network inventory](SECURITY_AUDIT_CURRENT.md#network-metadata-inventory).

## Android experience

Create a local identity and connect when an API origin is configured. Add someone by their exact Ghost Cloak ID or QR, exchange a message request, and compare the safety number before marking the contact verified. Names can be edited without changing the public ID or cryptographic identity. Local history, trust, Signal state, account credentials and attachment descriptors live in the SQLCipher endpoint database protected by an Android Keystore-wrapped random key. Normal photo/document presentation uses private no-backup scratch; external viewer copies are outside the app's control.

The debug variant defaults to the staging API origin. The release variant has an empty API origin unless the builder explicitly supplies a reviewed `ghostcloakReleaseApiOrigin`, and it rejects the known staging hostname. Debug has an isolated local demo; release does not. Open `Android/` in Android Studio and select the intended build variant and **a disposable AVD** for automated instrumentation. Do not use a populated physical phone for test fixtures. Installing from a different checkout or variant may produce different behavior even with the same package name.

App Lock, Safe Exit and Inactive Device Protection are local controls. Safe Exit destroys locally owned secrets/state and should return to fresh onboarding only after verified finalization; its intermediate recovery screen remains fail-closed. Inactivity destruction requires a proven same-boot monotonic interval; an uncertain cross-boot interval locks until successful normal authentication. View Once is an endpoint best effort and cannot erase bytes a recipient has already observed.

## Modules

| Module | Responsibility |
|---|---|
| `Android/app` | Compose UI, app lifecycle, app lock/Safe Exit, media presentation, notifications and workers |
| `Android/messaging` | Contact/request/block policy, View Once state, message validation and encrypted-record repository |
| `Android/identity`, `Android/crypto`, `Android/storage` | Device/trust types, libsignal integration, SQLCipher and Android Keystore |
| `Android/protocol`, `Android/transport`, `Android/attachments`, `Android/capabilities` | Bounded v2 protocol, HTTPS transport, streaming attachment encryption and signed capability proofs |
| `Android/test-support` | JVM acceptance and security fixtures; not shipped in release |
| `backend` | Device-auth challenges/sessions, Ghost Cloak ID lookup, opaque mailbox/blob storage and PostgreSQL migrations |
| `protocol`, `infrastructure` | Protocol/privacy designs and operator deployment material |

## Build and test

The Gradle wrapper and dependency-verification metadata in `Android/` are pinned. Use the installed Android SDK and the JDK required by the wrapper; do not accept changed artifacts by disabling verification or blindly regenerating checksums. To build with strict verification:

```powershell
cd Android
.\gradlew.bat --dependency-verification=strict :test-support:test :app:testDebugUnitTest :app:testReleaseUnitTest :app:assembleDebug :app:assembleRelease
```

The backend distribution is built with `:backend:distTar` or `:backend:installDist`. Android instrumentation must target **only a disposable AVD**; when using adb directly, specify its serial with `-s`. Test counts and dependency versions change, so use the build reports and version catalog rather than historical numbers in old phase reports. APKs are under `Android/app/build/outputs/apk/`; an assembled unsigned release APK is not distribution approval.

## Deployment and historical documents

Use the [current deployment runbook](infrastructure/DEPLOYMENT.md) for v2/V007–V008. V007 was a **destructive pre-release identity cutover**. The operator reports that the approved cutover completed with backups and schema checks, but **backup restore has not been rehearsed**. Future target databases require their own review; do not reapply V007 to a populated environment by assumption. No VPS action is performed by this repository change.

[`protocol/API_V1.md`](protocol/API_V1.md) and older phase reviews are historical, not current endpoint instructions. Current authentication and metadata boundaries are summarized in [the audit](SECURITY_AUDIT_CURRENT.md). The backend cannot decrypt message contents, but SQLCipher and end-to-end encryption cannot protect a compromised unlocked device or prevent an external viewer/recipient from retaining content.
