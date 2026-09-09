@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
package org.ghostcloak.messaging

import java.util.Base64
import kotlinx.serialization.*
import kotlinx.serialization.cbor.Cbor
import org.ghostcloak.crypto.RemoteKeyBundle
import org.ghostcloak.identity.RandomIdentifiers

/** Public material only. Strict canonical CBOR in a bounded, versioned text envelope. */
@Serializable
class ContactCard(val version: Int, val userId: String, val username: String, val deviceId: String,
    val registrationId: Int, val identity: ByteArray, val preKeyId: Int, val preKey: ByteArray,
    val signedId: Int, val signedKey: ByteArray, val signature: ByteArray,
    val kyberId: Int, val kyberKey: ByteArray, val kyberSignature: ByteArray) {
    fun bundle() = RemoteKeyBundle(deviceId, registrationId, identity, preKeyId, preKey, signedId,
        signedKey, signature, kyberId, kyberKey, kyberSignature)
    override fun toString() = "ContactCard(public material redacted)"
}
object ContactCardCodec {
    const val MAX_TEXT = 8192
    private const val PREFIX = "GHOSTCLOAK:1:"
    private val format = Cbor { encodeDefaults = true; ignoreUnknownKeys = false }
    private fun validate(card: ContactCard) {
        if (card.version != 1 || !RandomIdentifiers.valid(card.userId) || !RandomIdentifiers.valid(card.deviceId) ||
            !card.username.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.]{0,31}")) || card.registrationId !in 1..16380 ||
            listOf(card.preKeyId, card.signedId, card.kyberId).any { it <= 0 } ||
            listOf(card.identity, card.preKey, card.signedKey).any { it.size != 33 } ||
            card.signature.size != 64 || card.kyberSignature.size != 64 || card.kyberKey.size !in 1000..2000)
            throw AppFailure(AppError.INVALID_CARD)
    }
    fun encode(card: ContactCard): String {
        validate(card)
        return (PREFIX + Base64.getEncoder().encodeToString(format.encodeToByteArray(card))).also {
            if (it.length > MAX_TEXT) throw AppFailure(AppError.INVALID_CARD)
        }
    }
    fun decode(text: String): ContactCard {
        if (text.length !in PREFIX.length..MAX_TEXT || !text.startsWith(PREFIX)) throw AppFailure(AppError.INVALID_CARD)
        val bytes = try { Base64.getDecoder().decode(text.substring(PREFIX.length)) }
            catch (e: IllegalArgumentException) { throw AppFailure(AppError.INVALID_CARD) }
        val card = try { format.decodeFromByteArray<ContactCard>(bytes) }
            catch (e: SerializationException) { throw AppFailure(AppError.INVALID_CARD) }
            catch (e: IllegalStateException) { throw AppFailure(AppError.INVALID_CARD) }
        validate(card)
        if (encode(card) != text) throw AppFailure(AppError.INVALID_CARD)
        return card
    }
}
