package org.ghostcloak.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.ghostcloak.crypto.SignalProtocolEngine
import org.ghostcloak.identity.IdentityTrustState
import org.ghostcloak.messaging.*
import org.ghostcloak.storage.EncryptedEndpointStore
import org.ghostcloak.transport.LocalEncryptedRouter
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class MessagingStorageTest {
    @Test fun identityContactsVerificationAndUnicodeHistorySurviveSqlCipherReopen() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val an = "t-${UUID.randomUUID()}"; val bn = "t-${UUID.randomUUID()}"
        var ar = EncryptedEndpointStore.open(context, an)
        val br = EncryptedEndpointStore.open(context, bn)
        val router = LocalEncryptedRouter()
        try {
            var a = ConversationService(SignalProtocolEngine(ar), LocalRepository(ar))
            val b = ConversationService(SignalProtocolEngine(br), LocalRepository(br))
            assertNull(a.open()); val ai = a.create("Alice"); val bi = b.create("Bob")
            a.importCard(b.exportCard()); b.importCard(a.exportCard())
            a.verify(bi.deviceId, a.fingerprint(bi.deviceId))
            val ta = router.register(ai.deviceId); val tb = router.register(bi.deviceId)
            a.attach(ta); b.attach(tb)
            val text = "synthetic_history_secret Kreyòl 🔒 你好"
            a.send(bi.deviceId, text); b.receive(tb.receive().first())
            a.rename("Robert")
            ar.close()
            val disk = File(context.noBackupFilesDir, "$an.db").readBytes()
            assertFalse(disk.toString(Charsets.ISO_8859_1).contains("synthetic_history_secret"))
            assertFalse(disk.take(16).toByteArray().decodeToString().startsWith("SQLite format 3"))
            ar = EncryptedEndpointStore.open(context, an)
            a = ConversationService(SignalProtocolEngine(ar), LocalRepository(ar))
            val reopened = a.open()!!
            assertEquals(ai.deviceId, reopened.deviceId); assertArrayEquals(ai.publicKey, reopened.publicKey)
            assertEquals("Robert", reopened.username)
            assertEquals(IdentityTrustState.VERIFIED, a.contacts().single().identity!!.trustState)
            assertEquals(text, a.messages(bi.deviceId).single().body)
        } finally { ar.close(); br.close(); router.close() }
    }
}
