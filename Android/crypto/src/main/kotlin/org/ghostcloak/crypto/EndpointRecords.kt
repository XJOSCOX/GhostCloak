package org.ghostcloak.crypto

/** Operational storage failure, distinct from authentication or transport rejection. */
// Runtime exceptions propagate unchanged through libsignal's checked-exception filter in callbacks.
class EndpointStorageFailure : RuntimeException("Endpoint storage unavailable")
internal class CorruptEndpointState : RuntimeException("Endpoint state invalid")

/** Endpoint-only secret store. Calls occur off-main, within a serialized atomic transaction.
 * Returned arrays belong to the caller. Implementations must copy writes and roll back on failure.
 * No implementation may persist these values in plaintext. */
interface EndpointRecords {
    fun <T> transaction(block: () -> T): T
    fun read(key: String): ByteArray?
    fun write(key: String, value: ByteArray)
    fun remove(key: String)
    fun keys(prefix: String): List<String>
}
