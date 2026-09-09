package org.ghostcloak.crypto

import org.ghostcloak.identity.DeviceIdentity
import org.ghostcloak.identity.SecurityEvent
import org.ghostcloak.protocol.EncryptedEnvelope
import kotlinx.coroutines.flow.Flow

class RemoteKeyBundle(val deviceId: String, val registrationId: Int, val identity: ByteArray,
    val preKeyId: Int, val preKey: ByteArray, val signedId: Int, val signedKey: ByteArray,
    val signature: ByteArray, val kyberId: Int, val kyberKey: ByteArray, val kyberSignature: ByteArray)

enum class CryptoError { AuthenticationFailed, UnknownSession, IdentityChanged, MalformedEnvelope, Replay, WrongRecipient, StorageFailure }
class CryptoFailure(val error: CryptoError) : Exception(error.name)

interface SecureSessionEngine {
    val events: Flow<SecurityEvent>
    suspend fun createIdentity(username: String): DeviceIdentity
    suspend fun publicBundle(): RemoteKeyBundle
    suspend fun establishSession(remote: RemoteKeyBundle)
    suspend fun encrypt(remoteDeviceId: String, plaintext: ByteArray): EncryptedEnvelope
    suspend fun decrypt(envelope: EncryptedEnvelope): ByteArray
    suspend fun getRemoteFingerprint(remoteDeviceId: String): String
    suspend fun destroySession(remoteDeviceId: String)
}
