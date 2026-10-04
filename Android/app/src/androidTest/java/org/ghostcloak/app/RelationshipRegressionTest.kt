package org.ghostcloak.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.ghostcloak.crypto.SignalProtocolEngine
import org.ghostcloak.identity.RandomIdentifiers
import org.ghostcloak.messaging.*
import org.ghostcloak.protocol.SenderProfile
import org.ghostcloak.storage.EncryptedEndpointStore
import org.junit.Assert.*
import org.junit.Test

class RelationshipRegressionTest {
    @Test fun encryptedRejectReopenFreshRequestExplicitAddAndBlock()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val an=RandomIdentifiers.create();val bn=RandomIdentifiers.create()
        EncryptedEndpointStore.open(context,an).use { ar ->
            val ae=SignalProtocolEngine(ar);val a=ConversationService(ae,LocalRepository(ar))
            val ai=a.create("alice")
            lateinit var first:org.ghostcloak.protocol.EncryptedEnvelope
            lateinit var bi:org.ghostcloak.identity.DeviceIdentity
            val profile=SenderProfile(ai.userId,ai.deviceId,RandomIdentifiers.create(),"7K4M9Q2FX8DR")
            EncryptedEndpointStore.open(context,bn).use {br->
                val b=ConversationService(SignalProtocolEngine(br),LocalRepository(br))
                bi=b.create("bob");a.importCard(b.exportCard())
                first=ae.encrypt(bi.deviceId,ConversationPayload.encode("private",0))
                b.acceptNetwork(first,profile);b.deleteRequest(ai.deviceId)
                assertTrue(b.contacts().isEmpty());assertEquals(0,b.unreadCount())
            }
            EncryptedEndpointStore.open(context,bn).use {br->
                val repo=LocalRepository(br);val b=ConversationService(SignalProtocolEngine(br),repo)
                assertArrayEquals(bi.publicKey,b.open()!!.publicKey)
                b.acceptNetwork(first,profile);assertTrue(b.contacts().isEmpty())
                val second=ae.encrypt(bi.deviceId,ConversationPayload.encode("new private",0))
                b.acceptNetwork(second,profile)
                assertEquals(RelationshipState.REQUEST_PENDING,repo.relationshipState(ai.deviceId))
                assertTrue(b.messagesForUi(ai.deviceId).isEmpty())
                b.deleteRequest(ai.deviceId)
                val before=br.transaction {br.keys("session/").associateWith {br.read(it)!!}}
                b.importCard(a.exportCard());assertTrue(repo.isActiveContact(ai.deviceId))
                before.forEach {(k,v)->assertArrayEquals(v,br.transaction {br.read(k)})}
                b.acceptNetwork(first,profile);assertTrue(b.messages(ai.deviceId).isEmpty())
                b.block(ai.deviceId,true)
                try {b.importCard(a.exportCard());fail()} catch(e:AppFailure) {assertEquals(AppError.BLOCKED,e.error)}
                assertEquals(RelationshipState.BLOCKED,repo.relationshipState(ai.deviceId))
            }
        }
    }
}
