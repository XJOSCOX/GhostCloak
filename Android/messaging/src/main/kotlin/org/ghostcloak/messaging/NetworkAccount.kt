package org.ghostcloak.messaging

import org.ghostcloak.crypto.*
import org.ghostcloak.protocol.*
import org.ghostcloak.transport.*
import org.ghostcloak.identity.RandomIdentifiers
import java.security.*
import java.security.spec.*

/** EndpointRecords implementations must encrypt at rest (SQLCipher/Keystore on Android). */
class EndpointNetworkState(private val records: EndpointRecords, private val audience: String) : AccessTokenStore, RoutingDirectory {
    private val prefix = "network/${DeviceAuth.digest(audience.toByteArray()).joinToString("") { "%02x".format(it) }}/"
    override fun read(): String? = records.transaction { records.read(prefix + "token")?.decodeToString() }
    override fun save(token: String?) = records.transaction { if (token == null) records.remove(prefix + "token") else records.write(prefix + "token", token.toByteArray()) }
    override fun route(deviceId: String): String? = records.transaction { records.read(prefix + "route/$deviceId")?.decodeToString() }
    fun remember(entry: DirectoryEntry) = records.transaction {
        requireApi(RandomIdentifiers.valid(entry.deviceId) && RandomIdentifiers.valid(entry.routingId) && entry.deviceId == entry.bundle.deviceId)
        val old = records.read(prefix + "route/${entry.deviceId}")
        requireApi(old == null || old.decodeToString() == entry.routingId, "routing_changed")
        records.write(prefix + "route/${entry.deviceId}", entry.routingId.toByteArray())
    }
    fun registration(username: String, bundles: List<PublicBundle>): Registration = records.transaction {
        requireApi(records.read("local/device") != null, "identity_required")
        var encoded = records.read(prefix + "auth-private")
        if (encoded == null) {
            requireApi(records.read(prefix + "account") == null && records.read(prefix + "auth-public") == null, "credential_missing")
            val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), SecureRandom()) }.generateKeyPair()
            encoded = pair.private.encoded
            records.write(prefix + "auth-private", encoded!!)
            records.write(prefix + "auth-public", pair.public.encoded)
            records.write(prefix + "account", RandomIdentifiers.create().toByteArray())
            records.write(prefix + "routing", RandomIdentifiers.create().toByteArray())
        }
        encoded?.fill(0)
        Registration(records.read(prefix + "account")!!.decodeToString(), records.read("local/device")!!.decodeToString(),
            records.read(prefix + "routing")!!.decodeToString(), Usernames.normalize(username), records.read(prefix + "auth-public")!!, bundles)
    }
    fun sign(c: Challenge): ByteArray = records.transaction {
        requireApi(c.audience == audience && c.accountId == records.read(prefix + "account")?.decodeToString() && c.deviceId == records.read("local/device")?.decodeToString(), "challenge_binding")
        requireApi(c.random.size == 32 && c.purpose in setOf("register", "login") && c.expiresAt > System.currentTimeMillis(), "invalid_challenge")
        val secret = records.read(prefix + "auth-private") ?: throw ApiFailure(401, "credential_missing")
        try {
            val privateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(secret))
            Signature.getInstance("SHA256withECDSA").run { initSign(privateKey); update(DeviceAuth.statement(c)); sign() }
        } finally { secret.fill(0) }
    }
}
class NetworkAccount(private val client: HttpGhostClient, private val state: EndpointNetworkState) {
    suspend fun register(registration: Registration) {
        val hash = DeviceAuth.digest(NetworkCodec.encode(registration))
        val c = client.unauthenticated(ApiRequest.Issue(registration.accountId, registration.deviceId, "register", hash)).challenge ?: throw ApiFailure(502, "invalid_response")
        requireApi(c.purpose == "register" && c.registrationHash.contentEquals(hash), "challenge_binding")
        client.unauthenticated(ApiRequest.Register(registration, c.id, state.sign(c)))
    }
    suspend fun login(accountId: String, deviceId: String) {
        val c = client.unauthenticated(ApiRequest.Issue(accountId, deviceId, "login")).challenge ?: throw ApiFailure(502, "invalid_response")
        requireApi(c.purpose == "login" && c.registrationHash.isEmpty(), "challenge_binding")
        val grant = client.unauthenticated(ApiRequest.Verify(accountId, deviceId, c.id, state.sign(c))).session ?: throw ApiFailure(502, "invalid_response")
        requireApi(grant.token.matches(Regex("[A-Za-z0-9_-]{43}")) && grant.expiresAt > System.currentTimeMillis(), "invalid_response", 502)
        state.save(grant.token)
    }
    suspend fun logout() { client.call(ApiRequest.Revoke()); state.save(null) }
    /** Establish trust through the engine before storing routing data; usernames never bypass pins. */
    suspend fun connect(username: String, engine: SecureSessionEngine): DirectoryEntry {
        val entry = client.lookup(username)
        requireApi(entry.username == Usernames.normalize(username) && entry.deviceId == entry.bundle.deviceId &&
            RandomIdentifiers.valid(entry.accountId) && RandomIdentifiers.valid(entry.routingId), "directory_mismatch")
        entry.bundle.validate()
        engine.establishSession(entry.bundle.remote())
        state.remember(entry)
        return entry
    }
}
fun RemoteKeyBundle.publicData() = PublicBundle(deviceId, registrationId, identity, preKeyId, preKey, signedId, signedKey, signature, kyberId, kyberKey, kyberSignature)
fun PublicBundle.remote() = RemoteKeyBundle(deviceId, registrationId, identity, preKeyId, preKey, signedId, signedKey, signature, kyberId, kyberKey, kyberSignature)
