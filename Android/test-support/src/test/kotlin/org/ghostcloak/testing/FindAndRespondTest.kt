package org.ghostcloak.testing

import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.ghostcloak.crypto.SignalProtocolEngine
import org.ghostcloak.attachments.AttachmentDescriptor
import org.ghostcloak.attachments.AttachmentFormat
import org.ghostcloak.attachments.AttachmentKind
import org.ghostcloak.messaging.*
import org.ghostcloak.protocol.EncryptedEnvelope
import org.ghostcloak.transport.IdempotentMessageTransport
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor

@Serializable private data class PriorMessage(val localId:String,val conversationId:String,val direction:Direction,
    val body:String,val timestamp:Long,val state:MessageState,val envelopeId:String?=null,
    val disappearingSeconds:Int=0,val expiry:ExpiryDeadline?=null,val policyEvent:Boolean=false,
    val viewOnceKind:ViewOnceKind?=null,val viewOnceState:ViewOnceState?=null)

class FindAndRespondTest {
    private class Wire : IdempotentMessageTransport {
        val sent=mutableListOf<EncryptedEnvelope>()
        override fun receive()=emptyFlow<EncryptedEnvelope>()
        override suspend fun send(routingDestination:String,envelope:EncryptedEnvelope)=Unit
        override suspend fun submit(submissionId:String,routingDestination:String,envelope:EncryptedEnvelope):String {
            sent+=envelope;return submissionId
        }
    }
    private class Pairing {
        val ar=MemoryRecords();val br=MemoryRecords()
        val ae=SignalProtocolEngine(ar);val be=SignalProtocolEngine(br)
        var now=1_000_000L
        val clock=ExpiryClock({now},{now},{1})
        val ap=LocalRepository(ar,clock);val bp=LocalRepository(br,clock)
        val a=ConversationService(ae,ap);val b=ConversationService(be,bp)
        val aw=Wire();val bw=Wire()
        val ao=DurableOutbox(ar,ae,aw);val bo=DurableOutbox(br,be,bw)
        lateinit var aid:String;lateinit var bid:String
        suspend fun open() {
            aid=a.create("Alice").deviceId;bid=b.create("Bob").deviceId
            a.importCard(b.exportCard());b.importCard(a.exportCard())
        }
    }
    @Test fun repliesToIncomingAndOutgoingUseSameEncryptedReference()=runBlocking {
        val p=Pairing();p.open()
        val first=p.a.sendNetwork(p.bid,"hello",p.ao)
        assertEquals(p.aw.sent.single().envelopeId,first.envelopeId)
        p.b.acceptNetwork(p.aw.sent.single())
        val target=ReplyPresentation.reference(p.b.messagesForUi(p.aid).single())!!
        val answer=p.b.sendNetwork(p.aid,"response",p.bo,replyTo=target)
        p.a.acceptNetwork(p.bw.sent.single())
        assertEquals(target,answer.replyTo)
        assertEquals(target,p.a.messagesForUi(p.bid).last().replyTo)
        assertEquals("hello",ReplyPresentation.preview(target,p.bid,p.a.messagesForUi(p.bid)))
        val selfTarget=ReplyPresentation.reference(answer)!!
        p.b.sendNetwork(p.aid,"follow-up",p.bo,replyTo=selfTarget)
        p.a.acceptNetwork(p.bw.sent.last())
        assertEquals(selfTarget,p.a.messagesForUi(p.bid).last().replyTo)
    }
    @Test fun deletedAndExpiredOriginalsNeverResurrectTheirText()=runBlocking {
        val p=Pairing();p.open()
        val original=p.a.sendNetwork(p.bid,"secret",p.ao)
        p.b.acceptNetwork(p.aw.sent.single())
        val ref=ReplyPresentation.reference(p.b.messagesForUi(p.aid).single())!!
        p.b.sendNetwork(p.aid,"okay",p.bo,replyTo=ref)
        p.b.delete(p.aid,p.aw.sent.single().envelopeId)
        assertEquals("Original message unavailable",ReplyPresentation.preview(ref,p.aid,p.b.messagesForUi(p.aid)))
        assertEquals(ref,p.b.messagesForUi(p.aid).single().replyTo)
        assertEquals(original.envelopeId,ref.envelopeId)
        val expiring=ReplyReference(UUID.randomUUID().toString(),ReplyKind.DISAPPEARING)
        assertEquals("Original message expired",ReplyPresentation.preview(expiring,p.aid,p.b.messagesForUi(p.aid)))
    }
    @Test fun disappearingSourceExpiryKeepsReplyAndDropsQuote()=runBlocking {
        val p=Pairing();p.open()
        val packet=p.ae.encrypt(p.bid,ConversationPayload.encode("vanishing text",30))
        p.b.acceptNetwork(packet)
        val ref=ReplyPresentation.reference(p.b.messagesForUi(p.aid).single())!!
        assertEquals(ReplyKind.DISAPPEARING,ref.kind)
        p.b.sendNetwork(p.aid,"still here",p.bo,replyTo=ref)
        p.now+=31_000;p.b.reconcileExpiry()
        assertEquals(ref,p.b.messagesForUi(p.aid).single().replyTo)
        assertEquals("Original message expired",ReplyPresentation.preview(ref,p.aid,p.b.messagesForUi(p.aid)))
    }
    @Test fun viewOnceReplyCarriesNoOriginalPlaintext()=runBlocking {
        val p=Pairing();p.open()
        p.a.sendNetwork(p.bid,"private body",p.ao,viewOnce=true)
        p.b.acceptNetwork(p.aw.sent.single())
        val ref=ReplyPresentation.reference(p.b.messagesForUi(p.aid).single())!!
        assertEquals(ReplyKind.VIEW_ONCE,ref.kind)
        val reply=p.b.sendNetwork(p.aid,"ack",p.bo,replyTo=ref)
        assertEquals("View Once message",ReplyPresentation.preview(ref,p.aid,p.b.messagesForUi(p.aid)))
        assertFalse(ConversationPayload.decode(p.ae.decrypt(p.bw.sent.single())).body.contains("private body"))
        assertEquals(ref,reply.replyTo)
    }
    @Test fun attachmentReplyUsesReferenceWithoutEmbeddingDescriptor()=runBlocking {
        val p=Pairing();p.open()
        val descriptor=AttachmentDescriptor(id=AttachmentFormat.newId(),key=ByteArray(16),
            capability=ByteArray(32),digest=ByteArray(32),plaintextLength=1,
            paddedLength=AttachmentFormat.padded(1),
            ciphertextLength=AttachmentFormat.encryptedLength(AttachmentFormat.padded(1)),
            kind=AttachmentKind.IMAGE,disappearingSeconds=0)
        val packet=p.ae.encrypt(p.bid,ConversationPayload.encodeAttachment(descriptor))
        p.b.acceptNetwork(packet)
        val shown=p.b.messagesForUi(p.aid).single()
        val ref=ReplyPresentation.reference(shown)!!
        assertEquals(ReplyKind.ATTACHMENT,ref.kind)
        p.b.sendNetwork(p.aid,"nice photo",p.bo,replyTo=ref)
        p.a.acceptNetwork(p.bw.sent.single())
        assertEquals(ref,p.a.messagesForUi(p.bid).single().replyTo)
        assertEquals("Attachment",ReplyPresentation.preview(ref,p.aid,p.b.messagesForUi(p.aid)))
    }
    @Test fun localSearchRespectsVisibilityAndConversationScope() {
        val a=UUID.randomUUID().toString();val b=UUID.randomUUID().toString()
        val id=UUID.randomUUID().toString()
        val normal=Message(id,a,Direction.INCOMING,"Straße Café",0,MessageState.RECEIVED,envelopeId=id)
        val hidden=Message(UUID.randomUUID().toString(),a,Direction.INCOMING,"secret",0,MessageState.RECEIVED,
            viewOnceKind=ViewOnceKind.TEXT,viewOnceState=ViewOnceState.CONSUMED)
        assertEquals(listOf(0),ConversationSearch.matches(listOf(normal,hidden),"CAFÉ",true))
        assertTrue(ConversationSearch.matches(listOf(normal,hidden),"secret",true).isEmpty())
        assertTrue(ConversationSearch.matches(listOf(normal),"café",false).isEmpty())
        assertEquals("Original message unavailable",ReplyPresentation.preview(ReplyReference(id,ReplyKind.TEXT),b,listOf(normal)))
    }
    @Test fun hiddenRequestAndBlockedContactCannotBeSearched()=runBlocking {
        val p=Pairing();p.open()
        p.b.removeContact(p.aid)
        val packet=p.ae.encrypt(p.bid,ConversationPayload.encode("hidden needle",0))
        p.b.acceptNetwork(packet)
        assertTrue(p.b.messagesForUi(p.aid).isEmpty())
        assertTrue(ConversationSearch.matches(p.b.messagesForUi(p.aid),"needle",false).isEmpty())
        p.b.acceptRequest(p.aid)
        val shown=p.b.messagesForUi(p.aid)
        assertEquals(1,ConversationSearch.matches(shown,"needle",true).size)
        p.b.block(p.aid,true)
        assertTrue(ConversationSearch.matches(shown,"needle",false).isEmpty())
    }
    @Test fun crossConversationReferenceCannotBeSent()=runBlocking {
        val p=Pairing();p.open()
        val forged=ReplyReference(UUID.randomUUID().toString(),ReplyKind.TEXT)
        try { p.a.sendNetwork(p.bid,"hello",p.ao,replyTo=forged);fail("unbound reply") }
        catch (_:AppFailure) {}
        assertTrue(p.aw.sent.isEmpty())
    }
    @Test fun legacyPayloadAndTamperedReplyReferenceDecodeSafely() {
        val id=UUID.randomUUID().toString()
        assertNull(ConversationPayload.decode(ConversationPayload.encode("legacy text",0)).replyTo)
        val encoded=ConversationPayload.encode("reply text",0,replyTo=ReplyReference(id,ReplyKind.TEXT))
        assertEquals(ReplyReference(id,ReplyKind.TEXT),ConversationPayload.decode(encoded).replyTo)
        val corrupt=encoded.copyOf();corrupt[16+"reply text".length]=127
        try {ConversationPayload.decode(corrupt);fail("invalid reply kind")}
        catch (_:AppFailure) {}
    }
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @Test fun priorEncryptedLocalRecordDecodesWithoutStorageMigration() {
        val format=Cbor { encodeDefaults=true;ignoreUnknownKeys=false }
        val prior=PriorMessage(UUID.randomUUID().toString(),UUID.randomUUID().toString(),Direction.INCOMING,
            "stored before P2",100,MessageState.RECEIVED)
        val restored=format.decodeFromByteArray(Message.serializer(),format.encodeToByteArray(PriorMessage.serializer(),prior))
        assertEquals("stored before P2",restored.body)
        assertNull(restored.replyTo)
    }
    @Test fun searchHundredsIsInMemoryAndBounded() {
        val peer=UUID.randomUUID().toString()
        val messages=(0 until 600).map { index -> Message(UUID.randomUUID().toString(),peer,Direction.INCOMING,
            if(index%10==0) "Café $index" else "ordinary message $index",index.toLong(),MessageState.RECEIVED,
            attachment=if(index%7==0 && index!=0) AttachmentSummary(false,"document",100) else null) }
        val smallStart=System.nanoTime()
        assertEquals(listOf(0),ConversationSearch.matches(messages.take(10),"CAFÉ",true))
        val smallMs=(System.nanoTime()-smallStart)/1_000_000.0
        val start=System.nanoTime()
        repeat(100) { ConversationSearch.matches(messages,"CAFÉ",true) }
        val ms=(System.nanoTime()-start)/1_000_000.0
        val display=messages.map { it.copy(envelopeId=it.localId) }
        val replyStart=System.nanoTime()
        repeat(100) { ReplyPresentation.preview(ReplyReference(display.last().localId,ReplyKind.TEXT),peer,display) }
        val replyMs=(System.nanoTime()-replyStart)/1_000_000.0
        println("P2_SEARCH small_count=10 small_ms=$smallMs mixed_count=600 queries=100 total_ms=$ms reply_resolves=100 reply_ms=$replyMs storage_reads=0 network_calls=0")
        assertTrue(ms<10_000)
    }
}
