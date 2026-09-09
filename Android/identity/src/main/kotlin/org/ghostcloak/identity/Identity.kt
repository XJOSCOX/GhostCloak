package org.ghostcloak.identity

import java.util.UUID

data class DeviceIdentity(val userId: String, val username: String, val deviceId: String, val publicKey: ByteArray) {
    override fun toString() = "DeviceIdentity(public metadata only)"
}

object RandomIdentifiers {
    fun create(): String = UUID.randomUUID().toString()
    fun valid(value: String): Boolean = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
}

sealed interface SecurityEvent {
    data class RemoteIdentityChanged(val contactId: String) : SecurityEvent
}

/** Fixed codes only: no dynamic text, throwables, payloads, tokens or secrets. */
enum class SafeLogCode { IDENTITY_CREATED, SESSION_ESTABLISHED, REMOTE_IDENTITY_CHANGED, CRYPTO_REJECTED }
fun interface SecurityLogger { fun record(code: SafeLogCode) }
