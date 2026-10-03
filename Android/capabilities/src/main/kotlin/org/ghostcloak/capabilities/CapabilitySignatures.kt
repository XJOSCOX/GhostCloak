package org.ghostcloak.capabilities

import org.ghostcloak.protocol.*
import org.signal.libsignal.protocol.ecc.ECPublicKey

/** Same vetted identity signature primitive as Signal signed prekeys; no new private key. */
object CapabilitySignatures {
    fun verify(audience: String, account: String, routing: String, bundle: PublicBundle, now: Long): Boolean {
        val proof = bundle.capability ?: return false
        if (!AttachmentCapabilities.valid(proof, bundle, now)) return false
        return try {
            bundle.validate()
            ECPublicKey(bundle.identity).verifySignature(AttachmentCapabilities.statement(audience, account, routing, bundle, proof), proof.signature)
        } catch (_: Exception) { false }
    }
}
