package org.ghostcloak.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.ghostcloak.attachments.*
import org.ghostcloak.storage.EncryptedEndpointStore
import org.ghostcloak.protocol.BlobResult
import org.ghostcloak.identity.RandomIdentifiers
import org.junit.Assert.*
import org.junit.Test
import java.io.*

class AttachmentStorageTest {
    @Test fun sqlcipherJournalRestartQuarantineAndLockRevocation()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name=RandomIdentifiers.create()
        val root=File(context.noBackupFilesDir,"$name-attachments")
        var records=EncryptedEndpointStore.open(context,name)
        try {
            var store=AttachmentStore(records,root)
            val fixture=ByteArray(1048577){(it*13).toByte()}
            val descriptor=store.prepare(fixture.inputStream(),fixture.size.toLong(),AttachmentKind.DOCUMENT,30){true}
            records.close(); records=EncryptedEndpointStore.open(context,name)
            store=AttachmentStore(records,root)
            assertEquals(TransferState.ENCRYPTED,store.entry(descriptor.id)!!.state)
            var downloads=0
            val client=object:BlobTransferClient {
                override suspend fun reserve(descriptor:AttachmentDescriptor)=BlobResult(complete=false,expiresAt=Long.MAX_VALUE)
                override suspend fun upload(descriptor:AttachmentDescriptor,ciphertext:File)=BlobResult(complete=true,expiresAt=Long.MAX_VALUE)
                override suspend fun download(descriptor:AttachmentDescriptor,ciphertext:File) {
                    downloads++
                    File(root,"upload/${descriptor.id}").inputStream().use {input->ciphertext.outputStream().use {input.copyTo(it)}}
                }
            }
            store.upload(descriptor.id,client){true}
            val verified=store.download(descriptor,"synthetic",client){true}
            val output=ByteArrayOutputStream(); verified.copyTo(output); assertArrayEquals(fixture,output.toByteArray())
            store.invalidate()
            try {verified.copyTo(ByteArrayOutputStream());fail()}catch(_:IllegalStateException){}
            verified.close()
            assertTrue(File(root,"scratch").listFiles().orEmpty().isEmpty())
            assertEquals(1,downloads)
            store.download(descriptor,"synthetic",client){true}.use { reopened ->
                val copy=ByteArrayOutputStream();reopened.copyTo(copy);assertArrayEquals(fixture,copy.toByteArray())
            }
            assertEquals(1,downloads) // Cache reopening authenticates locally, without another GET.
            store.remove(descriptor.id); assertNull(store.entry(descriptor.id))
            assertTrue(File(root,"download").listFiles().orEmpty().isEmpty())
        } finally {records.close();root.deleteRecursively()}
    }
}
