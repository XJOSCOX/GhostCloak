package org.ghostcloak.messaging

import org.ghostcloak.attachments.AttachmentDescriptor
import org.ghostcloak.attachments.AttachmentFormat
import org.ghostcloak.attachments.AttachmentKind
import org.ghostcloak.crypto.EndpointRecords
import org.ghostcloak.identity.RandomIdentifiers
import org.ghostcloak.protocol.NetworkCodec
import org.ghostcloak.protocol.EncryptedEnvelope
import org.ghostcloak.protocol.EnvelopeCodec
import org.junit.Assert.*
import org.junit.Test

class GroupDisappearingV1Test {
    private class Records:EndpointRecords {
        private val values=mutableMapOf<String,ByteArray>()
        override fun <T> transaction(block:()->T):T=block()
        override fun read(key:String)=values[key]?.copyOf()
        override fun write(key:String,value:ByteArray) {values[key]=value.copyOf()}
        override fun remove(key:String) {values.remove(key)}
        override fun keys(prefix:String)=values.keys.filter {it.startsWith(prefix)}
    }
    private val group=GroupIds.create()
    private val sender=GroupIds.create()
    private val id=GroupIds.create()
    private val activation=ByteArray(32) {1}
    private val head=ByteArray(32) {2}
    private val policy=ByteArray(32) {3}
    private val timer=GroupDisappearingPolicyV1(seconds=30)
    private val timerDigest=GroupDisappearingRulesV1.digest(activation,timer)

    @Test fun exactDurationsAndDomainSeparatedDigest() {
        for(seconds in listOf(0,30,300,3600,86400,604800))
            GroupDisappearingRulesV1.validate(GroupDisappearingPolicyV1(seconds=seconds))
        assertThrows(IllegalArgumentException::class.java) {
            GroupDisappearingRulesV1.validate(GroupDisappearingPolicyV1(seconds=31))
        }
        assertFalse(timerDigest.contentEquals(GroupDisappearingRulesV1.digest(
            ByteArray(32) {9},timer)))
    }

    @Test fun timedTextAndMediaFramesRoundTripWithoutChangingFrozenVersions() {
        val text=GroupTextV4(groupId=group,epoch=2,senderMemberId=sender,logicalId=id,
            governanceActivationDigest=activation,governanceSequence=4,
            governanceHeadDigest=head,policyDigest=policy,
            disappearingPolicyDigest=timerDigest,disappearingSeconds=30,
            text="hello",replyToLogicalId=GroupIds.create())
        val encoded=ConversationPayload.encodeGroupTextV4(text)
        assertEquals(19,encoded[5].toInt())
        assertEquals(30,ConversationPayload.decode(encoded).groupTextV4?.disappearingSeconds)
        assertTrue(encoded.size<EnvelopeCodec.MAX_BODY)
        val descriptor=AttachmentDescriptor(id=AttachmentFormat.newId(),
            capability=ByteArray(32) {1},key=ByteArray(16) {2},digest=ByteArray(32) {3},
            plaintextLength=1024,paddedLength=AttachmentFormat.padded(1024),
            ciphertextLength=AttachmentFormat.encryptedLength(AttachmentFormat.padded(1024)),
            kind=AttachmentKind.IMAGE)
        val media=GroupMediaV2(groupId=group,epoch=2,senderMemberId=sender,logicalId=id,
            governanceActivationDigest=activation,governanceSequence=4,
            governanceHeadDigest=head,policyDigest=policy,
            disappearingPolicyDigest=timerDigest,disappearingSeconds=30,
            descriptor=descriptor,kind=AttachmentKind.IMAGE)
        val frame=ConversationPayload.encodeGroupMediaV2(media)
        assertEquals(20,frame[5].toInt())
        assertEquals(30,ConversationPayload.decode(frame).groupMediaV2?.disappearingSeconds)
        assertTrue(frame.size<=7168)
        assertThrows(IllegalArgumentException::class.java) {
            GroupTextV4Codec.encode(text.copy(disappearingSeconds=31))
        }
    }

    @Test fun incomingExpiryScrubsTextAndMediaAcrossRestartWithoutReplayResurrection() {
        val records=Records();var wall=1_000L;var elapsed=1_000L
        val clock=ExpiryClock({wall},{elapsed},{1})
        val store=GroupChatStore(records,clock)
        val senderDevice=RandomIdentifiers.create()
        val value=GroupTextV4(groupId=group,epoch=2,senderMemberId=sender,logicalId=id,
            governanceActivationDigest=activation,governanceSequence=4,
            governanceHeadDigest=head,policyDigest=policy,
            disappearingPolicyDigest=timerDigest,disappearingSeconds=30,text="secret")
        val envelope=RandomIdentifiers.create()
        store.queueV4(senderDevice,envelope,value)
        assertTrue(store.acceptV4(envelope,PendingGroupTextV4(senderDevice,value)))
        assertEquals("secret",store.message(group,id)?.text)
        assertEquals(1,store.unreadCount(group))
        wall+=30_001;elapsed+=30_001
        val restarted=GroupChatStore(records,clock)
        assertEquals(GroupExpiryState.EXPIRED,restarted.message(group,id)?.expiryState)
        assertEquals(0,restarted.unreadCount(group))
        assertEquals("",restarted.message(group,id)?.text)
        assertFalse(restarted.acceptV4(RandomIdentifiers.create(),PendingGroupTextV4(senderDevice,value)))
        assertEquals("",restarted.message(group,id)?.text)
        assertFalse(restarted.edit(group,id,sender,1,"resurrected"))
        assertFalse(restarted.react(group,id,sender,1,"👍"))
    }

