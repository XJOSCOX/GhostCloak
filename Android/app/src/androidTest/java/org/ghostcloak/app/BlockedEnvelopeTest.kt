package org.ghostcloak.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.ghostcloak.app.application.*
import org.ghostcloak.identity.RandomIdentifiers
import org.ghostcloak.messaging.*
import org.ghostcloak.crypto.*
import org.ghostcloak.protocol.*
import org.ghostcloak.transport.*
import org.ghostcloak.storage.EncryptedEndpointStore
import org.junit.Assert.*
import org.junit.Test

class BlockedEnvelopeTest {
    private class Notices:LocalNotifications {
        var posts=0
        override fun allowed()=true
        override fun active()=false
        override fun cancel() {}
        override fun post(quiet:Boolean):Boolean {posts++;return true}
    }
    private class Wire:GhostCloakTransport {
        val api=SyntheticNetwork()
        var failAck=false
        var acks=0
        override suspend fun execute(request:TransportRequest):TransportResponse {
            if(NetworkCodec.decode<ApiRequest>(request.body) is ApiRequest.Ack) {
                acks++
                if(failAck) {failAck=false;throw java.io.IOException("synthetic lost ACK")}
            }
            return api.execute(request)
        }
    }
    @Test fun offlineBlockedRecipientAckFailureRecreationAndUnblock()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val w=Wire();val notices=Notices();val bn=RandomIdentifiers.create()
        val a=AppRuntime(context,"https://fixture.invalid",RandomIdentifiers.create(),w)
        var b=AppRuntime(context,"https://fixture.invalid",bn,w,notifications=notices)
        try {
            a.use {a.create(it,"alice")};b.use {b.create(it,"bob")}
            a.use {a.addNetwork(b.ownGhostCloakId()!!,it)}
            val aid=a.use {it.open()!!.deviceId};val bid=b.use {it.open()!!.deviceId}
            a.use {a.send(it,bid,"first request")};b.use {b.syncNetwork(it);it.block(aid,true)}
            val beforePosts=notices.posts
            a.use {a.send(it,bid,"blocked text");a.syncNetwork(it)}
            assertEquals(MessageState.SERVER_ACCEPTED,a.use {it.messages(bid).last().state})
            assertEquals(1,w.api.mailbox.size)
            w.failAck=true
            try {b.use {b.syncNetwork(it)};fail()} catch(e:ApiFailure) {assertEquals(503,e.status)}
            b.use {assertTrue(it.contacts().isEmpty());assertTrue(it.messages(aid).isEmpty());assertEquals(0,it.unreadCount())}
            assertEquals(1,w.api.mailbox.size)
            b.close();b=AppRuntime(context,"https://fixture.invalid",bn,w,notifications=notices)
            b.use {b.syncNetwork(it);assertTrue(it.contacts().isEmpty());assertTrue(it.messages(aid).isEmpty())}
            assertTrue(w.api.mailbox.isEmpty());assertEquals(beforePosts,notices.posts)
            a.use {a.syncNetwork(it);assertEquals(MessageState.DELIVERED,it.messages(bid).last().state)}
            b.use {it.block(aid,false);assertTrue(it.messages(aid).isEmpty())}
            a.use {a.send(it,bid,"new after unblock")}
            b.use {b.syncNetwork(it);assertEquals(1,it.contacts().size);assertTrue(it.messagesForUi(aid).isEmpty())}
        } finally {a.close();b.close()}
    }
    @Test fun encryptedSinkReceiptAndRatchetRollbackThenSurviveReopenAndBootChange()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val an=RandomIdentifiers.create();val bn=RandomIdentifiers.create()
        var boot=1
        EncryptedEndpointStore.open(context,an).use {ar->
            val ae=SignalProtocolEngine(ar);val a=ConversationService(ae,LocalRepository(ar));val ai=a.create("alice")
            val profile=SenderProfile(ai.userId,ai.deviceId,RandomIdentifiers.create(),"7K4M9Q2FX8DR")
            lateinit var packet:EncryptedEnvelope
            EncryptedEndpointStore.open(context,bn).use {br->
                var failReceipt=false
                val records=object:EndpointRecords by br {
                    override fun write(key:String,value:ByteArray) {
                        br.write(key,value)
                        if(failReceipt && key.startsWith("app/accepted/")) throw EndpointStorageFailure()
                    }
                }
                val repo=LocalRepository(records,ExpiryClock({1000},{100},{boot}))
                val b=ConversationService(SignalProtocolEngine(records),repo);val bi=b.create("bob")
                a.importCard(b.exportCard());b.acceptNetwork(ae.encrypt(bi.deviceId,ConversationPayload.encode("first",0)),profile)
                b.block(ai.deviceId,true);packet=ae.encrypt(bi.deviceId,ConversationPayload.encode("blocked secret",0))
                val before=br.transaction {br.keys("").associateWith {br.read(it)!!}}
                failReceipt=true
                try {b.acceptNetwork(packet,profile);fail()} catch(e:CryptoFailure) {assertEquals(CryptoError.StorageFailure,e.error)}
                failReceipt=false
                assertEquals(before.keys,br.transaction {br.keys("").toSet()})
                before.forEach {(k,v)->assertArrayEquals(v,br.transaction {br.read(k)})}
                b.acceptNetwork(packet,profile)
                assertTrue(repo.accepted(ai.deviceId,packet.envelopeId,DeviceAuth.digest(EnvelopeCodec.encode(packet))))
            }
            boot++
            EncryptedEndpointStore.open(context,bn).use {br->
                val repo=LocalRepository(br,ExpiryClock({1000},{0},{boot}))
                val b=ConversationService(SignalProtocolEngine(br),repo);b.open();b.acceptNetwork(packet,profile)
                assertEquals(RequestState.BLOCKED,repo.request(ai.deviceId).state)
                assertTrue(b.contacts().isEmpty());assertTrue(b.messages(ai.deviceId).isEmpty());assertEquals(0,b.unreadCount())
                br.transaction {assertTrue(br.keys("app/notification/").isEmpty());assertTrue(br.keys("app/attachment/").isEmpty())}
            }
        }
    }
}
