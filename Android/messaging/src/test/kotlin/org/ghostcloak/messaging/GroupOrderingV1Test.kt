package org.ghostcloak.messaging

import org.ghostcloak.attachments.AttachmentDescriptor
import org.ghostcloak.attachments.AttachmentFormat
import org.ghostcloak.attachments.AttachmentKind
import org.ghostcloak.crypto.EndpointRecords
import org.ghostcloak.identity.RandomIdentifiers
import org.junit.Assert.*
import org.junit.Test

class GroupOrderingV1Test {
    private class Records:EndpointRecords {
        private val values=mutableMapOf<String,ByteArray>()
        override fun <T> transaction(block:()->T):T=block()
        override fun read(key:String)=values[key]?.copyOf()
        override fun write(key:String,value:ByteArray) {values[key]=value.copyOf()}
        override fun remove(key:String) {values.remove(key)}
        override fun keys(prefix:String)=values.keys.filter {it.startsWith(prefix)}
    }
    private val group=GroupIds.create()
    private val alice=GroupIds.create()
    private val bob=GroupIds.create()
    private val senderDevice=RandomIdentifiers.create()
    private val activation=ByteArray(32) {1}
    private val head=ByteArray(32) {2}
    private val policy=ByteArray(32) {3}
    private val timer=GroupDisappearingRulesV1.digest(activation,GroupDisappearingPolicyV1(seconds=0))
    private fun text(sequence:Long,label:String,sender:String=alice,
        governanceSequence:Long=1,headDigest:ByteArray=head,id:String=GroupIds.create())=
        GroupTextV5(groupId=group,epoch=2,senderMemberId=sender,logicalId=id,
            governanceActivationDigest=activation,governanceSequence=governanceSequence,
            governanceHeadDigest=headDigest,policyDigest=policy,
            disappearingPolicyDigest=timer,disappearingSeconds=0,
            senderSequence=sequence,text=label)
    private fun accept(store:GroupChatStore,value:GroupTextV5):Boolean {
        val envelope=RandomIdentifiers.create()
        store.queueV5(senderDevice,envelope,value)
        return store.acceptV5(envelope,PendingGroupTextV5(senderDevice,value))
    }
    private fun media(sequence:Long,kind:AttachmentKind):GroupMediaV3 {
        val length=1024L;val padded=AttachmentFormat.padded(length)
        val descriptor=AttachmentDescriptor(id=AttachmentFormat.newId(),
            capability=ByteArray(32) {4},key=ByteArray(16) {5},digest=ByteArray(32) {6},
            plaintextLength=length,paddedLength=padded,
            ciphertextLength=AttachmentFormat.encryptedLength(padded),kind=kind,
            filename=if(kind==AttachmentKind.DOCUMENT) "doc.pdf" else null,
            durationMillis=if(kind==AttachmentKind.VOICE_NOTE) 1000 else null)
        return GroupMediaV3(groupId=group,epoch=2,senderMemberId=alice,
            logicalId=GroupIds.create(),governanceActivationDigest=activation,
            governanceSequence=1,governanceHeadDigest=head,policyDigest=policy,
            disappearingPolicyDigest=timer,disappearingSeconds=0,senderSequence=sequence,
            descriptor=descriptor,kind=kind)
    }
    private fun accept(store:GroupChatStore,value:GroupMediaV3):Boolean {
        val envelope=RandomIdentifiers.create()
        store.queueMediaV3(senderDevice,envelope,value)
        return store.acceptMediaV3(envelope,PendingGroupMediaV3(senderDevice,value))
    }

    @Test fun tenOfflineMessagesAndLateInsertionRenderInSenderOrderAfterRestart() {
        val records=Records();val store=GroupChatStore(records)
        val labels=listOf("ONE","TWO","THREE","FOUR","FIVE","SIX","SEVEN","EIGHT","NINE","TEN")
        val values=labels.mapIndexed {index,label -> text(index+1L,label) }
        for(index in listOf(9,4,2,0,7,1,5,3,8,6)) assertTrue(accept(store,values[index]))
        assertEquals(labels,GroupChatStore(records).messages(group).map {it.text})
        assertFalse(accept(store,values[0]))
        assertEquals(10,GroupChatStore(records).messages(group).size)
    }

