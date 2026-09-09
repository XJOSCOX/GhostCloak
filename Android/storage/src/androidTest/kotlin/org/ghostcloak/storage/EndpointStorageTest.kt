package org.ghostcloak.storage

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.ghostcloak.crypto.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.util.UUID

class EndpointStorageTest {
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
}
