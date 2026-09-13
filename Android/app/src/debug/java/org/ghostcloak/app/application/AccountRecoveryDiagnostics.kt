package org.ghostcloak.app.application

import android.util.Log
import org.ghostcloak.crypto.EndpointRecords
import org.ghostcloak.protocol.DeviceAuth
import org.ghostcloak.transport.*
import java.security.KeyStore

/** Read-only investigation. Never generate/sign with a credential or print stored values. */
internal object AccountRecoveryDiagnostics {
    internal var sink: (String) -> Unit = { Log.d("GhostCloakAccount", it); Unit }
    private fun write(line: String) { try { sink(line) } catch (_: Exception) { } }
    fun path(category: String) {
        if (category in setOf("OPEN", "CONNECT_REGISTERED_LOGIN", "CONNECT_UNMARKED_LOGIN_FIRST",
                "CONNECT_REGISTER_AFTER_401", "SILENT_RENEWAL", "LOGOUT", "STATE_UNAVAILABLE",
                "CURRENT_NAMESPACE_ABSENT_OTHER_PRESENT", "OTHER_NAMESPACE_PRESENT", "REGISTRATION_METADATA_INCOMPLETE",
                "RENEWAL_BLOCKED", "LEGACY_CREDENTIAL_PRESENT"))
            write("STARTUP_PATH=$category")
    }
    fun snapshot(records: EndpointRecords, host: String?) {
        try {
            records.transaction {
                val prefix="network/${DeviceAuth.digest((host ?: "").toByteArray()).joinToString("") { "%02x".format(it) }}/"
                val keys=records.keys("").toSet()
                write("IDENTITY_PRESENT=${"local/device" in keys && "local/key" in keys}")
                write("REGISTRATION_PRESENT=${prefix+"registered" in keys}")
                write("SESSION_PRESENT=${prefix+"token" in keys}")
                val alias=records.read(prefix+"auth-alias")?.decodeToString()
                val present=alias!=null && prefix+"auth-public" in keys &&
                    KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.isKeyEntry(alias)
                write("DEVICE_CREDENTIAL_PRESENT=$present")
                if (keys.none { it.startsWith(prefix) } && keys.any { it.startsWith("network/") })
                    path("CURRENT_NAMESPACE_ABSENT_OTHER_PRESENT")
                if (keys.any { it.startsWith("network/") && !it.startsWith(prefix) }) path("OTHER_NAMESPACE_PRESENT")
                if ((prefix+"registered" in keys || alias!=null) &&
                    listOf("account", "routing", "auth-alias", "auth-public").any { prefix+it !in keys })
                    path("REGISTRATION_METADATA_INCOMPLETE")
                if ("app/renewal-blocked/$host" in keys) path("RENEWAL_BLOCKED")
                if (prefix+"auth-private" in keys) path("LEGACY_CREDENTIAL_PRESENT")
            }
        } catch (_: Exception) { path("STATE_UNAVAILABLE") }
    }
    fun wrap(delegate: GhostCloakTransport): GhostCloakTransport = GhostCloakTransport { request ->
        val category=when(request.endpoint.path) {
            "/v1/auth/challenge" -> "AUTH_CHALLENGE"
            "/v1/auth/verify" -> "AUTH_VERIFY"
            "/v1/accounts" -> "ACCOUNT_REGISTER"
            "/v1/auth/revoke" -> "AUTH_REVOKE"
            "/v1/accounts/username" -> "ACCOUNT_RENAME"
            "/v1/directory/lookup" -> "LOOKUP"
            "/v1/devices/prekeys" -> "PUBLISH"
            "/v1/messages" -> "SEND"
            "/v1/messages/fetch" -> "FETCH"
            "/v1/messages/ack" -> "ACK"
            else -> "OTHER"
        }
        write("ENDPOINT_CATEGORY=$category")
        var reported=false
        val observed=TransportRequest(request.endpoint,request.body,request.applicationAuthorization) { status ->
            reported=true
            if (status in 100..599) write("ENDPOINT_CATEGORY=$category HTTP_STATUS=$status")
            request.diagnosticHttpStatus?.invoke(status)
        }
        delegate.execute(observed).also {
            if (!reported && it.status in 100..599) write("ENDPOINT_CATEGORY=$category HTTP_STATUS=${it.status}")
        }
    }
}
