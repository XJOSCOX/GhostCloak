package org.ghostcloak.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.ghostcloak.protocol.*
import java.net.URI

interface AccessTokenStore { fun read(): String?; fun save(token: String?); }
interface RoutingDirectory { fun route(deviceId: String): String? }
interface GhostAuthClient { suspend fun unauthenticated(request: ApiRequest): ApiResponse }
interface GhostDirectoryClient { suspend fun lookup(username: String): DirectoryEntry; suspend fun publish(deviceId: String, bundles: List<PublicBundle>) }

/** Protocol/auth adapter. Connection policy belongs to GhostCloakTransport, never message logic. */
class HttpGhostClient(
    baseUrl: String,
    private val tokens: AccessTokenStore,
    allowLoopbackForTests: Boolean = false,
    private val transport: GhostCloakTransport = DirectHttpsTransport(allowLoopbackForTests),
) : GhostAuthClient, GhostDirectoryClient {
    private val base = URI(baseUrl)
    init { validateApiOrigin(base, allowLoopbackForTests) }
    override suspend fun unauthenticated(request: ApiRequest) = call(request, false)
    suspend fun call(request: ApiRequest, authenticated: Boolean = true): ApiResponse = withContext(Dispatchers.IO) {
        val bytes = NetworkCodec.encode(request)
        requireApi(bytes.size <= NetworkLimits.BODY, "body_size", 413)
        val authorization = if (authenticated) "Bearer ${tokens.read() ?: throw ApiFailure(401, "unauthorized")}" else null
        try {
            val result = transport.execute(TransportRequest(base.resolve(ApiRoutes.path(request)), bytes, authorization))
            requireApi(result.contentType == NetworkLimits.CONTENT_TYPE, "invalid_response", 502)
            requireApi(result.body.size <= NetworkLimits.RESPONSE, "response_size", 502)
            val response = NetworkCodec.decode<ApiResponse>(result.body, NetworkLimits.RESPONSE)
            requireApi(response.version == 1, "invalid_response", 502)
            if (result.status !in 200..299) throw ApiFailure(result.status, "server_rejected")
            requireApi(response.error == null, "invalid_response", 502)
            response
        } catch (e: java.io.IOException) { throw ApiFailure(503, "network_unavailable") }
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