    @Test fun senderTimerWaitsForEveryDurableRecipientCiphertext() {
        val records=Records();var wall=1_000L;var elapsed=1_000L
        val clock=ExpiryClock({wall},{elapsed},{1})
        val store=GroupChatStore(records,clock)
        val peers=(1..2).map {RandomIdentifiers.create()}
        val digest=ByteArray(32) {8}
        records.write("app/group-text/message/$group/$id",NetworkCodec.encode(
            GroupChatMessage(group,id,2,sender,true,"secret",store.nextOrder(),
                peers.map(::GroupRecipient),mediaBodyDigest=digest,disappearingSeconds=30)))
        records.write("app/group-text/v2-binding/$group/$id",NetworkCodec.encode(
            GroupTextV2Binding(activation,4,head,policy)))
        fun prepare(index:Int) {
            val outboxId=RandomIdentifiers.create();val envelopeId=RandomIdentifiers.create()
            store.markQueued(group,id,peers[index],outboxId)
            records.write("outbox/$outboxId",NetworkCodec.encode(OutboxEntry(outboxId,
                peers[index],OutboxState.CIPHERTEXT_READY,
                ciphertext=EnvelopeCodec.encode(EncryptedEnvelope(1,envelopeId,
                    RandomIdentifiers.create(),peers[index],2,byteArrayOf(1))),
                createdAt=1,envelopeId=envelopeId,
                groupMediaBinding=GroupMediaOutboxBinding(group,id,peers[index],head,
                    ByteArray(32) {4},digest))))
        }
        prepare(0)
        assertFalse(store.markMediaDeliveryPreparedIfReady(group,id))
        assertNull(store.message(group,id)?.expiry)
        prepare(1)
        assertTrue(store.markMediaDeliveryPreparedIfReady(group,id))
        assertEquals(31_000L,store.message(group,id)?.expiry?.wall)
        wall+=30_001;elapsed+=30_001
        assertEquals(GroupExpiryState.EXPIRED,GroupChatStore(records,clock).message(group,id)?.expiryState)
        assertEquals("",store.message(group,id)?.text)
        assertEquals(2,records.keys("outbox/").size)
    }

    @Test fun expiryKeepsOriginalDeadlineAfterEditAndReaction() {
        val records=Records();var wall=1_000L;var elapsed=1_000L
        val clock=ExpiryClock({wall},{elapsed},{1})
        val store=GroupChatStore(records,clock)
        records.write("app/group-text/message/$group/$id",NetworkCodec.encode(
            GroupChatMessage(group,id,2,sender,false,"original",store.nextOrder(),
                disappearingSeconds=30,expiry=ExpiryDeadline.start(30,clock.now()))))
        val original=store.message(group,id)!!.expiry
        wall+=20_000;elapsed+=20_000
        assertTrue(store.edit(group,id,sender,1,"edited"))
        assertTrue(store.react(group,id,sender,1,"👍"))
        assertEquals(original,store.message(group,id)!!.expiry)
        wall+=10_001;elapsed+=10_001
        assertEquals(GroupExpiryState.EXPIRED,store.message(group,id)?.expiryState)
        assertEquals("",store.message(group,id)?.text)
        assertTrue(store.message(group,id)?.reactions.isNullOrEmpty())
        assertFalse(store.edit(group,id,sender,2,"late"))
        assertFalse(store.react(group,id,sender,2,"❤️"))
    }

    @Test fun mediaExpiryScrubsActiveDescriptorButRetainsCiphertextArtifact() {
        val records=Records();var wall=1_000L;var elapsed=1_000L
        val clock=ExpiryClock({wall},{elapsed},{1})
        val store=GroupChatStore(records,clock)
        records.write("app/group-text/message/$group/$id",NetworkCodec.encode(
            GroupChatMessage(group,id,2,sender,true,"",store.nextOrder(),
                mediaKind=AttachmentKind.IMAGE,mediaCaption="caption",mediaFilename="private.jpg",
                disappearingSeconds=30,expiry=ExpiryDeadline.start(30,clock.now()),
                mediaDeliveryPrepared=true)))
        records.write("app/attachment/$group/$id",byteArrayOf(1,2,3))
        records.write("outbox/${RandomIdentifiers.create()}",byteArrayOf(4,5,6))
        wall+=30_001;elapsed+=30_001
        val expired=GroupChatStore(records,clock).message(group,id)!!
        assertEquals(GroupExpiryState.EXPIRED,expired.expiryState)
        assertNull(expired.mediaCaption)
        assertNull(expired.mediaFilename)
        assertNull(records.read("app/attachment/$group/$id"))
        assertEquals(1,records.keys("outbox/").size)
        assertEquals(GroupExpiryState.EXPIRED,GroupChatStore(records,clock).message(group,id)?.expiryState)
    }
}
