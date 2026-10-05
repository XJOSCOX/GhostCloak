package org.ghostcloak.testing

import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.ghostcloak.crypto.SignalProtocolEngine
import org.ghostcloak.attachments.*
import org.ghostcloak.messaging.*
import org.ghostcloak.protocol.*
import org.ghostcloak.transport.IdempotentMessageTransport
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class MessageEditingTest {
    private fun id()=UUID.randomUUID().toString()
    private class Wire:IdempotentMessageTransport {
        var offline=false
        val sent=mutableListOf<EncryptedEnvelope>()
        val submissions=mutableListOf<String>()
        override fun receive()=emptyFlow<EncryptedEnvelope>()
        override suspend fun send(routingDestination:String,envelope:EncryptedEnvelope)=Unit
        override suspend fun submit(submissionId:String,routingDestination:String,envelope:EncryptedEnvelope):String {
            if(offline) throw ApiFailure(503,"offline")
            sent+=envelope;submissions+=submissionId;return submissionId
        }
    }
    private class Pairing {
        val ar=MemoryRecords();val br=MemoryRecords()
        val ae=SignalProtocolEngine(ar);val be=SignalProtocolEngine(br)
        val ap=LocalRepository(ar);val bp=LocalRepository(br)
        val a=ConversationService(ae,ap);val b=ConversationService(be,bp)
        val aw=Wire();val bw=Wire()
        val ao=DurableOutbox(ar,ae,aw);val bo=DurableOutbox(br,be,bw)
        lateinit var aid:String;lateinit var bid:String
        suspend fun open() {
            aid=a.create("Alice").deviceId;bid=b.create("Bob").deviceId
            a.importCard(b.exportCard());b.importCard(a.exportCard())
            a.sendNetwork(bid,"hello",ao);b.acceptNetwork(aw.sent.last());b.acceptRequest(aid)
            b.sendNetwork(aid,"reply",bo);a.acceptNetwork(bw.sent.last())
            check(ap.editPeer(bid) && bp.editPeer(aid))
        }
    }

    @Test fun codecIsAuthenticatedBoundedAndSeparateFromExistingControls() {
        val update=EditUpdate(id(),1,"new text")
        assertEquals(update,ConversationPayload.decode(ConversationPayload.encodeEdit(update)).edit)
        assertTrue(ConversationPayload.decode(ConversationPayload.encode("hello",0)).supportsEdit)
        assertFalse(ConversationPayload.decode("legacy".encodeToByteArray()).supportsEdit)
        assertEquals(null,ConversationPayload.decode(ConversationPayload.encodeDelete(update.targetMessageId)).edit)
        assertThrows(AppFailure::class.java) {ConversationPayload.encodeEdit(update.copy(text=" "))}
        assertThrows(IllegalArgumentException::class.java) {ConversationPayload.encodeEdit(update.copy(revision=0))}
        val malformed=ConversationPayload.encodeEdit(update)
        malformed[5]=13
        assertThrows(AppFailure::class.java) {ConversationPayload.decode(malformed)}
    }

    @Test fun senderRevisionOfflineRetryAndNoOldPlaintext()=runBlocking {
        val p=Pairing();p.open()
        val old="P10_OLD_PLAINTEXT_873"
        val sent=p.a.sendNetwork(p.bid,old,p.ao)
        p.b.acceptNetwork(p.aw.sent.last())
        assertTrue(NotificationLedger(p.br).eligible().any {it.presentation.body==old})
        p.aw.offline=true
        assertEquals(EditRequestStatus.PENDING,p.a.editMessage(p.bid,sent.localId,"new text",p.ao))
        val local=p.ap.messages(p.bid).first {it.localId==sent.localId}
        assertEquals(1,local.editRevision);assertEquals("new text",local.body)
        assertEquals(EditRequestStatus.PENDING,local.editStatus)
        val queued=p.ao.pendingIds().single()
        assertThrows(IllegalArgumentException::class.java) {runBlocking {
            p.a.editMessage(p.bid,sent.localId,"second edit",p.ao)
        }}
        p.aw.offline=false;p.a.retryNetwork(p.ao)
        assertEquals(queued,p.aw.submissions.last())
        assertTrue(p.ao.pendingIds().isEmpty())
        assertEquals(EditRequestStatus.SENT,p.ap.messages(p.bid).first {it.localId==sent.localId}.editStatus)
        p.b.acceptNetwork(p.aw.sent.last())
        val remote=p.bp.messages(p.aid).first {it.localId==sent.envelopeId}
        assertEquals("new text",remote.body);assertEquals(1,remote.editRevision)
        assertTrue(NotificationLedger(p.br).eligible().none {it.presentation.body==old || it.presentation.body=="new text"})
        assertFalse(p.ar.keys("app/").any {key -> p.ar.read(key)?.toString(Charsets.ISO_8859_1)?.contains(old)==true})
        assertFalse(p.br.keys("app/").any {key -> p.br.read(key)?.toString(Charsets.ISO_8859_1)?.contains(old)==true})
        p.b.acceptNetwork(p.aw.sent.last())
        assertEquals(1,p.bp.messages(p.aid).first {it.localId==sent.envelopeId}.editRevision)
        assertEquals(EditRequestStatus.SENT,p.a.editMessage(p.bid,sent.localId,"second edited text",p.ao))
        p.b.acceptNetwork(p.aw.sent.last())
        assertEquals(2,p.bp.messages(p.aid).first {it.localId==sent.envelopeId}.editRevision)
        assertEquals("second edited text",p.bp.messages(p.aid).first {it.localId==sent.envelopeId}.body)
    }

    @Test fun orderedOutOfOrderAndStaleRevisionsPreserveReactionsRepliesAndSearch()=runBlocking {
        val p=Pairing();p.open()
        val sent=p.a.sendNetwork(p.bid,"before edit",p.ao)
        p.b.acceptNetwork(p.aw.sent.last())
        val target=sent.envelopeId!!
        assertTrue(p.bp.applyReaction(p.aid,ReactionUpdate(target,1,"👍"),false))
        val reply=Message(id(),p.aid,Direction.OUTGOING,"reply survives",1_000,MessageState.SERVER_ACCEPTED,id(),
            replyTo=ReplyReference(target,ReplyKind.TEXT))
        p.bp.save(reply)
        assertTrue(p.bp.applyRemoteEdit(p.aid,EditUpdate(target,2,"second revision")))
        assertTrue(p.bp.applyRemoteEdit(p.aid,EditUpdate(target,1,"stale revision")))
        assertTrue(p.bp.applyRemoteEdit(p.aid,EditUpdate(target,2,"conflicting duplicate")))
        val current=p.bp.messages(p.aid).first {it.localId==target}
        assertEquals("second revision",current.body);assertEquals(2,current.editRevision)
        assertEquals("second revision",ReplyPresentation.preview(reply.replyTo!!,p.aid,p.bp.messages(p.aid)))
        assertTrue(ConversationSearch.matches(p.bp.messages(p.aid),"before edit",true).isEmpty())
        assertEquals(1,ConversationSearch.matches(p.bp.messages(p.aid),"second revision",true).size)
        assertEquals("👍",p.bp.reactionSnapshot(p.aid)[target]?.single()?.emoji)
    }

    @Test fun editBeforeOriginalUsesLatestRevisionWithoutStoringOldBody()=runBlocking {
        val p=Pairing();p.open()
        val sent=p.a.sendNetwork(p.bid,"delayed original",p.ao)
        val original=p.aw.sent.last()
        p.a.editMessage(p.bid,sent.localId,"latest before arrival",p.ao)
        p.b.acceptNetwork(p.aw.sent.last())
        assertTrue(p.bp.messages(p.aid).none {it.localId==sent.envelopeId})
        p.b.acceptNetwork(original)
        assertEquals("latest before arrival",p.bp.messages(p.aid).first {it.localId==sent.envelopeId}.body)
        assertFalse(p.br.keys("app/").any {key -> p.br.read(key)?.toString(Charsets.ISO_8859_1)?.contains("delayed original")==true})
    }

    @Test fun onlyOriginalSenderCanEditAndDeleteAlwaysWins()=runBlocking {
        val p=Pairing();p.open()
        val sent=p.a.sendNetwork(p.bid,"original",p.ao)
        p.b.acceptNetwork(p.aw.sent.last())
        val target=sent.envelopeId!!
        assertThrows(IllegalArgumentException::class.java) {runBlocking {p.b.editMessage(p.aid,target,"forgery",p.bo)}}
        val forged=p.be.encrypt(p.aid,ConversationPayload.encodeEdit(EditUpdate(target,1,"forged peer content")))
        p.a.acceptNetwork(forged)
        assertEquals("original",p.ap.messages(p.bid).first {it.localId==sent.localId}.body)
        assertFalse(p.ar.keys("app/edit/").any {it.endsWith(target)})
        val other=id();p.bp.save(Contact(id(),id(),"Other",other))
        assertTrue(p.bp.applyRemoteEdit(other,EditUpdate(target,1,"wrong conversation")))
        assertEquals("original",p.bp.messages(p.aid).first {it.localId==target}.body)
        assertTrue(p.bp.applyRemoteEdit(p.aid,EditUpdate(target,1,"edited")))
        assertTrue(p.bp.applyRemoteDelete(p.aid,target))
        assertFalse(p.bp.applyRemoteEdit(p.aid,EditUpdate(target,2,"resurrect")))
        assertEquals("",p.bp.messages(p.aid).first {it.localId==target}.body)
        assertEquals("Original message deleted",ReplyPresentation.preview(ReplyReference(target,ReplyKind.TEXT),p.aid,p.bp.messages(p.aid)))
        assertTrue(p.bp.applyRemoteDelete(p.aid,target))
    }

    @Test fun blockedAndHiddenRequestEditsDoNotChangeVisibleOrHiddenText()=runBlocking {
        val p=Pairing();p.open()
        val sent=p.a.sendNetwork(p.bid,"visible before block",p.ao)
        p.b.acceptNetwork(p.aw.sent.last())
        p.bp.block(p.aid,true)
        p.a.editMessage(p.bid,sent.localId,"blocked edit",p.ao)
        val control=p.aw.sent.last()
        p.b.acceptNetwork(control);p.b.acceptNetwork(control)
        assertEquals("visible before block",p.bp.messages(p.aid).first {it.localId==sent.envelopeId}.body)

        val q=Pairing();q.open()
        q.bp.save(q.bp.contact(q.aid).copy(request=true))
        val hidden=q.a.sendNetwork(q.bid,"hidden original",q.ao)
        q.b.acceptNetwork(q.aw.sent.last())
        q.a.editMessage(q.bid,hidden.localId,"hidden edited",q.ao)
        q.b.acceptNetwork(q.aw.sent.last())
        assertEquals("hidden original",q.bp.messages(q.aid).first {it.localId==hidden.envelopeId}.body)
        assertFalse(q.bp.messages(q.aid).any {it.body=="hidden edited"})
    }

    @Test fun textRepliesRemainEditableButAttachmentsAndControlsDoNot()=runBlocking {
        val p=Pairing();p.open()
        val original=p.a.sendNetwork(p.bid,"original",p.ao)
        p.b.acceptNetwork(p.aw.sent.last())
        val reply=p.b.sendNetwork(p.aid,"first reply",p.bo,replyTo=ReplyReference(original.envelopeId!!,ReplyKind.TEXT))
        p.a.acceptNetwork(p.bw.sent.last())
        assertEquals(EditRequestStatus.SENT,p.b.editMessage(p.aid,reply.localId,"revised reply",p.bo))
        p.a.acceptNetwork(p.bw.sent.last())
        val received=p.ap.messages(p.bid).first {it.localId==reply.envelopeId}
        assertEquals("revised reply",received.body)
        assertEquals(ReplyReference(original.envelopeId!!,ReplyKind.TEXT),received.replyTo)

        val records=MemoryRecords();val repo=LocalRepository(records);val peer=id()
        repo.save(Contact(id(),id(),"Peer",peer))
        val target=id()
        repo.save(Message(target,peer,Direction.OUTGOING,"caption",1,MessageState.SERVER_ACCEPTED,target))
        val descriptor=AttachmentDescriptor(id=AttachmentFormat.newId(),key=ByteArray(16),capability=ByteArray(32),
            digest=ByteArray(32),plaintextLength=1,paddedLength=AttachmentFormat.padded(1),
            ciphertextLength=AttachmentFormat.encryptedLength(AttachmentFormat.padded(1)),kind=AttachmentKind.IMAGE)
        repo.attachment(peer,target,AttachmentFormat.encode(descriptor))
        assertThrows(IllegalArgumentException::class.java) {repo.ownEditUpdate(peer,target,"changed caption")}
        val incoming=id();repo.save(Message(incoming,peer,Direction.INCOMING,"caption",2,MessageState.RECEIVED,incoming))
        repo.attachment(peer,incoming,AttachmentFormat.encode(descriptor))
        assertFalse(repo.applyRemoteEdit(peer,EditUpdate(incoming,1,"changed caption")))
        val policy=id();repo.save(Message(policy,peer,Direction.INCOMING,"system",3,MessageState.RECEIVED,policy,policyEvent=true))
        assertFalse(repo.applyRemoteEdit(peer,EditUpdate(policy,1,"changed system")))
    }

    @Test fun expiryViewOnceAndLocalRemovalRejectEditsWithoutRestartingDeadline() {
        var now=10_000L
        val repo=LocalRepository(MemoryRecords(),ExpiryClock({now},{now},{1}))
        val peer=id();repo.save(Contact(id(),id(),"Peer",peer))
        val target=id();val deadline=ExpiryDeadline.start(30,repo.clock.now())
        repo.saveAccepted(Message(target,peer,Direction.INCOMING,"expiring",now,MessageState.RECEIVED,target,
            disappearingSeconds=30,expiry=deadline),ByteArray(32))
        assertTrue(repo.applyRemoteEdit(peer,EditUpdate(target,1,"before expiry")))
        assertEquals(deadline,repo.messages(peer).single().expiry)
        now+=31_000;repo.expire(peer)
        assertFalse(repo.applyRemoteEdit(peer,EditUpdate(target,2,"too late")))
        val once=id();repo.save(Message(once,peer,Direction.INCOMING,"once",now,MessageState.RECEIVED,once,
            viewOnceKind=ViewOnceKind.TEXT,viewOnceState=ViewOnceState.AVAILABLE))
        assertFalse(repo.applyRemoteEdit(peer,EditUpdate(once,1,"forbidden")))
        assertEquals("once",repo.messages(peer).single().body)
    }

    @Test fun fiveHundredMessagesRepeatedEditsStayTargeted() {
        val repo=LocalRepository(MemoryRecords());val peer=id()
        repo.save(Contact(id(),id(),"Peer",peer))
        val messages=(1..500).map { n -> val key=id();Message(key,peer,Direction.INCOMING,"message $n",n.toLong(),MessageState.RECEIVED,key) }
        messages.forEach(repo::save)
        val target=messages[250].localId
        val start=System.nanoTime()
        (1L..50L).forEach {revision -> assertTrue(repo.applyRemoteEdit(peer,EditUpdate(target,revision,"edit $revision")))}
        println("P10_EDIT_500_MESSAGES_50_REVISIONS_MS=${(System.nanoTime()-start)/1_000_000.0}")
        assertEquals("edit 50",repo.messages(peer).first {it.localId==target}.body)
        assertEquals("message 250",repo.messages(peer).first {it.localId==messages[249].localId}.body)
    }
}
