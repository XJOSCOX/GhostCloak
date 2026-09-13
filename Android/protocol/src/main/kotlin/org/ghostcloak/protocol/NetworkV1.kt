@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
package org.ghostcloak.protocol

import kotlinx.serialization.*
import kotlinx.serialization.cbor.Cbor
import org.ghostcloak.identity.RandomIdentifiers
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.*
import java.security.interfaces.ECPublicKey
import java.security.spec.*
import java.util.Locale

object NetworkLimits {
    const val BODY = 196608
    const val RESPONSE = 1100000
    const val BUNDLES = 16
    const val BATCH = 8
    const val CONTENT_TYPE = "application/vnd.ghostcloak.v1+cbor"
}
class ApiFailure(val status: Int, val code: String, val retryAfterMillis: Long? = null) : RuntimeException(code)
fun requireApi(ok: Boolean, code: String = "invalid_request", status: Int = 400) { if (!ok) throw ApiFailure(status, code) }
object Usernames {
    fun normalize(value: String): String {
        val normalized = value.lowercase(Locale.ROOT)
        requireApi(normalized.matches(Regex("[a-z0-9][a-z0-9_.]{2,23}")))
        requireApi(normalized !in setOf("admin", "administrator", "system", "support", "ghostcloak", "ghost_cloak", "ghost.cloak"))
        return normalized
    }
}
@Serializable
class PublicBundle(val deviceId: String, val registrationId: Int, val identity: ByteArray,
    val preKeyId: Int, val preKey: ByteArray, val signedId: Int, val signedKey: ByteArray,
    val signature: ByteArray, val kyberId: Int, val kyberKey: ByteArray, val kyberSignature: ByteArray) {
    fun validate() {
        requireApi(RandomIdentifiers.valid(deviceId) && registrationId in 1..16383)
        requireApi(listOf(preKeyId, signedId, kyberId).all { it > 0 })
        requireApi(listOf(identity, preKey, signedKey).all { it.size == 33 && it[0] == 5.toByte() && it.drop(1).any { b -> b != 0.toByte() } })
        requireApi(signature.size == 64 && kyberSignature.size == 64 && kyberKey.size == 1569 && kyberKey[0] == 8.toByte())
    }
    override fun toString() = "PublicBundle(<redacted>)"
}
@Serializable
class Registration(val accountId: String, val deviceId: String, val routingId: String,
    val username: String, val authPublicKey: ByteArray, val bundles: List<PublicBundle>) {
    override fun toString() = "Registration(<redacted>)"
}
@Serializable
class Challenge(val id: String, val random: ByteArray, val expiresAt: Long, val audience: String,
    val accountId: String, val deviceId: String, val purpose: String, val registrationHash: ByteArray) {
    override fun toString() = "Challenge(<redacted>)"
}
@Serializable
class SessionGrant(val token: String, val expiresAt: Long) { override fun toString() = "SessionGrant(<redacted>)" }
@Serializable
class RecoveredBinding(val accountId:String, val deviceId:String, val routingId:String, val session:SessionGrant) {
    override fun toString()="RecoveredBinding(<redacted>)"
}
@Serializable
class DirectoryEntry(val accountId: String, val deviceId: String, val routingId: String, val username: String, val bundle: PublicBundle)
@Serializable
class SenderProfile(val accountId: String, val deviceId: String, val routingId: String, val username: String)
@Serializable
class DeliveryStatus(val submissionId: String, val acknowledged: Boolean)
@Serializable
class Delivery(val serverMessageId: String, val encryptedEnvelope: ByteArray, val receivedAt: Long, val expiresAt: Long, @EncodeDefault(EncodeDefault.Mode.NEVER) val sender: SenderProfile? = null) {
    override fun toString() = "Delivery(<opaque>)"
}
@Serializable
sealed class ApiRequest {
    abstract val version: Int
    final override fun toString() = "ApiRequest(<redacted>)"
    @Serializable @SerialName("recovery_challenge") class RecoveryIssue(val authPublicKey:ByteArray, val deviceId:String, override val version:Int=1):ApiRequest()
    @Serializable @SerialName("recovery_verify") class RecoveryVerify(val authPublicKey:ByteArray, val deviceId:String, val challengeId:String, val signature:ByteArray, override val version:Int=1):ApiRequest()
    @Serializable @SerialName("challenge") class Issue(val accountId: String, val deviceId: String, val purpose: String, val registrationHash: ByteArray = byteArrayOf(), override val version: Int = 1) : ApiRequest()
    @Serializable @SerialName("register") class Register(val registration: Registration, val challengeId: String, val signature: ByteArray, override val version: Int = 1) : ApiRequest()
    @Serializable @SerialName("verify") class Verify(val accountId: String, val deviceId: String, val challengeId: String, val signature: ByteArray, override val version: Int = 1) : ApiRequest()
    @Serializable @SerialName("revoke") class Revoke(override val version: Int = 1) : ApiRequest()
    @Serializable @SerialName("rename") class Rename(val username: String, override val version: Int = 1) : ApiRequest()
    @Serializable @SerialName("lookup") class Lookup(val username: String, override val version: Int = 1) : ApiRequest()
    @Serializable @SerialName("prekeys") class Prekeys(val deviceId: String, val bundles: List<PublicBundle>, override val version: Int = 1,
        @EncodeDefault(EncodeDefault.Mode.NEVER) val inspect: Boolean = false,
        @EncodeDefault(EncodeDefault.Mode.NEVER) val probeIds: List<Int> = emptyList()) : ApiRequest()
    @Serializable @SerialName("send") class Send(val submissionId: String, val recipientRoutingId: String, val encryptedEnvelope: ByteArray, override val version: Int = 1) : ApiRequest()
    @Serializable @SerialName("fetch") class Fetch(override val version: Int = 1, @EncodeDefault(EncodeDefault.Mode.NEVER) val includeSenders: Boolean = false,
        @EncodeDefault(EncodeDefault.Mode.NEVER) val submissionIds: List<String> = emptyList(),
        @EncodeDefault(EncodeDefault.Mode.NEVER) val skipMessageIds: List<String> = emptyList()) : ApiRequest()
    @Serializable @SerialName("ack") class Ack(val serverMessageIds: List<String>, override val version: Int = 1) : ApiRequest()
}
@Serializable
class ApiResponse(val version: Int = 1, val challenge: Challenge? = null, val session: SessionGrant? = null,
    val directory: DirectoryEntry? = null, val deliveries: List<Delivery> = emptyList(), val serverMessageId: String? = null,
    val error: String? = null, @EncodeDefault(EncodeDefault.Mode.NEVER) val statuses: List<DeliveryStatus> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val recovered:RecoveredBinding?=null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val prekeyInventory:PrekeyPool?=null) { override fun toString() = "ApiResponse(<redacted>)" }

