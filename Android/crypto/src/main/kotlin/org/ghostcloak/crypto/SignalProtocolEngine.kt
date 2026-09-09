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
import org.signal.libsignal.protocol.ecc.*
import org.signal.libsignal.protocol.kem.*
import org.signal.libsignal.protocol.state.*
import org.signal.libsignal.protocol.message.*
import org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator
import java.security.SecureRandom

class SignalProtocolEngine(private val records: EndpointRecords,
    private val logger: SecurityLogger = SecurityLogger { }) : SecureSessionEngine {
    private val mutex = Mutex()
    private val notifications = Channel<SecurityEvent>(Channel.BUFFERED)
    override val events = notifications.receiveAsFlow()
    private val store = SignalStore(records)
    private fun text(key: String) = records.read(key)?.decodeToString() ?: throw CryptoFailure(CryptoError.UnknownSession)
    private fun local() = SignalProtocolAddress(text("local/device"), 1)
    private fun remote(id: String): SignalProtocolAddress {
        if (!RandomIdentifiers.valid(id)) throw CryptoFailure(CryptoError.MalformedEnvelope)
        return SignalProtocolAddress(id, 1)
    }
    private suspend fun <T> operation(contact: String? = null, block: () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            try { records.transaction(block) }
            catch (e: UntrustedIdentityException) {
                notifications.trySend(SecurityEvent.RemoteIdentityChanged(contact ?: e.name))
                logger.record(SafeLogCode.REMOTE_IDENTITY_CHANGED)
                throw CryptoFailure(CryptoError.IdentityChanged)
            }
            catch (e: DuplicateMessageException) { throw CryptoFailure(CryptoError.Replay) }
            catch (e: NoSessionException) { throw CryptoFailure(CryptoError.UnknownSession) }
            catch (e: InvalidMessageException) { throw CryptoFailure(CryptoError.AuthenticationFailed) }
            catch (e: InvalidKeyException) { throw CryptoFailure(CryptoError.AuthenticationFailed) }
            catch (e: InvalidKeyIdException) { throw CryptoFailure(CryptoError.AuthenticationFailed) }
            catch (e: InvalidVersionException) { throw CryptoFailure(CryptoError.MalformedEnvelope) }
            catch (e: LegacyMessageException) { throw CryptoFailure(CryptoError.MalformedEnvelope) }
            catch (e: IllegalArgumentException) { throw CryptoFailure(CryptoError.MalformedEnvelope) }
        }
    }
    override suspend fun createIdentity(username: String): DeviceIdentity = operation {
        require(username.matches(Regex("[A-Za-z0-9_]{1,32}")))
        if (records.read("local/device") == null) {
            check(records.keys("").isEmpty()) { "Incomplete endpoint identity" }
            val pair = IdentityKeyPair.generate()
            val encoded = pair.serialize()
            try { records.write("local/key", encoded) } finally { encoded.fill(0) }
            records.write("local/device", RandomIdentifiers.create().encodeToByteArray())
            records.write("local/user", RandomIdentifiers.create().encodeToByteArray())
            records.write("local/username", username.encodeToByteArray())
            records.write("local/registration", (SecureRandom().nextInt(16380) + 1).toString().encodeToByteArray())
            logger.record(SafeLogCode.IDENTITY_CREATED)
        }
        DeviceIdentity(text("local/user"), text("local/username"), text("local/device"), store.identityKeyPair.publicKey.serialize())
    }
    override suspend fun publicBundle(): RemoteKeyBundle = operation {
        // Bounded prototype pool. No silent deletion of keys needed for delayed packets.
        check(records.keys("signed/").size < 32) { "Prototype prekey pool exhausted; lifecycle review required" }
        val id = generateSequence { SecureRandom().nextInt(Int.MAX_VALUE - 1) + 1 }
            .first { !store.containsSignedPreKey(it) }
        val identity = store.identityKeyPair
        val pre = ECKeyPair.generate()
        val signed = ECKeyPair.generate()
        val kyber = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val signature = identity.privateKey.calculateSignature(signed.publicKey.serialize())
        val kyberSignature = identity.privateKey.calculateSignature(kyber.publicKey.serialize())
        store.storePreKey(id, PreKeyRecord(id, pre))
        store.storeSignedPreKey(id, SignedPreKeyRecord(id, System.currentTimeMillis(), signed, signature))
        store.storeKyberPreKey(id, KyberPreKeyRecord(id, System.currentTimeMillis(), kyber, kyberSignature))
        RemoteKeyBundle(local().name, store.localRegistrationId, identity.publicKey.serialize(), id,
            pre.publicKey.serialize(), id, signed.publicKey.serialize(), signature, id,
            kyber.publicKey.serialize(), kyberSignature)
    }
    override suspend fun establishSession(remote: RemoteKeyBundle) = operation(remote.deviceId) {
        val address = remote(remote.deviceId)
        check(records.read("destroyed/${address.name}") == null) { "Destroyed contact requires a new endpoint identity" }
        SessionBuilder(store, address, local()).process(PreKeyBundle(remote.registrationId, 1,
            remote.preKeyId, ECPublicKey(remote.preKey), remote.signedId, ECPublicKey(remote.signedKey),
            remote.signature, IdentityKey(remote.identity), remote.kyberId, KEMPublicKey(remote.kyberKey), remote.kyberSignature))
        logger.record(SafeLogCode.SESSION_ESTABLISHED)
    }
    override suspend fun encrypt(remoteDeviceId: String, plaintext: ByteArray): EncryptedEnvelope = operation(remoteDeviceId) {
        require(plaintext.size <= EnvelopeCodec.MAX_BODY)
        val address = remote(remoteDeviceId)
        val id = RandomIdentifiers.create()
        val sender = local().name
        val bound = EnvelopeCodec.content(BoundContent(1, id, sender, remoteDeviceId, plaintext))
        try {
            val message = SessionCipher(store, local(), address).encrypt(bound)
            EncryptedEnvelope(1, id, sender, remoteDeviceId, message.type, message.serialize())
        } finally { bound.fill(0) }
    }
    override suspend fun decrypt(envelope: EncryptedEnvelope): ByteArray = operation(envelope.senderDeviceId) {
        EnvelopeCodec.validate(envelope)
        if (envelope.recipientDeviceId != local().name) throw CryptoFailure(CryptoError.WrongRecipient)
        if (records.read("destroyed/${envelope.senderDeviceId}") != null) throw CryptoFailure(CryptoError.UnknownSession)
        val cipher = SessionCipher(store, local(), remote(envelope.senderDeviceId))
        val plain = when (envelope.messageType) {
            3 -> cipher.decrypt(PreKeySignalMessage(envelope.encryptedPayload))
            2 -> cipher.decrypt(SignalMessage(envelope.encryptedPayload))
            else -> throw CryptoFailure(CryptoError.MalformedEnvelope)
        }
        try {
            val bound = EnvelopeCodec.content(plain)
            if (bound.version != envelope.protocolVersion || bound.id != envelope.envelopeId ||
                bound.sender != envelope.senderDeviceId || bound.recipient != envelope.recipientDeviceId) {
                bound.body.fill(0)
                throw CryptoFailure(CryptoError.AuthenticationFailed)
            }
            bound.body
        } finally { plain.fill(0) }
    }
    override suspend fun getRemoteFingerprint(remoteDeviceId: String): String = operation(remoteDeviceId) {
        val identity = store.getIdentity(remote(remoteDeviceId)) ?: throw CryptoFailure(CryptoError.UnknownSession)
        NumericFingerprintGenerator(5200).createFor(2, local().name.encodeToByteArray(), store.identityKeyPair.publicKey,
            remoteDeviceId.encodeToByteArray(), identity).displayableFingerprint.displayText.chunked(5).joinToString(" ")
    }
    override suspend fun destroySession(remoteDeviceId: String) = operation(remoteDeviceId) {
        store.deleteSession(remote(remoteDeviceId))
        // Retain identity pin and block old prekey packets from resurrecting a destroyed session.
        records.write("destroyed/$remoteDeviceId", byteArrayOf(1))
    }
}
