package org.ghostcloak.transport

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import org.ghostcloak.protocol.EncryptedEnvelope
import org.ghostcloak.protocol.EnvelopeCodec

enum class TransportError { WrongRoute, QueueFull }
class TransportFailure(val error: TransportError) : Exception(error.name)
interface EncryptedMessageTransport {
    suspend fun send(routingDestination: String, envelope: EncryptedEnvelope)
    fun receive(): Flow<EncryptedEnvelope>
}

/** A bounded local wire round-trip. No plaintext or crypto/storage dependency. */
class MockEncryptedTransport : EncryptedMessageTransport {
    private val queue = Channel<EncryptedEnvelope>(32)
    override suspend fun send(routingDestination: String, envelope: EncryptedEnvelope) {
        if (routingDestination != envelope.recipientDeviceId) throw TransportFailure(TransportError.WrongRoute)
        val copy = EnvelopeCodec.decode(EnvelopeCodec.encode(envelope))
        if (!queue.trySend(copy).isSuccess) throw TransportFailure(TransportError.QueueFull)
    }
    override fun receive() = queue.receiveAsFlow()
}
