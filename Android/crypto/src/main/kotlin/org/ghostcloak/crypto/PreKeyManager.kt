package org.ghostcloak.crypto

import java.time.Clock
import java.time.Duration
import java.security.SecureRandom
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.state.*

data class PreKeyInventory(val unusedBundles: Int, val retainedSignedKeys: Int)
interface PreKeyManager {
    suspend fun createPublicationBundle(): RemoteKeyBundle
    /** Returns newly generated public bundles; never publishes or performs networking. */
    suspend fun replenishIfNeeded(): List<RemoteKeyBundle>
    suspend fun retireExpiredKeys(): Int
    suspend fun inventory(): PreKeyInventory
}

/** Local prototype policy, not an upstream Signal recommendation or a production rotation service. */
data class PreKeyPolicy(val targetUnused: Int = 1, val maximumRetained: Int = 32,
    val maximumRetention: Duration = Duration.ofDays(30)) {
    init { require(targetUnused in 1..maximumRetained && maximumRetained <= 10000); require(!maximumRetention.isNegative && !maximumRetention.isZero) }
}

internal class SignalPreKeys(private val db: EndpointRecords, private val store: SignalStore,
    private val device: () -> String, private val clock: Clock, private val policy: PreKeyPolicy) {
    private data class Meta(val signed: Int, val ec: Int, val kyber: Int, val created: Long)
    private fun metadata(): List<Meta> = store.loadSignedPreKeys().map { record ->
        val encoded = db.read("prekey-meta/${record.id}")
        if (encoded == null) {
            // Phase 1A used the same number in all three independent namespaces.
            Meta(record.id, record.id, record.id, record.timestamp)
        } else {
            val fields = encoded.decodeToString().split(',')
            if (fields.size != 3) throw CorruptEndpointState()
            Meta(record.id, fields[0].toIntOrNull() ?: throw CorruptEndpointState(),
                fields[1].toIntOrNull() ?: throw CorruptEndpointState(), fields[2].toLongOrNull() ?: throw CorruptEndpointState())
        }
    }
    fun inventory(): PreKeyInventory {
        val all = metadata()
        return PreKeyInventory(all.count { store.containsPreKey(it.ec) && store.containsKyberPreKey(it.kyber) }, all.size)
    }
    private fun allocate(namespace: String, forbidden: Set<Int> = emptySet()): Int {
        // IDs are lookup identifiers, not secrets. Persist issued IDs even after private-key retirement.
        if (db.keys("issued/$namespace/").size >= 10000) throw CryptoFailure(CryptoError.ResourceLimit)
        repeat(64) {
            val id = SecureRandom().nextInt(Int.MAX_VALUE - 1) + 1
            if (id !in forbidden && db.read("issued/$namespace/$id") == null && "$namespace/$id" !in db.keys("$namespace/")) {
                db.write("issued/$namespace/$id", byteArrayOf(1))
                return id
            }
        }
        throw CryptoFailure(CryptoError.ResourceLimit)
    }
    fun create(): RemoteKeyBundle {
        if (inventory().retainedSignedKeys >= policy.maximumRetained) throw CryptoFailure(CryptoError.ResourceLimit)
        val ecId = allocate("pre")
        val signedId = allocate("signed", setOf(ecId))
        val kyberId = allocate("kyber", setOf(ecId, signedId))
        val identity = store.identityKeyPair
        val ec = ECKeyPair.generate()
        val signed = ECKeyPair.generate()
        val kyber = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val signature = identity.privateKey.calculateSignature(signed.publicKey.serialize())
        val kyberSignature = identity.privateKey.calculateSignature(kyber.publicKey.serialize())
        val now = clock.millis()
        store.storePreKey(ecId, PreKeyRecord(ecId, ec))
        store.storeSignedPreKey(signedId, SignedPreKeyRecord(signedId, now, signed, signature))
        store.storeKyberPreKey(kyberId, KyberPreKeyRecord(kyberId, now, kyber, kyberSignature))
        db.write("prekey-meta/$signedId", "$ecId,$kyberId,$now".encodeToByteArray())
        return RemoteKeyBundle(device(), store.localRegistrationId, identity.publicKey.serialize(), ecId,
            ec.publicKey.serialize(), signedId, signed.publicKey.serialize(), signature, kyberId,
            kyber.publicKey.serialize(), kyberSignature)
    }
    fun replenish(): List<RemoteKeyBundle> = List((policy.targetUnused - inventory().unusedBundles).coerceAtLeast(0)) { create() }
    fun retire(): Int {
        val expired = metadata().filter { clock.millis() >= it.created && clock.millis() - it.created >= policy.maximumRetention.toMillis() }
        expired.forEach {
            // Preserve namespaces for migrated records as well as new ones; never reuse retired IDs.
            db.write("issued/pre/${it.ec}", byteArrayOf(1))
            db.write("issued/signed/${it.signed}", byteArrayOf(1))
            db.write("issued/kyber/${it.kyber}", byteArrayOf(1))
            store.removePreKey(it.ec)
            store.removeSignedPreKey(it.signed)
            db.remove("kyber/${it.kyber}")
            db.remove("prekey-meta/${it.signed}")
        }
        return expired.size
    }
}
