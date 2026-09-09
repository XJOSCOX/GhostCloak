# Cryptographic dependencies

Selected 2026-09-08. Versions are deliberate pins; no automatic crypto upgrades.

| Dependency | Version / license | Decision and review obligations |
|---|---|---|
| [Signal libsignal](https://github.com/signalapp/libsignal/tree/v0.102.1) | 0.102.1, AGPL-3.0 | Maintained by Signal; published Java/JNI implementation of authenticated asynchronous session establishment and evolving ratchets. External use is unsupported and APIs may change. Isolate vendor types within crypto. Review AGPL distribution/source obligations before release; repository privacy does not waive them. Uses native binaries from Signal's Maven repository, with Windows desktop and Android runtime artifacts. |
| [sqlcipher-android](https://github.com/sqlcipher/sqlcipher-android) | 4.19.0, BSD-style project license and bundled third-party notices | Maintained replacement for deprecated android-database-sqlcipher. Room SupportOpenHelperFactory integration. Review native ABI/page-size support, upstream artifact provenance and native logging. |
| [AndroidX Room](https://developer.android.com/jetpack/androidx/releases/room) | 2.8.4, Apache-2.0 | Structured local storage and atomic transactions over SQLCipher; no plaintext fallback. |
| [Kotlin serialization](https://github.com/Kotlin/kotlinx.serialization) | 1.9.0, Apache-2.0 | Explicit versioned CBOR schema. Strict size and re-encoding checks reject noncanonical input. |
| Android Keystore / JCA | Android platform | Device-bound AES-GCM key wraps a random database secret. Report actual KeyInfo protection; software-backed keys are permitted with reduced protection. |

The library, not application code, selects messaging primitives and ratchet algorithms. No replacement protocol is allowed if integration fails. This is not an independent audit of upstream cryptography. Review transitive dependencies and checksums before distribution. Current toolchain comes from the existing Android starter: AGP 9.4.0, Gradle 9.6.0, Kotlin 2.2.10. Added KSP 2.3.11 for Room and desugar_jdk_libs 2.1.5 for libsignal's Java 21 API requirements. Android packaging excludes desktop native resources and the upstream testing JNI library; the JVM test harness retains its desktop runtime.
