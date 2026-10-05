package org.ghostcloak.testing

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.emptyFlow
import org.ghostcloak.crypto.SignalProtocolEngine
import org.ghostcloak.messaging.*
import org.ghostcloak.protocol.*
import org.ghostcloak.transport.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ReactionTest {
    private fun id()=UUID.randomUUID().toString()

    @Test fun payloadIsBoundedEncryptedControlAndTextAdvertisesSupport() {
        val target=id()
        val encoded=ConversationPayload.encodeReaction(ReactionUpdate(target,3,"❤️"))
        assertEquals(256,encoded.size)
        assertFalse(encoded.toString(Charsets.ISO_8859_1).contains("❤️"))
        assertEquals(ReactionUpdate(target,3,"❤️"),ConversationPayload.decode(encoded).reaction)
        assertEquals(null,ConversationPayload.decode(ConversationPayload.encodeReaction(ReactionUpdate(target,4,null))).reaction?.emoji)
        assertTrue(ConversationPayload.decode(ConversationPayload.encode("hello",0,displayName="Alice")).supportsReactions)
        assertFalse(ConversationPayload.decode("legacy".encodeToByteArray()).supportsReactions)
        assertThrows(IllegalArgumentException::class.java) {ConversationPayload.encodeReaction(ReactionUpdate(target,0,"👍"))}
        assertThrows(IllegalArgumentException::class.java) {ConversationPayload.encodeReaction(ReactionUpdate(target,1,"💥"))}
    }

    @Test fun orderingDeletionExpiryAndPrivacyBoundaries() {
        val records=MemoryRecords()
        var wall=1_000_000L;var elapsed=1_000_000L
        val repo=LocalRepository(records,ExpiryClock({wall},{elapsed},{1}))
        val a=id();val b=id();val target=id()
        repo.save(Contact(id(),id(),"Accepted",a))
        repo.save(Contact(id(),id(),"Other",b))
        repo.save(Message(target,a,Direction.INCOMING,"message",wall,MessageState.RECEIVED,target))
        assertTrue(repo.applyReaction(a,ReactionUpdate(target,1,"👍"),false))
        assertFalse(repo.applyReaction(a,ReactionUpdate(target,1,"👍"),false))
        assertTrue(repo.applyReaction(a,ReactionUpdate(target,2,"😂"),false))
        assertFalse(repo.applyReaction(a,ReactionUpdate(target,1,"😢"),false))
        assertEquals("😂",repo.reactionSnapshot(a)[target]!!.single().emoji)
        assertTrue(repo.applyReaction(a,ReactionUpdate(target,3,null),false))
        assertTrue(repo.reactionSnapshot(a)[target].isNullOrEmpty())
        assertFalse(repo.applyReaction(a,ReactionUpdate(target,2,"❤️"),false))
        assertFalse(repo.applyReaction(b,ReactionUpdate(target,4,"👍"),false))
        val view=id();repo.save(Message(view,a,Direction.INCOMING,"",wall,MessageState.RECEIVED,view,
            viewOnceKind=ViewOnceKind.TEXT,viewOnceState=ViewOnceState.AVAILABLE))
        assertFalse(repo.applyReaction(a,ReactionUpdate(view,1,"👍"),false))
        val exp=id();repo.save(Message(exp,a,Direction.INCOMING,"temporary",wall,MessageState.RECEIVED,exp,
            disappearingSeconds=30,expiry=ExpiryDeadline.start(30,repo.clock.now())))
        assertTrue(repo.applyReaction(a,ReactionUpdate(exp,1,"👍"),false))
        wall+=31_000;elapsed+=31_000
        repo.expire(a)
        assertFalse(repo.applyReaction(a,ReactionUpdate(exp,2,"❤️"),false))
        assertTrue(repo.reactionSnapshot(a)[exp].isNullOrEmpty())
        repo.delete(a,target)
        assertFalse(repo.applyReaction(a,ReactionUpdate(target,4,"👍"),false))
        repo.save(Contact(id(),id(),"Hidden",b,request=true))
        repo.save(Message(target,b,Direction.INCOMING,"hidden",wall,MessageState.RECEIVED,target))
        assertFalse(repo.applyReaction(b,ReactionUpdate(target,1,"👍"),false))
        repo.save(Contact(id(),id(),"Blocked",b,blocked=true))
        assertFalse(repo.applyReaction(b,ReactionUpdate(target,1,"👍"),false))
    }

    @Test fun authenticatedReactionCrossesExistingEncryptedMailbox()=runBlocking {
        AttachmentTest.Fixture().use {f ->
            val a=f.person("alice");val b=f.person("bob")
            NetworkAccount(a.client,a.state).connect(b.state.ghostCloakId(),a.engine)
            val ar=LocalRepository(a.records);val br=LocalRepository(b.records)
            ar.save(Contact(id(),b.registration.accountId,"Bob",b.registration.deviceId))
            val sender=ConversationService(a.engine,ar);sender.open()
            val receiver=ConversationService(b.engine,br);receiver.open()
            val aOutbox=DurableOutbox(a.records,a.engine,NetworkMailboxTransport(a.client,a.state))
            val firstMessage=sender.sendNetwork(b.registration.deviceId,"hello",aOutbox)
            val first=b.client.call(ApiRequest.Fetch(includeSenders=true)).deliveries.single()
            first.sender?.let(b.state::remember)
            receiver.acceptNetwork(EnvelopeCodec.decode(first.encryptedEnvelope),first.sender)
            receiver.acceptRequest(a.registration.deviceId)
            b.client.call(ApiRequest.Ack(listOf(first.serverMessageId)))
            assertTrue(br.reactionPeer(a.registration.deviceId))
            val bOutbox=DurableOutbox(b.records,b.engine,NetworkMailboxTransport(b.client,b.state))
            receiver.sendNetwork(a.registration.deviceId,"hello back",bOutbox)
            val reply=a.client.call(ApiRequest.Fetch(includeSenders=true)).deliveries.single()
            sender.acceptNetwork(EnvelopeCodec.decode(reply.encryptedEnvelope),reply.sender)
            a.client.call(ApiRequest.Ack(listOf(reply.serverMessageId)))
            assertTrue(ar.reactionPeer(b.registration.deviceId))
            receiver.react(a.registration.deviceId,firstMessage.envelopeId!!,"👍",bOutbox)
            val reaction=a.client.call(ApiRequest.Fetch(includeSenders=true)).deliveries.single()
            assertFalse(reaction.encryptedEnvelope.toString(Charsets.ISO_8859_1).contains("👍"))
            sender.acceptNetwork(EnvelopeCodec.decode(reaction.encryptedEnvelope),reaction.sender)
            val shown=sender.messagesForUi(b.registration.deviceId).first {it.envelopeId==firstMessage.envelopeId}
            assertEquals(listOf(ReactionBadge("👍",false)),shown.reactions)
            assertEquals(2,sender.messagesForUi(b.registration.deviceId).size)
        }
    }

    @Test fun detailsExcludeInternalIdentifiersAndReportLocalState() {
        val secret=id();val message=Message(secret,id(),Direction.OUTGOING,"body",1_000,MessageState.FAILED,secret,
            disappearingSeconds=30,replyTo=ReplyReference(id(),ReplyKind.TEXT),
            reactions=listOf(ReactionBadge("👍",true)))
        val fields=MessageDetails.fields(message).toMap()
        assertEquals("Sent",fields["Direction"])
        assertTrue(fields["Status"]!!.contains("cannot be retried safely"))
        assertEquals("Yes",fields["Reply"])
        assertEquals("1",fields["Reactions"])
        assertFalse(fields.toString().contains(secret))
        assertFalse(fields.toString().contains(message.conversationId))
        assertFalse(fields.toString().contains(message.replyTo!!.envelopeId))
    }

    @Test fun hundredsOfMessagesUseOneIndexedReactionSnapshotAndSearchIgnoresEmoji() {
        val repo=LocalRepository(MemoryRecords())
        val peer=id();repo.save(Contact(id(),id(),"Peer",peer))
        val targets=(0 until 300).map {id()}
        targets.forEachIndexed {index,target ->
            repo.save(Message(target,peer,Direction.INCOMING,"message $index",index.toLong(),MessageState.RECEIVED,target))
        }
        targets.filterIndexed {index,_->index%4==0}.forEach {target ->
            assertTrue(repo.applyReaction(peer,ReactionUpdate(target,1,"👍"),false))
        }
        val started=System.nanoTime()
        val badges=repo.reactionSnapshot(peer)
        val messages=repo.messages(peer).map {it.copy(reactions=badges[it.envelopeId].orEmpty())}
        val elapsed=(System.nanoTime()-started)/1_000_000
        assertEquals(300,messages.size)
        assertEquals(75,messages.count {it.reactions.isNotEmpty()})
        assertTrue(ConversationSearch.matches(messages,"👍",true).isEmpty())
        println("P5_REACTION_SNAPSHOT_300_MESSAGES_75_TARGETS_MS=$elapsed")
    }

    private class Wire:IdempotentMessageTransport {
        var offline=true
        val submissions=mutableListOf<String>()
        val envelopes=mutableListOf<EncryptedEnvelope>()
        override suspend fun send(routingDestination:String,envelope:EncryptedEnvelope)=Unit
        override suspend fun submit(submissionId:String,routingDestination:String,envelope:EncryptedEnvelope):String {
            if(offline) throw ApiFailure(503,"offline")
            submissions.add(submissionId);envelopes.add(envelope);return submissionId
        }
        override fun receive()=emptyFlow<EncryptedEnvelope>()
    }

    @Test fun manualRetryReusesPendingSubmissionAndTerminalFailureCannotResend()=runBlocking {
        val records=MemoryRecords();val remote=MemoryRecords()
        val engine=SignalProtocolEngine(records);val peerEngine=SignalProtocolEngine(remote)
        val sender=ConversationService(engine,LocalRepository(records));val receiver=ConversationService(peerEngine,LocalRepository(remote))
        val peer=receiver.create("Bob").deviceId;val alice=sender.create("Alice").deviceId
        sender.importCard(receiver.exportCard());receiver.importCard(sender.exportCard())
        val wire=Wire();val outbox=DurableOutbox(records,engine,wire)
        val pending=sender.sendNetwork(peer,"pending",outbox)
        assertEquals(MessageState.PENDING,pending.state)
        assertEquals(listOf(pending.localId),outbox.pendingIds())
        wire.offline=false
        sender.retrySubmission(peer,pending.localId,outbox)
        assertEquals(listOf(pending.localId),wire.submissions)
        assertTrue(outbox.pendingIds().isEmpty())
        receiver.acceptNetwork(wire.envelopes.single())
        assertEquals("pending",receiver.messages(alice).single().body)
        assertThrows(IllegalArgumentException::class.java) {runBlocking {sender.retrySubmission(peer,pending.localId,outbox)}}
        assertEquals(1,wire.submissions.size)

        val ambiguous=DurableOutbox(records,engine,wire,crash={if(it==CrashPoint.AFTER_ENCRYPTION) error("simulated")})
        assertThrows(IllegalStateException::class.java) {runBlocking {sender.sendNetwork(peer,"ambiguous",ambiguous)}}
        val unresolved=ambiguous.pendingIds().single()
        sender.retrySubmission(peer,unresolved,DurableOutbox(records,engine,wire))
        assertEquals(MessageState.FAILED,sender.messages(peer).first {it.localId==unresolved}.state)
        assertEquals(1,wire.submissions.size)
    }
}
