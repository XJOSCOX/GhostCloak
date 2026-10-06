package org.ghostcloak.testing

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.emptyFlow
import org.ghostcloak.crypto.SignalProtocolEngine
import org.ghostcloak.messaging.*
import org.ghostcloak.protocol.EncryptedEnvelope
import org.ghostcloak.transport.IdempotentMessageTransport
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class PrivacyDefaultsTest {
    private fun id()=UUID.randomUUID().toString()
    private class Wire:IdempotentMessageTransport {
        val sent=mutableListOf<EncryptedEnvelope>()
        override fun receive()=emptyFlow<EncryptedEnvelope>()
        override suspend fun send(routingDestination:String,envelope:EncryptedEnvelope)=Unit
        override suspend fun submit(submissionId:String,routingDestination:String,envelope:EncryptedEnvelope):String {
            sent+=envelope;return submissionId
        }
    }

    @Test fun privacyFirstDefaultsPersistLocallyAndNeverRewriteExistingChat() {
        val records=MemoryRecords()
        val repository=LocalRepository(records)
        assertEquals(PrivacyDefaults(),repository.privacyDefaults())
        val first=id()
        repository.save(Contact(id(),id(),"First",first))
        repository.applyDefaultPolicyIfNew(first,true)
        assertEquals(0,repository.policy(first))
        repository.policy(first,30)
        val chosen=PrivacyDefaults(300,VoiceMaskPreference.STRONG,DownloadPreference.AUTOMATIC,
            DownloadPreference.MANUAL,DownloadPreference.MANUAL)
        repository.privacyDefaults(chosen)
        assertEquals(chosen,LocalRepository(records).privacyDefaults())
        repository.applyDefaultPolicyIfNew(first,true)
        assertEquals(30,repository.policy(first))
        assertFalse(first in repository.pendingDefaultTimers())
        repository.privacyDefaults(chosen.copy(disappearingSeconds=3600))
        assertEquals(30,repository.policy(first))
        val second=id()
        repository.save(Contact(id(),id(),"Second",second))
        repository.applyDefaultPolicyIfNew(second,true)
        assertEquals(3600,repository.policy(second))
        assertEquals(setOf(second),repository.pendingDefaultTimers().keys)
        assertTrue(records.keys("network/").none {it.contains("privacy")})
        assertThrows(AppFailure::class.java) {repository.privacyDefaults(chosen.copy(disappearingSeconds=42))}
    }

    @Test fun acceptedRequestSnapshotsDefaultAndBlockedRelationshipCannotDownload() {
        val records=MemoryRecords();val repository=LocalRepository(records)
        val peer=id()
        repository.privacyDefaults(PrivacyDefaults(disappearingSeconds=30))
        repository.save(Contact(id(),id(),"Request",peer,request=true))
        repository.startRequest(peer,null)
        repository.acceptRequest(peer,queueDefaultControl=true)
        assertEquals(30,repository.policy(peer))
        assertEquals(30,repository.pendingDefaultTimers()[peer])
        repository.clearPendingDefaultTimer(peer)
        assertTrue(repository.pendingDefaultTimers().isEmpty())
        repository.removeContact(peer)
        assertNull(repository.autoDownloadDescriptor(peer,id()))
    }

    @Test fun defaultTimerUsesDurableEncryptedControlOnce()=runBlocking {
        val ar=MemoryRecords();val br=MemoryRecords()
        val ae=SignalProtocolEngine(ar);val be=SignalProtocolEngine(br)
        val a=ConversationService(ae,LocalRepository(ar))
        val b=ConversationService(be,LocalRepository(br))
        val aid=a.create("Alice").deviceId;val bid=b.create("Bob").deviceId
        a.privacyDefaults(PrivacyDefaults(disappearingSeconds=300))
        a.importCard(b.exportCard());b.importCard(a.exportCard())
        assertEquals(300,LocalRepository(ar).pendingDefaultTimers()[bid])
        val wire=Wire();val outbox=DurableOutbox(ar,ae,wire)
        a.retryNetwork(outbox)
        assertTrue(LocalRepository(ar).pendingDefaultTimers().isEmpty())
        assertEquals(1,wire.sent.size)
        b.acceptNetwork(wire.sent.single())
        assertEquals(300,LocalRepository(br).policy(aid))
        a.retryNetwork(outbox)
        assertEquals(1,wire.sent.size)
    }

    @Test fun automaticDownloadEligibilityRejectsRequestsBlocksViewOnceAndExpired()=runBlocking {
        val records=MemoryRecords();val repository=LocalRepository(records)
        val peer=id();val messageId=id()
        repository.privacyDefaults(PrivacyDefaults(photos=DownloadPreference.AUTOMATIC))
        repository.save(Contact(id(),id(),"Accepted",peer))
        repository.save(Message(messageId,peer,Direction.INCOMING,"",System.currentTimeMillis(),MessageState.RECEIVED,messageId))
        AttachmentTest.Fixture().use {fixture ->
            val sender=fixture.person("sender")
            val descriptor=sender.store.prepare(byteArrayOf(1).inputStream(),1,
                org.ghostcloak.attachments.AttachmentKind.IMAGE,0){true}
            val encoded=org.ghostcloak.attachments.AttachmentFormat.encode(descriptor)
            repository.attachment(peer,messageId,encoded)
            assertEquals(listOf(peer to messageId),repository.pendingAutoDownloads())
            assertNotNull(repository.autoDownloadDescriptor(peer,messageId))
            repository.save(repository.messages(peer).first().copy(viewOnceKind=ViewOnceKind.PHOTO))
            assertNull(repository.autoDownloadDescriptor(peer,messageId))
            repository.save(repository.messages(peer).first().copy(viewOnceKind=null))
            repository.save(repository.contact(peer).copy(request=true))
            assertNull(repository.autoDownloadDescriptor(peer,messageId))
            val hidden=id()
            repository.save(Message(hidden,peer,Direction.INCOMING,"",System.currentTimeMillis(),MessageState.RECEIVED,hidden))
            repository.attachment(peer,hidden,encoded)
            assertFalse(peer to hidden in repository.pendingAutoDownloads())
            repository.save(repository.contact(peer).copy(request=false,blocked=true))
            assertNull(repository.autoDownloadDescriptor(peer,messageId))
            val blocked=id()
            repository.save(Message(blocked,peer,Direction.INCOMING,"",System.currentTimeMillis(),MessageState.RECEIVED,blocked))
            repository.attachment(peer,blocked,encoded)
            assertFalse(peer to blocked in repository.pendingAutoDownloads())
            repository.save(repository.contact(peer).copy(blocked=false))
            val viewOnce=id()
            repository.save(Message(viewOnce,peer,Direction.INCOMING,"",System.currentTimeMillis(),MessageState.RECEIVED,viewOnce,
                viewOnceKind=ViewOnceKind.PHOTO))
            repository.attachment(peer,viewOnce,encoded)
            assertFalse(peer to viewOnce in repository.pendingAutoDownloads())
            val now=repository.clock.now()
            repository.save(repository.messages(peer).first().copy(expiry=ExpiryDeadline(0,0,now.boot)))
            assertNull(repository.autoDownloadDescriptor(peer,messageId))
        }
    }
}
