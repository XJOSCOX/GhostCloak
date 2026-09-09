package org.ghostcloak.crypto

import org.signal.libsignal.protocol.*
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.state.*
import java.util.UUID

/** No cached mutable protocol state: failed transactions discard all library-side records. */
internal class SignalStore(private val db: EndpointRecords) : SignalProtocolStore {
    private val trust = TrustStore(db)
    var rejectedIdentity: Pair<String, IdentityKey>? = null
    private fun address(a: SignalProtocolAddress) = "${a.name}/${a.deviceId}"
    private fun required(k: String) = db.read(k) ?: throw InvalidKeyIdException("Missing endpoint record")
    override fun getIdentityKeyPair(): IdentityKeyPair = decodeStored(db.read("local/key") ?: throw CorruptEndpointState(), ::IdentityKeyPair)
    override fun getLocalRegistrationId() = db.read("local/registration")?.decodeToString()?.toIntOrNull() ?: throw CorruptEndpointState()
    override fun getIdentity(a: SignalProtocolAddress) = trust.pin(a.name)
    override fun isTrustedIdentity(a: SignalProtocolAddress, key: IdentityKey, direction: IdentityKeyStore.Direction): Boolean {
        val pinned = getIdentity(a)
        val matches = pinned == null || pinned == key
        if (!matches) rejectedIdentity = a.name to key
        return matches && trust.status(a.name)?.trustState != org.ghostcloak.identity.IdentityTrustState.CHANGED
    }
    override fun saveIdentity(a: SignalProtocolAddress, key: IdentityKey): IdentityKeyStore.IdentityChange {
        if (!isTrustedIdentity(a, key, IdentityKeyStore.Direction.SENDING)) throw UntrustedIdentityException(a.name, key)
        db.write("trust/${address(a)}", key.serialize())
        if (db.read("trust-state/${a.name}") == null) trust.setState(a.name, org.ghostcloak.identity.IdentityTrustState.UNVERIFIED)
        return IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
    }
    override fun loadPreKey(id: Int) = decodeStored(required("pre/$id"), ::PreKeyRecord)
    override fun storePreKey(id: Int, r: PreKeyRecord) = putSecret("pre/$id", r.serialize())
    override fun containsPreKey(id: Int) = db.keys("pre/").contains("pre/$id")
    override fun removePreKey(id: Int) = db.remove("pre/$id")
    override fun loadSignedPreKey(id: Int) = decodeStored(required("signed/$id"), ::SignedPreKeyRecord)
    override fun loadSignedPreKeys() = db.keys("signed/").map { loadSignedPreKey(it.substringAfter('/').toInt()) }
    override fun storeSignedPreKey(id: Int, r: SignedPreKeyRecord) = putSecret("signed/$id", r.serialize())
    override fun containsSignedPreKey(id: Int) = db.keys("signed/").contains("signed/$id")
    override fun removeSignedPreKey(id: Int) = db.remove("signed/$id")
    override fun loadKyberPreKey(id: Int) = decodeStored(required("kyber/$id"), ::KyberPreKeyRecord)
    override fun loadKyberPreKeys() = db.keys("kyber/").map { loadKyberPreKey(it.substringAfter('/').toInt()) }
    override fun storeKyberPreKey(id: Int, r: KyberPreKeyRecord) = putSecret("kyber/$id", r.serialize())
    override fun containsKyberPreKey(id: Int) = db.keys("kyber/").contains("kyber/$id")
    // Phase 1A publishes only one-time Kyber prekeys, never last-resort keys.
    override fun markKyberPreKeyUsed(id: Int, signedId: Int, baseKey: ECPublicKey) = db.remove("kyber/$id")
    override fun loadSession(a: SignalProtocolAddress) = db.read("session/${address(a)}")?.let { decodeStored(it, ::SessionRecord) } ?: SessionRecord()
    override fun loadExistingSessions(a: List<SignalProtocolAddress>) = a.map {
        if (!containsSession(it)) throw NoSessionException("Missing endpoint session")
        loadSession(it)
    }
    override fun getSubDeviceSessions(name: String) = db.keys("session/$name/").map { it.substringAfterLast('/').toInt() }
    override fun storeSession(a: SignalProtocolAddress, r: SessionRecord) = putSecret("session/${address(a)}", r.serialize())
    override fun containsSession(a: SignalProtocolAddress) = db.keys("session/").contains("session/${address(a)}")
    override fun deleteSession(a: SignalProtocolAddress) = db.remove("session/${address(a)}")
    override fun deleteAllSessions(name: String) = db.keys("session/$name/").forEach(db::remove)
    override fun storeSenderKey(a: SignalProtocolAddress, id: UUID, r: SenderKeyRecord): Unit = throw UnsupportedOperationException("Groups out of scope")
    override fun loadSenderKey(a: SignalProtocolAddress, id: UUID): SenderKeyRecord = throw UnsupportedOperationException("Groups out of scope")
    private fun putSecret(k: String, bytes: ByteArray) { try { db.write(k, bytes) } finally { bytes.fill(0) } }
}

/** Distinguishes corrupt persisted library records from unauthenticated network input. */
internal fun <T> decodeStored(bytes: ByteArray, decode: (ByteArray) -> T): T = try { decode(bytes) }
    catch (e: InvalidSessionException) { throw CorruptEndpointState() }
    catch (e: InvalidMessageException) { throw CorruptEndpointState() }
    catch (e: InvalidKeyException) { throw CorruptEndpointState() }
    finally { bytes.fill(0) }
