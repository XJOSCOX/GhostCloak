package org.ghostcloak.testing

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.ghostcloak.crypto.*
import org.ghostcloak.identity.*
import org.ghostcloak.protocol.*
import org.ghostcloak.transport.*
import org.ghostcloak.backend.OpaqueMailbox
import org.junit.Assert.*
import org.junit.Test
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage

class AcceptanceTest {
    private class Pairing {
        val aStore = MemoryRecords(); val bStore = MemoryRecords()
        val a = SignalProtocolEngine(aStore); val b = SignalProtocolEngine(bStore)
        lateinit var alice: DeviceIdentity; lateinit var bob: DeviceIdentity
        suspend fun setup() { alice = a.createIdentity("Alice"); bob = b.createIdentity("Bob"); a.establishSession(b.publicBundle()) }
        suspend fun packet(body: String = "hello bob") = a.encrypt(bob.deviceId, body.encodeToByteArray())
    }
    private fun altered(e: EncryptedEnvelope, bytes: ByteArray = e.encryptedPayload,
        sender: String = e.senderDeviceId, recipient: String = e.recipientDeviceId,
        id: String = e.envelopeId, type: Int = e.messageType, version: Int = e.protocolVersion) =
        EncryptedEnvelope(version, id, sender, recipient, type, bytes)
    private suspend fun rejected(expected: CryptoError? = null, action: suspend () -> Unit) {
        try { action(); fail("Expected security rejection") }
        catch (failure: CryptoFailure) { if (expected != null) assertEquals(expected, failure.error) }
    }

