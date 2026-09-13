package org.ghostcloak.app.application

import android.util.Log
import org.ghostcloak.app.BuildConfig
import java.io.File
import java.net.URI

/** Observes only the selected filenames. Never scans, opens, repairs or creates a store. */
internal object LocalStateDiagnostics {
    internal var sink: (String) -> Unit = { Log.d("GhostCloakStore", it); Unit }
    private fun emit(line: String) { try { sink(line) } catch (_: Exception) { } }

    /** No engine/service calls: even seemingly read-only service getters may migrate records. */
    fun inventory(records: org.ghostcloak.crypto.EndpointRecords, origin: String,
        keyExists: (String) -> Boolean = { alias ->
            java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias)
        }) {
        // Never turn a failed inventory/Keystore query into misleading all-false results.
        val lines = try { records.transaction {
            val keys = records.keys("").toSet()
            val host = URI(origin).host ?: ""
            val prefix = "network/${org.ghostcloak.protocol.DeviceAuth.digest(host.toByteArray()).joinToString("") { "%02x".format(it) }}/"
            buildList {
                val local = mapOf("LOCAL_IDENTITY_KEY_PRESENT" to "key", "LOCAL_DEVICE_PRESENT" to "device",
                    "LOCAL_USER_PRESENT" to "user", "LOCAL_USERNAME_PRESENT" to "username",
                    "LOCAL_SIGNAL_REGISTRATION_PRESENT" to "registration")
                local.forEach { (label, suffix) -> add("$label=${"local/$suffix" in keys}") }
                val network = mapOf("NETWORK_ACCOUNT_PRESENT" to "account", "NETWORK_ROUTING_PRESENT" to "routing",
                    "NETWORK_REGISTERED_MARKER_PRESENT" to "registered", "NETWORK_TOKEN_PRESENT" to "token",
                    "NETWORK_AUTH_ALIAS_RECORD_PRESENT" to "auth-alias", "NETWORK_AUTH_PUBLIC_RECORD_PRESENT" to "auth-public",
                    "NETWORK_LEGACY_AUTH_PRIVATE_PRESENT" to "auth-private")
                network.forEach { (label, suffix) -> add("$label=${prefix + suffix in keys}") }
                val counts = mapOf("CONTACT_RECORD_COUNT" to listOf("app/contact/"),
                    "MESSAGE_RECORD_COUNT" to listOf("app/message/"), "VERIFICATION_RECORD_COUNT" to listOf("trust-state/"),
                    "OUTBOX_RECORD_COUNT" to listOf("outbox/"), "DISAPPEARING_POLICY_COUNT" to listOf("app/disappearing/"),
                    "ATTACHMENT_RECORD_COUNT" to listOf("app/attachment/", "attachment/transfer/", "attachment/delete/"))
                counts.forEach { (label, prefixes) -> add("$label=${keys.count { key -> prefixes.any(key::startsWith) }}") }
                add("APP_LOCK_CONFIG_PRESENT=${"app/access-lock" in keys}")
                add("TOTAL_RECORD_COUNT=${keys.size}")
                val aliasBytes = if (prefix + "auth-alias" in keys) records.read(prefix + "auth-alias") else null
                val exists = if (aliasBytes == null) false else try {
                    keyExists(aliasBytes.decodeToString(throwOnInvalidSequence = true))
                } finally { aliasBytes.fill(0) }
                add("REFERENCED_AUTH_KEYSTORE_ENTRY_PRESENT=$exists")
            }
        } } catch (_: Exception) { emptyList() }
        lines.forEach(::emit)
        // Opening SQLCipher is not an integrity check. Do not add raw SQL or parser side effects here.
        emit("DATABASE_INTEGRITY=NOT_CHECKED")
    }

    internal fun originCategory(origin: String): String = when {
        origin.isEmpty() -> "empty"
        runCatching { URI(origin).host?.lowercase()?.trimEnd('.') == "api.ghostcloak.org" }.getOrDefault(false) -> "staging"
        // No production host has been designated. Never infer production from arbitrary input.
        else -> "other"
    }

    fun <T> open(directory: File, endpoint: String, origin: String, operation: () -> T): T {
        emit("PACKAGE_NAME=${BuildConfig.APPLICATION_ID}")
        emit("BUILD_TYPE=${BuildConfig.BUILD_TYPE}")
        emit("API_ORIGIN_CATEGORY=${originCategory(origin)}")
        emit("PROFILE_SLOT=${if (endpoint == "local") "default" else "nondefault"}")
        val presence = runCatching {
            File(directory, "$endpoint.db").exists() to File(directory, "$endpoint.wrapped").exists()
        }.getOrNull()
        if (presence != null) {
            emit("DATABASE_EXISTS=${presence.first}")
            emit("WRAPPED_KEY_EXISTS=${presence.second}")
            emit("ENDPOINT_STORE_EXISTS=${presence.first && presence.second}")
            if (!presence.first || !presence.second) emit("STORE_OPEN_RESULT=missing")
        } else emit("STORE_OPEN_RESULT=error")
        return try {
            operation().also {
                emit("STORE_OPEN_RESULT=${when (presence) {
                    true to true -> "existing"
                    false to false -> "new"
                    else -> "error"
                }}")
            }
        } catch (failure: Throwable) {
            emit("STORE_OPEN_RESULT=error")
            throw failure
        }
    }
}
