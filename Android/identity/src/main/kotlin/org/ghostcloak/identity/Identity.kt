package org.ghostcloak.identity

import java.util.UUID

data class DeviceIdentity(val userId: String, val username: String, val deviceId: String, val publicKey: ByteArray) {
    override fun toString() = "DeviceIdentity(public metadata only)"
}

object RandomIdentifiers {
    fun create(): String = UUID.randomUUID().toString()
    fun valid(value: String): Boolean = try { UUID.fromString(value).toString() == value }
        catch (e: IllegalArgumentException) { false }
}

enum class IdentityTrustState { UNVERIFIED, VERIFIED, CHANGED }
enum class SecurityPriority { NORMAL, HIGH }
data class RemoteIdentityStatus(val trustState: IdentityTrustState, val previousTrustState: IdentityTrustState? = null)
enum class SessionLifecycle { ACTIVE, DESTROYED, REQUIRES_REAUTHENTICATION }

sealed interface SecurityEvent {
    data class RemoteIdentityChanged(val contactId: String,
        val previousTrustState: IdentityTrustState = IdentityTrustState.UNVERIFIED) : SecurityEvent {
        val priority: SecurityPriority get() = if (previousTrustState == IdentityTrustState.VERIFIED) SecurityPriority.HIGH else SecurityPriority.NORMAL
        override fun toString() = "RemoteIdentityChanged(priority=$priority)"
    }
}

/** Fixed codes only: no dynamic text, throwables, payloads, tokens or secrets. */
enum class SafeLogCode { IDENTITY_CREATED, SESSION_ESTABLISHED, REMOTE_IDENTITY_CHANGED, CRYPTO_REJECTED }
fun interface SecurityLogger { fun record(code: SafeLogCode) }
