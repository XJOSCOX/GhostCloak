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
    private val renewSession: (suspend (String) -> Unit)? = null,
    private val authenticatedFailure: (ApiFailure) -> Unit = {},
    private val diagnostics: ((NetworkDiagnostic) -> Unit)? = null,
    private val fetchCooldown: FetchCooldown = FetchCooldown(),
    private val beforeFetch: () -> Unit = {},
) : GhostAuthClient, GhostDirectoryClient {
    private val base = URI(baseUrl)
    val fetchRetryDelayMillis get() = fetchCooldown.remainingMillis
    init { validateApiOrigin(base, allowLoopbackForTests) }
    override suspend fun unauthenticated(request: ApiRequest) = call(request, false)
    suspend fun call(request: ApiRequest, authenticated: Boolean = true): ApiResponse = withContext(Dispatchers.IO) {
        if (request is ApiRequest.Fetch && fetchCooldown.remainingMillis > 0)
            throw ApiFailure(429, "rate_limited", fetchCooldown.remainingMillis)
        val category = networkOperation(request)
        val started = System.nanoTime()
        fun diagnostic(event: NetworkEvent, http: Int? = null, failure: ApiFailure? = null, exception: Exception? = null, since: Long = started) {
            if (diagnostics != null) emitNetworkDiagnostic(diagnostics, NetworkDiagnostic(category, event,
                (System.nanoTime() - since) / 1_000_000, http, failure?.status, failure?.code, exception?.javaClass))
        }
        val bytes = NetworkCodec.encode(request)
        requireApi(bytes.size <= NetworkLimits.BODY, "body_size", 413)
        suspend fun attempt(token: String?): ApiResponse {
            if (request is ApiRequest.Fetch) beforeFetch()
            val attemptStarted = System.nanoTime()
            var httpStatus: Int? = null
            diagnostic(NetworkEvent.START, since = attemptStarted)
            val authorization = token?.let { "Bearer $it" }
            try {
                val reportStatus: ((Int) -> Unit)? = if (diagnostics == null) null else { value ->
                    httpStatus = value
                    diagnostic(NetworkEvent.HTTP, http = value, since = attemptStarted)
                }
                val result = transport.execute(TransportRequest(base.resolve(ApiRoutes.path(request)), bytes, authorization, reportStatus))
                httpStatus = result.status
                if (result.status == 429) throw ApiFailure(429, "rate_limited", result.retryAfterMillis)
                requireApi(result.contentType == NetworkLimits.CONTENT_TYPE, "invalid_response", 502)
                requireApi(result.body.size <= NetworkLimits.RESPONSE, "response_size", 502)
                val response = NetworkCodec.decode<ApiResponse>(result.body, NetworkLimits.RESPONSE)
                requireApi(response.version == 1, "invalid_response", 502)
                if (result.status !in 200..299) throw ApiFailure(result.status, "server_rejected")
                requireApi(response.error == null, "invalid_response", 502)
                if (request is ApiRequest.Fetch) fetchCooldown.succeeded()
                return response
            } catch (e: kotlinx.coroutines.CancellationException) {
                diagnostic(NetworkEvent.CANCELLED, httpStatus, since = attemptStarted); throw e
            } catch (e: ApiFailure) {
                if (e.status == 429 && request is ApiRequest.Fetch) fetchCooldown.rejected(e.retryAfterMillis)
                diagnostic(NetworkEvent.API_FAILURE, httpStatus, failure = e, since = attemptStarted); throw e
            } catch (e: Exception) {
                diagnostic(NetworkEvent.TRANSPORT_FAILURE, httpStatus, exception = e, since = attemptStarted)
                if (e is java.io.IOException) throw ApiFailure(503, "network_unavailable")
                throw e
            } finally { diagnostic(NetworkEvent.END, httpStatus, since = attemptStarted) }
        }
        if (!authenticated) return@withContext attempt(null)
        authenticatedExchange({ event -> diagnostic(event) }) { attempt(it) }
    }
    /** The only authenticated one-retry boundary, shared with bulk streaming operations. */
    suspend fun <T> authenticatedExchange(
        event: (NetworkEvent) -> Unit = {},
        reportTransientFailure: Boolean = true,
        beforeRenew: () -> Unit = {},
        attempt: suspend (String) -> T,
    ): T {
        try {
            val token = tokens.read() ?: throw ApiFailure(401, "unauthorized")
            return try { attempt(token) } catch (e: ApiFailure) {
                if (e.status != 401 || renewSession == null) throw e
                beforeRenew()
                event(NetworkEvent.RENEWAL_ATTEMPT)
                try { renewSession.invoke(token); event(NetworkEvent.RENEWAL_SUCCEEDED) }
                catch (failure: kotlinx.coroutines.CancellationException) { event(NetworkEvent.CANCELLED); throw failure }
                catch (failure: Exception) { event(NetworkEvent.RENEWAL_FAILED); throw failure }
                event(NetworkEvent.RETRY)
                attempt(tokens.read() ?: throw ApiFailure(401, "unauthorized"))
            }
        } catch (e: ApiFailure) {
            if (reportTransientFailure || e.status == 401) authenticatedFailure(e)
            throw e
        }
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
