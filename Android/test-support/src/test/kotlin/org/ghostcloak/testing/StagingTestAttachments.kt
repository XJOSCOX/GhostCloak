package org.ghostcloak.testing

import kotlinx.coroutines.runBlocking
import org.ghostcloak.crypto.*
import org.ghostcloak.messaging.*
import org.ghostcloak.protocol.*
import org.ghostcloak.transport.*
import org.ghostcloak.attachments.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.URI
import java.nio.file.Files
import java.util.UUID

/** Explicit staging-only acceptance: two disposable accounts, two encrypted synthetic blobs. */
class StagingTestAttachments {
    @Test fun photoAndDocumentOverDeployedHttps()=runBlocking {
        check(System.getenv("GHOSTCLOAK_STAGING_CONFIRM")=="isolated-staging")
        val origin=System.getenv("GHOSTCLOAK_STAGING_ORIGIN") ?: error("Staging origin required")
        check(URI(origin).scheme=="https" && URI(origin).host=="api.ghostcloak.org")
        val root=Files.createTempDirectory("ghostcloak-staging-attachments").toFile()
        class Person(val slot:String) {
            val records=MemoryRecords();val engine=SignalProtocolEngine(records)
            val state=EndpointNetworkState(records,URI(origin).host)
            val client=HttpGhostClient(origin,state)
            val repository=LocalRepository(records)
            val service=ConversationService(engine,repository)
            val store=AttachmentStore(records,File(root,slot))
            val bulk=StreamingBlobClient(origin,client,{state.read()!=null},{check(state.read()!=null)})
            val outbox=DurableOutbox(records,engine,NetworkMailboxTransport(client,state))
            lateinit var registration:Registration
            suspend fun start() {
                val name="test_"+UUID.randomUUID().toString().replace("-","").take(16)
                engine.createIdentity(name)
                registration=state.registration(name,listOf(engine.publicBundle().publicData()))
                NetworkAccount(client,state).run { register(registration);login(registration.accountId,registration.deviceId) }
                service.open()
            }
            suspend fun receive():Delivery {
                val incoming=client.call(ApiRequest.Fetch(includeSenders=true)).deliveries.single()
                incoming.sender?.let(state::remember)
                service.acceptNetwork(EnvelopeCodec.decode(incoming.encryptedEnvelope),incoming.sender)
                client.call(ApiRequest.Ack(listOf(incoming.serverMessageId)))
                return incoming
            }
        }
        val a=Person("a");val b=Person("b")
        val manifest=mutableListOf<String>()
        try {
            a.start();b.start()
            NetworkAccount(a.client,a.state).connect(b.registration.username,a.engine)
            a.repository.save(Contact("test-contact",b.registration.accountId,b.registration.username,b.registration.deviceId))
            a.service.sendNetwork(b.registration.deviceId,"Hi",a.outbox)
            b.receive();b.service.acceptRequest(a.registration.deviceId)
            assertTrue(b.service.attachmentPeer(a.registration.deviceId))
            b.service.sendNetwork(a.registration.deviceId,"Hi",b.outbox);a.receive()
            assertTrue(a.service.attachmentPeer(b.registration.deviceId))
            val image=java.awt.image.BufferedImage(16,16,java.awt.image.BufferedImage.TYPE_INT_RGB)
            val photo=ByteArrayOutputStream().also { javax.imageio.ImageIO.write(image,"jpeg",it) }.toByteArray()
            val document="GhostCloak synthetic attachment privacy fixture\n".toByteArray()+ByteArray(1024){it.toByte()}
            for((kind,bytes) in listOf(AttachmentKind.IMAGE to photo,AttachmentKind.DOCUMENT to document)) {
                val descriptor=a.store.prepare(bytes.inputStream(),bytes.size.toLong(),kind,0,
                    if(kind==AttachmentKind.DOCUMENT) "synthetic-private-document.bin" else null){true}
                a.store.upload(descriptor.id,a.bulk){true}
                val sent=a.service.sendAttachment(b.registration.deviceId,descriptor,a.outbox,a.service.attachmentPeer(b.registration.deviceId)) {
                    a.store.bind(descriptor.id,"${it.conversationId}/${it.localId}")
                }
                assertEquals(MessageState.SERVER_ACCEPTED,sent.state)
                val received=b.receive()
                val summary=b.service.messagesForUi(a.registration.deviceId).last().attachment!!
                assertEquals(kind==AttachmentKind.IMAGE,summary.photo)
                assertFalse(File(root,"b/download/${descriptor.id}").exists())
                val message=EnvelopeCodec.decode(received.encryptedEnvelope).envelopeId
                b.store.download(b.service.attachment(a.registration.deviceId,message)!!,"${a.registration.deviceId}/$message",b.bulk){true}.use {
                    val copy=ByteArrayOutputStream();it.copyTo(copy);assertArrayEquals(bytes,copy.toByteArray())
                }
                a.service.deliveryStatuses(a.client.call(ApiRequest.Fetch(submissionIds=listOf(sent.localId))).statuses)
                assertEquals(MessageState.DELIVERED,a.service.messages(b.registration.deviceId).last().state)
                val digest=descriptor.digest.joinToString(""){"%02x".format(it)}
                manifest += "${descriptor.id} $digest ${descriptor.ciphertextLength}"
            }
            // Optional private audit manifest contains only synthetic blob IDs/ciphertext digests/lengths.
            System.getenv("GHOSTCLOAK_STAGING_BLOB_AUDIT")?.let { File(it).writeText(manifest.joinToString("\n")) }
        } finally {
            for(person in listOf(a,b)) if(person.state.read()!=null) NetworkAccount(person.client,person.state).logout()
            root.deleteRecursively()
        }
        Unit
    }
}
