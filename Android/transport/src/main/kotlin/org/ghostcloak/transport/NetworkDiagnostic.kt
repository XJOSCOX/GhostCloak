package org.ghostcloak.transport

import org.ghostcloak.protocol.ApiRequest

enum class NetworkOperation { AUTH, FETCH, SEND, ACK, RECEIPT_STATUS, LOOKUP, PUBLISH }
enum class NetworkEvent { START, END, HTTP, TRANSPORT_FAILURE, API_FAILURE, CANCELLED, RENEWAL_ATTEMPT, RENEWAL_SUCCEEDED, RENEWAL_FAILED, RETRY }

/** Metadata only. Never accepts a request, response, URL, credential or exception message. */
data class NetworkDiagnostic(
    val operation: NetworkOperation,
    val event: NetworkEvent,
    val elapsedMs: Long = 0,
    val httpStatus: Int? = null,
    val apiStatus: Int? = null,
    val apiCode: String? = null,
    val exceptionClass: Class<out Exception>? = null,
) {
    fun line(): String = buildString {
        append("$operation $event elapsed=${elapsedMs}ms")
        httpStatus?.let { append(" http=$it") }
        apiStatus?.let { append(" apiStatus=$it") }
        apiCode?.let { append(" apiCode=${if (it in SAFE_CODES) it else "REDACTED"}") }
        exceptionClass?.let { append(" exception=${it.name}") }
    }
    companion object {
        private val SAFE_CODES = setOf("unauthorized", "connect_required", "credential_missing", "credential_unavailable",
            "legacy_auth_requires_reset", "network_unavailable", "server_rejected", "rate_limited", "invalid_response", "response_size",
            "body_size", "route_unknown", "not_found", "invalid_challenge", "challenge_binding",
            "duplicate_delivery", "directory_mismatch", "invalid_network_username", "identity_required", "routing_changed",
            "credential_changed", "platform_credential_required", "server_not_configured")

    }
}

fun networkOperation(request: ApiRequest): NetworkOperation = when (request) {
    is ApiRequest.Send -> NetworkOperation.SEND
    is ApiRequest.Fetch -> if (request.includeSenders || request.submissionIds.isEmpty()) NetworkOperation.FETCH else NetworkOperation.RECEIPT_STATUS
    is ApiRequest.Ack -> NetworkOperation.ACK
    is ApiRequest.Lookup -> NetworkOperation.LOOKUP
    is ApiRequest.Prekeys -> NetworkOperation.PUBLISH
    else -> NetworkOperation.AUTH
}

/** A failing diagnostic sink must never affect networking or cancellation. */
fun emitNetworkDiagnostic(sink: ((NetworkDiagnostic) -> Unit)?, event: NetworkDiagnostic) {
    try { sink?.invoke(event) } catch (_: Exception) { }
}
