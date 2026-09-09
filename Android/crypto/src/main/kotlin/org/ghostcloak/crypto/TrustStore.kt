package org.ghostcloak.crypto

import org.ghostcloak.identity.*
import org.signal.libsignal.protocol.IdentityKey

/** Uses existing Phase 1A identity pin keys; missing state on an existing pin migrates to UNVERIFIED. */
internal class TrustStore(private val db: EndpointRecords) {
    fun pin(id: String): IdentityKey? = db.read("trust/$id/1")?.let { decodeStored(it, ::IdentityKey) }
    private fun decodeState(value: String) = try { IdentityTrustState.valueOf(value) }
        catch (e: IllegalArgumentException) { throw CorruptEndpointState() }
    fun status(id: String): RemoteIdentityStatus? {
        if (pin(id) == null) return null
        val state = db.read("trust-state/$id")?.decodeToString()?.let(::decodeState)
            ?: IdentityTrustState.UNVERIFIED.also { setState(id, it) }
        val previous = db.read("trust-previous/$id")?.decodeToString()?.let(::decodeState)
        return RemoteIdentityStatus(state, previous)
    }
    fun setState(id: String, state: IdentityTrustState) = db.write("trust-state/$id", state.name.encodeToByteArray())
    fun changed(id: String, candidate: IdentityKey): SecurityEvent.RemoteIdentityChanged? {
        val old = pin(id) ?: return null
        if (old == candidate) return null
        val status = status(id)!!
        val previous = status.previousTrustState ?: status.trustState
        db.write("trust-previous/$id", previous.name.encodeToByteArray())
        db.write("trust-candidate/$id", candidate.serialize())
        setState(id, IdentityTrustState.CHANGED)
        return SecurityEvent.RemoteIdentityChanged(id, previous)
    }
    fun candidate(id: String) = db.read("trust-candidate/$id")?.let { decodeStored(it, ::IdentityKey) }
    fun ensureUsable(id: String) {
        if (status(id)?.trustState == IdentityTrustState.CHANGED) throw CryptoFailure(CryptoError.IdentityChanged, FailureSite.TRUST_TRANSITION)
    }
    fun acceptCandidate(id: String) {
        val candidate = candidate(id) ?: throw CryptoFailure(CryptoError.VerificationFailed)
        // The sole replacement path. Approval does not claim independent verification.
        db.write("trust/$id/1", candidate.serialize())
        setState(id, IdentityTrustState.UNVERIFIED)
        db.remove("trust-candidate/$id")
        db.remove("trust-previous/$id")
    }
}
