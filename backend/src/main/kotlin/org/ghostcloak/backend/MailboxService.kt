package org.ghostcloak.backend

import org.ghostcloak.protocol.*
import org.ghostcloak.identity.RandomIdentifiers
import java.security.SecureRandom
import java.security.MessageDigest
import java.time.Clock
import java.util.Base64

enum class ServerOperation { REGISTER, CHALLENGE, VERIFY, REVOKE, LOOKUP, ALLOCATE, ALLOCATE_TARGET, PREKEYS, SEND, FETCH, ACK, RECOVER_ISSUE, RECOVER_VERIFY, CHALLENGE_GLOBAL, VERIFY_GLOBAL }
fun interface RateLimiter { fun allow(operation: ServerOperation, principal: String, now: Long): Boolean }
internal fun rateMaximum(operation:ServerOperation, limit:Int):Int = when(operation) {
    ServerOperation.CHALLENGE_GLOBAL, ServerOperation.VERIFY_GLOBAL -> minOf(limit * 10,600)
    ServerOperation.CHALLENGE, ServerOperation.REGISTER, ServerOperation.VERIFY,
    ServerOperation.RECOVER_ISSUE, ServerOperation.RECOVER_VERIFY -> minOf(limit,10)
    ServerOperation.ALLOCATE_TARGET -> minOf(limit,2)
    ServerOperation.LOOKUP -> minOf(limit * 3,180)
    else -> limit
}
/** Fixed-window prototype control. No IP or device fingerprint collection. */
class DevelopmentRateLimiter(private val limit: Int = 60) : RateLimiter {
    private val counters = mutableMapOf<Pair<ServerOperation, String>, Pair<Long, Int>>()
    @Synchronized override fun allow(operation: ServerOperation, principal: String, now: Long): Boolean {
        val window = now / 60000
        counters.entries.removeIf { it.value.first != window }
        val key = operation to principal
        val count = counters[key]?.second ?: 0
        val maximum=rateMaximum(operation,limit)
        if (count >= maximum || (key !in counters && counters.size >= 2048)) return false
        counters[key] = window to count + 1
        return true
    }
}
enum class ServerResult { OK, REJECTED, INTERNAL }
fun interface ServerLogger { fun record(operation: ServerOperation, result: ServerResult) }
enum class AbuseEvent { RECOVERY_RATE_LIMITED, GLOBAL_RATE_LIMITED, PREKEY_ALLOCATION_RATE_LIMITED, PREKEY_ALLOCATION_REPLAYED }
class BackendPolicy(val audience: String = "ghostcloak.local", val challengeTtl: Long = 60000,
    val sessionTtl: Long = 300000, val mailboxTtl: Long = 604800000, val dedupeTtl: Long = 2592000000) {
    init { require(audience.matches(Regex("[a-z0-9.-]{1,100}"))); require(challengeTtl in 1000..120000 && sessionTtl in 1000..900000 && mailboxTtl in 1000..604800000 && dedupeTtl in mailboxTtl..2592000000) }
}
/** Transport limits; one logical five-member group text consumes four SENDs. */
object DeliveryCapacity {
    const val SENDER_LIVE_V3 = 16_384
    const val GLOBAL_LIVE_V3 = 100_000
    const val LEGACY_SENDER = 1_024
    const val LEGACY_GLOBAL = 10_000
    const val RECIPIENT_MAILBOX_ROWS = 512
    const val GLOBAL_MAILBOX_ROWS = 20_000
    const val RECIPIENT_MAILBOX_BYTES = 16L * 1024 * 1024
}
class MailboxService(private val db: BackendDatabase, private val clock: Clock = Clock.systemUTC(),
    val policy: BackendPolicy = BackendPolicy(), private val rate: RateLimiter = DevelopmentRateLimiter(),
    private val logger: ServerLogger = ServerLogger { _, _ -> },
    private val abuseLog:(AbuseEvent)->Unit = {},
    private val newGhostCloakId: () -> String = { GhostCloakIds.generate() }) {
    private val random = SecureRandom()
    private data class AllocationOutcome(val response:ApiResponse?=null,val failure:ApiFailure?=null)
    private fun now() = clock.millis()
    private fun freshBytes() = ByteArray(32).also(random::nextBytes)
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun tokenHash(token: String): String { requireApi(token.matches(Regex("[A-Za-z0-9_-]{43}")), "unauthorized", 401); return hex(DeviceAuth.digest(token.toByteArray())) }
    private fun anonymousPrincipal(r:ApiRequest):String = when(r) {
        is ApiRequest.RecoveryIssue -> hex(DeviceAuth.digest(r.authPublicKey))
        is ApiRequest.RecoveryVerify -> hex(DeviceAuth.digest(r.authPublicKey + r.challengeId.toByteArray()))
        is ApiRequest.Issue -> hex(DeviceAuth.digest(r.accountId.toByteArray() + r.deviceId.toByteArray() + r.registrationHash))
        is ApiRequest.Register -> hex(DeviceAuth.digest(r.challengeId.toByteArray()))
        is ApiRequest.Verify -> hex(DeviceAuth.digest(r.challengeId.toByteArray()))
        else -> "anonymous"
    }
    fun operation(r: ApiRequest): ServerOperation = when(r) {
        is ApiRequest.RecoveryIssue -> ServerOperation.RECOVER_ISSUE
        is ApiRequest.RecoveryVerify -> ServerOperation.RECOVER_VERIFY
        is ApiRequest.Issue -> ServerOperation.CHALLENGE; is ApiRequest.Register -> ServerOperation.REGISTER
        is ApiRequest.Verify -> ServerOperation.VERIFY; is ApiRequest.Revoke -> ServerOperation.REVOKE
        is ApiRequest.Lookup, is ApiRequest.CapabilityLookup -> ServerOperation.LOOKUP
        is ApiRequest.Allocate -> ServerOperation.ALLOCATE
        is ApiRequest.Prekeys, is ApiRequest.Capabilities -> ServerOperation.PREKEYS; is ApiRequest.Send -> ServerOperation.SEND
        is ApiRequest.Fetch -> ServerOperation.FETCH; is ApiRequest.Ack -> ServerOperation.ACK
    }
    fun execute(request: ApiRequest, token: String? = null): ApiResponse {
        // Copy/validate the boundary even for an in-process caller; prevents retained mutable input.
        val r = NetworkCodec.decode<ApiRequest>(NetworkCodec.encode(request))
        val op = operation(r)
        try {
            requireApi(r.version == 2, "unsupported_version")
            val preauth = r is ApiRequest.RecoveryIssue || r is ApiRequest.RecoveryVerify || r is ApiRequest.Issue || r is ApiRequest.Register || r is ApiRequest.Verify
            val principal = if (preauth) anonymousPrincipal(r) else if(token==null) "anonymous" else db.transaction { db.sessions.get(tokenHash(token))?.deviceId ?: "anonymous" }
            if(r !is ApiRequest.Allocate && !rate.allow(op, principal, now())) {
                if(r is ApiRequest.RecoveryIssue || r is ApiRequest.RecoveryVerify) abuseLog(AbuseEvent.RECOVERY_RATE_LIMITED)
                throw ApiFailure(429,"rate_limited")
            }
            if (r is ApiRequest.Issue || r is ApiRequest.RecoveryIssue)
                if(!rate.allow(ServerOperation.CHALLENGE_GLOBAL,"all",now())) {abuseLog(AbuseEvent.GLOBAL_RATE_LIMITED);throw ApiFailure(429,"rate_limited")}
            if (r is ApiRequest.RecoveryVerify)
                if(!rate.allow(ServerOperation.VERIFY_GLOBAL,"all",now())) {abuseLog(AbuseEvent.GLOBAL_RATE_LIMITED);throw ApiFailure(429,"rate_limited")}
            cleanup()
            // Claim a challenge in its own committed transaction. Every verification attempt burns it,
            // including failed registration or a bad signature. No error can restore it.
            val claimed = when(r) {
                is ApiRequest.RecoveryVerify -> try { consume(r.challengeId) } catch (_:ApiFailure) { throw ApiFailure(401,"recovery_failed") }
                is ApiRequest.Register -> consume(r.challengeId)
                is ApiRequest.Verify -> consume(r.challengeId)
                else -> null
            }
            val result = if(r is ApiRequest.Allocate) {
                val outcome=db.transaction { allocate(r,token ?: throw ApiFailure(401,"unauthorized")) }
                outcome.failure?.let {throw it}
                requireNotNull(outcome.response)
            } else db.transaction {
                when(r) {
                    is ApiRequest.RecoveryIssue -> recoveryIssue(r)
                    is ApiRequest.RecoveryVerify -> recover(r,claimed!!)
                    is ApiRequest.Issue -> issue(r)
                    is ApiRequest.Register -> register(r, claimed!!)
                    is ApiRequest.Verify -> login(r, claimed!!)
                    else -> authenticated(r, token ?: throw ApiFailure(401, "unauthorized"))
                }
            }
            logger.record(op, ServerResult.OK)
            return NetworkCodec.decode(NetworkCodec.encode(result), NetworkLimits.RESPONSE)
        } catch (e: ApiFailure) { logger.record(op, ServerResult.REJECTED); throw e }
    }
    fun cleanup() = db.transaction {
        val time = now()
        db.expiredChallengeIds(time).forEach { db.challenges.remove(it) }
        db.expiredSessionHashes(time).forEach { db.sessions.remove(it) }
        db.expireMailbox(time)
        // Small bounded receipt/idempotency tombstones survive payload deletion and sender downtime.
    }
    private fun consume(id: String): Challenge = db.transaction {
        requireApi(RandomIdentifiers.valid(id))
        val row = db.challenges.get(id) ?: throw ApiFailure(401, "invalid_challenge")
        db.challenges.remove(id)
        row.challenge
    }
    private fun issue(r: ApiRequest.Issue): ApiResponse {
        requireApi(RandomIdentifiers.valid(r.accountId) && RandomIdentifiers.valid(r.deviceId))
        requireApi((r.purpose == "register" && r.registrationHash.size == 32) || (r.purpose == "login" && r.registrationHash.isEmpty()))
        requireApi(db.challenges.size() < 1000, "capacity", 429)
        // Login challenges do not confirm account existence.
        val c = Challenge(RandomIdentifiers.create(), freshBytes(), now() + policy.challengeTtl, policy.audience, r.accountId, r.deviceId, r.purpose, r.registrationHash)
        db.challenges.put(c.id, ChallengeRow(c)); return ApiResponse(challenge = c)
    }
    private fun proof(c: Challenge, account: String, device: String, purpose: String, key: ByteArray, signature: ByteArray) {
        requireApi(c.expiresAt > now() && c.audience == policy.audience && c.accountId == account && c.deviceId == device && c.purpose == purpose && DeviceAuth.verify(key, c, signature), "invalid_proof", 401)
    }
    private fun register(r: ApiRequest.Register, c: Challenge): ApiResponse {
        val v = r.registration
        requireApi(listOf(v.accountId, v.deviceId, v.routingId).all(RandomIdentifiers::valid) && setOf(v.accountId, v.deviceId, v.routingId).size == 3)
        DeviceAuth.publicKey(v.authPublicKey)
        requireApi(MessageDigest.isEqual(c.registrationHash, DeviceAuth.digest(NetworkCodec.encode(v))), "invalid_proof", 401)
        proof(c, v.accountId, v.deviceId, "register", v.authPublicKey, r.signature)
        requireApi(db.accounts.size() < 1000, "capacity", 429)
        requireApi(db.accounts.get(v.accountId) == null && db.devices.get(v.deviceId) == null && db.deviceByRoutingId(v.routingId) == null && db.devices.all().none { it.authPublicKey.contentEquals(v.authPublicKey) }, "conflict", 409)
        validateBundles(v.deviceId, v.bundles, null)
        var assigned: String? = null
        for (attempt in 1..5) {
            val candidate = newGhostCloakId()
            requireApi(GhostCloakIds.valid(candidate), "invalid_ghostcloak_id")
            if (db.accountByGhostCloakId(candidate) == null) { assigned = candidate; break }
        }
        requireApi(assigned != null, "capacity", 503)
        db.accounts.put(v.accountId, AccountRow(v.accountId, assigned!!, v.deviceId))
        db.devices.put(v.deviceId, DeviceRow(v.deviceId, v.accountId, v.routingId, v.authPublicKey, v.bundles.first().identity))
        upload(v.deviceId, v.bundles)
        return ApiResponse(registeredId = assigned)
    }
    private fun login(r: ApiRequest.Verify, c: Challenge): ApiResponse {
        val device = db.devices.get(r.deviceId) ?: throw ApiFailure(401, "invalid_proof")
        requireApi(device.accountId == r.accountId, "invalid_proof", 401)
        proof(c, r.accountId, r.deviceId, "login", device.authPublicKey, r.signature)
        // Single active session for this single-device phase.
        db.sessions.all().filter { it.deviceId == device.id }.forEach { db.sessions.remove(it.hash) }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(freshBytes())
        val hash = tokenHash(token); val expires = now() + policy.sessionTtl
        db.sessions.put(hash, SessionRow(hash, device.id, expires))
        return ApiResponse(session = SessionGrant(token, expires, db.accounts.get(device.accountId)!!.ghostCloakId))
    }
    private fun validateBundles(deviceId: String, bundles: List<PublicBundle>, existing: PrekeyRow?) {
        requireApi(bundles.size in 1..NetworkLimits.BUNDLES && (existing?.pool?.size ?: 0) + bundles.size <= 32)
        requireApi((existing?.usedEc?.size ?: 0) + bundles.size <= 10000, "prekey_capacity", 429)
        val ec = mutableSetOf<Int>(); val pq = mutableSetOf<Int>(); val signed = existing?.signed?.toMutableMap() ?: mutableMapOf()
        val identity = db.devices.get(deviceId)?.identity ?: bundles.first().identity
        bundles.forEach {
            it.validate(); requireApi(it.capability == null && it.deviceId == deviceId && it.identity.contentEquals(identity))
            requireApi(ec.add(it.preKeyId) && pq.add(it.kyberId) && it.preKeyId !in (existing?.usedEc ?: emptySet()) && it.kyberId !in (existing?.usedPq ?: emptySet()), "duplicate_prekey", 409)
            val material = it.signedKey + it.signature
            requireApi(signed[it.signedId]?.contentEquals(material) != false, "signed_key_conflict", 409)
            signed[it.signedId] = material
        }
        requireApi(signed.size <= 10000, "prekey_capacity", 429)
    }
    private fun upload(deviceId: String, bundles: List<PublicBundle>) {
        val old = db.prekeys.get(deviceId); validateBundles(deviceId, bundles, old)
        db.prekeys.put(deviceId, PrekeyRow(deviceId, (old?.pool ?: emptyList()) + bundles,
            (old?.usedEc ?: emptySet()) + bundles.map { it.preKeyId }, (old?.usedPq ?: emptySet()) + bundles.map { it.kyberId },
            (old?.signed ?: emptyMap()) + bundles.associate { it.signedId to (it.signedKey + it.signature) }))
    }
    fun authenticateSession(token: String): DeviceRow = db.transaction {
        val session = db.sessions.get(tokenHash(token)) ?: throw ApiFailure(401, "unauthorized")
        requireApi(session.expiresAt > now(), "unauthorized", 401)
        db.devices.get(session.deviceId) ?: throw ApiFailure(401, "unauthorized")
    }
    private fun recoveryIssue(r:ApiRequest.RecoveryIssue):ApiResponse {
        DeviceAuth.publicKey(r.authPublicKey)
        requireApi(RandomIdentifiers.valid(r.deviceId))
        requireApi(db.challenges.size()<1000,"capacity",429)
        // No device lookup: identical issuance for known and unknown credentials.
        // accountId is an unrelated random transcript field, never the account binding.
        val c=Challenge(RandomIdentifiers.create(),freshBytes(),now()+minOf(policy.challengeTtl,60000),
            policy.audience,RandomIdentifiers.create(),r.deviceId,"recover",DeviceAuth.digest(r.authPublicKey))
        db.challenges.put(c.id,ChallengeRow(c))
        return ApiResponse(challenge=c)
    }
    private fun recover(r:ApiRequest.RecoveryVerify,c:Challenge):ApiResponse {
        try { DeviceAuth.publicKey(r.authPublicKey) } catch (_:Exception) { throw ApiFailure(401,"recovery_failed") }
        // Scan the bounded device set without an existence-dependent early return.
        val matches=db.devices.all().filter { MessageDigest.isEqual(it.authPublicKey,r.authPublicKey) }
        val device=matches.singleOrNull()
        val validSignature=DeviceAuth.verify(device?.authPublicKey ?: r.authPublicKey,c,r.signature)
        requireApi(validSignature && device!=null && device.id==r.deviceId && c.deviceId==r.deviceId &&
            c.purpose=="recover" && c.audience==policy.audience && c.expiresAt>now() &&
            MessageDigest.isEqual(c.registrationHash,DeviceAuth.digest(r.authPublicKey)),"recovery_failed",401)
        val existing=device!!
        requireApi(db.accounts.get(existing.accountId)?.deviceId==existing.id,"recovery_failed",401)
        // Same single-session semantics as login; no account/device/key mutation.
        db.sessions.all().filter { it.deviceId==existing.id }.forEach { db.sessions.remove(it.hash) }
        val token=Base64.getUrlEncoder().withoutPadding().encodeToString(freshBytes())
        val expiry=now()+policy.sessionTtl
        db.sessions.put(tokenHash(token),SessionRow(tokenHash(token),existing.id,expiry))
        return ApiResponse(recovered=RecoveredBinding(existing.accountId,existing.id,existing.routingId,SessionGrant(token,expiry,db.accounts.get(existing.accountId)!!.ghostCloakId)))
    }
    private fun authenticated(r: ApiRequest, token: String): ApiResponse {
        val hash = tokenHash(token)
        val device = authenticateSession(token)
        return when(r) {
            is ApiRequest.Revoke -> { db.sessions.remove(hash); ApiResponse() }
            is ApiRequest.Lookup -> {
                val id = GhostCloakIds.normalize(r.ghostCloakId)
                requireApi(id == r.ghostCloakId, "invalid_ghostcloak_id")
                val account = db.accountByGhostCloakId(id) ?: throw ApiFailure(404, "contact_unavailable")
                val target = db.devices.get(account.deviceId)!!
                requireApi(db.prekeys.get(target.id)?.pool?.isNotEmpty()==true,"contact_unavailable",404)
                // Old clients require directory.bundle and therefore fail explicitly; no key is allocated by search.
                ApiResponse(discovery=DirectorySummary(account.id,target.id,target.routingId,id))
            }
            is ApiRequest.CapabilityLookup -> {
                requireApi(RandomIdentifiers.valid(r.deviceId))
                requireApi((r.expectedAccountId==null)==(r.expectedIdentityDigest==null))
                val expectedAccount=r.expectedAccountId
                val expectedDigest=r.expectedIdentityDigest
                if(expectedAccount!=null) {
                    requireApi(RandomIdentifiers.valid(expectedAccount) && expectedDigest?.size==32)
                    val target=db.devices.get(r.deviceId)
                    // All exact-target mismatches have the same response. No prekey is read or consumed.
                    if(target==null || target.accountId!=expectedAccount ||
                        !MessageDigest.isEqual(DeviceAuth.digest(target.identity),expectedDigest))
                        throw ApiFailure(404,"contact_unavailable")
                    DeviceAuth.publicKey(target.authPublicKey)
                    ApiResponse(deviceBinding=DeviceBinding(accountId=target.accountId,deviceId=target.id,
                        authPublicKey=target.authPublicKey,identityDigest=DeviceAuth.digest(target.identity)))
                } else {
                    val target = db.devices.get(r.deviceId) ?: throw ApiFailure(404,"contact_unavailable")
                    val account = db.accounts.get(target.accountId)!!
                    // Refresh never consumes/resurrects a one-time key or establishes another session.
                    val bundle = db.prekeys.get(target.id)?.pool?.firstOrNull { it.capability != null }
                        ?: db.prekeys.get(target.id)?.pool?.firstOrNull()
                        ?: throw ApiFailure(404,"contact_unavailable")
                    ApiResponse(directory=DirectoryEntry(account.id,target.id,target.routingId,account.ghostCloakId,bundle),serverTime=now())
                }
            }
            is ApiRequest.Capabilities -> {
                requireApi(r.advertisements.size <= NetworkLimits.BUNDLES)
                val keys = db.prekeys.get(device.id) ?: throw ApiFailure(404,"not_found")
                if(r.advertisements.isEmpty()) {
                    ApiResponse(capabilityInventory=keys.pool,serverTime=now())
                } else {
                    requireApi(r.advertisements.map {it.preKeyId}.distinct().size == r.advertisements.size)
                    val replacements = r.advertisements.associateBy { it.preKeyId }
                    r.advertisements.forEach { b ->
                        val original = keys.pool.firstOrNull { it.preKeyId == b.preKeyId } ?: throw ApiFailure(409,"prekey_consumed")
                        requireApi(b.deviceId == device.id && b.identity.contentEquals(device.identity) &&
                            AttachmentCapabilities.digest(original).contentEquals(AttachmentCapabilities.digest(b)),"capability_binding")
                        requireApi(org.ghostcloak.capabilities.CapabilitySignatures.verify(policy.audience,device.accountId,device.routingId,b,now()),"invalid_capability")
                        requireApi((original.capability?.issuedAt ?: 0L) <= b.capability!!.issuedAt,"capability_rollback")
                    }
                    db.prekeys.put(device.id,PrekeyRow(device.id,keys.pool.map {replacements[it.preKeyId] ?: it},keys.usedEc,keys.usedPq,keys.signed))
                    ApiResponse()
                }
            }
            is ApiRequest.Prekeys -> {
                requireApi(r.deviceId == device.id, "forbidden", 403)
                if (r.inspect) {
                    requireApi(r.bundles.isEmpty() && r.probeIds.size <= NetworkLimits.BUNDLES &&
                        r.probeIds.distinct().size == r.probeIds.size && r.probeIds.all { it > 0 })
                    val keys = db.prekeys.get(device.id)
                    ApiResponse(prekeyInventory = PrekeyPool(keys?.pool?.size ?: 0,
                        r.probeIds.filter { it in (keys?.usedEc ?: emptySet()) }))
                } else {
                    requireApi(r.probeIds.isEmpty())
                    upload(device.id, r.bundles); ApiResponse()
                }
            }
            is ApiRequest.Send -> send(device, r)
            is ApiRequest.Fetch -> {
                requireApi(r.submissionIds.size <= NetworkLimits.BATCH && r.submissionIds.all { SubmissionIds.parse(it) != null })
                // Prefix lookup discloses only the caller's submissions; unknown/foreign IDs fail identically.
                val statuses = r.submissionIds.map { id ->
                    val row = db.submissions.get(device.id + "/" + id)
                    if (row == null && (SubmissionIds.parse(id) as? SubmissionIds.Parsed.ExpiringV3)?.expiresAt?.let { it < now() } == true)
                        DeliveryStatus(id, false, unavailable = true)
                    else {
                        row ?: throw ApiFailure(404, "not_found")
                        DeliveryStatus(id, row.acknowledged, r.retention && !row.acknowledged && row.mailboxExpiresAt <= now())
                    }
                }
                requireApi(r.skipMessageIds.size <= 128 && r.skipMessageIds.all(RandomIdentifiers::valid))
                val owned = db.mailboxForRecipient(device.routingId).filter { it.expiresAt > now() }
                    .sortedWith(compareBy<MailboxRow> { it.receivedAt }.thenBy { it.id })
                val deliveries = owned.filter { it.id !in r.skipMessageIds }.take(NetworkLimits.BATCH).map {
                        val sender = if (r.includeSenders) {
                            val source = db.devices.get(EnvelopeCodec.decode(it.encryptedEnvelope).senderDeviceId)!!
                            SenderProfile(source.accountId, source.id, source.routingId, db.accounts.get(source.accountId)!!.ghostCloakId)
                        } else null
                        Delivery(it.id, it.encryptedEnvelope.copyOf(), it.receivedAt, it.expiresAt, sender)
                    }
                ApiResponse(deliveries = deliveries, statuses = statuses, serverTime=if(r.retention) now() else null)
            }
            is ApiRequest.Ack -> {
                requireApi(r.serverMessageIds.size in 1..NetworkLimits.BATCH && r.serverMessageIds.all(RandomIdentifiers::valid))
                requireApi(r.serverMessageIds.none { id -> db.mailbox.get(id)?.let { it.recipientRoutingId != device.routingId } == true }, "forbidden", 403)
                r.serverMessageIds.forEach { id ->
                    // Only a still-owned mailbox row can produce an ACK receipt. Expiry is not delivery.
                    if (db.mailbox.get(id)?.expiresAt?.let {it>now()} == true) {
                        db.submissionByServerId(id).forEach { row ->
                            db.submissions.put(row.id, SubmissionRow(row.id, row.sender, row.digest, row.serverId, row.expiresAt, true,row.mailboxExpiresAt))
                        }
                        db.mailbox.remove(id)
                    }
                }; ApiResponse()
            }
            else -> throw ApiFailure(400, "invalid_request")
        }
    }
    private fun allocate(r:ApiRequest.Allocate, token:String):AllocationOutcome {
        val requester=authenticateSession(token)
        val id=GhostCloakIds.normalize(r.ghostCloakId)
        requireApi(id==r.ghostCloakId && RandomIdentifiers.valid(r.allocationId),"invalid_request")
        val rowId=requester.id+"/"+r.allocationId
        db.allocations.get(rowId)?.let { old ->
            if(old.requester!=requester.id) return AllocationOutcome(failure=ApiFailure(409,"allocation_conflict"))
            if(old.responseExpiresAt<=now() || old.bundle==null) return AllocationOutcome(failure=ApiFailure(404,"contact_unavailable"))
            val target=db.devices.get(old.target) ?: return AllocationOutcome(failure=ApiFailure(404,"contact_unavailable"))
            val account=db.accounts.get(target.accountId) ?: return AllocationOutcome(failure=ApiFailure(404,"contact_unavailable"))
            if(account.ghostCloakId!=id) return AllocationOutcome(failure=ApiFailure(409,"allocation_conflict"))
            abuseLog(AbuseEvent.PREKEY_ALLOCATION_REPLAYED)
            return AllocationOutcome(ApiResponse(directory=DirectoryEntry(account.id,target.id,target.routingId,id,old.bundle),serverTime=now()))
        }
        // Charge unknown and known IDs on the same path, before any account lookup.
        val time=now()
        if(!rate.allow(ServerOperation.ALLOCATE,requester.id,time) ||
            !rate.allow(ServerOperation.ALLOCATE_TARGET,requester.id+"/"+id,time))
            return AllocationOutcome(failure=ApiFailure(429,"rate_limited")).also {abuseLog(AbuseEvent.PREKEY_ALLOCATION_RATE_LIMITED)}
        if(db.allocations.size()>=10000 || db.countAllocations(requester.id)>=64)
            return AllocationOutcome(failure=ApiFailure(429,"rate_limited")).also {abuseLog(AbuseEvent.PREKEY_ALLOCATION_RATE_LIMITED)}
        val account=db.accountByGhostCloakId(id) ?: return AllocationOutcome(failure=ApiFailure(404,"contact_unavailable"))
        val target=db.devices.get(account.deviceId) ?: return AllocationOutcome(failure=ApiFailure(404,"contact_unavailable"))
        if(db.countAllocations(requester.id,target.id)>=4)
            return AllocationOutcome(failure=ApiFailure(429,"rate_limited")).also {abuseLog(AbuseEvent.PREKEY_ALLOCATION_RATE_LIMITED)}
        val keys=db.prekeys.get(target.id) ?: return AllocationOutcome(failure=ApiFailure(404,"contact_unavailable"))
        val bundle=keys.pool.firstOrNull() ?: return AllocationOutcome(failure=ApiFailure(404,"contact_unavailable"))
        db.prekeys.put(target.id,PrekeyRow(target.id,keys.pool.drop(1),keys.usedEc,keys.usedPq,keys.signed))
        db.allocations.put(rowId,AllocationRow(rowId,requester.id,target.id,bundle,time+86400000L,time+172800000L))
        return AllocationOutcome(ApiResponse(directory=DirectoryEntry(account.id,target.id,target.routingId,id,bundle),serverTime=time))
    }
    private fun send(sender: DeviceRow, r: ApiRequest.Send): ApiResponse {
        val format = SubmissionIds.parse(r.submissionId) ?: throw ApiFailure(400, "invalid_submission_id")
        requireApi(RandomIdentifiers.valid(r.recipientRoutingId))
        val receivedAt = now()
        if (format is SubmissionIds.Parsed.ExpiringV3) {
            requireApi(format.expiresAt >= receivedAt, "submission_expired", 410)
            requireApi(format.expiresAt - receivedAt <= SubmissionIds.MAX_LIFETIME_MILLIS,
                "submission_expiry_too_far", 400)
        }
        val target = db.deviceByRoutingId(r.recipientRoutingId) ?: throw ApiFailure(404, "not_found")
        val envelope = try { EnvelopeCodec.decode(r.encryptedEnvelope) } catch (e: IllegalArgumentException) { throw ApiFailure(400, "invalid_envelope") }
        requireApi(envelope.senderDeviceId == sender.id && envelope.recipientDeviceId == target.id, "wrong_route")
        val id = sender.id + "/" + r.submissionId
        val digest = DeviceAuth.digest(r.recipientRoutingId.toByteArray() + r.encryptedEnvelope)
        db.submissions.get(id)?.let { old ->
            requireApi(MessageDigest.isEqual(digest, old.digest), "idempotency_conflict", 409)
            return ApiResponse(serverMessageId = old.serverId)
        }
        val usage = db.mailboxUsageForRecipient(target.routingId)
        requireApi(usage.count < DeliveryCapacity.RECIPIENT_MAILBOX_ROWS &&
            usage.bytes + r.encryptedEnvelope.size <= DeliveryCapacity.RECIPIENT_MAILBOX_BYTES &&
            db.mailbox.size() < DeliveryCapacity.GLOBAL_MAILBOX_ROWS, "mailbox_full", 429)
        if (format is SubmissionIds.Parsed.ExpiringV3) {
            requireApi(db.countLiveSubmissions(receivedAt,sender.id) < DeliveryCapacity.SENDER_LIVE_V3 &&
                db.countLiveSubmissions(receivedAt) < DeliveryCapacity.GLOBAL_LIVE_V3, "submission_capacity", 429)
        } else {
            requireApi(db.countLiveSubmissions(receivedAt,sender.id,legacy=true) < DeliveryCapacity.LEGACY_SENDER &&
                db.countLiveSubmissions(receivedAt,legacy=true) < DeliveryCapacity.LEGACY_GLOBAL, "submission_capacity", 429)
        }
        val serverId = RandomIdentifiers.create()
        // One authoritative millisecond sample/deadline for payload and sender receipt.
        val expiresAt = Math.addExact(receivedAt, policy.mailboxTtl)
        val dedupeExpiresAt = if (format is SubmissionIds.Parsed.ExpiringV3) format.expiresAt
            else Math.addExact(receivedAt, policy.dedupeTtl)
        db.mailbox.put(serverId, MailboxRow(serverId, target.routingId, r.encryptedEnvelope.copyOf(), receivedAt, expiresAt))
        db.submissions.put(id, SubmissionRow(id, sender.id, digest, serverId, dedupeExpiresAt,mailboxExpiresAt=expiresAt))
        return ApiResponse(serverMessageId = serverId)
    }
}
