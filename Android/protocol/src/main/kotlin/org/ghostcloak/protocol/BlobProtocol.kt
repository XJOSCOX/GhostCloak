package org.ghostcloak.protocol

import kotlinx.serialization.Serializable

object BlobPolicy {
    const val MAX_BYTES = 26L * 1048576
    const val ACCOUNT_BYTES = 200L * 1048576
    const val DAILY_UPLOAD = 50L * 1048576
    const val DAILY_DOWNLOAD = 500L * 1048576
    const val GLOBAL_BYTES = 10L * 1073741824
    const val FREE_BYTES = 5L * 1073741824
    const val PARTIAL_TTL = 3600000L
    const val COMPLETE_TTL = 7L * 86400000
    const val CAPABILITY_HEADER = "X-GhostCloak-Blob-Capability"
    fun validId(value: String) = value.matches(Regex("[0-9a-f]{64}"))
}
/** Public operational metadata only. Never add an E2EE descriptor/key/type to this object. */
@Serializable class BlobReservation(val version: Int = 1, val id: String, val length: Long,
    val digest: ByteArray, val capabilityHash: ByteArray) {
    fun validate() { requireApi(version == 1 && BlobPolicy.validId(id) && length in 1..BlobPolicy.MAX_BYTES && digest.size == 32 && capabilityHash.size == 32) }
    override fun toString() = "BlobReservation(redacted)"
}
@Serializable class BlobResult(val version: Int = 1, val complete: Boolean, val expiresAt: Long) {
    override fun toString() = "BlobResult(redacted)"
}
