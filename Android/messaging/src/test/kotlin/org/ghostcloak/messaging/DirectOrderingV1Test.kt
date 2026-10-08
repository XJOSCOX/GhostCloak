package org.ghostcloak.messaging

import org.ghostcloak.crypto.EndpointRecords
import org.ghostcloak.identity.RandomIdentifiers
import org.junit.Assert.*
import org.junit.Test

class DirectOrderingV1Test {
    private class Records:EndpointRecords {
        private val values=mutableMapOf<String,ByteArray>()
        override fun <T> transaction(block:()->T):T=block()
        override fun read(key:String)=values[key]?.copyOf()
        override fun write(key:String,value:ByteArray) {values[key]=value.copyOf()}
        override fun remove(key:String) {values.remove(key)}
        override fun keys(prefix:String)=values.keys.filter {it.startsWith(prefix)}
    }
    private val alice=RandomIdentifiers.create()
    private val bob=RandomIdentifiers.create()
    private val aliceKey=ByteArray(32) {1}
    private val bobKey=ByteArray(32) {2}
    private val scope=DirectOrderingV1.scope(alice,bob,aliceKey,bobKey)
    private fun row(id:String,body:String,sequence:Long,sender:Direction=Direction.INCOMING,
        time:Long=sequence)=Message(id,bob,sender,body,time,MessageState.RECEIVED,
        orderingScopeDigest=scope,senderSequence=sequence)

    @Test fun offlineTenAndLateInsertionAreOrderedWithoutGapWait() {
        val labels=listOf("ONE","TWO","THREE","FOUR","FIVE","SIX","SEVEN","EIGHT","NINE","TEN")
        val rows=labels.mapIndexed {index,label -> row(RandomIdentifiers.create(),label,index+1L) }
        val shuffled=listOf(9,4,2,0,7,1,5,3,8,6).map {rows[it]}
        assertEquals(labels,mergeDirectPresentation(shuffled).map {it.body})
        assertEquals(listOf("ONE","THREE","FOUR"),
            mergeDirectPresentation(listOf(rows[3],rows[0],rows[2])).map {it.body})
        assertEquals(listOf("ONE","TWO","THREE","FOUR"),
            mergeDirectPresentation(listOf(rows[3],rows[0],rows[2],rows[1])).map {it.body})
    }

    @Test fun bidirectionalStreamsKeepOwnOrderWithoutGlobalSequenceSort() {
        val incoming=(1L..4).map {row(RandomIdentifiers.create(),"A$it",it)}
        val outgoing=(1L..3).map {row(RandomIdentifiers.create(),"B$it",it,Direction.OUTGOING)}
        val merged=mergeDirectPresentation(listOf(incoming[3],outgoing[2],incoming[1],
            outgoing[0],incoming[0],outgoing[1],incoming[2]))
        assertEquals(listOf("A1","A2","A3","A4"),merged.filter {it.direction==Direction.INCOMING}.map {it.body})
        assertEquals(listOf("B1","B2","B3"),merged.filter {it.direction==Direction.OUTGOING}.map {it.body})
    }

    @Test fun scopeResetsOnEitherIdentityOrDeviceChangeAndCounterSurvivesRestart() {
        val records=Records();val first=LocalRepository(records)
        assertEquals(1L,first.allocateDirectSequence(bob,scope))
        assertEquals(2L,LocalRepository(records).allocateDirectSequence(bob,scope))
        val changed=DirectOrderingV1.scope(alice,bob,ByteArray(32) {9},bobKey)
        assertFalse(scope.contentEquals(changed))
        assertEquals(1L,LocalRepository(records).allocateDirectSequence(bob,changed))
        assertEquals(3L,LocalRepository(records).allocateDirectSequence(bob,scope))
        assertFalse(scope.contentEquals(DirectOrderingV1.scope(alice,RandomIdentifiers.create(),aliceKey,bobKey)))
    }

    @Test fun duplicateSequenceConflictIsRejectedWithoutGapAllocation() {
        val records=Records();val store=LocalRepository(records)
        val x=RandomIdentifiers.create();val y=RandomIdentifiers.create()
        assertTrue(store.claimDirectSequence(bob,scope,5,x))
        assertFalse(store.claimDirectSequence(bob,scope,5,y))
        assertFalse(store.claimDirectSequence(bob,scope,5,x))
        assertTrue(LocalRepository(records).claimDirectSequence(bob,scope,6,y))
    }
    @Test fun restartLateInsertionAndViewOnceConsumptionKeepSequencePosition() {
        val records=Records();val first=LocalRepository(records)
        first.save(row(RandomIdentifiers.create(),"TWELVE",12))
        first.save(row(RandomIdentifiers.create(),"FOURTEEN",14))
        assertEquals(listOf("TWELVE","FOURTEEN"),LocalRepository(records).messages(bob).map {it.body})
        first.save(row(RandomIdentifiers.create(),"THIRTEEN",13))
        assertEquals(listOf("TWELVE","THIRTEEN","FOURTEEN"),
            LocalRepository(records).messages(bob).map {it.body})
        val once=row(RandomIdentifiers.create(),"",15).copy(viewOnceKind=ViewOnceKind.TEXT,
            viewOnceState=ViewOnceState.CONSUMED)
        first.save(once)
        val reloaded=LocalRepository(records).messages(bob).last()
        assertEquals(15L,reloaded.senderSequence)
        assertEquals(ViewOnceState.CONSUMED,reloaded.viewOnceState)
        assertEquals("",reloaded.body)
    }

    @Test fun authenticatedMarkerAndVersionedUserFrameRoundTrip() {
        val legacy=ConversationPayload.encode("hello",0)
        assertTrue(ConversationPayload.decode(legacy).supportsDirectOrdering)
        assertEquals("alice",ConversationPayload.decode(
            ConversationPayload.encode("hello",0,displayName="alice")).displayName)
        val ordered=ConversationPayload.encodeOrderedDirect(legacy,scope,12)
        assertEquals(23,ordered[5].toInt())
        val received=ConversationPayload.decode(ordered).directOrdered!!
        assertEquals(12L,received.senderSequence)
        assertArrayEquals(scope,received.scopeDigest)
        assertEquals("hello",received.inner.body)
        assertThrows(IllegalArgumentException::class.java) {
            ConversationPayload.encodeOrderedDirect(legacy,scope,0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConversationPayload.encodeOrderedDirect(ConversationPayload.encode("",30,true),scope,1)
        }
    }
}
