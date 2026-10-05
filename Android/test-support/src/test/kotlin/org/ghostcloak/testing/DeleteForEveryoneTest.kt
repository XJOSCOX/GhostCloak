package org.ghostcloak.testing

import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.ghostcloak.attachments.*
import org.ghostcloak.crypto.SignalProtocolEngine
import org.ghostcloak.messaging.*
import org.ghostcloak.protocol.*
import org.ghostcloak.transport.IdempotentMessageTransport
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class DeleteForEveryoneTest {
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
            val first=a.sendNetwork(bid,"capability hello",ao)
            b.acceptNetwork(aw.sent.last());b.acceptRequest(aid)
            b.sendNetwork(aid,"capability reply",bo)
            a.acceptNetwork(bw.sent.last())
            check(ap.deletePeer(bid) && bp.deletePeer(aid) && first.envelopeId!=null)
            a.markRead(bid);b.markRead(aid)
        }
    }

    @Test fun controlIsStrictAndPeerSupportIsAuthenticatedPadding() {
        val target=id()
        val bytes=ConversationPayload.encodeDelete(target)
        assertEquals(256,bytes.size)
        assertEquals(target,ConversationPayload.decode(bytes).deleteTargetId)
        assertTrue(ConversationPayload.decode(ConversationPayload.encode("hello",0)).supportsDelete)
        assertFalse(ConversationPayload.decode("legacy".encodeToByteArray()).supportsDelete)
        assertThrows(IllegalArgumentException::class.java) {ConversationPayload.encodeDelete("not-an-id")}
        bytes[5]=12
        assertThrows(AppFailure::class.java) {ConversationPayload.decode(bytes)}
    }

    @Test fun terminalTombstoneScrubsContentReactionsAttachmentsSearchAndLateOriginal() {
        val records=MemoryRecords();val repo=LocalRepository(records)
        val peer=id();val other=id();val target=id();val secret="P9_SECRET_4821"
        repo.save(Contact(id(),id(),"Peer",peer));repo.save(Contact(id(),id(),"Other",other))
        val message=Message(target,peer,Direction.INCOMING,secret,1000,MessageState.RECEIVED,target)
        repo.save(message)
        val descriptor=AttachmentDescriptor(id=AttachmentFormat.newId(),key=ByteArray(16),capability=ByteArray(32),
            digest=ByteArray(32),plaintextLength=1,paddedLength=AttachmentFormat.padded(1),
            ciphertextLength=AttachmentFormat.encryptedLength(AttachmentFormat.padded(1)),kind=AttachmentKind.VOICE_NOTE)
        repo.attachment(peer,target,AttachmentFormat.encode(descriptor))
        assertTrue(repo.applyReaction(peer,ReactionUpdate(target,1,"👍"),false))
        val reply=Message(id(),peer,Direction.INCOMING,"reply stays",1001,MessageState.RECEIVED,id(),
            replyTo=ReplyReference(target,ReplyKind.TEXT))
        repo.save(reply)
        assertTrue(repo.applyRemoteDelete(peer,target))
        assertTrue(repo.applyRemoteDelete(peer,target))
        val stored=repo.messages(peer).first {it.localId==target}
        assertTrue(stored.deleted);assertEquals("",stored.body)
        assertNull(repo.attachment(peer,target))
        assertFalse(repo.applyReaction(peer,ReactionUpdate(target,2,"❤️"),false))
        assertTrue(repo.reactionSnapshot(peer)[target].isNullOrEmpty())
        assertEquals("Original message deleted",ReplyPresentation.preview(reply.replyTo!!,peer,repo.messages(peer)))
        assertTrue(ConversationSearch.matches(repo.messages(peer),secret,true).isEmpty())
        assertFalse(records.keys("app/").any {key -> records.read(key)?.toString(Charsets.ISO_8859_1)?.contains(secret)==true})
        assertTrue(repo.applyRemoteDelete(other,target))
        assertTrue(repo.messages(peer).first {it.localId==target}.deleted)
        repo.delete(peer,target)
        assertTrue(repo.isDeleted(peer,target))
        assertFalse(repo.applyReaction(peer,ReactionUpdate(target,3,"👍"),false))
    }

    @Test fun encryptedDeleteIsBestEffortAndBlockedReceiverDiscardsIt()=runBlocking {
        val p=Pairing();p.open()
        val sent=p.a.sendNetwork(p.bid,"visible before block",p.ao)
        p.b.acceptNetwork(p.aw.sent.last())
        p.bp.block(p.aid,true)
        assertEquals(DeleteRequestStatus.SENT,p.a.deleteForEveryone(p.bid,sent.localId,p.ao))
        val control=p.aw.sent.last()
        p.b.acceptNetwork(control)
        assertEquals("visible before block",p.bp.messages(p.aid).first {it.localId==sent.envelopeId}.body)
        assertFalse(p.bp.isDeleted(p.aid,sent.envelopeId!!))
        // Replay has the same normal authenticated receipt behavior.
        p.b.acceptNetwork(control)
    }

    @Test fun offlineRetryKeepsOneSubmissionAndRecipientGetsPlaceholder()=runBlocking {
        val p=Pairing();p.open()
        val sent=p.a.sendNetwork(p.bid,"delete me",p.ao)
        p.b.acceptNetwork(p.aw.sent.last())
        p.aw.offline=true
        assertEquals(DeleteRequestStatus.PENDING,p.a.deleteForEveryone(p.bid,sent.localId,p.ao))
        val local=p.ap.messages(p.bid).first {it.localId==sent.localId}
        assertTrue(local.deleted);assertEquals("",local.body)
        assertEquals(DeleteRequestStatus.PENDING,local.deleteStatus)
        val queued=p.ao.pendingIds().single()
        p.aw.offline=false;p.a.retryNetwork(p.ao)
        assertTrue(p.ao.pendingIds().isEmpty())
        assertEquals(queued,p.aw.submissions.last())
        assertEquals(DeleteRequestStatus.SENT,p.ap.messages(p.bid).first {it.localId==sent.localId}.deleteStatus)
        val control=p.aw.sent.last()
        p.b.acceptNetwork(control)
        val received=p.bp.messages(p.aid).first {it.localId==sent.envelopeId}
        assertTrue(received.deleted);assertEquals("",received.body)
        assertEquals("This message was deleted",p.b.messagesForUi(p.aid).first {it.localId==sent.envelopeId}.body)
        p.b.acceptNetwork(control)
        assertEquals(1,p.bp.messages(p.aid).count {it.localId==sent.envelopeId})
    }

    @Test fun deleteBeforeOriginalNeverRevealsLateTextAndCrossConversationCannotDelete()=runBlocking {
        val p=Pairing();p.open()
        val sent=p.a.sendNetwork(p.bid,"LATE_SECRET_597",p.ao)
        val original=p.aw.sent.last()
        p.a.deleteForEveryone(p.bid,sent.localId,p.ao)
        val deletion=p.aw.sent.last()
        p.b.acceptNetwork(deletion)
        assertTrue(p.bp.isDeleted(p.aid,sent.envelopeId!!))
        p.b.acceptNetwork(original)
        val stored=p.bp.messages(p.aid).single {it.localId==sent.envelopeId}
        assertTrue(stored.deleted);assertEquals("",stored.body)
        assertTrue(ConversationSearch.matches(p.b.messagesForUi(p.aid),"LATE_SECRET_597",true).isEmpty())
        val other=id();p.bp.save(Contact(id(),id(),"Other",other))
        assertTrue(p.bp.applyRemoteDelete(other,sent.envelopeId!!))
        assertEquals("",p.bp.messages(p.aid).single {it.localId==sent.envelopeId}.body)
    }

    @Test fun unacceptedRequestCanBeDeletedWithoutRevealingIt()=runBlocking {
        val p=Pairing();p.open()
        p.bp.save(p.bp.contact(p.aid).copy(request=true))
        val sent=p.a.sendNetwork(p.bid,"HIDDEN_REQUEST_729",p.ao)
        p.b.acceptNetwork(p.aw.sent.last())
        p.a.deleteForEveryone(p.bid,sent.localId,p.ao)
        p.b.acceptNetwork(p.aw.sent.last())
        assertTrue(p.bp.messages(p.aid).first {it.localId==sent.envelopeId}.deleted)
        assertFalse(p.bp.messages(p.aid).any {it.body.contains("HIDDEN_REQUEST_729")})
    }

    @Test fun viewOnceAndDisappearingDeleteNeverReviveOrExtendLifetime() {
        var now=10_000L
        val repo=LocalRepository(MemoryRecords(),ExpiryClock({now},{now},{1}))
        val peer=id();repo.save(Contact(id(),id(),"Peer",peer))
        val once=id();repo.save(Message(once,peer,Direction.INCOMING,"ONCE_SECRET",now,MessageState.RECEIVED,once,
            viewOnceKind=ViewOnceKind.TEXT,viewOnceState=ViewOnceState.AVAILABLE))
        assertTrue(repo.applyRemoteDelete(peer,once))
        assertThrows(AppFailure::class.java) {repo.beginViewOnce(peer,once)}
        assertEquals("",repo.messages(peer).single().body)
        val exp=id();repo.save(Message(exp,peer,Direction.INCOMING,"EXPIRING_SECRET",now,MessageState.RECEIVED,exp,
            disappearingSeconds=30,expiry=ExpiryDeadline.start(30,repo.clock.now())))
        assertTrue(repo.applyRemoteDelete(peer,exp))
        now+=31_000;repo.expire(peer)
        assertTrue(repo.messages(peer).none {it.localId==exp})
        assertTrue(repo.isDeleted(peer,exp))
        assertTrue(repo.applyRemoteDelete(peer,exp))
    }

    @Test fun senderEligibilityAndUnsupportedPeerAreEnforced()=runBlocking {
        val p=Pairing();p.open()
        val sent=p.a.sendNetwork(p.bid,"my message",p.ao)
        assertThrows(AppFailure::class.java) {runBlocking {p.b.deleteForEveryone(p.aid,sent.envelopeId!!,p.bo)}}
        p.ap.deletePeer(p.bid,false)
        assertThrows(IllegalArgumentException::class.java) {runBlocking {p.a.deleteForEveryone(p.bid,sent.localId,p.ao)}}
        assertFalse(p.ap.messages(p.bid).first {it.localId==sent.localId}.deleted)
    }

    @Test fun photoDocumentVoiceAndCaptionLeaveNoLogicalPlaintextOrAttachmentReference() {
        val records=MemoryRecords();val repo=LocalRepository(records)
        val peer=id();repo.save(Contact(id(),id(),"Peer",peer))
        AttachmentKind.entries.filter {it in setOf(AttachmentKind.IMAGE,AttachmentKind.DOCUMENT,AttachmentKind.VOICE_NOTE)}
            .forEachIndexed {index,kind ->
                val target=id();val secret="P9_MEDIA_SECRET_${index}_728"
                repo.save(Message(target,peer,Direction.INCOMING,secret,1_000L+index,MessageState.RECEIVED,target))
                val descriptor=AttachmentDescriptor(id=AttachmentFormat.newId(),key=ByteArray(16),capability=ByteArray(32),
                    digest=ByteArray(32),plaintextLength=1,paddedLength=AttachmentFormat.padded(1),
                    ciphertextLength=AttachmentFormat.encryptedLength(AttachmentFormat.padded(1)),kind=kind)
                repo.attachment(peer,target,AttachmentFormat.encode(descriptor))
                assertTrue("$kind must initially retain its file", "$peer/$target" in repo.retainedAttachmentReferences())
                assertTrue(repo.applyRemoteDelete(peer,target))
                assertNull(repo.attachment(peer,target))
                assertFalse("$kind must release its file", "$peer/$target" in repo.retainedAttachmentReferences())
                assertFalse(repo.attachmentAvailable(peer,target))
                assertEquals("",repo.messages(peer).first {it.localId==target}.body)
                assertFalse(records.keys("app/").any {key ->
                    records.read(key)?.toString(Charsets.ISO_8859_1)?.contains(secret)==true
                })
            }
    }

    @Test fun hundredsOfMessagesUseTheTargetOnlyForScrubbing() {
        val records=MemoryRecords();val repo=LocalRepository(records)
        val peer=id();repo.save(Contact(id(),id(),"Peer",peer))
        val messages=(1..500).map {n -> val key=id();Message(key,peer,Direction.INCOMING,"message $n",n.toLong(),
            MessageState.RECEIVED,key) }
        messages.forEach(repo::save)
        val target=messages[250]
        val start=System.nanoTime()
        assertTrue(repo.applyRemoteDelete(peer,target.envelopeId!!))
        println("P9_DELETE_500_MESSAGE_LOOKUP_MS=${(System.nanoTime()-start)/1_000_000.0}")
        assertEquals(1,repo.messages(peer).count {it.deleted})
        assertEquals("",repo.messages(peer).first {it.localId==target.localId}.body)
        assertEquals("message 250",repo.messages(peer).first {it.localId==messages[249].localId}.body)
    }
}
