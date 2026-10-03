package org.ghostcloak.protocol

import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/** Coarse compatibility only. No settings, relationship state or software/device version. */
@Serializable
class AttachmentCapability(val version: Int = 1, val flags: Int = 1, val issuedAt: Long,
    val expiresAt: Long, val bundleDigest: ByteArray, val signature: ByteArray) {
    override fun toString() = "AttachmentCapability(<redacted>)"
}

object AttachmentCapabilities {
    const val LIFETIME = 24 * 60 * 60 * 1000L
    const val MAX_PROOF_BYTES = 512
    fun digest(bundle: PublicBundle) = DeviceAuth.digest(NetworkCodec.encode(bundle.withCapability(null)))
    fun valid(proof: AttachmentCapability, bundle: PublicBundle, now: Long): Boolean =
        proof.version == 1 && proof.flags == 1 && proof.signature.size == 64 && proof.bundleDigest.size == 32 &&
        proof.issuedAt > 0 && proof.issuedAt <= now && proof.expiresAt > now &&
        proof.expiresAt > proof.issuedAt && proof.expiresAt - proof.issuedAt <= LIFETIME &&
        MessageDigest.isEqual(proof.bundleDigest, digest(bundle)) && NetworkCodec.encode(proof).size <= MAX_PROOF_BYTES

    /** Domain-separated, length-delimited transcript; big-endian fixed-width integers. */
    fun statement(audience: String, account: String, routing: String, bundle: PublicBundle,
        proof: AttachmentCapability): ByteArray = ByteArrayOutputStream().also { out ->
        DataOutputStream(out).use { d ->
            fun field(bytes: ByteArray) { d.writeInt(bytes.size); d.write(bytes) }
            listOf("GhostCloak.IdentityCapability.ATTACHMENT_V1", audience, account, bundle.deviceId, routing)
                .forEach { field(it.toByteArray(Charsets.UTF_8)) }
            field(bundle.identity); d.writeInt(proof.version); d.writeInt(proof.flags)
            d.writeLong(proof.issuedAt); d.writeLong(proof.expiresAt); field(proof.bundleDigest)
        }
    }.toByteArray()
}
