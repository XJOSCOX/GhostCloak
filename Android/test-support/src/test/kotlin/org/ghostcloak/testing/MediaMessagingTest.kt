package org.ghostcloak.testing

import kotlinx.coroutines.runBlocking
import org.ghostcloak.attachments.*
import org.ghostcloak.crypto.*
import org.ghostcloak.messaging.*
import org.ghostcloak.protocol.*
import org.ghostcloak.transport.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

class MediaMessagingTest {
    @Test fun voiceDescriptorCaptionAndLegacyAttachmentPayloads() {
        val directory=java.nio.file.Files.createTempDirectory("media-payload").toFile()
        try {
            val source=ByteArray(8192) {it.toByte()}
            val raw=AttachmentFormat.encrypt(source.inputStream(),File(directory,"ciphertext"),source.size.toLong(),AttachmentKind.VOICE_NOTE)
            val voice=AttachmentDescriptor(id=raw.id,capability=raw.capability,key=raw.key,digest=raw.digest,
                plaintextLength=raw.plaintextLength,paddedLength=raw.paddedLength,ciphertextLength=raw.ciphertextLength,
                kind=AttachmentKind.VOICE_NOTE,durationMillis=2100)
            val encoded=ConversationPayload.encodeAttachment(voice,caption="A quiet note")
            val decoded=ConversationPayload.decode(encoded)
            assertEquals("A quiet note",decoded.body)
            assertEquals(2100L,AttachmentFormat.decode(decoded.attachment!!).durationMillis)
            assertFalse(File(directory,"ciphertext").readBytes().toString(Charsets.ISO_8859_1).contains("A quiet note"))
            val legacy=AttachmentDescriptor(id=raw.id,capability=raw.capability,key=raw.key,digest=raw.digest,
                plaintextLength=raw.plaintextLength,paddedLength=raw.paddedLength,ciphertextLength=raw.ciphertextLength,
                kind=AttachmentKind.IMAGE)
            assertEquals("",ConversationPayload.decode(ConversationPayload.encodeAttachment(legacy)).body)
            assertEquals("Photo caption",ConversationPayload.decode(ConversationPayload.encodeAttachment(legacy,caption="Photo caption")).body)
            val document=AttachmentDescriptor(id=raw.id,capability=raw.capability,key=raw.key,digest=raw.digest,
                plaintextLength=raw.plaintextLength,paddedLength=raw.paddedLength,ciphertextLength=raw.ciphertextLength,
                kind=AttachmentKind.DOCUMENT,filename=AttachmentFormat.sanitizeFilename("../../private.txt"))
            assertFalse(document.filename!!.contains('/'))
            assertEquals("Document caption",ConversationPayload.decode(ConversationPayload.encodeAttachment(document,caption="Document caption")).body)
            assertThrows(IllegalArgumentException::class.java) {ConversationPayload.encodeAttachment(legacy,viewOnce=true,caption="secret")}
            assertThrows(AppFailure::class.java) {ConversationPayload.validateCaption("bad\u0000caption")}
        } finally {directory.deleteRecursively()}
    }

    @Test fun voiceSendReceiveUsesEncryptedBlobAndPrivateCaption()=runBlocking {
        AttachmentTest.Fixture().use {f->
            val a=f.person("alice");val b=f.person("bob")
            NetworkAccount(a.client,a.state).connect(b.state.ghostCloakId(),a.engine)
            val ar=LocalRepository(a.records);val br=LocalRepository(b.records)
            ar.save(Contact("contact",b.registration.accountId,"Bob",b.registration.deviceId))
            val sender=ConversationService(a.engine,ar);sender.open()
            val receiver=ConversationService(b.engine,br);receiver.open()
            val aOutbox=DurableOutbox(a.records,a.engine,NetworkMailboxTransport(a.client,a.state))
            sender.sendNetwork(b.registration.deviceId,"hello",aOutbox)
            val first=b.client.call(ApiRequest.Fetch(includeSenders=true)).deliveries.single()
            first.sender?.let(b.state::remember)
            receiver.acceptNetwork(EnvelopeCodec.decode(first.encryptedEnvelope),first.sender)
            receiver.acceptRequest(a.registration.deviceId)
            b.client.call(ApiRequest.Ack(listOf(first.serverMessageId)))
            val bOutbox=DurableOutbox(b.records,b.engine,NetworkMailboxTransport(b.client,b.state))
            val reply=receiver.sendNetwork(a.registration.deviceId,"hello back",bOutbox)
            assertEquals(MessageState.SERVER_ACCEPTED,reply.state)
            val second=a.client.call(ApiRequest.Fetch(includeSenders=true)).deliveries.single()
            sender.acceptNetwork(EnvelopeCodec.decode(second.encryptedEnvelope),second.sender)
            a.client.call(ApiRequest.Ack(listOf(second.serverMessageId)))
            assertTrue(sender.mediaPeer(b.registration.deviceId))
            val audio=ByteArray(64000) {(it*13).toByte()}
            val descriptor=a.store.prepare(audio.inputStream(),audio.size.toLong(),AttachmentKind.VOICE_NOTE,0,
                durationMillis=1500){true}
            a.store.upload(descriptor.id,a.bulk){true}
            val sent=sender.sendAttachment(b.registration.deviceId,descriptor,aOutbox,true,caption="meet me later")
            val delivery=b.client.call(ApiRequest.Fetch(includeSenders=true)).deliveries.single()
            val networkBytes=delivery.encryptedEnvelope.toString(Charsets.ISO_8859_1)
            assertFalse(networkBytes.contains("meet me later"))
            assertFalse(networkBytes.contains("VOICE_NOTE"))
            receiver.acceptNetwork(EnvelopeCodec.decode(delivery.encryptedEnvelope),delivery.sender)
            val shown=receiver.messagesForUi(a.registration.deviceId).last()
            assertEquals("meet me later",shown.attachment?.caption)
            assertEquals(AttachmentKind.VOICE_NOTE,shown.attachment?.kind)
            assertEquals(1500L,shown.attachment?.durationMillis)
            assertTrue(ConversationSearch.matches(listOf(shown),"later",true).isNotEmpty())
            assertEquals("meet me later",ReplyPresentation.preview(ReplyReference(sent.envelopeId!!,ReplyKind.ATTACHMENT),
                a.registration.deviceId,listOf(shown)))
            val saved=receiver.attachment(a.registration.deviceId,shown.localId)!!
            b.store.download(saved,"voice",b.bulk){true}.use {verified ->
                val plain=ByteArrayOutputStream();verified.copyTo(plain);assertArrayEquals(audio,plain.toByteArray())
            }
        }
    }

