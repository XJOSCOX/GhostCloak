package org.ghostcloak.crypto

import org.signal.libsignal.protocol.*
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.state.*
import java.util.UUID

/** No cached mutable protocol state: failed transactions discard all library-side records. */
internal class SignalStore(private val db: EndpointRecords) : SignalProtocolStore {
    private fun address(a: SignalProtocolAddress) = "${a.name}/${a.deviceId}"
    private fun required(k: String) = db.read(k) ?: throw InvalidKeyIdException("Missing endpoint record")
    override fun getIdentityKeyPair(): IdentityKeyPair = required("local/key").let { try { IdentityKeyPair(it) } finally { it.fill(0) } }
    override fun getLocalRegistrationId() = required("local/registration").decodeToString().toInt()
    override fun getIdentity(a: SignalProtocolAddress) = db.read("trust/${address(a)}")?.let { IdentityKey(it) }
    override fun isTrustedIdentity(a: SignalProtocolAddress, key: IdentityKey, direction: IdentityKeyStore.Direction) = getIdentity(a)?.let { it == key } ?: true
    override fun saveIdentity(a: SignalProtocolAddress, key: IdentityKey): IdentityKeyStore.IdentityChange {
        if (!isTrustedIdentity(a, key, IdentityKeyStore.Direction.SENDING)) throw UntrustedIdentityException(a.name, key)
        db.write("trust/${address(a)}", key.serialize())
        return IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
    }
    override fun loadPreKey(id: Int) = required("pre/$id").let { try { PreKeyRecord(it) } finally { it.fill(0) } }
    override fun storePreKey(id: Int, r: PreKeyRecord) = putSecret("pre/$id", r.serialize())
    override fun containsPreKey(id: Int) = db.keys("pre/").contains("pre/$id")
    override fun removePreKey(id: Int) = db.remove("pre/$id")
    override fun loadSignedPreKey(id: Int) = required("signed/$id").let { try { SignedPreKeyRecord(it) } finally { it.fill(0) } }
    override fun loadSignedPreKeys() = db.keys("signed/").map { loadSignedPreKey(it.substringAfter('/').toInt()) }
    override fun storeSignedPreKey(id: Int, r: SignedPreKeyRecord) = putSecret("signed/$id", r.serialize())
    override fun containsSignedPreKey(id: Int) = db.keys("signed/").contains("signed/$id")
    override fun removeSignedPreKey(id: Int) = db.remove("signed/$id")
    override fun loadKyberPreKey(id: Int) = required("kyber/$id").let { try { KyberPreKeyRecord(it) } finally { it.fill(0) } }
    override fun loadKyberPreKeys() = db.keys("kyber/").map { loadKyberPreKey(it.substringAfter('/').toInt()) }
    override fun storeKyberPreKey(id: Int, r: KyberPreKeyRecord) = putSecret("kyber/$id", r.serialize())
    override fun containsKyberPreKey(id: Int) = db.keys("kyber/").contains("kyber/$id")
    // Phase 1A publishes only one-time Kyber prekeys, never last-resort keys.
    override fun markKyberPreKeyUsed(id: Int, signedId: Int, baseKey: ECPublicKey) = db.remove("kyber/$id")
    override fun loadSession(a: SignalProtocolAddress) = db.read("session/${address(a)}")?.let { try { SessionRecord(it) } finally { it.fill(0) } } ?: SessionRecord()
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
