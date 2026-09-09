package org.ghostcloak.storage

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.ghostcloak.crypto.*
import org.ghostcloak.identity.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.util.UUID

class EndpointStorageTest {
    @Test fun deviceAuthIsNonExportableAndMissingKeyFailsClosed() {
        val alias="ghostcloak.test.auth."+UUID.randomUUID()
        val keys=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        try {
            val auth=KeystoreDeviceAuth()
            val publicKey=auth.publicKey(alias,true)
            assertNull(keys.getKey(alias,null).encoded)
            val statement=ByteArray(32).also {java.security.SecureRandom().nextBytes(it)}
            val signature=auth.sign(alias,statement)
            val verifier=java.security.Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(java.security.KeyFactory.getInstance("EC").generatePublic(java.security.spec.X509EncodedKeySpec(publicKey)))
            verifier.update(statement); assertTrue(verifier.verify(signature))
            assertArrayEquals(publicKey,KeystoreDeviceAuth().publicKey(alias,false))
            keys.deleteEntry(alias)
            try {auth.publicKey(alias,false); fail("Missing key regenerated")} catch(_:EndpointStorageFailure) {}
            try {auth.sign(alias,statement); fail("Missing key signed")} catch(_:EndpointStorageFailure) {}
            assertFalse(keys.containsAlias(alias))
        } finally {keys.deleteEntry(alias)}
    }
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun name() = "t-${UUID.randomUUID()}"

    @Test fun identityAndRatchetSurviveEncryptedDatabaseReopen() = runBlocking {
        val aName = name(); val bName = name()
        val aStore = EncryptedEndpointStore.open(context, aName)
        var bStore = EncryptedEndpointStore.open(context, bName)
        try {
            val a = SignalProtocolEngine(aStore); var b = SignalProtocolEngine(bStore)
            val alice = a.createIdentity("Alice"); val bob = b.createIdentity("Bob")
            a.establishSession(b.publicBundle())
            val packet = a.encrypt(bob.deviceId, "hello bob".encodeToByteArray())
            assertEquals("hello bob", b.decrypt(packet).decodeToString())
            bStore.close()
            val disk = File(context.noBackupFilesDir, "$bName.db").readBytes()
            assertFalse(disk.take(16).toByteArray().decodeToString().startsWith("SQLite format 3"))
            assertFalse(disk.toString(Charsets.ISO_8859_1).contains("hello bob"))
            bStore = EncryptedEndpointStore.open(context, bName)
            b = SignalProtocolEngine(bStore)
            assertEquals(bob.deviceId, b.createIdentity("Bob").deviceId)
            try { b.decrypt(packet); fail("Replay accepted after reopen") }
            catch (e: CryptoFailure) { assertEquals(CryptoError.Replay, e.error) }
            val reply = b.encrypt(alice.deviceId, "reply".encodeToByteArray())
            assertEquals("reply", a.decrypt(reply).decodeToString())
            assertEquals(a.getRemoteFingerprint(bob.deviceId), b.getRemoteFingerprint(alice.deviceId))
            val normal = a.encrypt(bob.deviceId, "normal".encodeToByteArray())
            assertEquals(2, normal.messageType)
            b.decrypt(normal).fill(0)
            bStore.close()
            bStore = EncryptedEndpointStore.open(context, bName)
            b = SignalProtocolEngine(bStore)
            for (duplicate in listOf(packet, normal)) {
                try { b.decrypt(duplicate); fail("Replay accepted after reopen") }
                catch (e: CryptoFailure) { assertEquals(CryptoError.Replay, e.error) }
            }
        } finally { aStore.close(); bStore.close() }
    }

    @Test fun transactionFailureRollsBackAllWrites() {
        EncryptedEndpointStore.open(context, name()).use { store ->
            store.transaction { store.write("original", byteArrayOf(1)) }
            try { store.transaction { store.write("original", byteArrayOf(2)); store.write("new", byteArrayOf(3)); error("fixture") } }
            catch (expected: IllegalStateException) { }
            assertArrayEquals(byteArrayOf(1), store.read("original"))
            assertNull(store.read("new"))
        }
    }

