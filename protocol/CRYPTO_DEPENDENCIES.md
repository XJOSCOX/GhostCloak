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

## Phase 1A.1 artifact inventory and integrity

No dependency versions were changed in this hardening pass. Exact direct security-relevant coordinates are:

| Maven coordinate | Configured artifact origin | Native contents / source |
|---|---|---|
| org.signal:libsignal-client:0.102.1 | Signal Maven, https://build-artifacts.signal.org/libraries/maven | Java API and desktop JNI resources; [pinned source](https://github.com/signalapp/libsignal/tree/v0.102.1), AGPL-3.0 |
| org.signal:libsignal-android:0.102.1 | Signal Maven | Android libsignal_jni.so; testing JNI excluded from app; same source/license |
| net.zetetic:sqlcipher-android:4.19.0 | Maven Central | Android libsqlcipher.so; [source](https://github.com/sqlcipher/sqlcipher-android), BSD-style plus bundled notices |
| androidx.room:room-runtime:2.8.4; androidx.room:room-compiler:2.8.4 | Google Maven | Room runtime/compiler; [source](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/room/), Apache-2.0; database encryption supplied separately by SQLCipher |
| org.jetbrains.kotlinx:kotlinx-serialization-cbor:1.9.0 | Maven Central | JVM CBOR implementation; [source](https://github.com/Kotlin/kotlinx.serialization/tree/v1.9.0), Apache-2.0 |
| org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2 | Maven Central | Coroutine synchronization/runtime; [source](https://github.com/Kotlin/kotlinx.coroutines/tree/1.10.2), Apache-2.0 |
| com.android.tools:desugar_jdk_libs:2.1.5 | Google Maven | Java API backports; [source and notices](https://github.com/google/desugar_jdk_libs), GPLv2 with Classpath Exception and additional notices |

Variant-specific JVM/Android coordinates, transitive artifacts and build plugins are recorded individually in `Android/gradle/verification-metadata.xml`. Repository declarations are centralized in `Android/settings.gradle.kts`; Google and Maven Central precede the group-filtered Signal repository. These are configured publication origins, not an independently authenticated chain of custody. Desktop native code is retained only in the JVM harness; app packaging excludes desktop resources and libsignal_jni_testing.so. ABI compatibility, native internals, reproducibility and third-party notices still require release review.

SHA-256 verification is implemented for artifacts and dependency metadata resolved by the full unit/instrumentation/build/lint task set. Signatures are not verified. Initial metadata was bootstrapped using `--write-verification-metadata sha256`; this is trust in the downloaded/cache baseline, not proof that the baseline is authentic. A reviewer must independently attest critical hashes against trusted upstream evidence before distribution. See [Gradle's verification guidance](https://docs.gradle.org/current/userguide/dependency_verification.html).

Normal builds enforce the committed metadata; CI can explicitly use `--dependency-verification strict`. Enforcement was tested by temporarily replacing the expected libsignal-client JAR hash with an incorrect value: Gradle rejected the dependency, and the original metadata was restored. The full task set then passed with strict verification and without regeneration. For a new host variant or deliberate dependency update, generate proposed additions on a controlled machine, inspect the diff and attest origins/hashes before committing. Never disable verification or blindly accept regenerated checksums to fix a mismatch. A release SBOM and artifact-signature policy remain future work.

## Pinned API/source review

The dependency source JAR was inspected alongside the versioned sources. [PreKeyBundle](https://github.com/signalapp/libsignal/blob/v0.102.1/java/shared/java/org/signal/libsignal/protocol/state/PreKeyBundle.kt) carries separate EC, signed and Kyber IDs. [PreKeyStore](https://github.com/signalapp/libsignal/blob/v0.102.1/java/shared/java/org/signal/libsignal/protocol/state/PreKeyStore.java), [SignedPreKeyStore](https://github.com/signalapp/libsignal/blob/v0.102.1/java/shared/java/org/signal/libsignal/protocol/state/SignedPreKeyStore.java) and [KyberPreKeyStore](https://github.com/signalapp/libsignal/blob/v0.102.1/java/shared/java/org/signal/libsignal/protocol/state/KyberPreKeyStore.java) address separate key namespaces. Equal integers across namespaces are consistent with those contracts; new local bundles allocate distinct values for clarity. Kyber callbacks distinguish one-time removal from last-resort reuse tracking; this prototype supports only one-time keys. Retention policy is local policy, not an upstream security recommendation.

[NumericFingerprintGenerator](https://github.com/signalapp/libsignal/blob/v0.102.1/java/shared/java/org/signal/libsignal/protocol/fingerprint/NumericFingerprintGenerator.java) binds both identity keys and stable identifiers. Matching display values, identity replacement and restart are tested; see TRUST_MODEL.md. [FilterExceptions](https://github.com/signalapp/libsignal/blob/v0.102.1/java/shared/java/org/signal/libsignal/internal/FilterExceptions.java) wraps unexpected checked callback exceptions in AssertionError but preserves runtime exceptions. Local storage failures therefore use a dedicated runtime type and are classified at the application boundary; unrelated programmer failures are not relabeled as authentication failures.

## Android Studio source attachments

IDE source-attachment follow-up (2026-09-09): command-line builds did not resolve all artifacts requested by Android Studio's source dependency model. The sync failure report identified 116 distinct source JARs with missing hashes. Each belongs to an already-pinned component and its cached SHA-256 was compared with a separate HTTPS download from the reported publisher repository (Google Maven, Maven Central or Signal Maven). Their exact source filenames and hashes are now recorded, including variant-specific Kotlin plugin source classifiers. No compiled-artifact hashes, dependency versions or verification policy were changed. This comparison is not an independent publisher-signature audit.

The opt-in `gradle/verify-ide-sources.init.gradle` maintenance task resolves every recorded source JAR using its exact classifier. `verifyIdeSources` and `:app:assembleDebug` passed with strict dependency verification. Additional IDE artifact types remain fail-closed until reviewed; no trusted-artifact wildcard or source exclusion is used.

## Release blocker: owner/legal review

Ghost Cloak must review libsignal's AGPL-3.0 obligations before distributing the application. This requires owner/legal review and is a release blocker. Keeping the repository private must not be assumed to avoid obligations. This document supplies no legal conclusion and makes no change to the project's licensing strategy.
