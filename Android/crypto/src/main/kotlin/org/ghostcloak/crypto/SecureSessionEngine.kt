package org.ghostcloak.crypto

import org.ghostcloak.identity.DeviceIdentity
import org.ghostcloak.identity.SecurityEvent
import org.ghostcloak.identity.RemoteIdentityStatus
import org.ghostcloak.identity.SessionLifecycle
import org.ghostcloak.protocol.EncryptedEnvelope
import kotlinx.coroutines.flow.Flow

class RemoteKeyBundle(val deviceId: String, val registrationId: Int, val identity: ByteArray,
    val preKeyId: Int, val preKey: ByteArray, val signedId: Int, val signedKey: ByteArray,
    val signature: ByteArray, val kyberId: Int, val kyberKey: ByteArray, val kyberSignature: ByteArray)

enum class CryptoError { AuthenticationFailed, UnknownSession, IdentityChanged, MalformedEnvelope, Replay, WrongRecipient, StorageFailure, InternalProtocolFailure, VerificationFailed, ReauthenticationRequired, ResourceLimit }
enum class FailureSite { INPUT, SIGNAL, LOCAL_STATE, COMMIT, TRUST_TRANSITION }
/** Safe categories only: raw upstream messages/causes must not reach UI or logs. */
class CryptoFailure(val error: CryptoError, val site: FailureSite = FailureSite.INPUT) : Exception(error.name)

interface SecureSessionEngine {
    val events: Flow<SecurityEvent>
    suspend fun createIdentity(displayName: String): DeviceIdentity
    suspend fun publicBundle(): RemoteKeyBundle
    suspend fun signAttachmentCapability(audience: String, account: String, routing: String,
        bundle: org.ghostcloak.protocol.PublicBundle, time: Long): org.ghostcloak.protocol.AttachmentCapability
    suspend fun verifyAttachmentCapability(audience: String, entry: org.ghostcloak.protocol.DirectoryEntry,
        time: Long): Boolean
    suspend fun establishSession(remote: RemoteKeyBundle)
    /** Check a retained peer pin without replacing its existing ratchet. */
    suspend fun validateRetainedIdentity(remote: RemoteKeyBundle)
    suspend fun encrypt(remoteDeviceId: String, plaintext: ByteArray): EncryptedEnvelope
    suspend fun decrypt(envelope: EncryptedEnvelope): ByteArray
    /** Commit application acceptance using the same EndpointRecords transaction as the ratchet.
     * The callback must use that same store, must not suspend or retain the supplied plaintext. */
    suspend fun decryptAndCommit(envelope: EncryptedEnvelope, accept: (ByteArray) -> Unit)
    suspend fun getRemoteFingerprint(remoteDeviceId: String): String
    suspend fun destroySession(remoteDeviceId: String)
    suspend fun getRemoteIdentityStatus(remoteDeviceId: String): RemoteIdentityStatus?
    /** Null unless the existing accepted Signal pin is usable for a group authority lookup. */
    suspend fun trustedRemoteIdentityDigest(remoteDeviceId:String):ByteArray? = null
    suspend fun verifyRemoteIdentity(remoteDeviceId: String, expectedFingerprint: String)
    suspend fun getPendingFingerprint(remoteDeviceId: String): String
    suspend fun trustNewIdentity(remoteDeviceId: String, expectedFingerprint: String)
    suspend fun getSessionLifecycle(remoteDeviceId: String): SessionLifecycle?
    suspend fun reestablishSession(remote: RemoteKeyBundle, expectedFingerprint: String)
    suspend fun prepareReestablishment(remoteDeviceId: String, expectedFingerprint: String): RemoteKeyBundle
    suspend fun renameLocalUser(displayName: String): DeviceIdentity
    val preKeys: PreKeyManager
}
