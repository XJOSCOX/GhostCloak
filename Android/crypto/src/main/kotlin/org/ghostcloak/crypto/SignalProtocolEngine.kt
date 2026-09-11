package org.ghostcloak.crypto

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.ghostcloak.identity.*
import org.ghostcloak.protocol.*
import org.signal.libsignal.protocol.*
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.message.*
import org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator
import java.security.SecureRandom
import java.time.Clock

class SignalProtocolEngine(private val records: EndpointRecords,
    private val logger: SecurityLogger = SecurityLogger { },
    clock: Clock = Clock.systemUTC(), preKeyPolicy: PreKeyPolicy = PreKeyPolicy()) : SecureSessionEngine {
    private val mutex = Mutex()
    private val notifications = Channel<SecurityEvent>(Channel.BUFFERED)
    override val events = notifications.receiveAsFlow()
    private val store = SignalStore(records)
    private val trust = TrustStore(records)
    private val keyManager = SignalPreKeys(records, store, { local().name }, clock, preKeyPolicy)
    override val preKeys: PreKeyManager = object : PreKeyManager {
        override suspend fun createPublicationBundle() = operation { keyManager.create() }
        override suspend fun replenishIfNeeded() = operation { keyManager.replenish() }
        override suspend fun retireExpiredKeys() = operation { keyManager.retire() }
        override suspend fun inventory() = operation { keyManager.inventory() }
    }
    private fun text(key: String) = records.read(key)?.decodeToString() ?: throw CorruptEndpointState()
    private fun local() = SignalProtocolAddress(text("local/device"), 1)
    private fun remote(id: String): SignalProtocolAddress {
        require(RandomIdentifiers.valid(id)) { "Invalid device identifier" }
        return SignalProtocolAddress(id, 1)
    }
    private fun <T> atomic(site: FailureSite = FailureSite.COMMIT, block: () -> T): T =
        try { records.transaction(block) }
        catch (e: EndpointStorageFailure) { throw CryptoFailure(CryptoError.StorageFailure, site) }
        catch (e: CorruptEndpointState) { throw CryptoFailure(CryptoError.StorageFailure, FailureSite.LOCAL_STATE) }

    private suspend fun <T> operation(block: () -> T): T {
        var outgoingSecret: ByteArray? = null
        var delivered = false
        try {
            val result = withContext(Dispatchers.IO) {
                mutex.withLock {
                    store.rejectedIdentity = null
                    try { atomic { block().also { if (it is ByteArray) outgoingSecret = it } } }
                    catch (e: UntrustedIdentityException) {
                        // Crypto callbacks are rolled back. Persist the alert in a separate transaction.
                        val rejected = store.rejectedIdentity
                        val event = if (rejected == null) null else atomic(FailureSite.TRUST_TRANSITION) {
                            trust.changed(rejected.first, rejected.second)?.also {
                                store.deleteSession(remote(rejected.first))
                                setLifecycle(rejected.first, SessionLifecycle.REQUIRES_REAUTHENTICATION)
                                records.write("admission/${rejected.first}", "outbound".encodeToByteArray())
                            }
                        }
                        if (event != null) notifications.trySend(event)
                        logger.record(SafeLogCode.REMOTE_IDENTITY_CHANGED)
                        throw CryptoFailure(CryptoError.IdentityChanged, FailureSite.TRUST_TRANSITION)
                    }
                    catch (e: DuplicateMessageException) { throw CryptoFailure(CryptoError.Replay, FailureSite.SIGNAL) }
                    catch (e: ReusedBaseKeyException) { throw CryptoFailure(CryptoError.Replay, FailureSite.SIGNAL) }
                    catch (e: NoSessionException) { throw CryptoFailure(CryptoError.UnknownSession, FailureSite.SIGNAL) }
                    catch (e: InvalidMessageException) { throw CryptoFailure(CryptoError.AuthenticationFailed, FailureSite.SIGNAL) }
                    catch (e: InvalidKeyException) { throw CryptoFailure(CryptoError.AuthenticationFailed, FailureSite.SIGNAL) }
                    catch (e: InvalidKeyIdException) { throw CryptoFailure(CryptoError.AuthenticationFailed, FailureSite.SIGNAL) }
                    catch (e: InvalidVersionException) { throw CryptoFailure(CryptoError.MalformedEnvelope, FailureSite.SIGNAL) }
                    catch (e: LegacyMessageException) { throw CryptoFailure(CryptoError.MalformedEnvelope, FailureSite.SIGNAL) }
                    catch (e: InvalidSessionException) { throw CryptoFailure(CryptoError.InternalProtocolFailure, FailureSite.SIGNAL) }
                    catch (e: MalformedFrame) { throw CryptoFailure(CryptoError.MalformedEnvelope, FailureSite.INPUT) }
                    finally { store.rejectedIdentity = null }
                }
            }
            delivered = true
            return result
        } finally {
            // Includes commit failure and cancellation between IO completion and caller resumption.
            if (!delivered) outgoingSecret?.fill(0)
        }
    }
    private fun identity() = DeviceIdentity(text("local/user"), text("local/username"), text("local/device"), store.identityKeyPair.publicKey.serialize())
    override suspend fun createIdentity(username: String): DeviceIdentity = operation {
        require(username.matches(Regex("[A-Za-z0-9_]{1,32}")))
        if (records.read("local/device") == null) {
            if (records.keys("").isNotEmpty()) throw CorruptEndpointState()
            val encoded = IdentityKeyPair.generate().serialize()
            try { records.write("local/key", encoded) } finally { encoded.fill(0) }
            records.write("local/device", RandomIdentifiers.create().encodeToByteArray())
            records.write("local/user", RandomIdentifiers.create().encodeToByteArray())
            records.write("local/username", username.encodeToByteArray())
            records.write("local/registration", (SecureRandom().nextInt(16380) + 1).toString().encodeToByteArray())
            logger.record(SafeLogCode.IDENTITY_CREATED)
        }
        identity()
    }
    override suspend fun renameLocalUser(username: String): DeviceIdentity = operation {
        require(username.matches(Regex("[A-Za-z0-9_]{1,32}")))
        local()
        records.write("local/username", username.encodeToByteArray())
        identity()
    }
    override suspend fun publicBundle(): RemoteKeyBundle = preKeys.createPublicationBundle()

    private fun lifecycle(id: String): SessionLifecycle? {
        val stored = records.read("lifecycle/$id")?.decodeToString()
        if (stored != null) return try { SessionLifecycle.valueOf(stored) }
            catch (e: IllegalArgumentException) { throw CorruptEndpointState() }
        if (records.read("destroyed/$id") != null) {
            setLifecycle(id, SessionLifecycle.DESTROYED)
            records.write("admission/$id", "outbound".encodeToByteArray())
            records.remove("destroyed/$id")
            return SessionLifecycle.DESTROYED
        }
        return if (store.containsSession(remote(id))) SessionLifecycle.ACTIVE else null
    }
    private fun setLifecycle(id: String, state: SessionLifecycle) =
        records.write("lifecycle/$id", state.name.encodeToByteArray())
    private fun fingerprint(id: String, key: IdentityKey): String =
        NumericFingerprintGenerator(5200).createFor(2, local().name.encodeToByteArray(), store.identityKeyPair.publicKey,
            id.encodeToByteArray(), key).displayableFingerprint.displayText.chunked(5).joinToString(" ")
    private fun validateApproval(id: String, expected: String) {
        remote(id)
        trust.ensureUsable(id)
        val pin = trust.pin(id) ?: throw CryptoFailure(CryptoError.VerificationFailed)
        if (fingerprint(id, pin) != expected) throw CryptoFailure(CryptoError.VerificationFailed)
    }
    private fun processBundle(bundle: RemoteKeyBundle) {
        val address = remote(bundle.deviceId)
        SessionBuilder(store, address, local()).process(PreKeyBundle(bundle.registrationId, 1,
            bundle.preKeyId, ECPublicKey(bundle.preKey), bundle.signedId, ECPublicKey(bundle.signedKey),
            bundle.signature, IdentityKey(bundle.identity), bundle.kyberId, KEMPublicKey(bundle.kyberKey), bundle.kyberSignature))
        setLifecycle(bundle.deviceId, SessionLifecycle.ACTIVE)
        logger.record(SafeLogCode.SESSION_ESTABLISHED)
    }
    override suspend fun establishSession(remote: RemoteKeyBundle) = operation {
        trust.ensureUsable(remote.deviceId)
        when (lifecycle(remote.deviceId)) {
            SessionLifecycle.DESTROYED, SessionLifecycle.REQUIRES_REAUTHENTICATION -> throw CryptoFailure(CryptoError.ReauthenticationRequired)
            else -> processBundle(remote)
        }
    }
    override suspend fun reestablishSession(remote: RemoteKeyBundle, expectedFingerprint: String) = operation {
        validateApproval(remote.deviceId, expectedFingerprint)
        store.deleteSession(remote(remote.deviceId))
        processBundle(remote)
        // This endpoint initiates; the reply must be a Signal message, never an old prekey message.
        records.write("admission/${remote.deviceId}", "outbound".encodeToByteArray())
    }
    override suspend fun prepareReestablishment(remoteDeviceId: String, expectedFingerprint: String): RemoteKeyBundle = operation {
        validateApproval(remoteDeviceId, expectedFingerprint)
        store.deleteSession(remote(remoteDeviceId))
        val bundle = keyManager.create()
        setLifecycle(remoteDeviceId, SessionLifecycle.REQUIRES_REAUTHENTICATION)
        // Only this freshly generated, local signed/one-time EC key pair may start the replacement.
        records.write("admission/$remoteDeviceId", "${bundle.signedId},${bundle.preKeyId}".encodeToByteArray())
        bundle
    }
    override suspend fun getSessionLifecycle(remoteDeviceId: String) = operation { lifecycle(remoteDeviceId) }
    override suspend fun encrypt(remoteDeviceId: String, plaintext: ByteArray): EncryptedEnvelope = operation {
        require(plaintext.size <= EnvelopeCodec.MAX_BODY)
        val address = remote(remoteDeviceId)
        trust.ensureUsable(remoteDeviceId)
        if (lifecycle(remoteDeviceId) != SessionLifecycle.ACTIVE) throw CryptoFailure(CryptoError.UnknownSession)
        val id = RandomIdentifiers.create()
        val sender = local().name
        val bound = EnvelopeCodec.content(BoundContent(1, id, sender, remoteDeviceId, plaintext))
        try {
            val message = SessionCipher(store, local(), address).encrypt(bound)
            if (message.type !in listOf(2, 3)) throw CryptoFailure(CryptoError.InternalProtocolFailure, FailureSite.SIGNAL)
            EncryptedEnvelope(1, id, sender, remoteDeviceId, message.type, message.serialize())
        } finally { bound.fill(0) }
    }
    override suspend fun decrypt(envelope: EncryptedEnvelope): ByteArray = operation { decryptContent(envelope) }
    override suspend fun decryptAndCommit(envelope: EncryptedEnvelope, accept: (ByteArray) -> Unit) = operation {
        val bytes = decryptContent(envelope)
        try { accept(bytes) } finally { bytes.fill(0) }
    }
    private fun decryptContent(envelope: EncryptedEnvelope): ByteArray {
        EnvelopeCodec.validate(envelope)
        if (envelope.recipientDeviceId != local().name) throw CryptoFailure(CryptoError.WrongRecipient)
        val id = envelope.senderDeviceId
        trust.ensureUsable(id)
        val state = lifecycle(id)
        if (state == SessionLifecycle.DESTROYED) throw CryptoFailure(CryptoError.UnknownSession)
        val seenKey = "accepted/$id/${envelope.envelopeId}"
        if (records.read(seenKey) != null) throw CryptoFailure(CryptoError.Replay)
        if (records.keys("accepted/").size >= 10000) throw CryptoFailure(CryptoError.ResourceLimit)
        val cipher = SessionCipher(store, local(), remote(id))
        val plain = when (envelope.messageType) {
            3 -> {
                val message = PreKeySignalMessage(envelope.encryptedPayload)
                val admission = records.read("admission/$id")?.decodeToString()
                if (admission == "outbound" || (admission != null &&
                    admission != "${message.signedPreKeyId},${message.preKeyId.orElse(-1)}")) {
                    throw CryptoFailure(CryptoError.ReauthenticationRequired)
                }
                if (state == SessionLifecycle.REQUIRES_REAUTHENTICATION && admission == null)
                    throw CryptoFailure(CryptoError.ReauthenticationRequired)
                cipher.decrypt(message)
            }
            2 -> {
                if (state != SessionLifecycle.ACTIVE) throw CryptoFailure(CryptoError.UnknownSession)
                cipher.decrypt(SignalMessage(envelope.encryptedPayload))
            }
            else -> throw CryptoFailure(CryptoError.MalformedEnvelope)
        }
        return try {
            val bound = EnvelopeCodec.content(plain)
            try {
                if (bound.version != envelope.protocolVersion || bound.id != envelope.envelopeId ||
                    bound.sender != id || bound.recipient != envelope.recipientDeviceId)
                    throw CryptoFailure(CryptoError.AuthenticationFailed)
                records.write(seenKey, byteArrayOf(1))
                setLifecycle(id, SessionLifecycle.ACTIVE)
                bound.body.copyOf()
            } finally { bound.body.fill(0) }
        } finally { plain.fill(0) }
    }
    override suspend fun getRemoteFingerprint(remoteDeviceId: String): String = operation {
        remote(remoteDeviceId)
        fingerprint(remoteDeviceId, trust.pin(remoteDeviceId) ?: throw CryptoFailure(CryptoError.UnknownSession))
    }
    override suspend fun getRemoteIdentityStatus(remoteDeviceId: String) = operation { remote(remoteDeviceId); trust.status(remoteDeviceId) }
    override suspend fun verifyRemoteIdentity(remoteDeviceId: String, expectedFingerprint: String) = operation {
        validateApproval(remoteDeviceId, expectedFingerprint)
        trust.setState(remoteDeviceId, IdentityTrustState.VERIFIED)
    }
    override suspend fun getPendingFingerprint(remoteDeviceId: String) = operation {
        remote(remoteDeviceId)
        fingerprint(remoteDeviceId, trust.candidate(remoteDeviceId) ?: throw CryptoFailure(CryptoError.VerificationFailed))
    }
    override suspend fun trustNewIdentity(remoteDeviceId: String, expectedFingerprint: String) = operation {
        remote(remoteDeviceId)
        if (trust.status(remoteDeviceId)?.trustState != IdentityTrustState.CHANGED) throw CryptoFailure(CryptoError.VerificationFailed)
        val candidate = trust.candidate(remoteDeviceId) ?: throw CryptoFailure(CryptoError.VerificationFailed)
        if (fingerprint(remoteDeviceId, candidate) != expectedFingerprint) throw CryptoFailure(CryptoError.VerificationFailed)
        trust.acceptCandidate(remoteDeviceId)
        store.deleteSession(remote(remoteDeviceId))
        setLifecycle(remoteDeviceId, SessionLifecycle.REQUIRES_REAUTHENTICATION)
        records.write("admission/$remoteDeviceId", "outbound".encodeToByteArray())
    }
    override suspend fun destroySession(remoteDeviceId: String) = operation {
        store.deleteSession(remote(remoteDeviceId))
        setLifecycle(remoteDeviceId, SessionLifecycle.DESTROYED)
        records.write("admission/$remoteDeviceId", "outbound".encodeToByteArray())
    }
}
