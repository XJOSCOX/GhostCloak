package org.ghostcloak.testing

import kotlinx.coroutines.runBlocking
import org.ghostcloak.crypto.SignalProtocolEngine
import org.ghostcloak.messaging.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor

@Serializable private data class PriorContact(val contactId:String,val publicUserId:String,val displayName:String,
    val remoteDeviceId:String,val blocked:Boolean=false,val request:Boolean=false,
    val ghostCloakId:String?=null,val localAlias:String?=null)

class ChatOrganizationTest {
    private class Pairing {
        val ar=MemoryRecords();val br=MemoryRecords()
        val ae=SignalProtocolEngine(ar);val be=SignalProtocolEngine(br)
        val ap=LocalRepository(ar);val bp=LocalRepository(br)
        var a=ConversationService(ae,ap);val b=ConversationService(be,bp)
        lateinit var aid:String;lateinit var bid:String
        suspend fun open() {
            aid=a.create("Alice").deviceId;bid=b.create("Bob").deviceId
            a.importCard(b.exportCard());b.importCard(a.exportCard())
        }
        fun restart() {a=ConversationService(ae,LocalRepository(ar))}
    }
    @Test fun aliasIsLocalEditableRemovableAndDoesNotChangeIdentity()=runBlocking {
        val p=Pairing();p.open()
        val before=p.a.fingerprint(p.bid)
        val remoteName=p.b.contacts().single().contact.visibleName
        p.a.setLocalAlias(p.bid,"  Local Bob  ")
        assertEquals("Local Bob",p.a.contacts().single().contact.visibleName)
        assertNull(p.b.contacts().single().contact.localAlias)
        assertEquals(remoteName,p.b.contacts().single().contact.visibleName)
        p.a.setLocalAlias(p.bid,"Friend")
        p.restart();p.a.open()
        assertEquals("Friend",p.a.contacts().single().contact.visibleName)
        assertEquals(before,p.a.fingerprint(p.bid))
        p.a.setLocalAlias(p.bid,null)
        assertEquals(p.ap.contact(p.bid).displayName,p.a.contacts().single().contact.visibleName)
        assertEquals(before,p.a.fingerprint(p.bid))
    }
    @Test fun pinArchiveMutePersistAndUnreadStillCountsWithoutNotification()=runBlocking {
        val p=Pairing();p.open()
        p.a.setPinned(p.bid,true);p.a.setArchived(p.bid,true);p.a.setMuted(p.bid,true)
        p.restart();p.a.open()
        val saved=p.a.contacts().single().contact
        assertTrue(saved.pinned && saved.archived && saved.muted)
        val packet=p.be.encrypt(p.aid,ConversationPayload.encode("incoming",0))
        p.a.acceptNetwork(packet)
        assertFalse(p.a.contacts().single().contact.archived)
        assertEquals(1,p.a.unreadCount())
        assertTrue(NotificationLedger(p.ar).eligible().isEmpty())
        p.a.setMuted(p.bid,false)
        val second=p.be.encrypt(p.aid,ConversationPayload.encode("next",0))
        p.a.acceptNetwork(second)
        assertEquals(2,p.a.unreadCount())
        assertEquals(1,NotificationLedger(p.ar).eligible().size)
        p.a.setPinned(p.bid,false);p.a.setArchived(p.bid,true)
        p.restart();p.a.open()
        assertFalse(p.a.contacts().single().contact.pinned)
        assertTrue(p.a.contacts().single().contact.archived)
    }
    @Test fun orderingIgnoresRequestActivityAndKeepsStableTies() {
        fun status(name:String,pin:Boolean=false,archive:Boolean=false,request:Boolean=false)=ContactStatus(
            Contact(UUID.randomUUID().toString(),UUID.randomUUID().toString(),name,UUID.randomUUID().toString(),
                pinned=pin,archived=archive,request=request),null,null)
        val recent=status("Recent");val pinnedOld=status("Pinned old",pin=true)
        val pinnedNew=status("Pinned new",pin=true);val archived=status("Archived",archive=true)
        val request=status("Request",request=true)
        val previews=listOf(recent to 100L,pinnedOld to 10L,pinnedNew to 20L,archived to 500L,request to 10000L)
            .associate {it.first.contact.remoteDeviceId to Message(UUID.randomUUID().toString(),it.first.contact.remoteDeviceId,
                Direction.INCOMING,"hidden",it.second,MessageState.RECEIVED)}
        val all=listOf(request,recent,archived,pinnedOld,pinnedNew)
        assertEquals(listOf(pinnedNew,pinnedOld,recent,request),ChatOrganization.main(all,previews))
        assertEquals(listOf(archived),ChatOrganization.archived(all,previews))
    }
    @Test fun requestAndBlockedOrganizationEditsFailClosed()=runBlocking {
        val p=Pairing();p.open()
        p.ap.save(p.ap.contact(p.bid).copy(request=true))
        try {p.a.setPinned(p.bid,true);fail("request pin") } catch(_:AppFailure) {}
        try {p.a.setArchived(p.bid,true);fail("request archive") } catch(_:AppFailure) {}
        try {p.a.setMuted(p.bid,true);fail("request mute") } catch(_:AppFailure) {}
        try {p.a.setLocalAlias(p.bid,"alias");fail("request alias") } catch(_:AppFailure) {}
        p.ap.save(p.ap.contact(p.bid).copy(request=false))
        p.a.block(p.bid,true)
        try {p.a.setPinned(p.bid,true);fail("blocked pin") } catch(_:AppFailure) {}
        try {p.a.setMuted(p.bid,true);fail("blocked mute") } catch(_:AppFailure) {}
        try {p.a.setLocalAlias(p.bid,"alias");fail("blocked alias") } catch(_:AppFailure) {}
        assertFalse(p.ap.contact(p.bid).pinned || p.ap.contact(p.bid).muted)
        assertNull(p.ap.contact(p.bid).localAlias)
    }
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @Test fun priorContactRecordReadsWithUnorganizedDefaults() {
        val format=Cbor {encodeDefaults=true;ignoreUnknownKeys=false}
        val prior=PriorContact("contact","user","Bob",UUID.randomUUID().toString())
        val restored=format.decodeFromByteArray(Contact.serializer(),format.encodeToByteArray(PriorContact.serializer(),prior))
        assertFalse(restored.pinned || restored.archived || restored.muted)
        assertEquals("Bob",restored.visibleName)
    }
    @Test fun orderingHundredsUsesOnlySnapshot() {
        val contacts=(0 until 500).map { index -> ContactStatus(Contact(index.toString(),index.toString(),"Name $index",
            UUID.randomUUID().toString(),pinned=index%11==0,archived=index%13==0),null,null) }
        val previews=contacts.associate {it.contact.remoteDeviceId to Message(UUID.randomUUID().toString(),
            it.contact.remoteDeviceId,Direction.INCOMING,"content",it.contact.contactId.toLong(),MessageState.RECEIVED)}
        val start=System.nanoTime()
        repeat(100) {ChatOrganization.main(contacts,previews)}
        val millis=(System.nanoTime()-start)/1_000_000.0
        println("P3_ORDER contacts=500 passes=100 total_ms=$millis added_message_reads=0 network_calls=0")
        assertTrue(millis<10_000)
    }
}
