# Sensitive logging and memory audit — Phase 1A.1

Scope: repository-owned source and documentation, searched for `Log.`, `println`, `print(`, `System.out`, `printStackTrace`, stack-trace mentions and `toString()`. Generated build output and ignored upstream research sources are not application source. Dependency internals require separate binary/source review.

| Occurrence | Review result |
|---|---|
| AcceptanceTest `println` calls | Synthetic hello-bob text, opaque envelope label, ciphertext size and synthetic safety number only. JVM test source; excluded from app. |
| Fixture conversions of ciphertext/database bytes to strings | Assertions about synthetic data; no application output. |
| DeviceIdentity, SecretRecord and EncryptedEnvelope `toString` | Redacted/fixed text; no keys, payload or identifiers emitted. |
| RemoteIdentityChanged `toString` | Priority only; omits the contact ID. |
| UUID and registration-ID `toString` | Internal identifier formatting, not logging. |
| `getRemoteFingerprint`/`fingerprint` names | Search substring matches, not `print` calls. Values return through domain APIs. |
| ArchitectureTest logging-pattern strings | Test-only source checks; not logging invocations. |
| Gradle wrapper/build diagnostics | Tooling status and test reports; not production message handling. No custom payload stack-trace output. |

Production Kotlin/Java contains no direct logging sinks for plaintext, ciphertext, safety numbers, device IDs, secrets or tokens. The injectable `SecurityLogger` accepts fixed `SafeLogCode` values only; its default implementation is empty. SQLCipher Java logging is set to NoopTarget. Libsignal logging is not initialized and no logger provider is installed. Upstream native diagnostics and platform-generated crash traces remain an independent audit item. The Compose status screen intentionally displays the local random device ID; this is not a logging sink.

ArchitectureTest enforces no libsignal type references in other production modules, no backend crypto/storage dependency and no direct application logging calls. It is a source-level regression check, not whole-program information-flow analysis; a new alias/wrapper or dependency could bypass a lexical check and requires review.

## Secret-buffer ownership

Serialized identity, EC/signed/Kyber prekey and session records are copied into encrypted storage then their owned serialization/read buffers are overwritten. SQLCipher write arguments are copied for the synchronous DAO call and wiped afterward. The 32-byte database secret is retained until all Room connections close, then wiped; wiping after initial open would break later pooled connections. Failed creation and open use `finally` cleanup without broad exception catches.

Encrypt owns and wipes encoded BoundContent after libsignal returns; the caller retains ownership of its original plaintext. Decrypt wipes the library plaintext and the decoded body after copying the successful return value. If commit fails or coroutine cancellation prevents delivery, the engine wipes its unreturned result buffer. The failed-commit test observes that owned buffer without logging it. Signatures and public-key serializations are public material; wiping them is not a private-key-erasure claim.

Not guaranteed: deterministic erasure of caller buffers, immutable strings created by callers, partial allocations inside a failed CBOR decode, Kotlin serializer copies, JNI objects, native library key structures, SQLCipher internal key buffers, GC/JIT copies or flash/WAL remnants. The app does not log raw exception messages/causes: CryptoFailure exposes safe error/site enums. Unexpected programmer exceptions remain unexpected rather than becoming authentication failures. Test assertions may include stack traces of synthetic failures.

## Phase 1B application/UI audit

Re-audited repository-owned Kotlin/Java source, including the debug application source set. MainActivity is now only an activity/theme entry point. Application state, Contact, Message and ContactCard override toString with fixed redacted descriptions. ContactStatus renders only an already-redacted Contact plus trust/lifecycle enums. No production println, printStackTrace, Log, System.out, message/card/fingerprint/ciphertext logging or new logging SDK exists. The existing synthetic JVM acceptance prints remain test-only.

The debug coordinator displays synthetic identities and replies through the UI rather than printing them. Normal user-entered debug messages also traverse encrypted storage/crypto boundaries and are never logged. Safe UI error mappings use enums; raw storage/library exceptions are not shown. Local IDs used in internal record keys and navigation routes are routing metadata, not log events. Dependency/native diagnostics remain outside the source-level guarantee.

LocalRepository wipes encoded/decoded CBOR byte buffers it owns; ConversationService wipes UTF-8 send/receive buffers; the UI byte counter wipes its temporary encoding. Message bodies necessarily remain immutable Strings in Compose/application state while displayed or read from SQLCipher. Serializer, keyboard, accessibility, JNI, SQLCipher and GC copies cannot be deterministically erased. Plaintext drafts are not deliberately written to saved state, preferences or files. Clipboard Copy/Cut is explicit and marked sensitive, including text-field selection paths; clipboard and keyboard exposure remain external endpoint risks.

Clipboard test environment note: the API-37 emulator's host clipboard synchronization was observed to reconstruct the returned clip with only com.android.systemui.SUPPRESS_CLIPBOARD_OVERLAY. The Android test therefore asserts the sensitive flag on the exact outgoing ClipData and separately checks native clipboard text delivery. It does not assert that privileged host/platform software preserves metadata. The [AOSP EmulatorClipboardMonitor source](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/clipboard/EmulatorClipboardMonitor.java) constructs host-origin clips with that overlay flag. This reinforces the documented clipboard exposure limit.
