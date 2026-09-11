package org.ghostcloak.app

import org.ghostcloak.protocol.*
import org.ghostcloak.identity.RandomIdentifiers
import org.ghostcloak.transport.*
import java.security.SecureRandom

/** Socket-free protocol fixture. Production backend behavior is tested separately by JVM/PG suites. */
internal class SyntheticNetwork : GhostCloakTransport {
    var rejectAuthenticated = false
    var logins = 0
    @Synchronized fun expireSessions() { sessions.clear() }
    var offline = false
    var loseRegistrationResponse = false
    var rejectSend = false
    var requests = 0
    var registrations = 0
    val accounts = linkedMapOf<String, Registration>()
    val sessions = mutableMapOf<String, Registration>()
    val lookedUp = mutableListOf<String>()
    val sent = mutableListOf<ByteArray>()
    val mailbox = linkedMapOf<String, Pair<String, Delivery>>()
    private val challenges = mutableMapOf<String, Challenge>()
    private val prekeys = mutableMapOf<String, MutableList<PublicBundle>>()
    private val submissions = mutableMapOf<String, String>()
    private val acknowledged = mutableSetOf<String>()
    private val random = SecureRandom()
    override suspend fun execute(request: TransportRequest): TransportResponse = respond(request)
    @Synchronized private fun respond(request: TransportRequest): TransportResponse {
        check(request.endpoint.host == "fixture.invalid")
        requests++
        if (offline) throw java.io.IOException("synthetic offline")
        val r = NetworkCodec.decode<ApiRequest>(request.body)
        check(request.endpoint.path == ApiRoutes.path(r))
        val response = when (r) {
            is ApiRequest.Issue -> {
                if (r.purpose == "login") requireApi(accounts[r.accountId]?.deviceId == r.deviceId, "unauthorized", 401)
                val c = Challenge(RandomIdentifiers.create(), random.generateSeed(32), System.currentTimeMillis() + 60000,
                    "fixture.invalid", r.accountId, r.deviceId, r.purpose, r.registrationHash)
                challenges[c.id] = c; ApiResponse(challenge = c)
            }
            is ApiRequest.Register -> {
                val c = challenges.remove(r.challengeId)!!
                val registration = r.registration
                check(c.registrationHash.contentEquals(DeviceAuth.digest(NetworkCodec.encode(registration))))
                check(DeviceAuth.verify(registration.authPublicKey, c, r.signature))
                requireApi(accounts.values.none { it.username == registration.username }, "conflict", 409)
                accounts[registration.accountId] = registration
                prekeys[registration.deviceId] = registration.bundles.toMutableList()
                registrations++
                if (loseRegistrationResponse) { loseRegistrationResponse = false; throw java.io.IOException("synthetic lost response") }
                ApiResponse()
            }
            is ApiRequest.Verify -> {
                val c = challenges.remove(r.challengeId)!!
                val registration = accounts[r.accountId]!!
                check(DeviceAuth.verify(registration.authPublicKey, c, r.signature))
                val token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(random.generateSeed(32))
                sessions.entries.removeAll { it.value.deviceId == registration.deviceId }
                sessions[token] = registration
                logins++
                ApiResponse(session = SessionGrant(token, System.currentTimeMillis() + 300000))
            }
            else -> {
                if (rejectAuthenticated) throw ApiFailure(401, "unauthorized")
                val me = sessions[request.applicationAuthorization?.removePrefix("Bearer ")] ?: throw ApiFailure(401, "unauthorized")
                when (r) {
                    is ApiRequest.Lookup -> {
                        lookedUp.add(r.username)
                        val target = accounts.values.singleOrNull { it.username == r.username } ?: throw ApiFailure(404, "not_found")
                        val bundle = prekeys[target.deviceId]!!.removeAt(0)
                        ApiResponse(directory = DirectoryEntry(target.accountId, target.deviceId, target.routingId, target.username, bundle))
                    }
                    is ApiRequest.Send -> {
                        sent.add(r.encryptedEnvelope.copyOf())
                        if (rejectSend) throw ApiFailure(503, "synthetic_unavailable")
                        val target = accounts.values.single { it.routingId == r.recipientRoutingId }
                        val envelope = EnvelopeCodec.decode(r.encryptedEnvelope)
                        requireApi(envelope.senderDeviceId == me.deviceId && envelope.recipientDeviceId == target.deviceId, "wrong_route")
                        val id = submissions.getOrPut(me.deviceId + r.submissionId) {
                            RandomIdentifiers.create().also { id ->
                                mailbox[id] = target.deviceId to Delivery(id, r.encryptedEnvelope.copyOf(), System.currentTimeMillis(), System.currentTimeMillis() + 86400000)
                            }
                        }
                        ApiResponse(serverMessageId = id)
                    }
                    is ApiRequest.Fetch -> ApiResponse(deliveries = mailbox.values.filter { it.first == me.deviceId && it.second.serverMessageId !in r.skipMessageIds }.take(NetworkLimits.BATCH).map {
                        val d = it.second
                        val sender = accounts.values.single { a -> a.deviceId == EnvelopeCodec.decode(d.encryptedEnvelope).senderDeviceId }
                        Delivery(d.serverMessageId, d.encryptedEnvelope, d.receivedAt, d.expiresAt,
                            if (r.includeSenders) SenderProfile(sender.accountId, sender.deviceId, sender.routingId, sender.username) else null)
                    }, statuses = r.submissionIds.map { id ->
                        val serverId = submissions[me.deviceId + id] ?: throw ApiFailure(404, "not_found")
                        DeliveryStatus(id, serverId in acknowledged)
                    })
                    is ApiRequest.Ack -> {
                        requireApi(r.serverMessageIds.all { mailbox[it]?.first == me.deviceId }, "forbidden", 403)
                        r.serverMessageIds.forEach { acknowledged.add(it); mailbox.remove(it) }; ApiResponse()
                    }
                    is ApiRequest.Prekeys -> { prekeys[me.deviceId]!!.addAll(r.bundles); ApiResponse() }
                    is ApiRequest.Revoke -> { sessions.entries.removeAll { it.value.deviceId == me.deviceId }; ApiResponse() }
                    else -> error("Unexpected fixture operation")
                }
            }
        }
        return TransportResponse(200, NetworkLimits.CONTENT_TYPE, NetworkCodec.encode(response))
    }
}