    @Test fun aliceBobCharlieDemo() = runBlocking {
        val p = Pairing().apply { setup() }
        val charlie = SignalProtocolEngine(MemoryRecords()); charlie.createIdentity("Charlie")
        assertFalse(p.alice.publicKey.contentEquals(p.bob.publicKey))
        val packet = p.packet()
        val transport = MockEncryptedTransport()
        transport.send(p.bob.deviceId, packet)
        val received = transport.receive().first()
        val mailbox = OpaqueMailbox()
        mailbox.storeEncryptedEnvelope(p.bob.deviceId, EnvelopeCodec.encode(received))
        val wire = mailbox.takeEncryptedEnvelope(p.bob.deviceId)!!
        assertFalse(wire.toString(Charsets.ISO_8859_1).contains("hello bob"))
        rejected(CryptoError.WrongRecipient) { charlie.decrypt(packet) }
        val plain = p.b.decrypt(EnvelopeCodec.decode(wire))
        assertEquals("hello bob", plain.decodeToString())
        println("Alice plaintext (synthetic fixture):\nhello bob")
        println(received)
        println("Ciphertext size: ${received.encryptedPayload.size} bytes")
        println("Bob plaintext (synthetic fixture):\n${plain.decodeToString()}")
        plain.fill(0)
        rejected(CryptoError.Replay) { p.b.decrypt(packet) }
        assertFalse(OpaqueMailbox::class.java.methods.any { it.name.contains("decrypt", true) })
        val code = p.a.getRemoteFingerprint(p.bob.deviceId)
        assertEquals(code, p.b.getRemoteFingerprint(p.alice.deviceId))
        println("Alice ↔ Bob security code (synthetic identities):\n$code")
    }
    @Test fun modifiedTruncatedAndAppendedCiphertextFailWithoutConsumingState() = runBlocking {
        val p = Pairing().apply { setup() }; val packet = p.packet()
        val variants = listOf(packet.encryptedPayload.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() },
            packet.encryptedPayload.copyOf(packet.encryptedPayload.size - 8), packet.encryptedPayload + byteArrayOf(0x7f, 0x33, 0x01))
        for (bytes in variants) rejected { p.b.decrypt(altered(packet, bytes)) }
        assertEquals("hello bob", p.b.decrypt(packet).decodeToString())
    }
    @Test fun routingAndVersionTamperingFails() = runBlocking {
        val p = Pairing().apply { setup() }; val packet = p.packet()
        rejected { p.b.decrypt(altered(packet, sender = RandomIdentifiers.create())) }
        rejected { p.b.decrypt(altered(packet, recipient = RandomIdentifiers.create())) }
        rejected { p.b.decrypt(altered(packet, id = RandomIdentifiers.create())) }
        rejected { p.b.decrypt(altered(packet, type = 2)) }
        rejected { p.b.decrypt(altered(packet, version = 2)) }
        assertEquals("hello bob", p.b.decrypt(packet).decodeToString())
    }
    @Test fun wrongRecipientCannotBypassRoutingGuard() = runBlocking {
        val p = Pairing().apply { setup() }
        val c = SignalProtocolEngine(MemoryRecords()); val charlie = c.createIdentity("Charlie")
        val packet = p.packet()
        rejected { c.decrypt(altered(packet, recipient = charlie.deviceId)) }
    }
    @Test fun identityReplacementIsRejectedAndOriginalPinSurvives() = runBlocking {
        val p = Pairing().apply { setup() }
        p.b.decrypt(p.packet())
        val code = p.a.getRemoteFingerprint(p.bob.deviceId)
        val replacement = SignalProtocolEngine(MemoryRecords()); replacement.createIdentity("Bob")
        val b = replacement.publicBundle()
        val spoofed = RemoteKeyBundle(p.bob.deviceId, b.registrationId, b.identity, b.preKeyId, b.preKey,
            b.signedId, b.signedKey, b.signature, b.kyberId, b.kyberKey, b.kyberSignature)
        rejected(CryptoError.IdentityChanged) { p.a.establishSession(spoofed) }
        assertEquals(SecurityEvent.RemoteIdentityChanged(p.bob.deviceId), p.a.events.first())
        assertEquals(code, p.a.getRemoteFingerprint(p.bob.deviceId))
    }
    @Test fun badPrekeySignatureRejected() = runBlocking {
        val p = Pairing().apply { setup() }; val b = p.b.publicBundle()
        b.signature[0] = (b.signature[0].toInt() xor 1).toByte()
        rejected(CryptoError.AuthenticationFailed) { p.a.establishSession(b) }
    }
    @Test fun countersAdvanceAndOutOfOrderMessagesWork() = runBlocking {
        val p = Pairing().apply { setup() }
        val packets = (1..3).map { p.packet("M$it") }
        // Inspect public protocol counters only; no secret-key test hook or logging.
        val counters = packets.map { PreKeySignalMessage(it.encryptedPayload).whisperMessage.counter }
        assertEquals(listOf(0, 1, 2), counters)
        assertEquals("M3", p.b.decrypt(packets[2]).decodeToString())
        assertEquals("M1", p.b.decrypt(packets[0]).decodeToString())
        assertEquals("M2", p.b.decrypt(packets[1]).decodeToString())
        rejected(CryptoError.Replay) { p.b.decrypt(packets[0]) }
        val reply = p.b.encrypt(p.alice.deviceId, "ack".encodeToByteArray())
        p.a.decrypt(reply)
        val next = p.packet("M4")
        assertEquals(2, next.messageType)
        assertFalse(PreKeySignalMessage(packets[0].encryptedPayload).whisperMessage.senderRatchetKey.serialize()
            .contentEquals(SignalMessage(next.encryptedPayload).senderRatchetKey.serialize()))
        assertEquals("M4", p.b.decrypt(next).decodeToString())
    }
    @Test fun restartPreservesReplayAndIdentity() = runBlocking {
        val p = Pairing().apply { setup() }; val packet = p.packet(); p.b.decrypt(packet)
        val restarted = SignalProtocolEngine(p.bStore)
        assertEquals(p.bob.deviceId, restarted.createIdentity("Bob").deviceId)
        rejected(CryptoError.Replay) { restarted.decrypt(packet) }
        assertEquals("next", restarted.decrypt(p.packet("next")).decodeToString())
    }
    @Test fun destructionRejectsSessionResurrection() = runBlocking {
        val p = Pairing().apply { setup() }; val packet = p.packet(); p.b.decrypt(packet)
        p.b.destroySession(p.alice.deviceId)
        assertTrue(p.bStore.keys("session/").isEmpty())
        rejected(CryptoError.UnknownSession) { p.b.decrypt(packet) }
        rejected(CryptoError.UnknownSession) { p.b.decrypt(p.packet("later")) }
    }
    @Test fun failedCommitReleasesNoPlaintextAndDoesNotConsumePacket() = runBlocking {
        val p = Pairing().apply { setup() }; val packet = p.packet()
        p.bStore.failCommit = true
        try { p.b.decrypt(packet); fail() } catch (expected: IllegalStateException) { /* storage failure propagates */ }
        p.bStore.failCommit = false
        assertEquals("hello bob", p.b.decrypt(packet).decodeToString())
    }
    @Test fun wireParserRejectsTrailingAndOversizedInput() = runBlocking {
        val p = Pairing().apply { setup() }; val wire = EnvelopeCodec.encode(p.packet())
        for (bad in listOf(wire + byteArrayOf(1), wire.copyOf(10), ByteArray(EnvelopeCodec.MAX_PACKET + 1))) {
            try { EnvelopeCodec.decode(bad); fail() } catch (expected: IllegalArgumentException) { }
        }
    }
}
