package org.ghostcloak.testing

import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.ghostcloak.crypto.*
import org.ghostcloak.identity.RandomIdentifiers
import org.ghostcloak.messaging.*
import org.ghostcloak.protocol.*
import org.ghostcloak.transport.IdempotentMessageTransport
import org.junit.Assert.*
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

class ProfileV2Test {
    private fun jpeg(side:Int=128,quality:Float=0.45f):ByteArray {
        val image=BufferedImage(side,side,BufferedImage.TYPE_INT_RGB)
        for(y in 0 until side) for(x in 0 until side) image.setRGB(x,y,
            (((x*37+y*19) and 255) shl 16) or (((x*11+y*23) and 255) shl 8) or ((x*17+y*13) and 255))
        val out=ByteArrayOutputStream()
        val writer=ImageIO.getImageWritersByFormatName("jpeg").next()
        writer.output=ImageIO.createImageOutputStream(out)
        writer.write(null,javax.imageio.IIOImage(image,null,null),writer.defaultWriteParam.apply {
            compressionMode=ImageWriteParam.MODE_EXPLICIT;compressionQuality=quality
        })
        writer.dispose();return out.toByteArray()
    }
    private class Wire:IdempotentMessageTransport {
        var offline=false
        val sent=mutableListOf<EncryptedEnvelope>()
        override fun receive()=emptyFlow<EncryptedEnvelope>()
        override suspend fun send(routingDestination:String,envelope:EncryptedEnvelope) {}
        override suspend fun submit(submissionId:String,routingDestination:String,envelope:EncryptedEnvelope):String {
            if(offline) throw ApiFailure(503,"offline")
            sent.add(envelope);return submissionId
        }
    }
    private class Pair {
        val ar=MemoryRecords();val br=MemoryRecords()
        val ae=SignalProtocolEngine(ar);val be=SignalProtocolEngine(br)
        val a=ConversationService(ae,LocalRepository(ar));val b=ConversationService(be,LocalRepository(br))
        val wire=Wire();val out=DurableOutbox(br,be,wire);val outA=DurableOutbox(ar,ae,wire)
        lateinit var aid:String;lateinit var bid:String
        suspend fun prepare() {
            aid=a.create("Alice").deviceId;bid=b.create("Bob").deviceId
            a.importCard(b.exportCard())
            val first=ae.encrypt(bid,ConversationPayload.encode("Hello",0,displayName="Alice"))
            b.acceptNetwork(first,SenderProfile(ar.read("local/user")!!.decodeToString(),aid,
                RandomIdentifiers.create(),"7K4M9Q2FX8DR"))
        }
        suspend fun accept() {b.acceptRequest(aid,out);b.retryNetwork(out);b.retryNetwork(out)}
    }
    @Test fun budgetAndMalformedInputs() {
        val image=jpeg()
        assertTrue(image.size<=ProfileRules.MAX_PHOTO_BYTES)
        ProfileRules.photo(image)
        val nearCap=(listOf(128,160,192,224,256).flatMap {side ->
            listOf(0.35f,0.45f,0.55f,0.65f,0.75f,0.85f).map {jpeg(side,it)}
        }).filter {it.size<=ProfileRules.MAX_PHOTO_BYTES}.maxBy {it.size}
        assertTrue("No valid near-cap photo",nearCap.size>=7000)
        ProfileRules.photo(nearCap)
        assertTrue(ConversationPayload.encodeProfile(ProfileUpdate(1,"Alice","About",nearCap,true)).size<=ProfileRules.MAX_PADDED_UPDATE)
        val update=ConversationPayload.encodeProfile(ProfileUpdate(1,"Alice","About",image,true))
        assertTrue(update.size<=ProfileRules.MAX_PADDED_UPDATE)
        assertTrue(EnvelopeCodec.MAX_BODY-update.size>=7680)
        assertEquals(image.size,ConversationPayload.decode(update).profile!!.photo!!.size)
        assertThrows(IllegalArgumentException::class.java) {ProfileRules.photo(ByteArray(ProfileRules.MAX_PHOTO_BYTES+1))}
        assertThrows(IllegalArgumentException::class.java) {ProfileRules.photo(byteArrayOf(1,2,3))}
        val truncatedMarker=image.copyOfRange(0,128).also {it[126]=0xff.toByte();it[127]=0xff.toByte()}
        assertThrows(IllegalArgumentException::class.java) {ProfileRules.photo(truncatedMarker)}
        val exif=image.copyOfRange(0,2)+byteArrayOf(0xff.toByte(),0xe1.toByte(),0,4,1,2)+image.copyOfRange(2,image.size)
        assertThrows(IllegalArgumentException::class.java) {ProfileRules.photo(exif)}
        assertEquals("",ProfileRules.about("  "))
        assertThrows(IllegalArgumentException::class.java) {ProfileRules.about("bad\ntext")}
    }
    @Test fun acceptedOnlyEncryptedOrderingRemovalAndAlias()=runBlocking {
        val p=Pair();p.prepare()
        val photo=jpeg()
        p.b.setAbout("  Private about  ");p.b.setProfilePhoto(photo)
        assertNull(LocalRepository(p.br).remoteProfile(p.aid))
        assertFalse(p.b.contacts().single().sharedPhoto!=null)
        p.accept()
        val encrypted=p.wire.sent.single()
        val request=NetworkCodec.encode(ApiRequest.Send(RandomIdentifiers.create(),RandomIdentifiers.create(),EnvelopeCodec.encode(encrypted)))
        assertFalse(request.toString(Charsets.ISO_8859_1).contains("Private about"))
        assertFalse(request.toString(Charsets.ISO_8859_1).contains("Bob"))
        p.a.acceptNetwork(encrypted)
        val received=p.a.contacts().single()
        assertEquals("Private about",received.sharedAbout)
        assertArrayEquals(photo,received.sharedPhoto)
        LocalRepository(p.ar).localAlias(p.bid,"Local friend")
        assertEquals("Local friend",p.a.contacts().single().contact.visibleName)
        val revision=p.b.localProfile().revision
        p.a.acceptNetwork(p.be.encrypt(p.aid,ConversationPayload.encodeProfile(ProfileUpdate(revision-1,"Old","Old",null,true))))
        assertEquals("Private about",p.a.contacts().single().sharedAbout)
        p.a.acceptNetwork(p.be.encrypt(p.aid,ConversationPayload.encodeProfile(ProfileUpdate(revision,"Old","Old",null,true))))
        assertArrayEquals(photo,p.a.contacts().single().sharedPhoto)
        p.a.acceptNetwork(p.be.encrypt(p.aid,ConversationPayload.encodeProfile(ProfileUpdate(revision+1,"Bob","",null,true))))
        assertNull(p.a.contacts().single().sharedPhoto)
        assertNull(p.a.contacts().single().sharedAbout)
        assertEquals("Local friend",p.a.contacts().single().contact.visibleName)
        p.a.block(p.bid,true)
        p.a.acceptNetwork(p.be.encrypt(p.aid,ConversationPayload.encodeProfile(ProfileUpdate(revision+2,"Bob","new",photo,true))))
        assertNull(LocalRepository(p.ar).remoteProfile(p.bid))
        p.a.setAbout("after block")
        assertTrue(LocalRepository(p.ar).profileIntents().isEmpty())
    }
    @Test fun requestAndOfflineAcceptanceKeepProfilePrivateUntilDelivery()=runBlocking {
        val p=Pair();p.prepare();p.b.setAbout("Secret")
        assertNull(p.b.contacts().single().sharedAbout)
        p.wire.offline=true;p.b.acceptRequest(p.aid,p.out)
        try {p.b.retryNetwork(p.out)} catch (_:ApiFailure) {}
        assertTrue(p.wire.sent.isEmpty())
        p.wire.offline=false;p.b.retryNetwork(p.out);p.b.retryNetwork(p.out)
        assertEquals(1,p.wire.sent.size)
        p.a.acceptNetwork(p.wire.sent.single())
        assertEquals("Secret",p.a.contacts().single().sharedAbout)
    }
    @Test fun initiatorEditWaitsUntilPeerAcceptsRequest()=runBlocking {
        val p=Pair();p.prepare()
        p.a.setAbout("Only after acceptance")
        assertTrue(LocalRepository(p.ar).profileIntents().isEmpty())
        assertNull(LocalRepository(p.br).remoteProfile(p.aid))
        p.accept()
        p.a.acceptNetwork(p.wire.sent.single())
        assertTrue(LocalRepository(p.ar).profileIntents().contains(p.bid))
    }
    @Test fun reciprocalProfileArrivesOnlyAfterAcceptedExchange()=runBlocking {
        val p=Pair();p.prepare()
        p.a.setAbout("Alice private About")
        p.b.setAbout("Bob private About")
        assertTrue(p.wire.sent.isEmpty())
        p.accept()
        assertEquals(1,p.wire.sent.size)
        p.a.acceptNetwork(p.wire.sent.single())
        assertEquals("Bob private About",p.a.contacts().single().sharedAbout)
        p.a.retryNetwork(p.outA);p.a.retryNetwork(p.outA)
        assertEquals(2,p.wire.sent.size)
        p.b.acceptNetwork(p.wire.sent.last())
        assertEquals("Alice private About",p.b.contacts().single().sharedAbout)
    }
    @Test fun supersededOfflineProfilesAreCancelledBeforeUpload()=runBlocking {
        val p=Pair();p.prepare()
        p.wire.offline=true
        p.b.acceptRequest(p.aid,p.out)
        p.b.setAbout("first")
        p.b.retryNetwork(p.out)
        p.b.setAbout("final")
        p.wire.offline=false
        p.b.retryNetwork(p.out);p.b.retryNetwork(p.out)
        assertEquals(1,p.wire.sent.size)
        p.a.acceptNetwork(p.wire.sent.single())
        assertEquals("final",p.a.contacts().single().sharedAbout)
    }
    @Test fun optOutDoesNotSendAnotherLegacyNameAndModernPeerReceivesRemoval()=runBlocking {
        val p=Pair();p.prepare()
        p.b.setProfileSharing(false)
        p.b.acceptRequest(p.aid,p.out);p.b.retryNetwork(p.out);p.b.retryNetwork(p.out)
        assertEquals(1,p.wire.sent.size)
        p.a.acceptNetwork(p.wire.sent.single())
        assertEquals("",p.a.contacts().single().contact.displayName)
        assertNull(p.a.contacts().single().sharedAbout)
        val legacy=Pair();legacy.prepare()
        LocalRepository(legacy.br).profilePeer(legacy.aid,false)
        legacy.b.setProfileSharing(false)
        legacy.b.acceptRequest(legacy.aid,legacy.out)
        legacy.b.retryNetwork(legacy.out)
        assertTrue(legacy.wire.sent.isEmpty())
    }
    @Test fun oldPeerReceivesOnlyLegacyNameAndNotPhotoOrAbout()=runBlocking {
        val p=Pair();p.prepare()
        LocalRepository(p.br).profilePeer(p.aid,false)
        p.b.setAbout("Not for old peer")
        p.b.setProfilePhoto(jpeg())
        p.b.acceptRequest(p.aid,p.out);p.b.retryNetwork(p.out);p.b.retryNetwork(p.out)
        assertEquals(1,p.wire.sent.size)
        val decrypted=p.ae.decrypt(p.wire.sent.single())
        val content=ConversationPayload.decode(decrypted)
        assertTrue(content.profileUpdate)
        assertNull(content.profile)
        assertEquals("Bob",content.displayName)
        assertFalse(decrypted.toString(Charsets.ISO_8859_1).contains("Not for old peer"))
    }
}
