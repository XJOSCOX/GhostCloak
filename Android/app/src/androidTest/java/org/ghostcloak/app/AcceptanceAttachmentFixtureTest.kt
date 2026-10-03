package org.ghostcloak.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.ghostcloak.attachments.*
import org.ghostcloak.storage.EncryptedEndpointStore
import org.ghostcloak.identity.RandomIdentifiers
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AcceptanceAttachmentFixtureTest {
    @Test fun photoAndDocumentUseRealUploadBindAndVerifiedPresentation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = RandomIdentifiers.create()
        val root = File(context.noBackupFilesDir, "$name-attachments")
        EncryptedEndpointStore.open(context, name).use { records ->
            try {
                val store = AttachmentStore(records, root)
                for (kind in listOf(AttachmentKind.IMAGE, AttachmentKind.DOCUMENT)) {
                    val bytes = byteArrayOf(1, 2, 3)
                    val descriptor = prepareAcceptanceAttachment(store, bytes, kind, "synthetic/$kind")
                    assertEquals(TransferState.READY, store.entry(descriptor.id)!!.state)
                    assertTrue(File(root, "upload/${descriptor.id}").isFile)
                    assertEquals(setOf("synthetic/$kind"), store.entry(descriptor.id)!!.references)
                    descriptor.key.fill(0)
                }
            } finally { root.deleteRecursively() }
        }
    }
}