    @Test fun missingKeystoreKeyDoesNotRegenerateIdentity() {
        val endpoint = name()
        EncryptedEndpointStore.open(context, endpoint).close()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("ghost-cloak.db.$endpoint") }
        try { EncryptedEndpointStore.open(context, endpoint).close(); fail("Missing key accepted") }
        catch (expected: EndpointStorageFailure) { }
    }

    @Test fun tamperedWrappedDatabaseSecretFailsClosed() {
        val endpoint = name()
        EncryptedEndpointStore.open(context, endpoint).close()
        val file = File(context.noBackupFilesDir, "$endpoint.wrapped")
        file.writeBytes(file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
        try { EncryptedEndpointStore.open(context, endpoint).close(); fail("Tampered wrapping accepted") }
        catch (expected: EndpointStorageFailure) { }
    }

    @Test fun missingDatabaseDoesNotSilentlyReplaceIdentity() {
        val endpoint = name()
        EncryptedEndpointStore.open(context, endpoint).close()
        assertTrue(File(context.noBackupFilesDir, "$endpoint.db").delete())
        try { EncryptedEndpointStore.open(context, endpoint).close(); fail("Missing database recreated") }
        catch (expected: EndpointStorageFailure) { }
    }

    @Test fun missingWrappedSecretFailsWithoutChangingDatabaseOrKey() {
        val endpoint = name()
        EncryptedEndpointStore.open(context, endpoint).close()
        val database = File(context.noBackupFilesDir, "$endpoint.db").readBytes()
        assertTrue(File(context.noBackupFilesDir, "$endpoint.wrapped").delete())
        try { EncryptedEndpointStore.open(context, endpoint).close(); fail("Missing wrapping accepted") }
        catch (expected: EndpointStorageFailure) { }
        assertArrayEquals(database, File(context.noBackupFilesDir, "$endpoint.db").readBytes())
        assertTrue(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias("ghost-cloak.db.$endpoint"))
    }

    @Test fun truncatedAndMalformedWrappingFramesFailClosed() {
        for (length in listOf(0, 12, 60, 62)) {
            val endpoint = name()
            EncryptedEndpointStore.open(context, endpoint).close()
            val file = File(context.noBackupFilesDir, "$endpoint.wrapped")
            file.writeBytes(file.readBytes().copyOf(length))
            try { EncryptedEndpointStore.open(context, endpoint).close(); fail("Malformed wrapping accepted") }
            catch (expected: EndpointStorageFailure) { }
        }
    }

    @Test fun corruptWrappingVersionAndCiphertextFailClosed() {
        for (index in listOf(0, 20)) {
            val endpoint = name()
            EncryptedEndpointStore.open(context, endpoint).close()
            val file = File(context.noBackupFilesDir, "$endpoint.wrapped")
            file.writeBytes(file.readBytes().also { it[index] = (it[index].toInt() xor 1).toByte() })
            try { EncryptedEndpointStore.open(context, endpoint).close(); fail("Corrupt wrapping accepted") }
            catch (expected: EndpointStorageFailure) { }
        }
    }

    @Test fun verifiedAndChangedTrustSurviveRealDatabaseReopen() = runBlocking<Unit> {
        val aName = name()
        var aStore = EncryptedEndpointStore.open(context, aName)
        val bStore = EncryptedEndpointStore.open(context, name())
        val replacementStore = EncryptedEndpointStore.open(context, name())
        try {
            var a = SignalProtocolEngine(aStore); val b = SignalProtocolEngine(bStore)
            a.createIdentity("Alice"); val bob = b.createIdentity("Bob")
            a.establishSession(b.publicBundle())
            val original = a.getRemoteFingerprint(bob.deviceId)
            a.verifyRemoteIdentity(bob.deviceId, original)
            aStore.close(); aStore = EncryptedEndpointStore.open(context, aName); a = SignalProtocolEngine(aStore)
            assertEquals(IdentityTrustState.VERIFIED, a.getRemoteIdentityStatus(bob.deviceId)!!.trustState)
            assertEquals(original, a.getRemoteFingerprint(bob.deviceId))
            val replacement = SignalProtocolEngine(replacementStore); replacement.createIdentity("Bob")
            replacementStore.transaction { replacementStore.write("local/device", bob.deviceId.encodeToByteArray()) }
            val bundle = replacement.publicBundle()
            try { a.establishSession(bundle); fail("Changed key accepted") }
            catch (e: CryptoFailure) { assertEquals(CryptoError.IdentityChanged, e.error) }
            val pending = a.getPendingFingerprint(bob.deviceId)
            assertNotEquals(original, pending)
            aStore.close(); aStore = EncryptedEndpointStore.open(context, aName); a = SignalProtocolEngine(aStore)
            assertEquals(RemoteIdentityStatus(IdentityTrustState.CHANGED, IdentityTrustState.VERIFIED), a.getRemoteIdentityStatus(bob.deviceId))
            assertEquals(pending, a.getPendingFingerprint(bob.deviceId))
            try { a.establishSession(bundle); fail("Changed key accepted after reopen") }
            catch (e: CryptoFailure) { assertEquals(CryptoError.IdentityChanged, e.error) }
            a.trustNewIdentity(bob.deviceId, pending)
            a.reestablishSession(bundle, pending)
            assertEquals("approved", replacement.decrypt(a.encrypt(bob.deviceId, "approved".encodeToByteArray())).decodeToString())
        } finally { aStore.close(); bStore.close(); replacementStore.close() }
    }
}
