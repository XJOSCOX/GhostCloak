package org.ghostcloak.backend

import org.ghostcloak.protocol.*
import org.ghostcloak.identity.RandomIdentifiers
import java.security.SecureRandom
import java.security.MessageDigest
import java.time.Clock
import java.util.Base64

enum class ServerOperation { REGISTER, CHALLENGE, VERIFY, REVOKE, RENAME, LOOKUP, PREKEYS, SEND, FETCH, ACK }
fun interface RateLimiter { fun allow(operation: ServerOperation, principal: String, now: Long): Boolean }
/** Fixed-window prototype control. No IP or device fingerprint collection. */
class DevelopmentRateLimiter(private val limit: Int = 60) : RateLimiter {
    private val counters = mutableMapOf<Pair<ServerOperation, String>, Pair<Long, Int>>()
    @Synchronized override fun allow(operation: ServerOperation, principal: String, now: Long): Boolean {
        val window = now / 60000
        counters.entries.removeIf { it.value.first != window }
        val key = operation to principal
        val count = counters[key]?.second ?: 0
        if (count >= limit || (key !in counters && counters.size >= 2048)) return false
        counters[key] = window to count + 1
        return true
    }
}
enum class ServerResult { OK, REJECTED, INTERNAL }
fun interface ServerLogger { fun record(operation: ServerOperation, result: ServerResult) }
class BackendPolicy(val audience: String = "ghostcloak.local", val challengeTtl: Long = 60000,
    val sessionTtl: Long = 300000, val mailboxTtl: Long = 86400000, val dedupeTtl: Long = 604800000) {
    init { require(audience.matches(Regex("[a-z0-9.-]{1,100}"))); require(challengeTtl in 1000..120000 && sessionTtl in 1000..900000 && mailboxTtl in 1000..604800000 && dedupeTtl in mailboxTtl..2592000000) }
}
class MailboxService(private val db: BackendDatabase, private val clock: Clock = Clock.systemUTC(),
    val policy: BackendPolicy = BackendPolicy(), private val rate: RateLimiter = DevelopmentRateLimiter(),
    private val logger: ServerLogger = ServerLogger { _, _ -> }) {
    private val random = SecureRandom()
    private fun now() = clock.millis()
    private fun freshBytes() = ByteArray(32).also(random::nextBytes)
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun tokenHash(token: String): String { requireApi(token.matches(Regex("[A-Za-z0-9_-]{43}")), "unauthorized", 401); return hex(DeviceAuth.digest(token.toByteArray())) }
    fun operation(r: ApiRequest): ServerOperation = when(r) {
        is ApiRequest.Issue -> ServerOperation.CHALLENGE; is ApiRequest.Register -> ServerOperation.REGISTER
        is ApiRequest.Verify -> ServerOperation.VERIFY; is ApiRequest.Revoke -> ServerOperation.REVOKE
        is ApiRequest.Rename -> ServerOperation.RENAME; is ApiRequest.Lookup -> ServerOperation.LOOKUP
        is ApiRequest.Prekeys -> ServerOperation.PREKEYS; is ApiRequest.Send -> ServerOperation.SEND
        is ApiRequest.Fetch -> ServerOperation.FETCH; is ApiRequest.Ack -> ServerOperation.ACK
    }
    fun execute(request: ApiRequest, token: String? = null): ApiResponse {
        // Copy/validate the boundary even for an in-process caller; prevents retained mutable input.
        val r = NetworkCodec.decode<ApiRequest>(NetworkCodec.encode(request))
        val op = operation(r)
        try {
            requireApi(r.version == 1, "unsupported_version")
            val principal = if (token == null) "anonymous" else db.transaction { db.sessions.get(tokenHash(token))?.deviceId ?: "anonymous" }
            requireApi(rate.allow(op, principal, now()), "rate_limited", 429)
            cleanup()
            // Claim a challenge in its own committed transaction. Every verification attempt burns it,
            // including failed registration or a bad signature. No error can restore it.
            val claimed = when(r) {
                is ApiRequest.Register -> consume(r.challengeId)
                is ApiRequest.Verify -> consume(r.challengeId)
                else -> null
            }
            val result = db.transaction {
                when(r) {
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
        db.challenges.all().filter { it.challenge.expiresAt <= time }.forEach { db.challenges.remove(it.challenge.id) }
        db.sessions.all().filter { it.expiresAt <= time }.forEach { db.sessions.remove(it.hash) }
        db.mailbox.all().filter { it.expiresAt <= time }.forEach { db.mailbox.remove(it.id) }
        db.submissions.all().filter { it.expiresAt <= time }.forEach { db.submissions.remove(it.id) }
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
        val name = Usernames.normalize(v.username); requireApi(name == v.username)
        DeviceAuth.publicKey(v.authPublicKey)
        requireApi(MessageDigest.isEqual(c.registrationHash, DeviceAuth.digest(NetworkCodec.encode(v))), "invalid_proof", 401)
        proof(c, v.accountId, v.deviceId, "register", v.authPublicKey, r.signature)
        requireApi(db.accounts.size() < 1000, "capacity", 429)
        requireApi(db.accounts.get(v.accountId) == null && db.accounts.all().none { it.username == name } && db.devices.get(v.deviceId) == null && db.devices.all().none { it.routingId == v.routingId || it.authPublicKey.contentEquals(v.authPublicKey) }, "conflict", 409)
        validateBundles(v.deviceId, v.bundles, null)
        db.accounts.put(v.accountId, AccountRow(v.accountId, name, v.deviceId))
        db.devices.put(v.deviceId, DeviceRow(v.deviceId, v.accountId, v.routingId, v.authPublicKey, v.bundles.first().identity))
        upload(v.deviceId, v.bundles)
        return ApiResponse()
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
        return ApiResponse(session = SessionGrant(token, expires))
    }
    private fun validateBundles(deviceId: String, bundles: List<PublicBundle>, existing: PrekeyRow?) {
        requireApi(bundles.size in 1..NetworkLimits.BUNDLES && (existing?.pool?.size ?: 0) + bundles.size <= 32)
        requireApi((existing?.usedEc?.size ?: 0) + bundles.size <= 10000, "prekey_capacity", 429)
        val ec = mutableSetOf<Int>(); val pq = mutableSetOf<Int>(); val signed = existing?.signed?.toMutableMap() ?: mutableMapOf()
        val identity = db.devices.get(deviceId)?.identity ?: bundles.first().identity
        bundles.forEach {
            it.validate(); requireApi(it.deviceId == deviceId && it.identity.contentEquals(identity))
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
    private fun authenticated(r: ApiRequest, token: String): ApiResponse {
        val hash = tokenHash(token)
        val session = db.sessions.get(hash) ?: throw ApiFailure(401, "unauthorized")
        requireApi(session.expiresAt > now(), "unauthorized", 401)
        val device = db.devices.get(session.deviceId) ?: throw ApiFailure(401, "unauthorized")
        return when(r) {
            is ApiRequest.Revoke -> { db.sessions.remove(hash); ApiResponse() }
            is ApiRequest.Rename -> {
                val name = Usernames.normalize(r.username)
                requireApi(db.accounts.all().none { it.username == name && it.id != device.accountId }, "conflict", 409)
                db.accounts.put(device.accountId, AccountRow(device.accountId, name, device.id)); ApiResponse()
            }
            is ApiRequest.Lookup -> {
                val name = Usernames.normalize(r.username)
                val account = db.accounts.all().firstOrNull { it.username == name } ?: throw ApiFailure(404, "not_found")
                val target = db.devices.get(account.deviceId)!!
                val keys = db.prekeys.get(target.id)!!
                val bundle = keys.pool.firstOrNull() ?: throw ApiFailure(409, "prekeys_exhausted")
                db.prekeys.put(target.id, PrekeyRow(target.id, keys.pool.drop(1), keys.usedEc, keys.usedPq, keys.signed))
                ApiResponse(directory = DirectoryEntry(account.id, target.id, target.routingId, name, bundle))
            }
            is ApiRequest.Prekeys -> { requireApi(r.deviceId == device.id, "forbidden", 403); upload(device.id, r.bundles); ApiResponse() }
            is ApiRequest.Send -> send(device, r)
            is ApiRequest.Fetch -> ApiResponse(deliveries = db.mailbox.all().filter { it.recipientRoutingId == device.routingId }.sortedBy { it.receivedAt }.take(NetworkLimits.BATCH).map { Delivery(it.id, it.encryptedEnvelope.copyOf(), it.receivedAt, it.expiresAt) })
            is ApiRequest.Ack -> {
                requireApi(r.serverMessageIds.size in 1..NetworkLimits.BATCH && r.serverMessageIds.all(RandomIdentifiers::valid))
                requireApi(r.serverMessageIds.none { id -> db.mailbox.get(id)?.let { it.recipientRoutingId != device.routingId } == true }, "forbidden", 403)
                r.serverMessageIds.forEach { db.mailbox.remove(it) }; ApiResponse()
            }
            else -> throw ApiFailure(400, "invalid_request")
        }
    }
    private fun send(sender: DeviceRow, r: ApiRequest.Send): ApiResponse {
        requireApi(RandomIdentifiers.valid(r.submissionId) && RandomIdentifiers.valid(r.recipientRoutingId))
        val target = db.devices.all().firstOrNull { it.routingId == r.recipientRoutingId } ?: throw ApiFailure(404, "not_found")
        val envelope = try { EnvelopeCodec.decode(r.encryptedEnvelope) } catch (e: IllegalArgumentException) { throw ApiFailure(400, "invalid_envelope") }
        requireApi(envelope.senderDeviceId == sender.id && envelope.recipientDeviceId == target.id, "wrong_route")
        val id = sender.id + "/" + r.submissionId
        val digest = DeviceAuth.digest(r.recipientRoutingId.toByteArray() + r.encryptedEnvelope)
        db.submissions.get(id)?.let { old ->
            requireApi(MessageDigest.isEqual(digest, old.digest), "idempotency_conflict", 409)
            return ApiResponse(serverMessageId = old.serverId)
        }
        val records = db.mailbox.all().filter { it.recipientRoutingId == target.routingId }
        requireApi(records.size < 128 && records.sumOf { it.encryptedEnvelope.size.toLong() } + r.encryptedEnvelope.size <= 8L * 1024 * 1024 && db.mailbox.size() < 2048, "mailbox_full", 429)
        requireApi(db.submissions.all().count { it.sender == sender.id } < 1024 && db.submissions.size() < 10000, "submission_capacity", 429)
        val serverId = RandomIdentifiers.create()
        db.mailbox.put(serverId, MailboxRow(serverId, target.routingId, r.encryptedEnvelope.copyOf(), now(), now() + policy.mailboxTtl))
        db.submissions.put(id, SubmissionRow(id, sender.id, digest, serverId, now() + policy.dedupeTtl))
        return ApiResponse(serverMessageId = serverId)
    }
}