@Serializable class PrekeyPool(val available: Int, val acceptedIds: List<Int> = emptyList())

object NetworkCodec {
    @PublishedApi internal val format = Cbor {
        encodeDefaults = true; ignoreUnknownKeys = false
        alwaysUseByteString = true; useDefiniteLengthEncoding = true
    }
    inline fun <reified T> encode(value: T): ByteArray = format.encodeToByteArray(value)
    inline fun <reified T> decode(bytes: ByteArray, max: Int = NetworkLimits.BODY): T {
        requireApi(bytes.size in 1..max, "body_size", 413)
        return try { format.decodeFromByteArray<T>(bytes).also { requireApi(encode(it).contentEquals(bytes), "noncanonical_body") } }
        catch (e: SerializationException) { throw ApiFailure(400, "invalid_schema") }
        catch (e: IllegalStateException) { throw ApiFailure(400, "invalid_schema") }
    }
}
object DeviceAuth {
    fun digest(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    fun publicKey(bytes: ByteArray): ECPublicKey {
        requireApi(bytes.size in 80..128, "invalid_credential")
        return try {
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes)) as ECPublicKey
            val parameters = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)
            requireApi(key.params.curve == parameters.curve && key.params.generator == parameters.generator && key.params.order == parameters.order && key.params.cofactor == parameters.cofactor, "invalid_credential")
            requireApi(key.encoded.contentEquals(bytes), "invalid_credential")
            key
        } catch (e: GeneralSecurityException) { throw ApiFailure(400, "invalid_credential") }
    }
    /** Length-prefixed UTF-8/bytes; integers use big endian. Never concatenate ambiguous strings. */
    fun statement(c: Challenge): ByteArray = ByteArrayOutputStream().also { out ->
        DataOutputStream(out).use { d ->
            d.writeInt(1)
            fun field(bytes: ByteArray) { d.writeInt(bytes.size); d.write(bytes) }
            listOf("GhostCloak.DeviceAuth", c.purpose, c.id, c.accountId, c.deviceId, c.audience).forEach { field(it.toByteArray(Charsets.UTF_8)) }
            field(c.random); d.writeLong(c.expiresAt); field(c.registrationHash)
        }
    }.toByteArray()
    fun verify(key: ByteArray, c: Challenge, signature: ByteArray): Boolean {
        if (signature.size !in 8..80) return false
        return try { Signature.getInstance("SHA256withECDSA").run { initVerify(publicKey(key)); update(statement(c)); verify(signature) } }
        catch (e: GeneralSecurityException) { false }
    }
}

object ApiRoutes {
    fun path(r: ApiRequest): String = when(r) {
        is ApiRequest.RecoveryIssue -> "/v1/auth/recovery/challenge"
        is ApiRequest.RecoveryVerify -> "/v1/auth/recovery/verify"
        is ApiRequest.Issue -> "/v1/auth/challenge"; is ApiRequest.Register -> "/v1/accounts"
        is ApiRequest.Verify -> "/v1/auth/verify"; is ApiRequest.Revoke -> "/v1/auth/revoke"
        is ApiRequest.Rename -> "/v1/accounts/username"; is ApiRequest.Lookup -> "/v1/directory/lookup"
        is ApiRequest.Prekeys -> "/v1/devices/prekeys"; is ApiRequest.Send -> "/v1/messages"
        is ApiRequest.Fetch -> "/v1/messages/fetch"; is ApiRequest.Ack -> "/v1/messages/ack"
    }
}
