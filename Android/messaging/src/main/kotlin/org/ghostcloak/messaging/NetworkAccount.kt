package org.ghostcloak.messaging

import org.ghostcloak.crypto.*
import org.ghostcloak.protocol.*
import org.ghostcloak.transport.*
import org.ghostcloak.identity.RandomIdentifiers
import java.security.*
import java.security.spec.*

/** EndpointRecords implementations must encrypt at rest (SQLCipher/Keystore on Android). */
enum class AccountConnectionState { NEW_ACCOUNT, REGISTERED, RECOVERY_REQUIRED, RECOVERING, RECOVERED, LOGGED_OUT }
class EndpointNetworkState(private val records: EndpointRecords, private val audience: String,
    private val credential: DeviceAuthCredential? = null) : AccessTokenStore, RoutingDirectory {
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
    fun remember(sender: SenderProfile) = records.transaction {
        requireApi(listOf(sender.accountId, sender.deviceId, sender.routingId).all(RandomIdentifiers::valid))
        requireApi(GhostCloakIds.valid(sender.ghostCloakId))
        val key = prefix + "route/${sender.deviceId}"
        requireApi(records.read(key)?.decodeToString()?.let { it == sender.routingId } != false, "routing_changed")
        records.write(key, sender.routingId.toByteArray())
    }
    fun registration(bundles: List<PublicBundle>): Registration = records.transaction {
        requireApi(records.read("local/device") != null, "identity_required")
        if (credential != null) {
            val legacy=records.read(prefix+"auth-private")
            if(legacy!=null) {legacy.fill(0); throw ApiFailure(409,"legacy_auth_requires_reset")}
            val storedAlias=records.read(prefix+"auth-alias")?.decodeToString()
            val existingPublic=records.read(prefix+"auth-public")
            requireApi((storedAlias==null)==(existingPublic==null),"credential_missing")
            if(storedAlias==null) requireApi(records.read(prefix+"account")==null && records.read(prefix+"routing")==null && records.read(prefix+"registered")==null,"credential_missing")
            val alias=storedAlias ?: ("ghostcloak.auth."+prefix.removePrefix("network/").removeSuffix("/")+"."+records.read("local/device")!!.decodeToString())
            val publicKey=credential.publicKey(alias,create=existingPublic==null)
            requireApi(existingPublic==null || MessageDigest.isEqual(existingPublic,publicKey),"credential_changed")
            if(existingPublic==null) {
                records.write(prefix+"auth-alias",alias.toByteArray()); records.write(prefix+"auth-public",publicKey)
                records.write(prefix+"account",RandomIdentifiers.create().toByteArray()); records.write(prefix+"routing",RandomIdentifiers.create().toByteArray())
            }
            return@transaction Registration(records.read(prefix+"account")!!.decodeToString(),records.read("local/device")!!.decodeToString(),
                records.read(prefix+"routing")!!.decodeToString(),publicKey,bundles)
        }
        requireApi(records.read(prefix+"auth-alias")==null,"platform_credential_required")
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
            records.read(prefix + "routing")!!.decodeToString(), records.read(prefix + "auth-public")!!, bundles)
    }
    fun sign(c: Challenge): ByteArray = records.transaction {
        requireApi(c.audience == audience && c.accountId == records.read(prefix + "account")?.decodeToString() && c.deviceId == records.read("local/device")?.decodeToString(), "challenge_binding")
        requireApi(c.random.size == 32 && c.purpose in setOf("register", "login") && c.expiresAt > System.currentTimeMillis(), "invalid_challenge")
        if(credential!=null) {
            if(records.read(prefix+"auth-private")!=null) throw ApiFailure(409,"legacy_auth_requires_reset")
            val alias=records.read(prefix+"auth-alias")?.decodeToString() ?: throw ApiFailure(401,"credential_missing")
            return@transaction credential.sign(alias,DeviceAuth.statement(c))
        }
        val secret = records.read(prefix + "auth-private") ?: throw ApiFailure(401, "credential_missing")
        try {
            val privateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(secret))
            Signature.getInstance("SHA256withECDSA").run { initSign(privateKey); update(DeviceAuth.statement(c)); sign() }
        } finally { secret.fill(0) }
    }
    fun registered():Boolean=records.transaction {records.read(prefix+"registered")!=null}
    fun registeredGroupPublicKey():ByteArray=records.transaction {
        requireApi(records.read(prefix+"registered")!=null,"credential_missing",401)
        val key=records.read(prefix+"auth-public") ?: throw ApiFailure(401,"credential_missing")
        DeviceAuth.publicKey(key)
        key
    }
    fun ghostCloakId():String=records.transaction {records.read(prefix+"ghostcloak-id")?.decodeToString()?.takeIf(GhostCloakIds::valid) ?: throw ApiFailure(401,"credential_missing")}
    fun markRegistered(ghostCloakId:String)=records.transaction {
        requireApi(GhostCloakIds.valid(ghostCloakId),"invalid_ghostcloak_id")
        val old=records.read(prefix+"ghostcloak-id")?.decodeToString()
        requireApi(old==null || old==ghostCloakId,"identity_changed",409)
        records.write(prefix+"ghostcloak-id",ghostCloakId.toByteArray())
        records.write(prefix+"registered",byteArrayOf(1))
        records.remove("app/new-network-account"); records.remove(prefix+"pending-registration")
        records.remove(prefix+"recovery-required"); records.remove(prefix+"logged-out")
    }
    /** Dormant P13.1 signer. Reuses the registered non-exportable device-auth key only for
     * recognized, separately domain-separated group statements. No login/recovery path calls it. */
    fun signGroupStatement(statement:ByteArray):ByteArray = records.transaction {
        require(GroupStatements.isGroupSigningStatement(statement))
        requireApi(credential!=null && records.read(prefix+"registered")!=null,"credential_missing",401)
        requireApi(records.read(prefix+"auth-private")==null,"platform_credential_required")
        val alias=records.read(prefix+"auth-alias")?.decodeToString() ?: throw ApiFailure(401,"credential_missing")
        val pinned=records.read(prefix+"auth-public") ?: throw ApiFailure(401,"credential_missing")
        requireApi(MessageDigest.isEqual(pinned,credential!!.publicKey(alias,false)),"credential_changed")
        credential.sign(alias,statement)
    }
    fun acceptSession(grant:SessionGrant)=records.transaction {
        requireApi(grant.token.matches(Regex("[A-Za-z0-9_-]{43}")) && grant.expiresAt>System.currentTimeMillis() &&
            GhostCloakIds.valid(grant.ghostCloakId),"invalid_response",502)
        markRegistered(grant.ghostCloakId)
        save(grant.token)
    }
    fun accountId():String=records.transaction {records.read(prefix+"account")?.decodeToString() ?: throw ApiFailure(401,"credential_missing")}
    fun ownRouting():String=records.transaction {records.read(prefix+"routing")?.decodeToString() ?: throw ApiFailure(401,"credential_missing")}
    fun ownDevice():String=records.transaction {records.read("local/device")?.decodeToString() ?: throw ApiFailure(401,"credential_missing")}
    fun allocationIdFor(ghostCloakId:String):String=records.transaction {
        val id=GhostCloakIds.normalize(ghostCloakId)
        val key=prefix+"allocation/"+id
        records.read(key)?.decodeToString()?.split('|')?.let { parts ->
            val age=parts.getOrNull(1)?.toLongOrNull()?.let {System.currentTimeMillis()-it}
            requireApi(RandomIdentifiers.valid(parts[0]) && age!=null && age in 0 until 82800000L,"allocation_retry_expired",409)
            parts[0]
        } ?: RandomIdentifiers.create().also {records.write(key,"$it|${System.currentTimeMillis()}".toByteArray())}
    }
    fun completeAllocation(ghostCloakId:String)=records.transaction {records.remove(prefix+"allocation/"+GhostCloakIds.normalize(ghostCloakId))}
    fun connectionState():AccountConnectionState=records.transaction {
        when {
            records.read(prefix+"logged-out")!=null -> AccountConnectionState.LOGGED_OUT
            records.read(prefix+"recovery-required")!=null -> AccountConnectionState.RECOVERY_REQUIRED
            registered() && records.read(prefix+"ghostcloak-id")?.decodeToString()?.let(GhostCloakIds::valid)==true -> AccountConnectionState.REGISTERED
            records.read("app/new-network-account")!=null && records.keys("network/").all {it.startsWith(prefix)} -> AccountConnectionState.NEW_ACCOUNT
            else -> AccountConnectionState.RECOVERY_REQUIRED
        }
    }
    fun requireRecovery()=records.transaction {records.write(prefix+"recovery-required",byteArrayOf(1))}
    fun markLoggedOut()=records.transaction {records.write(prefix+"logged-out",byteArrayOf(1))}
    fun pendingRegistration():Registration?=records.transaction {records.read(prefix+"pending-registration")?.let {NetworkCodec.decode<Registration>(it)}}
    fun prepareNew(bundles:List<PublicBundle>):Registration=records.transaction {
        requireApi(connectionState()==AccountConnectionState.NEW_ACCOUNT,"recovery_required",401)
        pendingRegistration() ?: registration(bundles).also {records.write(prefix+"pending-registration",NetworkCodec.encode(it))}
    }
    /** Never creates or replaces a key, even with missing candidate account/routing records. */
    fun recoveryPublicKey():ByteArray=records.transaction {
        requireApi(records.read(prefix+"auth-private")==null && credential!=null,"recovery_failed",401)
        val alias=records.read(prefix+"auth-alias")?.decodeToString() ?: throw ApiFailure(401,"recovery_failed")
        val public=records.read(prefix+"auth-public") ?: throw ApiFailure(401,"recovery_failed")
        requireApi(MessageDigest.isEqual(public,credential!!.publicKey(alias,false)),"recovery_failed",401)
        DeviceAuth.publicKey(public); public
    }
    fun signRecovery(c:Challenge,public:ByteArray):ByteArray=records.transaction {
        requireApi(c.purpose=="recover" && c.audience==audience && c.deviceId==records.read("local/device")?.decodeToString() &&
            c.random.size==32 && c.expiresAt>System.currentTimeMillis() && c.expiresAt<=System.currentTimeMillis()+120000 &&
            RandomIdentifiers.valid(c.id) && RandomIdentifiers.valid(c.accountId) &&
            MessageDigest.isEqual(c.registrationHash,DeviceAuth.digest(public)) &&
            MessageDigest.isEqual(public,recoveryPublicKey()),"recovery_failed",401)
        credential!!.sign(records.read(prefix+"auth-alias")!!.decodeToString(),DeviceAuth.statement(c))
    }
    fun commitRecovery(binding:RecoveredBinding,public:ByteArray)=records.transaction {
        requireApi(binding.deviceId==records.read("local/device")?.decodeToString() &&
            listOf(binding.accountId,binding.deviceId,binding.routingId).all(RandomIdentifiers::valid) &&
            setOf(binding.accountId,binding.deviceId,binding.routingId).size==3 &&
            binding.session.token.matches(Regex("[A-Za-z0-9_-]{43}")) && binding.session.expiresAt>System.currentTimeMillis() &&
            GhostCloakIds.valid(binding.session.ghostCloakId) &&
            MessageDigest.isEqual(public,recoveryPublicKey()),"recovery_failed",401)
        records.write(prefix+"account",binding.accountId.toByteArray())
        records.write(prefix+"routing",binding.routingId.toByteArray())
        save(binding.session.token);markRegistered(binding.session.ghostCloakId)
        records.remove("app/renewal-blocked/$audience")
    }
}
class NetworkAccount(private val client: HttpGhostClient, private val state: EndpointNetworkState) {
    suspend fun register(registration: Registration) {
        val hash = DeviceAuth.digest(NetworkCodec.encode(registration))
        val c = client.unauthenticated(ApiRequest.Issue(registration.accountId, registration.deviceId, "register", hash)).challenge ?: throw ApiFailure(502, "invalid_response")
        requireApi(c.purpose == "register" && c.registrationHash.contentEquals(hash), "challenge_binding")
        val id=client.unauthenticated(ApiRequest.Register(registration, c.id, state.sign(c))).registeredId
        requireApi(id!=null && GhostCloakIds.valid(id),"invalid_response",502)
    }
    suspend fun login(accountId: String, deviceId: String) {
        val c = client.unauthenticated(ApiRequest.Issue(accountId, deviceId, "login")).challenge ?: throw ApiFailure(502, "invalid_response")
        requireApi(c.purpose == "login" && c.registrationHash.isEmpty(), "challenge_binding")
        val grant = client.unauthenticated(ApiRequest.Verify(accountId, deviceId, c.id, state.sign(c))).session ?: throw ApiFailure(502, "invalid_response")
        requireApi(grant.token.matches(Regex("[A-Za-z0-9_-]{43}")) && grant.expiresAt > System.currentTimeMillis() && GhostCloakIds.valid(grant.ghostCloakId), "invalid_response", 502)
        state.acceptSession(grant)
    }
    suspend fun logout() { client.call(ApiRequest.Revoke()); state.save(null) }
    /** Establish trust through the engine before storing routing data; public IDs never bypass pins. */
    suspend fun connect(ghostCloakId: String, engine: SecureSessionEngine): DirectoryEntry {
        val entry = client.lookup(ghostCloakId,state.allocationIdFor(ghostCloakId))
        requireApi(entry.ghostCloakId == GhostCloakIds.normalize(ghostCloakId) && entry.deviceId == entry.bundle.deviceId &&
            RandomIdentifiers.valid(entry.accountId) && RandomIdentifiers.valid(entry.routingId), "directory_mismatch")
        entry.bundle.validate()
        engine.establishSession(entry.bundle.remote())
        state.remember(entry)
        state.completeAllocation(ghostCloakId)
        return entry
    }
}
fun RemoteKeyBundle.publicData() = PublicBundle(deviceId, registrationId, identity, preKeyId, preKey, signedId, signedKey, signature, kyberId, kyberKey, kyberSignature)
fun PublicBundle.remote() = RemoteKeyBundle(deviceId, registrationId, identity, preKeyId, preKey, signedId, signedKey, signature, kyberId, kyberKey, kyberSignature)