    @Test fun gapsNeverBlockAndConflictFailsClosed() {
        val store=GroupChatStore(Records())
        val first=text(1,"one")
        assertTrue(accept(store,first))
        assertFalse(accept(store,first.copy(senderSequence=5)))
        assertTrue(accept(store,text(3,"three")))
        assertTrue(accept(store,text(4,"four")))
        assertEquals(listOf("one","three","four"),store.messages(group).map {it.text})
        assertTrue(accept(store,text(2,"two")))
        assertEquals(listOf("one","two","three","four"),store.messages(group).map {it.text})
        assertFalse(accept(store,text(3,"conflict")))
        assertEquals(4,store.messages(group).size)
    }

    @Test fun mixedMediaAndMultiSenderShareOneSequencePerSender() {
        val store=GroupChatStore(Records())
        val a1=text(1,"text1");val a2=media(2,AttachmentKind.IMAGE)
        val a3=text(3,"text2");val a4=media(4,AttachmentKind.VOICE_NOTE)
        val a5=media(5,AttachmentKind.DOCUMENT);val a6=text(6,"reply")
        val a7=text(7,"text3");val b1=text(1,"B1",bob);val b2=text(2,"B2",bob)
        assertTrue(accept(store,a7));assertTrue(accept(store,b2));assertTrue(accept(store,a5))
        assertTrue(accept(store,a3));assertTrue(accept(store,b1));assertTrue(accept(store,a4))
        assertTrue(accept(store,a2));assertTrue(accept(store,a6));assertTrue(accept(store,a1))
        val rows=store.messages(group)
        assertEquals(listOf(1L,2L,3L,4L,5L,6L,7L),
            rows.filter {it.senderMemberId==alice}.map {it.senderSequence})
        assertEquals(listOf(1L,2L),rows.filter {it.senderMemberId==bob}.map {it.senderSequence})
        assertEquals(listOf(null,AttachmentKind.IMAGE,null,AttachmentKind.VOICE_NOTE,
            AttachmentKind.DOCUMENT,null,null),rows.filter {it.senderMemberId==alice}.map {it.mediaKind})
    }

    @Test fun counterResetsAtNewHeadAndPersistsThroughRestart() {
        val records=Records();val store=GroupChatStore(records)
        assertEquals(1L,store.allocateSenderSequence(group,alice,activation,1,head))
        assertEquals(2L,GroupChatStore(records).allocateSenderSequence(group,alice,activation,1,head))
        val nextHead=ByteArray(32) {9}
        assertEquals(1L,GroupChatStore(records).allocateSenderSequence(group,alice,activation,2,nextHead))
        assertEquals(2L,GroupChatStore(records).allocateSenderSequence(group,alice,activation,2,nextHead))
        assertEquals(1L,GroupChatStore(records).allocateSenderSequence(group,bob,activation,2,nextHead))
    }

    @Test fun orderedWireRoundTripsWithTimerOffAndDoesNotMutateOldFormats() {
        val text=text(1,"hello")
        val frame=ConversationPayload.encodeGroupTextV5(text)
        assertEquals(21,frame[5].toInt())
        assertEquals(1L,ConversationPayload.decode(frame).groupTextV5?.senderSequence)
        val media=media(2,AttachmentKind.IMAGE)
        val mediaFrame=ConversationPayload.encodeGroupMediaV3(media)
        assertEquals(22,mediaFrame[5].toInt())
        assertEquals(2L,ConversationPayload.decode(mediaFrame).groupMediaV3?.senderSequence)
        assertThrows(IllegalArgumentException::class.java) {GroupTextV5Codec.encode(text.copy(senderSequence=0))}
        assertThrows(IllegalArgumentException::class.java) {GroupMediaCodecV3.encode(media.copy(senderSequence=4097))}
    }

    @Test fun authenticatedCapabilityEchoAdvertisesOrderingOnlyInPaddedControl() {
        val control=GroupControl(kind=GroupControlKind.GOVERNANCE_CAPABILITY_ECHO,groupId=group)
        val frame=ConversationPayload.encodeGroup(control)
        val parsed=ConversationPayload.decode(frame)
        assertTrue(parsed.supportsGroupOrdering)
        assertTrue(parsed.supportsGroupDisappearing)
        assertEquals(GroupControlKind.GOVERNANCE_CAPABILITY_ECHO,parsed.groupControl?.kind)
    }
}
