package org.ghostcloak.testing

import kotlinx.coroutines.runBlocking
import org.ghostcloak.crypto.SignalProtocolEngine
import org.ghostcloak.identity.RandomIdentifiers
import org.ghostcloak.messaging.EndpointNetworkState
import org.ghostcloak.messaging.publicData
import org.ghostcloak.protocol.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Explicitly opted-in, bounded tests of the single owner-authorized production hostname. */
class LiveAdversarialA1Test {
    private val origin = URI("https://api.ghostcloak.org")
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER).build()
    private var calls = 0
    private val maximumCalls = 90

    private data class Wire(val status: Int, val bytes: ByteArray, val millis: Long) {
        fun response(): ApiResponse = NetworkCodec.decode(bytes, NetworkLimits.RESPONSE)
    }
    private fun send(path: String, bytes: ByteArray, token: String? = null,
                     contentType: String = NetworkLimits.CONTENT_TYPE, method: String = "POST",
                     query: String = "", extraHeaders: List<Pair<String, String>> = emptyList()): Wire {
        check(++calls <= maximumCalls) { "A1_REQUEST_CAP" }
        check(path.startsWith("/v2/auth/") || path == "/v2/accounts" || path == "/health")
        val uri = origin.resolve(path + query)
        check(uri.scheme == "https" && uri.host == "api.ghostcloak.org" && uri.port == -1)
        val builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
            .header("Accept", NetworkLimits.CONTENT_TYPE)
        if (method == "POST") {
            builder.header("Content-Type", contentType)
            if (token != null) builder.header("Authorization", "Bearer $token")
            extraHeaders.forEach { (name, value) -> builder.header(name, value) }
            builder.POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
        } else builder.GET()
        val start = System.nanoTime()
        val reply = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
        check(reply.body().size <= NetworkLimits.RESPONSE) { "A1_RESPONSE_CAP" }
        return Wire(reply.statusCode(), reply.body(), (System.nanoTime() - start) / 1_000_000)
    }
    private fun send(request: ApiRequest, token: String? = null) =
        send(ApiRoutes.path(request), NetworkCodec.encode(request), token)
    private fun healthy() {
        val wire = send("/health", byteArrayOf(), method = "GET")
        assertEquals(200, wire.status)
        assertEquals("{\"status\":\"ok\"}", wire.bytes.toString(Charsets.UTF_8))
    }
    private fun pass(id: String, evidence: String) = println("$id PASS $evidence")
    private fun reject(wire: Wire, expected: Set<Int> = setOf(400, 401, 404, 405, 413, 415, 429)) {
        assertTrue("unexpected_http_${wire.status}", wire.status in expected)
        val body = wire.bytes.toString(Charsets.ISO_8859_1)
        assertFalse(body.contains("Exception", ignoreCase = true))
        assertFalse(body.contains("jdbc:", ignoreCase = true))
        assertFalse(body.contains("/opt/ghostcloak", ignoreCase = true))
        assertFalse(body.contains("stacktrace", ignoreCase = true))
    }
    private class Fixture(val state: EndpointNetworkState, val credential: RecoveryCredential,
                          val registration: Registration) {
        fun sign(c: Challenge) = state.sign(c)
        fun recoverySign(c: Challenge) = state.signRecovery(c, registration.authPublicKey)
        fun alteredSignature(c: Challenge): ByteArray = credential.sign("fixture", DeviceAuth.statement(c))
    }
    private suspend fun fixture(): Fixture {
        val records = MemoryRecords()
        val engine = SignalProtocolEngine(records)
        engine.createIdentity("A1 disposable")
        val credential = RecoveryCredential()
        val state = EndpointNetworkState(records, origin.host, credential)
        val registration = state.registration(listOf(engine.publicBundle().publicData()))
        return Fixture(state, credential, registration)
    }
    private fun challenge(f: Fixture, purpose: String): Challenge {
        val r = f.registration
        val hash = if (purpose == "register") DeviceAuth.digest(NetworkCodec.encode(r)) else byteArrayOf()
        val wire = send(ApiRequest.Issue(r.accountId, r.deviceId, purpose, hash))
        assertEquals(200, wire.status)
        return wire.response().challenge!!
    }
    private fun register(f: Fixture) {
        val c = challenge(f, "register")
        val wire = send(ApiRequest.Register(f.registration, c.id, f.sign(c)))
        assertEquals(200, wire.status)
        assertNotNull(wire.response().registeredId)
    }
    private fun login(f: Fixture): String {
        val c = challenge(f, "login")
        val wire = send(ApiRequest.Verify(f.registration.accountId, f.registration.deviceId, c.id, f.sign(c)))
        assertEquals(200, wire.status)
        return wire.response().session!!.token
    }
    private fun recoveryChallenge(f: Fixture): Challenge {
        val wire = send(ApiRequest.RecoveryIssue(f.registration.authPublicKey, f.registration.deviceId))
        assertEquals(200, wire.status)
        return wire.response().challenge!!
    }

    @Test fun boundedOwnedHostAuthenticationRecoveryAndSessionProbes() = runBlocking {
        assumeTrue("Live A1 requires explicit opt-in", System.getenv("GHOSTCLOAK_ADVERSARIAL_LIVE") == "true")
        healthy()
        val d = fixture()
        val e = fixture()
        register(d)
        register(e)
        healthy()

        // A1.1: an identical signed login proof must never issue a second session.
        val firstChallenge = challenge(d, "login")
        val firstProof = ApiRequest.Verify(d.registration.accountId, d.registration.deviceId,
            firstChallenge.id, d.sign(firstChallenge))
        assertEquals(200, send(firstProof).status)
        reject(send(firstProof), setOf(401))
        pass("A1.1", "first=200 replay=401")

        // A1.3 and A1.5: wrong private key and cross-account/device claims.
        val wrong = challenge(d, "login")
        reject(send(ApiRequest.Verify(d.registration.accountId, d.registration.deviceId,
            wrong.id, e.credential.sign("fixture", DeviceAuth.statement(wrong)))), setOf(401))
        pass("A1.3", "wrong_key=401")
        val crossed = challenge(d, "login")
        reject(send(ApiRequest.Verify(e.registration.accountId, e.registration.deviceId,
            crossed.id, d.sign(crossed))), setOf(401))
        pass("A1.5", "cross_account_device=401")

        // A1.4/A1.7: the challenge statement binds ID, nonce, account, device,
        // audience, purpose and registration hash. Each mutation gets a fresh challenge.
        val transforms: List<Pair<String, (Challenge) -> Challenge>> = listOf(
            "id" to { c -> Challenge(RandomIdentifiers.create(), c.random, c.expiresAt, c.audience, c.accountId, c.deviceId, c.purpose, c.registrationHash) },
            "random" to { c -> Challenge(c.id, ByteArray(32) { 7 }, c.expiresAt, c.audience, c.accountId, c.deviceId, c.purpose, c.registrationHash) },
            "account" to { c -> Challenge(c.id, c.random, c.expiresAt, c.audience, RandomIdentifiers.create(), c.deviceId, c.purpose, c.registrationHash) },
            "device" to { c -> Challenge(c.id, c.random, c.expiresAt, c.audience, c.accountId, RandomIdentifiers.create(), c.purpose, c.registrationHash) },
            "audience" to { c -> Challenge(c.id, c.random, c.expiresAt, "other.invalid", c.accountId, c.deviceId, c.purpose, c.registrationHash) },
            "purpose" to { c -> Challenge(c.id, c.random, c.expiresAt, c.audience, c.accountId, c.deviceId, "register", c.registrationHash) },
            "hash" to { c -> Challenge(c.id, c.random, c.expiresAt, c.audience, c.accountId, c.deviceId, c.purpose, ByteArray(32)) }
        )
        for ((index, pair) in transforms.withIndex()) {
            val (_, transform) = pair
            val owner = if (index < 4) d else e
            val c = challenge(owner, "login")
            val signature = owner.alteredSignature(transform(c))
            reject(send(ApiRequest.Verify(owner.registration.accountId, owner.registration.deviceId, c.id, signature)), setOf(401))
        }
        pass("A1.4", "seven_statement_mutations=rejected")
        pass("A1.7", "different_audience_signature=401")

        val registerPurpose = challenge(Fixture(e.state, e.credential,
            Registration(RandomIdentifiers.create(), RandomIdentifiers.create(), RandomIdentifiers.create(),
                e.registration.authPublicKey, e.registration.bundles)), "register")
        reject(send(ApiRequest.Verify(e.registration.accountId, e.registration.deviceId,
            registerPurpose.id, e.credential.sign("fixture", DeviceAuth.statement(registerPurpose)))), setOf(401))
        val loginPurpose = challenge(d, "login")
        reject(send(ApiRequest.Register(d.registration, loginPurpose.id, d.sign(loginPurpose))), setOf(401))
        val recoveryPurpose = recoveryChallenge(d)
        reject(send(ApiRequest.Verify(d.registration.accountId, d.registration.deviceId,
            recoveryPurpose.id, d.recoverySign(recoveryPurpose))), setOf(401))
        val ordinaryPurpose = challenge(e, "login")
        reject(send(ApiRequest.RecoveryVerify(e.registration.authPublicKey, e.registration.deviceId,
            ordinaryPurpose.id, e.credential.sign("fixture", DeviceAuth.statement(ordinaryPurpose)))), setOf(401))
        pass("A1.6", "register_login_recovery_purpose_confusion=rejected")
        healthy()

        // A1.8 token format probes, then A1.9 revoke and replay.
        val token = login(d)
        val validRevoke = ApiRequest.Revoke()
        val invalidTokens = listOf("", "malformed", "A".repeat(43), token.dropLast(1),
            token + "A", (if (token[0] == 'A') "B" else "A") + token.drop(1))
        invalidTokens.forEach { reject(send(validRevoke, it), setOf(401)) }
        pass("A1.8", "six_invalid_token_forms=401")
        assertEquals(200, send(validRevoke, token).status)
        reject(send(validRevoke, token), setOf(401))
        pass("A1.9", "revoke=200 reuse=401")
        healthy()

        // A1.12 known/unknown recovery issue: only status, length and coarse timing reported.
        val known = send(ApiRequest.RecoveryIssue(d.registration.authPublicKey, d.registration.deviceId))
        val unknownKey = RecoveryCredential().pair.public.encoded
        val unknown = send(ApiRequest.RecoveryIssue(unknownKey, d.registration.deviceId))
        assertEquals(200, known.status)
        assertEquals(200, unknown.status)
        assertEquals(known.bytes.size, unknown.bytes.size)
        val knownTimes = mutableListOf(known.millis)
        val unknownTimes = mutableListOf(unknown.millis)
        repeat(2) {
            val a = send(ApiRequest.RecoveryIssue(d.registration.authPublicKey, d.registration.deviceId))
            val b = send(ApiRequest.RecoveryIssue(unknownKey, d.registration.deviceId))
            assertEquals(200, a.status)
            assertEquals(200, b.status)
            assertEquals(a.bytes.size, b.bytes.size)
            knownTimes += a.millis
            unknownTimes += b.millis
        }
        val knownProof = send(ApiRequest.RecoveryVerify(d.registration.authPublicKey,
            d.registration.deviceId, known.response().challenge!!.id, ByteArray(64)))
        val unknownProof = send(ApiRequest.RecoveryVerify(unknownKey,
            d.registration.deviceId, unknown.response().challenge!!.id, ByteArray(64)))
        reject(knownProof, setOf(401))
        reject(unknownProof, setOf(401))
        assertEquals(knownProof.bytes.size, unknownProof.bytes.size)
        assertEquals(knownProof.response().error, unknownProof.response().error)
        pass("A1.12", "three_pairs_status_and_size_equal bad_proof_shape_equal timing_ms_ranges=${knownTimes.min()}-${knownTimes.max()},${unknownTimes.min()}-${unknownTimes.max()}")
        val crossRecovery = recoveryChallenge(d)
        reject(send(ApiRequest.RecoveryVerify(e.registration.authPublicKey, e.registration.deviceId,
            crossRecovery.id, e.credential.sign("fixture", DeviceAuth.statement(crossRecovery)))), setOf(401))
        pass("A1.5_RECOVERY", "cross_recovery=401")

        // A1.13: exactly the documented ten permitted requests in one fresh key bucket,
        // one rejection, then one request for a different fresh key.
        val noisyKey = RecoveryCredential().pair.public.encoded
        repeat(10) { assertEquals(200, send(ApiRequest.RecoveryIssue(noisyKey, d.registration.deviceId)).status) }
        reject(send(ApiRequest.RecoveryIssue(noisyKey, d.registration.deviceId)), setOf(429))
        val otherKey = RecoveryCredential().pair.public.encoded
        assertEquals(200, send(ApiRequest.RecoveryIssue(otherKey, e.registration.deviceId)).status)
        pass("A1.13", "same_material_10x200_then_429 other_material=200")
        healthy()

        // A1.16: conflicting credentials must never authorize a revoke.
        val eToken = login(e)
        val revokeBody = NetworkCodec.encode(ApiRequest.Revoke())
        val revokePath = ApiRoutes.path(ApiRequest.Revoke())
        reject(send(revokePath, revokeBody, eToken,
            extraHeaders = listOf("Authorization" to "Bearer invalid")), setOf(400, 401))
        reject(send(revokePath, revokeBody,
            extraHeaders = listOf("Authorization" to "bearer invalid")), setOf(400, 401))
        reject(send(revokePath, revokeBody,
            extraHeaders = listOf("Authorization" to "Bearer  invalid")), setOf(400, 401))
        reject(send(revokePath, revokeBody, eToken,
            extraHeaders = listOf("Content-Type" to "text/plain")), setOf(400, 415))
        assertEquals(200, send(ApiRequest.Revoke(), eToken).status)
        pass("A1.16", "conflicting_and_malformed_headers=rejected valid_session_retained_until_revoke")
        healthy()

        // A1.15-17 bounded malformed-auth corpus. No request approaches the body cap.
        val sample = NetworkCodec.encode(ApiRequest.Issue(RandomIdentifiers.create(), RandomIdentifiers.create(), "login"))
        val path = "/v2/auth/challenge"
        val malformed = listOf(byteArrayOf(), byteArrayOf(0xff.toByte()), byteArrayOf(0xa1.toByte(), 0xff.toByte()),
            sample.dropLast(1).toByteArray(), sample + byteArrayOf(0))
        malformed.forEach { reject(send(path, it)) }
        reject(send(path, sample, contentType = "text/plain"), setOf(415))
        reject(send(path, sample, method = "GET"), setOf(405))
        reject(send(path, sample, query = "?unexpected=1"))
        reject(send("/v2/auth/verify", sample), setOf(400, 404))
        reject(send(path, NetworkCodec.encode(ApiRequest.Revoke())), setOf(400, 404))
        pass("A1.15", "ten_malformed_route_method_body_cases=safe_4xx")
        pass("A1.17", "rejections=no_exception_or_path_markers")
        healthy()
        pass("A1.18", "final_health=200")
        println("A1_TOTAL_REQUESTS=$calls")
    }
}
