package org.ghostcloak.testing

import kotlinx.coroutines.runBlocking
import org.ghostcloak.crypto.*
import org.ghostcloak.messaging.*
import org.ghostcloak.protocol.*
import org.ghostcloak.attachments.*
import org.junit.Assert.*
import org.junit.Test

class BlockedEnvelopeTest {
    @Test fun blockAndUnblockFlagsCannotCommitWithoutRequestTransition() {
        val memory=MemoryRecords();var failRequest=false
        val records=object:EndpointRecords by memory {
            override fun write(key:String,value:ByteArray) {
                memory.write(key,value)
                if(failRequest && key.startsWith("app/request/")) throw EndpointStorageFailure()
            }
        }
        val repo=LocalRepository(records);val id=org.ghostcloak.identity.RandomIdentifiers.create()
        repo.save(Contact(id,id,"synthetic",id,request=true));repo.startRequest(id,null)
        repo.save(Message("first",id,Direction.INCOMING,"private",1,MessageState.RECEIVED))
        failRequest=true
        assertThrows(EndpointStorageFailure::class.java) {repo.block(id,true)}
        assertFalse(repo.contact(id).blocked);assertEquals(RequestState.PENDING,repo.request(id).state)
        assertEquals(1,repo.messages(id).size)
        failRequest=false;repo.block(id,true)
        failRequest=true
        assertThrows(EndpointStorageFailure::class.java) {repo.block(id,false)}
        assertTrue(repo.contact(id).blocked);assertEquals(RequestState.BLOCKED,repo.request(id).state)
        assertTrue(repo.messages(id).isEmpty())
    }
    @Test fun actualMailboxBlockedAttachmentsAreAckedWithoutDownloadingBodies()=runBlocking {
        AttachmentTest.Fixture().use {f->
            val a=f.person("alice");val b=f.person("bob")
            NetworkAccount(a.client,a.state).connect("bob",a.engine)
            val repo=LocalRepository(b.records);val receiver=ConversationService(b.engine,repo);receiver.open()
            val first=a.engine.encrypt(b.registration.deviceId,ConversationPayload.encode("request",0))
            a.client.call(ApiRequest.Send(first.envelopeId,b.registration.routingId,EnvelopeCodec.encode(first)))
            val initial=b.client.call(ApiRequest.Fetch(includeSenders=true)).deliveries.single()
            receiver.acceptNetwork(first,initial.sender);b.client.call(ApiRequest.Ack(listOf(initial.serverMessageId)))
            receiver.block(a.registration.deviceId,true)
            for(kind in listOf(AttachmentKind.IMAGE,AttachmentKind.DOCUMENT)) {
                val d=a.store.prepare(byteArrayOf(1).inputStream(),1,kind,0,"private.bin"){true}
                a.store.upload(d.id,a.bulk){true}
                val packet=a.engine.encrypt(b.registration.deviceId,ConversationPayload.encodeAttachment(d))
                a.client.call(ApiRequest.Send(packet.envelopeId,b.registration.routingId,EnvelopeCodec.encode(packet)))
                val query=ApiRequest.Fetch(submissionIds=listOf(packet.envelopeId))
                assertFalse(a.client.call(query).statuses.single().acknowledged) // No fabricated offline ACK.
                val delivery=b.client.call(ApiRequest.Fetch(includeSenders=true)).deliveries.single()
                receiver.acceptNetwork(EnvelopeCodec.decode(delivery.encryptedEnvelope),delivery.sender)
                b.client.call(ApiRequest.Ack(listOf(delivery.serverMessageId)))
                b.client.call(ApiRequest.Ack(listOf(delivery.serverMessageId))) // Existing idempotent ACK.
                assertTrue(a.client.call(query).statuses.single().acknowledged)
                assertTrue(b.client.call(ApiRequest.Fetch()).deliveries.isEmpty())
                assertTrue(receiver.contacts().isEmpty());assertTrue(receiver.messages(a.registration.deviceId).isEmpty())
                assertEquals(0,receiver.unreadCount());assertFalse(repo.hasAttachment(a.registration.deviceId,packet.envelopeId))
                assertTrue(b.store.cachedReferences().isEmpty())
                for(dir in listOf("download","scratch")) assertTrue(java.io.File(b.directory,dir).listFiles().orEmpty().isEmpty())
                assertTrue(java.io.File(f.directory,"server/${d.id}").exists()) // Normal independent blob retention.
            }
        }
    }
    private class Peers {
        val ar=MemoryRecords();val br=MemoryRecords()
        var failReceipt=false
        val records=object:EndpointRecords by br {
            override fun write(key:String,value:ByteArray) {
                br.write(key,value)
                if(failReceipt && key.startsWith("app/accepted/")) throw EndpointStorageFailure()
            }
        }
        val ae=SignalProtocolEngine(ar)
        val a=ConversationService(ae,LocalRepository(ar))
        val repo=LocalRepository(records)
        var b=ConversationService(SignalProtocolEngine(records),repo)
        lateinit var aid:String;lateinit var bid:String;lateinit var profile:SenderProfile
        suspend fun open() {
            val ai=a.create("alice");val bi=b.create("bob");aid=ai.deviceId;bid=bi.deviceId
            profile=SenderProfile(ai.userId,aid,org.ghostcloak.identity.RandomIdentifiers.create(),"alice")
            a.importCard(b.exportCard());b.acceptNetwork(packet(),profile);b.block(aid,true)
        }
        suspend fun packet(bytes:ByteArray=ConversationPayload.encode("private",0)):EncryptedEnvelope =
            try {ae.encrypt(bid,bytes)} finally {bytes.fill(0)}
        fun empty() {
            assertTrue(b.run {repo.messages(aid)}.isEmpty());assertFalse(repo.requestActive(aid))
            assertTrue(repo.contact(aid).blocked);assertEquals(RequestState.BLOCKED,repo.request(aid).state)
            assertEquals(0,repo.unreadCount(aid))
            br.transaction {
                for(prefix in listOf("app/message/","app/attachment/","app/notification/","app/read/"))
                    assertTrue(br.keys(prefix).isEmpty())
            }
        }
    }
    @Test fun blockedTextCommitReplayRestartAndUnblockNeverRestoreContent()=runBlocking {
        val p=Peers();p.open();val pin=p.b.fingerprint(p.aid);val one=p.packet()
        p.b.acceptNetwork(one,p.profile);p.empty()
        val after=p.br.transaction {p.br.keys("").associateWith {p.br.read(it)!!}}
        p.b=ConversationService(SignalProtocolEngine(p.br),LocalRepository(p.br));p.b.open()
        p.b.acceptNetwork(one,p.profile)
        after.forEach {(k,v)->assertArrayEquals(v,p.br.transaction {p.br.read(k)})}
        assertEquals(after.keys,p.br.transaction {p.br.keys("").toSet()});p.empty()
        p.b.block(p.aid,false);p.b.acceptNetwork(one,p.profile)
        assertTrue(p.b.contacts().isEmpty());assertTrue(p.b.messages(p.aid).isEmpty())
        p.b.acceptNetwork(p.packet(),p.profile)
        assertEquals(1,p.b.contacts().size);assertTrue(p.b.messagesForUi(p.aid).isEmpty())
        assertEquals(pin,p.b.fingerprint(p.aid))
    }
    @Test fun blockedPhotoAndDocumentPersistOnlySecurityReceipts()=runBlocking {
        val p=Peers();p.open()
        for(kind in listOf(AttachmentKind.IMAGE,AttachmentKind.DOCUMENT)) {
            val padded=AttachmentFormat.padded(1)
            val descriptor=AttachmentDescriptor(id=AttachmentFormat.newId(),capability=ByteArray(32),key=ByteArray(16),
                digest=ByteArray(32),plaintextLength=1,paddedLength=padded,ciphertextLength=AttachmentFormat.encryptedLength(padded),
                kind=kind,filename="private.bin")
            val packet=p.packet(ConversationPayload.encodeAttachment(descriptor))
            p.b.acceptNetwork(packet,p.profile);p.empty()
            assertFalse(p.repo.hasAttachment(p.aid,packet.envelopeId))
            assertTrue(p.repo.accepted(p.aid,packet.envelopeId,DeviceAuth.digest(EnvelopeCodec.encode(packet))))
        }
    }
    @Test fun failedSecurityCommitRollsBackRatchetAndReceiptAndCanRetryAfterRestart()=runBlocking {
        val p=Peers();p.open();val packet=p.packet()
        val before=p.br.transaction {p.br.keys("").associateWith {p.br.read(it)!!}}
        p.failReceipt=true
        try {p.b.acceptNetwork(packet,p.profile);fail()} catch(e:CryptoFailure) {assertEquals(CryptoError.StorageFailure,e.error)}
        p.failReceipt=false
        assertEquals(before.keys,p.br.transaction {p.br.keys("").toSet()})
        before.forEach {(k,v)->assertArrayEquals(v,p.br.transaction {p.br.read(k)})}
        p.b=ConversationService(SignalProtocolEngine(p.records),p.repo);p.b.open();p.b.acceptNetwork(packet,p.profile);p.empty()
    }
    @Test fun unauthenticatedBlockedEnvelopeCannotGetSecurityReceipt()=runBlocking {
        val p=Peers();p.open();val original=p.packet()
        // Outer routing fields must match authenticated BoundContent; claiming Block is insufficient.
        val packet=EncryptedEnvelope(original.protocolVersion,org.ghostcloak.identity.RandomIdentifiers.create(),
            original.senderDeviceId,original.recipientDeviceId,original.messageType,original.encryptedPayload)
        try {p.b.acceptNetwork(packet,p.profile);fail()} catch(_:CryptoFailure) {}
        assertFalse(p.repo.accepted(p.aid,packet.envelopeId,DeviceAuth.digest(EnvelopeCodec.encode(packet))));p.empty()
    }
}
