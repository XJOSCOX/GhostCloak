package org.ghostcloak.testing

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.ghostcloak.crypto.*
import org.ghostcloak.identity.*
import org.ghostcloak.messaging.*
import org.ghostcloak.transport.*
import org.junit.Assert.*
import org.junit.Test

class MessagingTest {
    private fun service(r: MemoryRecords = MemoryRecords()) = ConversationService(SignalProtocolEngine(r), LocalRepository(r))
    @Test fun verifiedReplacementDisablesSendingUntilExplicitApproval() = runBlocking<Unit> {
        val ar = MemoryRecords(); val a = service(ar); val b = service()
        a.create("Alice"); val bob = b.create("Bob"); a.importCard(b.exportCard())
        val old = a.fingerprint(bob.deviceId); a.verify(bob.deviceId, old)
        val replacement = service(); replacement.create("Bob")
        val fresh = ContactCardCodec.decode(replacement.exportCard())
        val changed = ContactCardCodec.encode(ContactCard(1, bob.userId, "Bob", bob.deviceId, fresh.registrationId,
            fresh.identity, fresh.preKeyId, fresh.preKey, fresh.signedId, fresh.signedKey, fresh.signature,
            fresh.kyberId, fresh.kyberKey, fresh.kyberSignature))
        try { a.importCard(changed); fail("Accepted replacement") } catch (e: CryptoFailure) { assertEquals(CryptoError.IdentityChanged, e.error) }
        val reopened = service(ar); reopened.open()
        assertEquals(IdentityTrustState.CHANGED, reopened.contacts().single().identity!!.trustState)
        assertEquals(IdentityTrustState.VERIFIED, reopened.contacts().single().identity!!.previousTrustState)
        try { reopened.send(bob.deviceId, "must not send"); fail("Sent to changed key") } catch (e: CryptoFailure) { assertEquals(CryptoError.IdentityChanged, e.error) }
        assertTrue(reopened.messages(bob.deviceId).isEmpty())
        val pending = reopened.fingerprint(bob.deviceId, true); assertNotEquals(old, pending)
        try { reopened.trustReplacement(bob.deviceId, old); fail("Accepted stale approval") } catch (e: CryptoFailure) { assertEquals(CryptoError.VerificationFailed, e.error) }
        reopened.trustReplacement(bob.deviceId, pending)
        assertEquals(IdentityTrustState.UNVERIFIED, reopened.contacts().single().identity!!.trustState)
        assertEquals(SessionLifecycle.ACTIVE, reopened.contacts().single().session)
    }
    @Test fun firstLaunchAndRenamePreserveIdentity() = runBlocking<Unit> {
        val records = MemoryRecords(); val a = service(records)
        assertNull(a.open()); val first = a.create("Alice"); val renamed = a.rename("Robert")
        assertEquals(first.deviceId, renamed.deviceId); assertEquals(first.userId, renamed.userId)
        assertArrayEquals(first.publicKey, renamed.publicKey); assertEquals("Robert", service(records).open()!!.username)
    }
    @Test fun contactsVerificationAndMessagesSurviveRestart() = runBlocking<Unit> {
        val records = MemoryRecords(); val a = service(records); val b = service()
        val alice = a.create("Alice"); val bob = b.create("Bob")
        a.importCard(b.exportCard()); b.importCard(a.exportCard())
        assertEquals(IdentityTrustState.UNVERIFIED, a.contacts().single().identity!!.trustState)
        val fingerprint = a.fingerprint(bob.deviceId); assertEquals(fingerprint, b.fingerprint(alice.deviceId))
        a.verify(bob.deviceId, fingerprint)
        val router = LocalEncryptedRouter(); val ta = router.register(alice.deviceId); val tb = router.register(bob.deviceId)
        a.attach(ta); b.attach(tb)
        val sent = a.send(bob.deviceId, "Bonjou 👋")
        val envelope = tb.receive().first()
        assertFalse(envelope.encryptedPayload.decodeToString().contains("Bonjou"))
        assertEquals("Bonjou 👋", b.receive(envelope).body)
        try { a.delivered(bob.deviceId, sent.localId, RandomIdentifiers.create()); fail("Accepted unrelated receipt") }
        catch (e: AppFailure) { assertEquals(AppError.CONTACT_UNAVAILABLE, e.error) }
        a.delivered(bob.deviceId, sent.localId, envelope.envelopeId)
        val reopened = service(records); reopened.open()
        assertEquals(IdentityTrustState.VERIFIED, reopened.contacts().single().identity!!.trustState)
        assertEquals(fingerprint, reopened.fingerprint(bob.deviceId))
        assertEquals("Bonjou 👋", reopened.messages(bob.deviceId).single().body)
        assertEquals(MessageState.DELIVERED_LOCAL_SIMULATION, reopened.messages(bob.deviceId).single().state)
        router.close()
    }
    @Test fun unicodeRoundTripsThroughCiphertextRouter() = runBlocking<Unit> {
        val a = service(); val b = service(); val ai = a.create("Alice"); val bi = b.create("Bob")
        a.importCard(b.exportCard()); b.importCard(a.exportCard())
        val router = LocalEncryptedRouter(); val ta = router.register(ai.deviceId); val tb = router.register(bi.deviceId)
        a.attach(ta); b.attach(tb)
        for (body in listOf("English", "Kreyòl: kòman ou ye?", "👩🏽‍💻🔒", "Crème brûlée", "你好 مرحبا नमस्ते")) {
            a.send(bi.deviceId, body); assertEquals(body, b.receive(tb.receive().first()).body)
            b.send(ai.deviceId, body); assertEquals(body, a.receive(ta.receive().first()).body)
        }
        router.close()
    }
    @Test fun invalidCardsAndDuplicateMappingsRejected() = runBlocking<Unit> {
        val a = service(); val b = service(); a.create("Alice"); b.create("Bob")
        val card = b.exportCard()
        for (bad in listOf("", "hello", card + "!", card.replace("GHOSTCLOAK:1:", "GHOSTCLOAK:2:"), "x".repeat(8193))) {
            try { a.importCard(bad); fail("Accepted malformed card") } catch (e: AppFailure) { assertEquals(AppError.INVALID_CARD, e.error) }
        }
        a.importCard(card)
        try { a.importCard(card); fail("Accepted duplicate") } catch (e: AppFailure) { assertEquals(AppError.DUPLICATE_CONTACT, e.error) }
        try { a.importCard(a.exportCard()); fail("Accepted own card") } catch (e: AppFailure) { assertEquals(AppError.AMBIGUOUS_IDENTITY, e.error) }
    }
    @Test fun malformedFieldsDuplicateCborAndBadSignaturesAreRejected() = runBlocking<Unit> {
        val a = service(); val b = service(); a.create("Alice"); val bob = b.create("Bob")
        val text = b.exportCard(); val card = ContactCardCodec.decode(text)
        val raw = java.util.Base64.getDecoder().decode(text.substringAfter("GHOSTCLOAK:1:"))
        fun position(needle: ByteArray) = (0..raw.size - needle.size).first { start -> needle.indices.all { raw[start + it] == needle[it] } }
        fun wire(bytes: ByteArray) = "GHOSTCLOAK:1:" + java.util.Base64.getEncoder().encodeToString(bytes)
        val wrongVersion = raw.copyOf().apply { this[position("version".encodeToByteArray()) + 7] = 2 }
        val wrongDevice = raw.copyOf().apply { this[position(bob.deviceId.encodeToByteArray())] = 'z'.code.toByte() }
        assertEquals(0xbf.toByte(), raw[0])
        val duplicate = raw.copyOfRange(0, 1) + byteArrayOf(0x67) + "version".encodeToByteArray() + byteArrayOf(1) + raw.copyOfRange(1, raw.size)
        for (bytes in listOf(wrongVersion, wrongDevice, duplicate, raw + byteArrayOf(0))) {
            try { a.importCard(wire(bytes)); fail("Accepted malformed fields") } catch (e: AppFailure) { assertEquals(AppError.INVALID_CARD, e.error) }
        }
        val badSignature = card.signature.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() }
        val invalid = ContactCardCodec.encode(ContactCard(1, card.userId, card.username, card.deviceId, card.registrationId,
            card.identity, card.preKeyId, card.preKey, card.signedId, card.signedKey, badSignature, card.kyberId, card.kyberKey, card.kyberSignature))
        try { a.importCard(invalid); fail("Accepted invalid signature") } catch (e: CryptoFailure) { assertEquals(CryptoError.AuthenticationFailed, e.error) }
        assertTrue(a.contacts().isEmpty())
    }
    @Test fun emptyAndUtf8OversizeAreRejectedBeforeTransport() {
        for (text in listOf("", " \n\t", "😀".repeat(4097), "x".repeat(16385), "\uD800")) {
            try { TextRules.encode(text); fail("Accepted invalid text") } catch (e: AppFailure) { /* Safe category only. */ }
        }
        assertEquals(16384, TextRules.encode("😀".repeat(4096)).size)
    }
    @Test fun blockingAndLocalDeletionPreserveContact() = runBlocking<Unit> {
        val a = service(); val b = service(); val ai = a.create("Alice"); val bi = b.create("Bob")
        a.importCard(b.exportCard()); b.importCard(a.exportCard())
        val router = LocalEncryptedRouter(); val ta = router.register(ai.deviceId); val tb = router.register(bi.deviceId)
        a.attach(ta); b.attach(tb); a.send(bi.deviceId, "test")
        b.block(ai.deviceId, true)
        try { b.receive(tb.receive().first()); fail("Accepted blocked packet") } catch (e: AppFailure) { assertEquals(AppError.BLOCKED, e.error) }
        val message = a.messages(bi.deviceId).single(); a.delete(bi.deviceId, message.localId)
        assertTrue(a.messages(bi.deviceId).isEmpty()); assertEquals(1, a.contacts().size)
        router.close()
    }
}
