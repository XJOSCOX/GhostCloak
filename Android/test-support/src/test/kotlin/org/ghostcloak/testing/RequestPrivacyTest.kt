package org.ghostcloak.testing

import kotlinx.coroutines.runBlocking
import org.ghostcloak.messaging.*
import org.ghostcloak.protocol.*
import org.ghostcloak.crypto.*
import org.ghostcloak.identity.RandomIdentifiers
import org.junit.Assert.*
import org.junit.Test

class RequestPrivacyTest {
    @Test fun commitBoundaryAcceptDeleteAndExpiryHaveTransactionalWinners() {
        for(action in listOf("accept-before","accept-at","delete-before","delete-at")) {
            val records=MemoryRecords();var elapsed=0L
            fun repo()=LocalRepository(records,ExpiryClock({Long.MAX_VALUE/2},{elapsed},{1}))
            var r=repo();val id=RandomIdentifiers.create()
            r.serverReference(1000000);r.save(Contact(id,id,"synthetic",id,request=true));r.startRequest(id,1)
            r.save(Message("private",id,Direction.INCOMING,"secret",1,MessageState.RECEIVED))
            elapsed=if(action.endsWith("before")) REQUEST_WINDOW-1 else REQUEST_WINDOW
            r=repo()
            when(action) {
                "accept-before" -> r.acceptRequest(id)
                "accept-at" -> assertThrows(IllegalArgumentException::class.java) {r.acceptRequest(id)}
                else -> {r.expireRequests();r.finishRequest(id,RequestState.REJECTED)}
            }
            elapsed=REQUEST_WINDOW+1;r=repo();repeat(2){r.expireRequests()}
            if(action=="accept-before") {
                assertEquals(RequestState.ACCEPTED,r.request(id).state);assertEquals(1,r.messages(id).size)
            } else {
                assertTrue(r.messages(id).isEmpty());assertEquals(0,r.unreadCount(id))
                assertFalse(r.isActiveContact(id));assertFalse(r.contact(id).blocked)
                assertThrows(IllegalArgumentException::class.java) {r.acceptRequest(id)}
            }
        }
    }
    @Test fun rebootUsesOriginalCommitAnchorAndNeverWallClockOrFreshWindow() {
        val records=MemoryRecords();var wall=1000L;var elapsed=0L;var boot=1
        fun repo()=LocalRepository(records,ExpiryClock({wall},{elapsed},{boot}))
        var r=repo();val id=RandomIdentifiers.create();val committed=1000000L
        r.serverReference(committed);r.save(Contact(id,id,"synthetic",id,request=true));r.startRequest(id,1)
        val anchor=r.request(id).acceptedAt
        wall=-999999;elapsed=REQUEST_WINDOW-1;r=repo();assertFalse(r.requestExpired(id))
        boot++;elapsed=1;wall=Long.MAX_VALUE/2;r=repo()
        assertThrows(IllegalArgumentException::class.java) {r.acceptRequest(id)}
        r.serverReference(committed+REQUEST_WINDOW-1);assertFalse(r.requestExpired(id))
        elapsed++;assertTrue(r.requestExpired(id));repeat(2){r.expireRequests()}
        assertEquals(anchor,r.request(id).acceptedAt);assertEquals(RequestState.EXPIRED,r.request(id).state)
        boot++;elapsed=0;r=repo();assertEquals(RequestState.EXPIRED,r.request(id).state)
    }
    @Test fun upgradeRecoversPersistedCommitDeadlineOnSameBootAndKeepsEarlierDeadlineAfterReboot() {
        for(reboot in listOf(false,true)) {
            val records=MemoryRecords();var elapsed=1000L;var boot=1
            val r=LocalRepository(records,ExpiryClock({1},{elapsed},{boot}));val id=RandomIdentifiers.create()
            r.serverReference(1000000);r.save(Contact(id,id,"synthetic",id,request=true))
            // Historical serialized shape intentionally lacks clockVersion.
            @kotlinx.serialization.Serializable
            data class Old(val state:RequestState=RequestState.PENDING,val hidden:Boolean=true,
                val acceptedAt:Long?=1,val grace:ExpiryDeadline,val lastEnvelopeAt:Long?=1)
            @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
            val encoded=kotlinx.serialization.cbor.Cbor {encodeDefaults=true}.encodeToByteArray(Old.serializer(),
                Old(grace=ExpiryDeadline(REQUEST_WINDOW+1,REQUEST_WINDOW+1000,1)))
            records.transaction {records.write("app/request/$id",encoded)}
            if(reboot) {boot++;elapsed=0;r.serverReference(1001000)} else elapsed+=100
            val upgraded=r.request(id)
            assertEquals(1,upgraded.clockVersion)
            assertEquals(if(reboot) 1L else 1000000L,upgraded.acceptedAt)
            assertEquals(REQUEST_WINDOW+1000,upgraded.grace.elapsed)
            assertEquals(upgraded,LocalRepository(records,r.clock).request(id))
        }
    }
    @Test fun failedAuthenticatedCommitCannotLeaveRequestTimerOrReplayReceipt()=runBlocking {
        AttachmentTest.Fixture().use {f->
            val a=f.person("alice");val b=f.person("bob");NetworkAccount(a.client,a.state).connect("bob",a.engine)
            var reject=true
            val records=object:EndpointRecords by b.records {
                override fun write(key:String,value:ByteArray) {
                    b.records.write(key,value)
                    if(reject && key.startsWith("app/request/")) throw EndpointStorageFailure()
                }
            }
            var elapsed=0L;val repo=LocalRepository(records,ExpiryClock({1},{elapsed},{1}))
            val receiver=ConversationService(SignalProtocolEngine(records),repo);receiver.open();receiver.serverReference(1000000)
            val packet=a.engine.encrypt(b.registration.deviceId,ConversationPayload.encode("private",0))
            val profile=SenderProfile(a.registration.accountId,a.registration.deviceId,a.registration.routingId,"alice")
            try {receiver.acceptNetwork(packet,profile,1);fail()}catch(e:CryptoFailure){assertEquals(CryptoError.StorageFailure,e.error)}
            records.transaction {assertTrue(records.keys("app/request/").isEmpty());assertTrue(records.keys("app/accepted/").isEmpty())}
            reject=false;elapsed=60000;receiver.acceptNetwork(packet,profile,1)
            assertEquals(1060000L,repo.request(a.registration.deviceId).acceptedAt)
            assertEquals(REQUEST_WINDOW+60000,repo.request(a.registration.deviceId).grace.elapsed)
        }
    }
    @Test fun offlineRequestGetsFullCommitWindowAndReplayCannotRestoreExpiredAttachment()=runBlocking {
        AttachmentTest.Fixture().use { f ->
            val a=f.person("alice");val b=f.person("bob")
            NetworkAccount(a.client,a.state).connect("bob",a.engine)
            var elapsed=0L
            val repo=LocalRepository(b.records,ExpiryClock({1000},{elapsed},{1}));val receiver=ConversationService(b.engine,repo);receiver.open()
            val id=a.registration.deviceId;val accepted=1000000L
            val committed=accepted+60L*60*60*1000
            receiver.serverReference(committed)
            val descriptor=a.store.prepare(byteArrayOf(1).inputStream(),1,
                org.ghostcloak.attachments.AttachmentKind.DOCUMENT,0,"hidden.pdf") {true}
            val bytes=ConversationPayload.encodeAttachment(descriptor)
            val packet=try {a.engine.encrypt(b.registration.deviceId,bytes)} finally {bytes.fill(0)}
            val profile=SenderProfile(a.registration.accountId,id,a.registration.routingId,"alice")
            receiver.acceptNetwork(packet,profile,accepted)
            assertEquals(committed,repo.request(id).acceptedAt)
            assertEquals(RequestState.PENDING,repo.request(id).state)
            assertTrue(repo.hasAttachment(id,packet.envelopeId))
            elapsed=REQUEST_WINDOW-1
            assertFalse(repo.requestExpired(id))
            elapsed=REQUEST_WINDOW
            receiver.reconcileExpiry()
            assertEquals(RequestState.EXPIRED,repo.request(id).state)
            assertTrue(receiver.contacts().isEmpty());assertTrue(receiver.messagesForUi(id).isEmpty())
            assertFalse(repo.hasAttachment(id,packet.envelopeId));assertEquals(0,receiver.unreadCount())
            receiver.acceptNetwork(packet,profile,accepted)
            assertTrue(receiver.messages(id).isEmpty())
            try {receiver.acceptRequest(id);fail("Expired request accepted")} catch(_:IllegalArgumentException) {}
            elapsed++
            receiver.acceptNetwork(a.engine.encrypt(b.registration.deviceId,ConversationPayload.encode("new",0)),profile,accepted)
            assertEquals(RequestState.PENDING,repo.request(id).state)
            assertEquals(REQUEST_WINDOW+elapsed,repo.request(id).grace.elapsed)
        }
    }
    @Test fun visibleTextStillHidesTimerAndRebootWithholdsUntilTrustedTimeReturns()=runBlocking {
        AttachmentTest.Fixture().use {f->
            val a=f.person("alice");val b=f.person("bob")
            NetworkAccount(a.client,a.state).connect("bob",a.engine)
            var boot=1
            val repo=LocalRepository(b.records,ExpiryClock({1000000},{100},{boot}))
            val receiver=ConversationService(b.engine,repo);receiver.open();receiver.requireRequestConfirmation(false)
            receiver.serverReference(1000000)
            val packet=a.engine.encrypt(b.registration.deviceId,ConversationPayload.encode("visible text",30))
            val id=a.registration.deviceId
            receiver.acceptNetwork(packet,SenderProfile(a.registration.accountId,id,a.registration.routingId,"alice"),1000000)
            val visible=receiver.messagesForUi(id).single()
            assertEquals("visible text",visible.body);assertEquals(0,visible.disappearingSeconds);assertNull(visible.expiry)
            assertEquals(30,receiver.messages(id).single().disappearingSeconds)
            boot++
            assertTrue(receiver.messagesForUi(id).isEmpty())
            receiver.serverReference(1000100)
            assertEquals("visible text",receiver.messagesForUi(id).single().body)
        }
    }
    @Test fun hiddenRequestAuthenticatesBeforeRevealAndSettingOnlyAffectsFutureRequests()=runBlocking {
        AttachmentTest.Fixture().use {f->
            val a=f.person("alice");val b=f.person("bob")
            NetworkAccount(a.client,a.state).connect("bob",a.engine)
            val repo=LocalRepository(b.records);val receiver=ConversationService(b.engine,repo);receiver.open()
            suspend fun receive(body:String):EncryptedEnvelope {
                val packet=a.engine.encrypt(b.registration.deviceId,ConversationPayload.encode(body,0))
                receiver.acceptNetwork(packet,SenderProfile(a.registration.accountId,a.registration.deviceId,a.registration.routingId,"alice"))
                return packet
            }
            val first=receive("secret test")
            assertEquals("secret test",receiver.messages(a.registration.deviceId).single().body)
            assertTrue(receiver.messagesForUi(a.registration.deviceId).isEmpty())
            assertNotNull(receiver.fingerprint(a.registration.deviceId))
            receiver.requireRequestConfirmation(false)
            assertTrue(receiver.messagesForUi(a.registration.deviceId).isEmpty())
            receiver.acceptRequest(a.registration.deviceId)
            assertEquals("secret test",receiver.messagesForUi(a.registration.deviceId).single().body)
            receiver.acceptNetwork(first)
            assertEquals(1,receiver.messagesForUi(a.registration.deviceId).size)
            receive("normal future message")
            assertEquals(2,receiver.messagesForUi(a.registration.deviceId).size)
        }
    }
    @Test fun authoritativeExpirySurvivesRestartAndIgnoresWallClockChanges() {
        val records=MemoryRecords();var wall=1000L;var elapsed=100L;var boot=1
        fun repo()=LocalRepository(records,ExpiryClock({wall},{elapsed},{boot}))
        var r=repo();val id=RandomIdentifiers.create()
        r.save(Contact(id,id,"synthetic",id,request=true));r.serverReference(1000000)
        r.startRequest(id,1000000)
        r.save(Message("message",id,Direction.INCOMING,"secret",wall,MessageState.RECEIVED))
        elapsed+=REQUEST_WINDOW-1;wall+=365L*86400000
        r=repo();assertFalse(r.requestExpired(id));assertTrue(r.requestHidden(id))
        elapsed++;assertTrue(r.requestExpired(id));r.expireRequests()
        assertTrue(r.messages(id).isEmpty());assertEquals(0,r.unreadCount(id))
        assertThrows(IllegalArgumentException::class.java) {r.acceptRequest(id)}
        boot++;elapsed=0;r=repo();assertEquals(RequestState.EXPIRED,r.request(id).state)
    }
    @Test fun rebootRequiresFreshReferenceAndDeleteDoesNotBecomeBlock() {
        val records=MemoryRecords();var boot=1;var elapsed=0L
        fun repo()=LocalRepository(records,ExpiryClock({1},{elapsed},{boot}))
        var r=repo();val id=RandomIdentifiers.create()
        r.save(Contact(id,id,"synthetic",id,request=true));r.serverReference(1000000);r.startRequest(id,1000000)
        boot++;r=repo()
        assertThrows(IllegalArgumentException::class.java) {r.acceptRequest(id)}
        r.serverReference(1001000);r.finishRequest(id,RequestState.REJECTED)
        assertFalse(r.contact(id).blocked);r.restartRequestIfEligible(id,1002000)
        assertEquals(RequestState.PENDING,r.request(id).state)
        r.serverReference(5000000);r.restartRequestIfEligible(id,5000000)
        assertEquals(RequestState.PENDING,r.request(id).state)
        r.finishRequest(id,RequestState.BLOCKED);r.serverReference(10000000);r.restartRequestIfEligible(id,10000000)
        assertEquals(RequestState.BLOCKED,r.request(id).state)
    }
    @Test fun visiblePreferenceIsCapturedAndAcceptedHistoryIsNeverRequestExpired() {
        val r=LocalRepository(MemoryRecords());val id=RandomIdentifiers.create()
        r.requireRequestConfirmation(false);r.save(Contact(id,id,"synthetic",id,request=true));r.startRequest(id,null)
        assertFalse(r.requestHidden(id));r.requireRequestConfirmation(true);assertFalse(r.requestHidden(id))
        r.acceptRequest(id);assertFalse(r.contact(id).request)
        r.expireRequests();assertEquals(RequestState.ACCEPTED,r.request(id).state)
    }
}
