package org.ghostcloak.transport

import org.ghostcloak.protocol.*
import java.net.HttpURLConnection
import java.net.URI

/** Trusted in-process adapter. Preserve bytes and endpoint identity; never retry via another mode.
 * Implementations must bound responses while reading and keep relay credentials outside this request.
 * No peer address or proxy identity headers cross this boundary.
 */
fun interface GhostCloakTransport {
    suspend fun execute(request: TransportRequest): TransportResponse
}

/** Always POST with the v1 content type. Authorization is application-scoped, not relay auth. */
class TransportRequest(val endpoint: URI, val body: ByteArray, val applicationAuthorization: String?,
    val diagnosticHttpStatus: ((Int) -> Unit)? = null) {
    override fun toString() = "TransportRequest(<redacted>)"
}

/** Only protocol-relevant fields are returned; headers cannot supply an authenticated identity. */
class TransportResponse(val status: Int, val contentType: String?, val body: ByteArray, val retryAfterMillis: Long? = null) {
    override fun toString() = "TransportResponse(<redacted>)"
}

enum class TransportMode { STANDARD, PRIVATE_RELAY }

object TransportPolicy {
    /** Relay code/configuration is absent in production. A future rollout must explicitly open this gate. */
    fun select(
        mode: TransportMode = TransportMode.STANDARD,
        relayEnabled: Boolean = false,
        relay: GhostCloakTransport? = null,
    ): GhostCloakTransport = when (mode) {
        TransportMode.STANDARD -> DirectHttpsTransport()
        TransportMode.PRIVATE_RELAY -> {
            requireApi(relayEnabled && relay != null, "relay_unavailable", 503)
            relay!!
        }
    }
}

internal fun validateApiOrigin(base: URI, allowLoopbackForTests: Boolean) {
    require(base.rawUserInfo == null && base.rawQuery == null && base.rawFragment == null && base.path in listOf("", "/"))
    require(base.host != null && (base.scheme == "https" ||
        (allowLoopbackForTests && base.scheme == "http" && base.host in setOf("127.0.0.1", "localhost", "[::1]"))))
}

/** Existing platform connection behavior: default TLS/hostname verification, no redirects. */
class DirectHttpsTransport(private val allowLoopbackForTests: Boolean = false) : GhostCloakTransport {
    override suspend fun execute(request: TransportRequest): TransportResponse {
        // The protocol client validates the origin, including its explicit loopback-only test exception.
        // Independently reject non-TLS remote endpoints even if an adapter is invoked directly.
        val endpoint = request.endpoint
        validateApiOrigin(URI(endpoint.scheme, endpoint.rawAuthority, null, null, null), allowLoopbackForTests)
        require(endpoint.rawUserInfo == null && endpoint.rawQuery == null && endpoint.rawFragment == null)
        requireApi(request.body.size <= NetworkLimits.BODY, "body_size", 413)
        val connection = endpoint.toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"; connection.instanceFollowRedirects = false
            connection.connectTimeout = 5000; connection.readTimeout = 5000
            connection.useCaches = false; connection.doOutput = true
            connection.setRequestProperty("Content-Type", NetworkLimits.CONTENT_TYPE)
            connection.setRequestProperty("User-Agent", "GhostCloak/1")
            request.applicationAuthorization?.let { connection.setRequestProperty("Authorization", it) }
            connection.setFixedLengthStreamingMode(request.body.size)
            connection.outputStream.use { it.write(request.body) }
            val status = connection.responseCode
            try { request.diagnosticHttpStatus?.invoke(status) } catch (_: Exception) { }
            val contentType = connection.getHeaderField("Content-Type")
            if (status == 429) return TransportResponse(status, contentType, byteArrayOf(),
                parseRetryAfter(connection.getHeaderField("Retry-After")))
            requireApi(contentType == NetworkLimits.CONTENT_TYPE, "invalid_response", 502)
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            requireApi(stream != null, "invalid_response", 502)
            val body = stream!!.use { input ->
                val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    requireApi(out.size() + n <= NetworkLimits.RESPONSE, "response_size", 502)
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
            return TransportResponse(status, contentType, body)
        } finally { connection.disconnect() }
    }
}
