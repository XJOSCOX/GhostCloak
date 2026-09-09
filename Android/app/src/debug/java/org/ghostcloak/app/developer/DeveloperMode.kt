package org.ghostcloak.app.developer

import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.ghostcloak.app.application.DemoSession
import org.ghostcloak.crypto.SignalProtocolEngine
import org.ghostcloak.messaging.*
import org.ghostcloak.storage.EncryptedEndpointStore
import org.ghostcloak.transport.LocalEncryptedRouter

/** Compiled only in debug. Separate encrypted databases; never replaces the user's identity. */
object DeveloperMode {
    const val available = true
    private var session: DemoSession? = null
    suspend fun create(context: Context): DemoSession {
        session?.let { return it }
        val ar = EncryptedEndpointStore.open(context, "demo-alice")
        val br = EncryptedEndpointStore.open(context, "demo-bob")
        val alice = ConversationService(SignalProtocolEngine(ar), LocalRepository(ar))
        val bob = ConversationService(SignalProtocolEngine(br), LocalRepository(br))
        val ai = alice.open() ?: alice.create("Alice")
        val bi = bob.open() ?: bob.create("Bob")
        if (alice.contacts().isEmpty()) alice.importCard(bob.exportCard())
        if (bob.contacts().isEmpty()) bob.importCard(alice.exportCard())
        val router = LocalEncryptedRouter()
        val ta = router.register(ai.deviceId); val tb = router.register(bi.deviceId)
        alice.attach(ta); bob.attach(tb)
        val result = object : DemoSession {
            override val service = alice
            override suspend fun deliver(message: Message) {
                if (message.conversationId != bi.deviceId) return
                withTimeout(10000) {
                    val received = bob.receive(tb.receive().first())
                    alice.delivered(bi.deviceId, message.localId, received.localId)
                    val reply = bob.send(ai.deviceId, "Hello Alice. Your text arrived at Bob's local endpoint.")
                    val response = alice.receive(ta.receive().first())
                    bob.delivered(ai.deviceId, reply.localId, response.localId)
                }
            }
        }
        session = result
        if (alice.messages(bi.deviceId).isEmpty()) result.deliver(alice.send(bi.deviceId, "hello Bob"))
        return result
    }
}
