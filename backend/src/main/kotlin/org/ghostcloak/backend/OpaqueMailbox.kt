package org.ghostcloak.backend

import org.ghostcloak.protocol.EnvelopeCodec

interface OpaqueEnvelopeStorage {
    fun storeEncryptedEnvelope(recipientRoutingId: String, encryptedEnvelope: ByteArray)
    fun takeEncryptedEnvelope(recipientRoutingId: String): ByteArray?
}

/** Local skeleton only; no server listener, auth, endpoint state, crypto dependency or decryption API. */
class OpaqueMailbox : OpaqueEnvelopeStorage {
    private val queues = mutableMapOf<String, ArrayDeque<ByteArray>>()
    @Synchronized override fun storeEncryptedEnvelope(recipientRoutingId: String, encryptedEnvelope: ByteArray) {
        val envelope = EnvelopeCodec.decode(encryptedEnvelope)
        require(envelope.recipientDeviceId == recipientRoutingId)
        check(queues.values.sumOf { it.size } < 32) { "Prototype mailbox full" }
        queues.getOrPut(recipientRoutingId) { ArrayDeque() }.addLast(encryptedEnvelope.copyOf())
    }
    @Synchronized override fun takeEncryptedEnvelope(recipientRoutingId: String): ByteArray? {
        val queue = queues[recipientRoutingId] ?: return null
        val result = queue.removeFirstOrNull()
        if (queue.isEmpty()) queues.remove(recipientRoutingId)
        return result
    }
}