    @Test fun mediaCapabilityIsSeparateFromLegacyAttachmentSupport()=runBlocking {
        val records=MemoryRecords();val repository=LocalRepository(records)
        val id=UUID.randomUUID().toString()
        assertFalse(repository.mediaPeer(id))
        repository.attachmentPeer(id,true)
        assertFalse(repository.mediaPeer(id))
        val text=ConversationPayload.decode(ConversationPayload.encode("hello",0,displayName="Alice"))
        assertTrue(text.supportsAttachments)
        assertTrue(text.supportsMedia)
        repository.mediaPeer(id,text.supportsMedia)
        assertTrue(LocalRepository(records).mediaPeer(id))
        repository.resetCapabilities(id)
        assertFalse(repository.mediaPeer(id))
    }
    @Test fun voiceCannotBeSentToPeerWithoutAuthenticatedMediaAdvertisement()=runBlocking {
        AttachmentTest.Fixture().use {f ->
            val a=f.person("alice");val b=f.person("bob")
            NetworkAccount(a.client,a.state).connect(b.state.ghostCloakId(),a.engine)
            val repository=LocalRepository(a.records)
            repository.save(Contact("contact",b.registration.accountId,"Bob",b.registration.deviceId))
            val sender=ConversationService(a.engine,repository);sender.open()
            val data=byteArrayOf(1,2,3,4)
            val descriptor=a.store.prepare(data.inputStream(),4,AttachmentKind.VOICE_NOTE,0,durationMillis=1000){true}
            val outbox=DurableOutbox(a.records,a.engine,NetworkMailboxTransport(a.client,a.state))
            assertThrows(IllegalArgumentException::class.java) {runBlocking {
                sender.sendAttachment(b.registration.deviceId,descriptor,outbox,true)
            }}
            assertTrue(sender.messages(b.registration.deviceId).isEmpty())
        }
    }

    @Test fun disappearingVoiceRemovesCaptionAndDescriptor() {
        val records=MemoryRecords()
        var wall=1_000_000L;var elapsed=1_000_000L
        val clock=ExpiryClock({wall},{elapsed},{1})
        val repository=LocalRepository(records,clock)
        val id=UUID.randomUUID().toString();val local=UUID.randomUUID().toString()
        val directory=java.nio.file.Files.createTempDirectory("expiring-voice").toFile()
        try {
            val data=byteArrayOf(1,2,3,4)
            val raw=AttachmentFormat.encrypt(data.inputStream(),File(directory,"ciphertext"),4,AttachmentKind.VOICE_NOTE,30)
            val descriptor=AttachmentDescriptor(id=raw.id,capability=raw.capability,key=raw.key,digest=raw.digest,
                plaintextLength=raw.plaintextLength,paddedLength=raw.paddedLength,ciphertextLength=raw.ciphertextLength,
                kind=AttachmentKind.VOICE_NOTE,durationMillis=1000,disappearingSeconds=30)
            repository.save(Message(local,id,Direction.INCOMING,"temporary caption",wall,MessageState.RECEIVED,local,
                disappearingSeconds=30,expiry=ExpiryDeadline.start(30,clock.now())))
            repository.attachment(id,local,AttachmentFormat.encode(descriptor))
            wall+=31_000;elapsed+=31_000
            assertEquals(1,repository.expire())
            assertTrue(repository.messages(id).isEmpty())
            assertNull(repository.attachment(id,local))
        } finally {directory.deleteRecursively()}
    }
}
