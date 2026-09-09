package org.ghostcloak.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.ghostcloak.protocol.*
import java.net.HttpURLConnection
import java.net.URI

interface AccessTokenStore { fun read(): String?; fun save(token: String?); }
interface RoutingDirectory { fun route(deviceId: String): String? }
interface GhostAuthClient { suspend fun unauthenticated(request: ApiRequest): ApiResponse }
interface GhostDirectoryClient { suspend fun lookup(username: String): DirectoryEntry; suspend fun publish(deviceId: String, bundles: List<PublicBundle>) }

/** Blocking JDK/Android HTTPS implementation, dispatched off-main. No redirects or ambient auth. */
class HttpGhostClient(baseUrl: String, private val tokens: AccessTokenStore, allowLoopbackForTests: Boolean = false) : GhostAuthClient, GhostDirectoryClient {
    private val base = URI(baseUrl)
    init {
        require(base.rawUserInfo == null && base.rawQuery == null && base.rawFragment == null && base.path in listOf("", "/"))
        require(base.host != null && (base.scheme == "https" || (allowLoopbackForTests && base.scheme == "http" && base.host in setOf("127.0.0.1", "localhost", "[::1]"))))
    }
    override suspend fun unauthenticated(request: ApiRequest) = call(request, false)
    suspend fun call(request: ApiRequest, authenticated: Boolean = true): ApiResponse = withContext(Dispatchers.IO) {
        val bytes = NetworkCodec.encode(request)
        requireApi(bytes.size <= NetworkLimits.BODY, "body_size", 413)
        val connection = base.resolve(ApiRoutes.path(request)).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"; connection.instanceFollowRedirects = false
            connection.connectTimeout = 5000; connection.readTimeout = 5000
            connection.useCaches = false; connection.doOutput = true
            connection.setRequestProperty("Content-Type", NetworkLimits.CONTENT_TYPE)
            connection.setRequestProperty("User-Agent", "GhostCloak/1")
            if (authenticated) connection.setRequestProperty("Authorization", "Bearer ${tokens.read() ?: throw ApiFailure(401, "unauthorized")}")
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val status = connection.responseCode
            requireApi(connection.getHeaderField("Content-Type") == NetworkLimits.CONTENT_TYPE, "invalid_response", 502)
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            requireApi(stream != null, "invalid_response", 502)
            val body = stream!!.use { input ->
                val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { val n = input.read(buffer); if (n < 0) break; requireApi(out.size() + n <= NetworkLimits.RESPONSE, "response_size", 502); out.write(buffer, 0, n) }
                out.toByteArray()
            }
            val response = NetworkCodec.decode<ApiResponse>(body, NetworkLimits.RESPONSE)
            requireApi(response.version == 1, "invalid_response", 502)
            if (status !in 200..299) throw ApiFailure(status, "server_rejected")
            requireApi(response.error == null, "invalid_response", 502)
            response
        } catch (e: java.io.IOException) { throw ApiFailure(503, "network_unavailable") }
        finally { connection.disconnect() }
    }
    override suspend fun lookup(username: String) = call(ApiRequest.Lookup(Usernames.normalize(username))).directory ?: throw ApiFailure(502, "invalid_response")
    override suspend fun publish(deviceId: String, bundles: List<PublicBundle>) { call(ApiRequest.Prekeys(deviceId, bundles)) }
}
interface IdempotentMessageTransport : EncryptedMessageTransport {
    suspend fun submit(submissionId: String, routingDestination: String, envelope: EncryptedEnvelope): String
}
class NetworkMailboxTransport(private val client: HttpGhostClient, private val directory: RoutingDirectory) : IdempotentMessageTransport {
    override suspend fun send(routingDestination: String, envelope: EncryptedEnvelope) { submit(envelope.envelopeId, routingDestination, envelope) }
    override suspend fun submit(submissionId: String, routingDestination: String, envelope: EncryptedEnvelope): String {
        requireApi(routingDestination == envelope.recipientDeviceId, "wrong_route")
        val route = directory.route(routingDestination) ?: throw ApiFailure(404, "route_unknown")
        return client.call(ApiRequest.Send(submissionId, route, EnvelopeCodec.encode(envelope))).serverMessageId ?: throw ApiFailure(502, "invalid_response")
    }
    suspend fun fetch(): List<Delivery> = client.call(ApiRequest.Fetch()).deliveries.also { requireApi(it.size <= NetworkLimits.BATCH, "invalid_response", 502) }
    /** Caller must commit endpoint processing before explicitly acknowledging these IDs. */
    suspend fun acknowledgeAccepted(ids: List<String>) { client.call(ApiRequest.Ack(ids)) }
    override fun receive() = flow {
        while (true) { fetch().forEach { emit(EnvelopeCodec.decode(it.encryptedEnvelope)) }; delay(2000) }
    }
}
