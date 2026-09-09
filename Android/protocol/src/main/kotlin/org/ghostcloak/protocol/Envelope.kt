@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
package org.ghostcloak.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.decodeFromByteArray
import org.ghostcloak.identity.RandomIdentifiers

class MalformedFrame : IllegalArgumentException("Malformed protocol frame")

@Serializable
class EncryptedEnvelope(val protocolVersion: Int, val envelopeId: String,
    val senderDeviceId: String, val recipientDeviceId: String,
    val messageType: Int, val encryptedPayload: ByteArray) {
    override fun toString() = "EncryptedEnvelope(payload=<opaque ciphertext>)"
}

/** Binding data is encrypted inside Signal; outer routing fields alone are not trusted. */
@Serializable
class BoundContent(val version: Int, val id: String, val sender: String, val recipient: String, val body: ByteArray)

object EnvelopeCodec {
    const val MAX_PACKET = 131072
    const val MAX_BODY = 16384
    private val format = Cbor { encodeDefaults = true; ignoreUnknownKeys = false }
    fun validate(e: EncryptedEnvelope) {
        if (e.protocolVersion != 1 || e.messageType !in listOf(2, 3) ||
            !listOf(e.envelopeId, e.senderDeviceId, e.recipientDeviceId).all(RandomIdentifiers::valid) ||
            e.encryptedPayload.size !in 1..MAX_PACKET) throw MalformedFrame()
    }
    fun encode(e: EncryptedEnvelope): ByteArray { validate(e); return format.encodeToByteArray(e).also { require(it.size <= MAX_PACKET) } }
    fun decode(bytes: ByteArray): EncryptedEnvelope {
        if (bytes.size !in 1..MAX_PACKET) throw MalformedFrame()
        val e = parse { format.decodeFromByteArray<EncryptedEnvelope>(bytes) }
        validate(e)
        if (!encode(e).contentEquals(bytes)) throw MalformedFrame()
        return e
    }
    fun content(c: BoundContent): ByteArray = format.encodeToByteArray(c)
    fun content(bytes: ByteArray): BoundContent {
        if (bytes.size > MAX_PACKET) throw MalformedFrame()
        val result = parse { format.decodeFromByteArray<BoundContent>(bytes) }
        if (result.body.size > MAX_BODY) { result.body.fill(0); throw MalformedFrame() }
        return result
    }
    private fun <T> parse(block: () -> T): T = try { block() }
        catch (e: kotlinx.serialization.SerializationException) { throw MalformedFrame() }
        catch (e: IllegalStateException) { throw MalformedFrame() }
}
