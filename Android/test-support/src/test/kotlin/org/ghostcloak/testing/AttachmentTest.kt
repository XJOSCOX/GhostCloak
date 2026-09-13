package org.ghostcloak.testing

import kotlinx.coroutines.*
import org.ghostcloak.attachments.*
import org.ghostcloak.backend.*
import org.ghostcloak.crypto.*
import org.ghostcloak.messaging.*
import org.ghostcloak.protocol.*
import org.ghostcloak.transport.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.ServerSocket
import java.nio.file.Files
import java.time.*

class AttachmentTest {
    @Test fun authenticatedPaddingEstablishesSupportAndAttachmentUiDoesNotExposeRequestMetadata()=runBlocking {
        Fixture().use { f ->
            val a=f.person("alice");val b=f.person("bob")
            NetworkAccount(a.client,a.state).connect("bob",a.engine)
            val ar=LocalRepository(a.records);val br=LocalRepository(b.records)
            ar.save(Contact("synthetic-contact",b.registration.accountId,"bob",b.registration.deviceId))
            val sender=ConversationService(a.engine,ar);sender.open()
            val receiver=ConversationService(b.engine,br);receiver.open()
            assertFalse(sender.attachmentPeer(b.registration.deviceId))
            val outbox=DurableOutbox(a.records,a.engine,NetworkMailboxTransport(a.client,a.state))
            sender.sendNetwork(b.registration.deviceId,"hello",outbox)
            val text=b.client.call(ApiRequest.Fetch(includeSenders=true)).deliveries.single()
            receiver.acceptNetwork(EnvelopeCodec.decode(text.encryptedEnvelope),text.sender)
            assertTrue(br.attachmentPeer(a.registration.deviceId))
            b.client.call(ApiRequest.Ack(listOf(text.serverMessageId)))
            val source=byteArrayOf(0,1,2,3,-1)
            val descriptor=a.store.prepare(source.inputStream(),source.size.toLong(),AttachmentKind.DOCUMENT,0,"private-document.pdf"){true}
            assertEquals("private-document.pdf",a.store.entry(descriptor.id)!!.descriptor.filename)
            a.store.upload(descriptor.id,a.bulk){true}
            sender.sendAttachment(b.registration.deviceId,descriptor,outbox,true)
            val attachment=b.client.call(ApiRequest.Fetch(includeSenders=true)).deliveries.single()
            receiver.acceptNetwork(EnvelopeCodec.decode(attachment.encryptedEnvelope),attachment.sender)
            val before=receiver.messagesForUi(a.registration.deviceId).last()
            assertEquals("Attachment",before.attachment!!.filename);assertEquals(0L,before.attachment!!.bytes)
            try { receiver.attachment(a.registration.deviceId,before.localId);fail() } catch (_:AppFailure) {}
            receiver.acceptRequest(a.registration.deviceId)
            val reopened=ConversationService(b.engine,LocalRepository(b.records));reopened.open()
            assertTrue(reopened.attachmentPeer(a.registration.deviceId))
            val after=reopened.messagesForUi(a.registration.deviceId).last()
            assertEquals("private-document.pdf",after.attachment!!.filename)
            assertFalse(File(b.directory,"download").listFiles().orEmpty().any())
            // UI serialization does not persist presentation fields or change existing message schema.
            assertFalse(NetworkCodec.encode(after).toString(Charsets.ISO_8859_1).contains("capability"))
            b.store.download(reopened.attachment(a.registration.deviceId,after.localId)!!,"message",b.bulk){true}.use {
                val copied=ByteArrayOutputStream();it.copyTo(copied);assertArrayEquals(source,copied.toByteArray())
            }
        }
    }
    @Test fun revokingAccessCancelsActiveTransferBeforePlaintextIsPublished()=runBlocking {
        Fixture().use { f ->
            val a=f.person("alice")
            val d=a.store.prepare(byteArrayOf(1).inputStream(),1,AttachmentKind.IMAGE,0){true}
            val started=CompletableDeferred<Unit>()
            val client=object:BlobTransferClient {
                override suspend fun reserve(descriptor:AttachmentDescriptor):BlobResult=error("unused")
                override suspend fun upload(descriptor:AttachmentDescriptor,ciphertext:File):BlobResult=error("unused")
                override suspend fun download(descriptor:AttachmentDescriptor,ciphertext:File) {
                    ciphertext.writeBytes(byteArrayOf(1))
                    started.complete(Unit)
                    awaitCancellation()
                }
            }
            val transfer=async {a.store.download(d,"message",client){true}}
            withTimeout(5000) {started.await()}
            a.store.invalidate()
            try {withTimeout(5000) {transfer.await()};fail()}catch(_:CancellationException){}
            assertEquals(TransferState.FAILED,a.store.entry(d.id)!!.state)
            assertTrue(File(a.directory,"scratch").listFiles().orEmpty().isEmpty())
            assertTrue(File(a.directory,"download").listFiles().orEmpty().isEmpty())
        }
    }
    @Test fun attachmentOutboxExpiryAndReplayUseExistingMessageSemantics()=runBlocking {
        Fixture().use {f->
            val a=f.person("alice"); val b=f.person("bob")
            NetworkAccount(a.client,a.state).connect("bob",a.engine)
            var time=1000000L
            val clock=ExpiryClock({time},{time},{1})
            val ar=LocalRepository(a.records,clock); val br=LocalRepository(b.records,clock)
            ar.save(Contact("synthetic-contact",b.registration.accountId,"bob",b.registration.deviceId))
            val sender=ConversationService(a.engine,ar);sender.open()
            val receiver=ConversationService(b.engine,br);receiver.open()
            val d=a.store.prepare(byteArrayOf(1,2).inputStream(),2,AttachmentKind.DOCUMENT,30){true}
            a.store.upload(d.id,a.bulk){true}
            val outbox=DurableOutbox(a.records,a.engine,NetworkMailboxTransport(a.client,a.state))
            val sent=sender.sendAttachment(b.registration.deviceId,d,outbox,true) {a.store.bind(d.id,"${it.conversationId}/${it.localId}")}
            assertEquals(MessageState.SERVER_ACCEPTED,sent.state);assertNull(sent.expiry)
            time+=120000
            assertEquals(1,sender.messages(b.registration.deviceId).size)
            val delivery=b.client.call(ApiRequest.Fetch(includeSenders=true)).deliveries.single()
            val envelope=EnvelopeCodec.decode(delivery.encryptedEnvelope)
            receiver.acceptNetwork(envelope,delivery.sender)
            receiver.acceptRequest(a.registration.deviceId)
            b.client.call(ApiRequest.Ack(listOf(delivery.serverMessageId)))
            val status=a.client.call(ApiRequest.Fetch(submissionIds=listOf(sent.localId))).statuses.single()
            assertTrue(status.acknowledged)
            ar.deliveredOutgoing(b.registration.deviceId,sent.localId)
            val deadline=sender.messages(b.registration.deviceId).single().expiry
            ar.deliveredOutgoing(b.registration.deviceId,sent.localId)
            assertEquals(deadline,sender.messages(b.registration.deviceId).single().expiry)
            b.store.download(receiver.attachment(a.registration.deviceId,envelope.envelopeId)!!,
                "${a.registration.deviceId}/${envelope.envelopeId}",b.bulk){true}.close()
            time+=30001;receiver.reconcileExpiry()
            b.store.reconcileReferences(emptySet(),emptySet())
            assertNull(b.store.entry(d.id));assertTrue(File(b.directory,"download").listFiles().orEmpty().isEmpty())
            receiver.acceptNetwork(envelope,delivery.sender)
            assertTrue(receiver.messages(a.registration.deviceId).isEmpty())
            sender.reconcileExpiry();a.store.reconcileReferences(emptySet(),emptySet())
            assertNull(a.store.entry(d.id))
        }
    }
    @Test fun missingCapabilityNeverAuthorizesEvenWithZeroCapabilityHash()=runBlocking {
        Fixture().use {f->
            val a=f.person("alice");val bytes=byteArrayOf(1)
            val id="e".repeat(64)
            f.blobs.reserve(a.state.read()!!,BlobReservation(id=id,length=1,digest=DeviceAuth.digest(bytes),capabilityHash=DeviceAuth.digest(ByteArray(32))))
            f.blobs.upload(a.state.read()!!,id,bytes.inputStream())
            assertThrows(ApiFailure::class.java){ f.blobs.download(a.state.read()!!,id,null) }
            assertThrows(ApiFailure::class.java){ f.blobs.download(a.state.read()!!,id,"invalid") }
            Unit
        }
    }
    @Test fun oversizedTransformedAndRedirectedDownloadsNeverLeaveFiles()=runBlocking {
        val directory=Files.createTempDirectory("blob-http-fault").toFile()
        val server=com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1",0),0)
        var mode="oversize"
        server.createContext("/v1/attachments/") { exchange ->
            try {
                exchange.responseHeaders.add("Content-Type","application/octet-stream")
                if(mode=="redirect") {exchange.responseHeaders.add("Location","http://127.0.0.1:1/");exchange.sendResponseHeaders(302,-1)}
                else {
                    if(mode=="encoding") exchange.responseHeaders.add("Content-Encoding","gzip")
                    exchange.sendResponseHeaders(200,0)
                    exchange.responseBody.write(ByteArray(65577))
                }
            } catch(_:IOException) {} finally {exchange.close()}
        }
        server.start()
        try {
            val d=AttachmentFormat.encrypt(byteArrayOf(1).inputStream(),File(directory,"source"),1,AttachmentKind.IMAGE)
            val tokens=object:AccessTokenStore {override fun read()="t".repeat(43); override fun save(token:String?) {} }
            val origin="http://127.0.0.1:${server.address.port}"
            val bulk=StreamingBlobClient(origin,HttpGhostClient(origin,tokens,true),{true},{},true)
            for(value in listOf("oversize","encoding","redirect")) {
                mode=value
                val output=File(directory,"received")
                try {bulk.download(d,output);fail()}catch(_:ApiFailure){}
                assertFalse(output.exists())
            }
        } finally {server.stop(0);directory.deleteRecursively()}
    }
    @Test fun bulkUsesExistingRenewalOnceAndLogoutCannotReconnect()=runBlocking {
        Fixture().use {f->
            val a=f.person("alice")
            val identity=a.engine.createIdentity("alice")
            val d=a.store.prepare(byteArrayOf(1).inputStream(),1,AttachmentKind.IMAGE,0){true}
            var renewals=0; var visibleFailures=0
            val client=HttpGhostClient(f.origin,a.state,true,renewSession={
                renewals++
                NetworkAccount(a.client,a.state).login(a.registration.accountId,a.registration.deviceId)
            },authenticatedFailure={visibleFailures++})
            val bulk=StreamingBlobClient(f.origin,client,{a.allowed},{check(a.allowed)},true)
            f.time.millis+=600000
            a.store.upload(d.id,bulk){a.allowed}
            assertEquals(1,renewals); assertEquals(0,visibleFailures)
            assertArrayEquals(identity.publicKey,a.engine.createIdentity("alice").publicKey)
            a.allowed=false
            try {bulk.reserve(d);fail()}catch(_:kotlinx.coroutines.CancellationException){}
            assertEquals(1,renewals)
            assertEquals(0,visibleFailures)
            a.allowed=true
            f.time.millis+=600000
            val failing=HttpGhostClient(f.origin,a.state,true,renewSession={renewals++},authenticatedFailure={visibleFailures++})
            try {StreamingBlobClient(f.origin,failing,{true},{},true).reserve(d);fail()}catch(e:ApiFailure){assertEquals(401,e.status)}
            assertEquals(2,renewals);assertEquals(1,visibleFailures)
        }
    }
    @Test fun requestsAndByteReservationsAreBoundedWithoutPathDisclosure()=runBlocking {
        Fixture().use {f->
            val a=f.person("alice")
            assertThrows(ApiFailure::class.java) {f.blobs.reserve(a.state.read()!!,BlobReservation(id="../file",length=1,digest=ByteArray(32),capabilityHash=ByteArray(32)))}
            assertThrows(ApiFailure::class.java) {f.blobs.reserve(a.state.read()!!,BlobReservation(id="1".repeat(64),length=BlobPolicy.MAX_BYTES+1,digest=ByteArray(32),capabilityHash=ByteArray(32)))}
            repeat(2) {i->f.blobs.reserve(a.state.read()!!,BlobReservation(id=(i+1).toString().repeat(64),length=1,digest=ByteArray(32),capabilityHash=ByteArray(32)))}
            assertThrows(ApiFailure::class.java) {f.blobs.reserve(a.state.read()!!,BlobReservation(id="3".repeat(64),length=1,digest=ByteArray(32),capabilityHash=ByteArray(32)))}
            repeat(5) { try {f.blobs.download(a.state.read()!!,"f".repeat(64),null)}catch(_:ApiFailure){} }
            try {f.blobs.download(a.state.read()!!,"f".repeat(64),null);fail()}catch(e:ApiFailure){assertEquals(429,e.status)}
            f.time.millis+=BlobPolicy.PARTIAL_TTL; f.blobs.cleanup()
            assertEquals(0,f.db.transaction {f.db.blobs.size()})
        }
    }
    class Time:Clock() {
        var millis=System.currentTimeMillis()
        override fun getZone()=ZoneOffset.UTC
        override fun withZone(zone:ZoneId):Clock=this
        override fun instant()=Instant.ofEpochMilli(millis)
    }
    class Fixture:AutoCloseable {
        val time=Time(); val db=MemoryBackendDatabase()
        val service=MailboxService(db,time,rate=RateLimiter {_,_,_->true})
        val directory=Files.createTempDirectory("blob-fixture").toFile()
        val blobs=BlobService(db,service,File(directory,"server"),time,0)
        private val port=ServerSocket(0).use {it.localPort}
        val origin="http://127.0.0.1:$port"
        val server=ProductionHttpServer(service,{true},false,port,blobs).start()
        suspend fun person(name:String):Person {
            val person=Person(this,name); person.engine.createIdentity(name)
            val registration=person.state.registration(name,listOf(person.engine.publicBundle().publicData()))
            NetworkAccount(person.client,person.state).apply { register(registration); login(registration.accountId,registration.deviceId) }
            person.registration=registration; return person
        }
        override fun close() {server.close();blobs.close();directory.deleteRecursively()}
    }
    class Person(val fixture:Fixture,val name:String) {
        val records=MemoryRecords(); val engine=SignalProtocolEngine(records)
        val state=EndpointNetworkState(records,"ghostcloak.local")
        lateinit var registration:Registration
        val client=HttpGhostClient(fixture.origin,state,true)
        var allowed=true
        val bulk=StreamingBlobClient(fixture.origin,client,{allowed},{check(allowed)},true)
        val directory=File(fixture.directory,name)
        var store=AttachmentStore(records,directory)
    }
    @Test fun twoClientsEncryptedDescriptorAckDownloadReplayAndDelete()=runBlocking {
        Fixture().use { f ->
            val a=f.person("alice"); val b=f.person("bob")
            NetworkAccount(a.client,a.state).connect("bob",a.engine)
            val source=ByteArray(1200000) {(it*19).toByte()}
            val prepared=a.store.prepare(source.inputStream(),source.size.toLong(),AttachmentKind.DOCUMENT,30){true}
            val d=AttachmentDescriptor(id=prepared.id,capability=prepared.capability,key=prepared.key,digest=prepared.digest,
                plaintextLength=prepared.plaintextLength,paddedLength=prepared.paddedLength,ciphertextLength=prepared.ciphertextLength,
                kind=prepared.kind,filename="synthetic-private-name.bin",disappearingSeconds=prepared.disappearingSeconds)
            a.store=AttachmentStore(a.records,a.directory) // death after encryption
            a.store.upload(d.id,a.bulk){true}
            a.store=AttachmentStore(a.records,a.directory) // death after upload
            assertEquals(TransferState.UPLOADED,a.store.entry(d.id)!!.state)
            val transport=NetworkMailboxTransport(a.client,a.state)
            val payload=ConversationPayload.encodeAttachment(d)
            val packet=a.engine.encrypt(b.registration.deviceId,payload)
            transport.submit(packet.envelopeId,b.registration.deviceId,packet)
            val receiver=ConversationService(b.engine,LocalRepository(b.records)); receiver.open()
            val inbox=b.client.call(ApiRequest.Fetch(includeSenders=true)).deliveries.single()
            receiver.acceptNetwork(EnvelopeCodec.decode(inbox.encryptedEnvelope),inbox.sender)
            // Unknown sender descriptor is committed but no body transfer occurs.
            assertFalse(File(b.directory,"download").listFiles().orEmpty().any())
            b.client.call(ApiRequest.Ack(listOf(inbox.serverMessageId)))
            assertTrue(a.client.call(ApiRequest.Fetch(submissionIds=listOf(packet.envelopeId))).statuses.single().acknowledged)
            receiver.acceptRequest(a.registration.deviceId)
            val decoded=receiver.attachment(a.registration.deviceId,packet.envelopeId)!!
            assertEquals("synthetic-private-name.bin",decoded.filename)
            b.store.download(decoded,"received",b.bulk){true}.use { verified ->
                val output=ByteArrayOutputStream(); verified.copyTo(output); assertArrayEquals(source,output.toByteArray())
            }
            receiver.acceptNetwork(EnvelopeCodec.decode(inbox.encryptedEnvelope),inbox.sender)
            assertEquals(1,receiver.messages(a.registration.deviceId).size)
            val serverBytes=File(f.directory,"server/${d.id}").readBytes()
            assertFalse(serverBytes.toString(Charsets.ISO_8859_1).contains(source.copyOfRange(0,64).toString(Charsets.ISO_8859_1)))
            assertFalse(f.db.dump().toString(Charsets.ISO_8859_1).contains("synthetic-private-name.bin"))
            assertFalse(f.db.dump().toString(Charsets.ISO_8859_1).contains(d.key.toString(Charsets.ISO_8859_1)))
            b.store.remove(d.id)
            assertTrue(File(b.directory,"scratch").listFiles().orEmpty().isEmpty())
            assertTrue(File(b.directory,"download").listFiles().orEmpty().isEmpty())
        }
    }
    @Test fun capabilityOwnerAndFixedRetentionWithInterruptedUpload()=runBlocking {
        Fixture().use { f ->
            val a=f.person("alice"); val b=f.person("bob")
            val d=a.store.prepare(byteArrayOf(1,2,3).inputStream(),3,AttachmentKind.IMAGE,0){true}
            val reservation=BlobReservation(id=d.id,length=d.ciphertextLength,digest=d.digest,capabilityHash=DeviceAuth.digest(d.capability))
            f.blobs.reserve(a.state.read()!!,reservation)
            assertThrows(ApiFailure::class.java) { f.blobs.reserve(b.state.read()!!,reservation) }
            assertThrows(ApiFailure::class.java) { f.blobs.upload(a.state.read()!!,d.id,byteArrayOf(1).inputStream()) }
            assertFalse(File(f.directory,"server/${d.id}.partial").exists())
            a.store.upload(d.id,a.bulk){true}
            val expiry=f.blobs.reserve(a.state.read()!!,reservation).expiresAt
            f.time.millis+=1000
            a.bulk.upload(d,File(a.directory,"upload/${d.id}"))
            assertEquals(expiry,f.blobs.reserve(a.state.read()!!,reservation).expiresAt)
            assertThrows(ApiFailure::class.java) {f.blobs.download(b.state.read()!!,d.id,null)}
            assertThrows(ApiFailure::class.java) {f.blobs.download(b.state.read()!!,d.id,"0".repeat(64))}
            assertThrows(ApiFailure::class.java) {f.blobs.download("bad",d.id,null)}
            f.blobs.download(b.state.read()!!,d.id,d.capability.joinToString(""){"%02x".format(it)}).use { assertEquals(d.ciphertextLength,it.readBytes().size.toLong()) }
            f.time.millis=expiry; f.blobs.cleanup()
            assertFalse(File(f.directory,"server/${d.id}").exists())
            assertNull(f.db.transaction {f.db.blobs.get(d.id)})
        }
    }
    @Test fun journalLockCancellationAndRestartNeverExposeScratch()=runBlocking {
        Fixture().use {f->
            val a=f.person("alice")
            val d=a.store.prepare(byteArrayOf(1).inputStream(),1,AttachmentKind.IMAGE,0){true}
            a.store.upload(d.id,a.bulk){true}
            val verified=a.store.download(d,"message",a.bulk){a.allowed}
            a.allowed=false; a.store.invalidate()
            assertThrows(Exception::class.java) {verified.copyTo(ByteArrayOutputStream())}
            verified.close()
            a.store=AttachmentStore(a.records,a.directory)
            assertTrue(File(a.directory,"scratch").listFiles().orEmpty().isEmpty())
            try {a.store.upload(d.id,a.bulk){a.allowed};fail()}catch(_:Exception){}
        }
    }
}
