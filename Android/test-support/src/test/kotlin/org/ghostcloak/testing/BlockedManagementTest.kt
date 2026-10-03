package org.ghostcloak.testing

import kotlinx.coroutines.runBlocking
import org.ghostcloak.crypto.*
import org.ghostcloak.messaging.*
import org.ghostcloak.protocol.*
import org.ghostcloak.identity.*
import org.junit.Assert.*
import org.junit.Test

class BlockedManagementTest {
    @Test fun explicitAddAfterUnblockReusesRelationshipAndChangedIdentityStillFails()=runBlocking {
        val ar=MemoryRecords();val br=MemoryRecords();val ae=SignalProtocolEngine(ar)
        val a=ConversationService(ae,LocalRepository(ar));val repo=LocalRepository(br)
        val b=ConversationService(SignalProtocolEngine(br),repo);val ai=a.create("alice");val bi=b.create("bob")
        a.importCard(b.exportCard())
        b.acceptNetwork(ae.encrypt(bi.deviceId,ConversationPayload.encode("first",0)),
            SenderProfile(ai.userId,ai.deviceId,RandomIdentifiers.create(),"alice"))
        val pin=b.fingerprint(ai.deviceId);b.block(ai.deviceId,true)
        assertThrows(AppFailure::class.java){runBlocking{b.importCard(a.exportCard())}}
        b.unblock(ai.deviceId)
        val thirdRecords=MemoryRecords();val third=ConversationService(SignalProtocolEngine(thirdRecords),LocalRepository(thirdRecords))
        third.create("mallory")
        val original=ContactCardCodec.decode(a.exportCard());val replacement=ContactCardCodec.decode(third.exportCard())
        val forged=ContactCard(original.version,original.userId,original.username,original.deviceId,
            replacement.registrationId,replacement.identity,replacement.preKeyId,replacement.preKey,
            replacement.signedId,replacement.signedKey,replacement.signature,replacement.kyberId,replacement.kyberKey,replacement.kyberSignature)
        try{b.importCard(ContactCardCodec.encode(forged));fail()}catch(e:CryptoFailure){assertEquals(CryptoError.IdentityChanged,e.error)}
        assertEquals(pin,b.fingerprint(ai.deviceId));assertFalse(repo.isActiveContact(ai.deviceId))
        // Use an independent unchanged fixture for successful explicit Add, since changed
        // identity deliberately gates the previous session until explicit security review.
        val cr=MemoryRecords();val c=ConversationService(SignalProtocolEngine(cr),LocalRepository(cr));val ci=c.create("carol")
        a.importCard(c.exportCard());c.acceptNetwork(ae.encrypt(ci.deviceId,ConversationPayload.encode("first",0)),
            SenderProfile(ai.userId,ai.deviceId,RandomIdentifiers.create(),"alice"))
        c.block(ai.deviceId,true);c.unblock(ai.deviceId);c.importCard(a.exportCard())
        assertEquals(1,c.contacts().size);assertFalse(c.contacts().single().contact.request)
    }
    @Test fun listSortingIdempotencyRestartAndFailedUnblockUseOneEncryptedFlag() {
        val memory=MemoryRecords();var fail=false
        val records=object:EndpointRecords by memory {
            override fun write(key:String,value:ByteArray) {
                memory.write(key,value)
                if(fail && key.startsWith("app/request/")) throw EndpointStorageFailure()
            }
        }
        var repo=LocalRepository(records)
        assertTrue(repo.listBlocked().isEmpty())
        for(name in listOf("zoe","Alice")) {
            repo.save(Contact(name,name,name,name,request=true));repo.startRequest(name,null)
            repeat(2){repo.block(name,true)}
        }
        repo=LocalRepository(records);assertEquals(listOf("Alice","zoe"),repo.listBlocked().map {it.displayName})
        fail=true;assertThrows(EndpointStorageFailure::class.java){repo.unblock("Alice")}
        assertTrue(repo.isBlocked("Alice"));assertEquals(RequestState.BLOCKED,repo.request("Alice").state)
        fail=false;repo.unblock("Alice");repo.unblock("Alice")
        repo=LocalRepository(records);assertFalse(repo.isBlocked("Alice"))
        assertEquals(RelationshipState.DORMANT_UNACCEPTED,repo.relationshipState("Alice"))
        assertEquals(listOf("zoe"),repo.listBlocked().map {it.displayName})
    }
    @Test fun discardedMessagesReplayNeverReturnsAndNewMessageRequiresAcceptance()=runBlocking {
        val ar=MemoryRecords();val br=MemoryRecords();val ae=SignalProtocolEngine(ar)
        val a=ConversationService(ae,LocalRepository(ar));val repo=LocalRepository(br)
        var b=ConversationService(SignalProtocolEngine(br),repo)
        val ai=a.create("alice");val bi=b.create("bob");a.importCard(b.exportCard())
        val profile=SenderProfile(ai.userId,ai.deviceId,RandomIdentifiers.create(),"alice")
        suspend fun packet()=ae.encrypt(bi.deviceId,ConversationPayload.encode("private",0))
        b.acceptNetwork(packet(),profile);val pin=b.fingerprint(ai.deviceId);b.block(ai.deviceId,true)
        val discarded=listOf(packet(),packet());for(p in discarded)b.acceptNetwork(p,profile)
        val security=br.transaction {br.keys("session/").associateWith {br.read(it)!!}}
        b.unblock(ai.deviceId)
        assertEquals(pin,b.fingerprint(ai.deviceId));security.forEach {(k,v)->assertArrayEquals(v,br.transaction {br.read(k)})}
        b=ConversationService(SignalProtocolEngine(br),LocalRepository(br));b.open()
        assertTrue(b.blockedContacts().isEmpty());for(p in discarded)b.acceptNetwork(p,profile)
        assertTrue(b.contacts().isEmpty());assertTrue(b.messages(ai.deviceId).isEmpty());assertEquals(0,b.unreadCount())
        b.acceptNetwork(packet(),profile);assertTrue(b.messagesForUi(ai.deviceId).isEmpty())
        b.acceptRequest(ai.deviceId);assertEquals(1,b.messagesForUi(ai.deviceId).size)
    }
    @Test fun acceptedRelationshipSurvivesButDiscardedIncomingDoesNot()=runBlocking {
        val ar=MemoryRecords();val br=MemoryRecords();val ae=SignalProtocolEngine(ar)
        val a=ConversationService(ae,LocalRepository(ar));val repo=LocalRepository(br)
        val b=ConversationService(SignalProtocolEngine(br),repo);val ai=a.create("alice");val bi=b.create("bob")
        a.importCard(b.exportCard());b.importCard(a.exportCard())
        b.acceptNetwork(ae.encrypt(bi.deviceId,ConversationPayload.encode("retained",0)))
        b.block(ai.deviceId,true);assertEquals(1,b.blockedContacts().size)
        val blocked=ae.encrypt(bi.deviceId,ConversationPayload.encode("discarded",0));b.acceptNetwork(blocked)
        b.unblock(ai.deviceId);assertTrue(repo.isActiveContact(ai.deviceId))
        b.acceptNetwork(blocked);assertEquals(listOf("retained"),b.messagesForUi(ai.deviceId).map {it.body})
        b.acceptNetwork(ae.encrypt(bi.deviceId,ConversationPayload.encode("new",0)))
        assertEquals(listOf("retained","new"),b.messagesForUi(ai.deviceId).map {it.body})
    }
}
