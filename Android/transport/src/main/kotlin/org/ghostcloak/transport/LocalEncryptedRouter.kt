package org.ghostcloak.transport

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.ghostcloak.protocol.EncryptedEnvelope
import org.ghostcloak.protocol.EnvelopeCodec
import java.util.concurrent.ConcurrentHashMap

/** In-process only; routes serialized ciphertext, with no endpoint service or decryption API. */
class LocalEncryptedRouter {
    private val routes = ConcurrentHashMap<String, Channel<EncryptedEnvelope>>()
    fun register(deviceId: String): EncryptedMessageTransport {
        val inbox = Channel<EncryptedEnvelope>(32)
        require(routes.putIfAbsent(deviceId, inbox) == null)
        return object : EncryptedMessageTransport {
            override suspend fun send(routingDestination: String, envelope: EncryptedEnvelope) {
                if (envelope.senderDeviceId != deviceId || envelope.recipientDeviceId != routingDestination)
                    throw TransportFailure(TransportError.WrongRoute)
                val target = routes[routingDestination] ?: throw TransportFailure(TransportError.WrongRoute)
                val copy = EnvelopeCodec.decode(EnvelopeCodec.encode(envelope))
                if (!target.trySend(copy).isSuccess) throw TransportFailure(TransportError.QueueFull)
            }
            override fun receive() = inbox.receiveAsFlow()
        }
    }
    fun close() { routes.values.forEach { it.close() }; routes.clear() }
}
