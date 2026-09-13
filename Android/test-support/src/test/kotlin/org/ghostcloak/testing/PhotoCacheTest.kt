package org.ghostcloak.testing

import kotlinx.coroutines.runBlocking
import org.ghostcloak.attachments.*
import org.ghostcloak.protocol.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*

class PhotoCacheTest {
    private fun noNetwork()=object:BlobTransferClient {
        override suspend fun reserve(descriptor:AttachmentDescriptor):BlobResult=error("unexpected network")
        override suspend fun upload(descriptor:AttachmentDescriptor,ciphertext:File):BlobResult=error("unexpected network")
        override suspend fun download(descriptor:AttachmentDescriptor,ciphertext:File)=error("unexpected network")
    }
    @Test fun senderUsesOwnCiphertextAndReceivedHistorySurvivesServerTtlAndRestart()=runBlocking {
        AttachmentTest.Fixture().use { f ->
            val a=f.person("alice"); val b=f.person("bob")
            val bytes=byteArrayOf(1,2,3)
            val d=a.store.prepare(bytes.inputStream(),3,AttachmentKind.IMAGE,0){true}
            a.store.upload(d.id,a.bulk){true}; a.store.bind(d.id,"c/message")
            a.store.download(d,"c/message",noNetwork()){true}.close()
            assertTrue(File(a.directory,"download").listFiles().orEmpty().isEmpty())
            b.store.download(d,"c/message",b.bulk){true}.close()
            // A corrupt existing cache entry can be replaced without charging its bytes twice.
            val cached=File(b.directory,"download/${d.id}")
            val damaged=cached.readBytes().also {it[50]=(it[50].toInt() xor 1).toByte()}
            cached.writeBytes(damaged)
            b.store=AttachmentStore(b.records,b.directory,photoCacheLimit=d.ciphertextLength)
            b.store.download(d,"c/message",b.bulk){true}.close()
            f.time.millis+=8L*86400000
            b.store=AttachmentStore(b.records,b.directory)
            b.store.reconcileReferences(setOf("c/message"),emptySet())
            b.store.download(d,"c/message",noNetwork()){true}.use {
                val output=ByteArrayOutputStream();it.copyTo(output);assertArrayEquals(bytes,output.toByteArray())
            }
            assertTrue(File(b.directory,"scratch").listFiles().orEmpty().isEmpty())
            b.store.reconcileReferences(emptySet(),emptySet())
            assertFalse(File(b.directory,"download/${d.id}").exists())
            assertNull(b.store.entry(d.id))
        }
    }
    @Test fun capRefusesNewPhotosInsteadOfEvictingOnlyHistoryOrPendingUpload()=runBlocking {
        AttachmentTest.Fixture().use { f ->
            val a=f.person("alice"); val b=f.person("bob")
            val d=a.store.prepare(byteArrayOf(1).inputStream(),1,AttachmentKind.IMAGE,0){true}
            a.store.upload(d.id,a.bulk){true}
            b.store=AttachmentStore(b.records,b.directory,photoCacheLimit=d.ciphertextLength)
            b.store.download(d,"c/one",b.bulk){true}.close()
            val next=a.store.prepare(byteArrayOf(2).inputStream(),1,AttachmentKind.IMAGE,0){true}
            a.store.upload(next.id,a.bulk){true}
            try { b.store.download(next,"c/two",noNetwork()){true}; fail() } catch(e:IllegalStateException) {
                assertEquals("photo_cache_full",e.message)
            }
            assertTrue(File(b.directory,"download/${d.id}").exists())
            val pending=f.person("pending")
            pending.store=AttachmentStore(pending.records,pending.directory,photoCacheLimit=d.ciphertextLength)
            val first=pending.store.prepare(byteArrayOf(1).inputStream(),1,AttachmentKind.IMAGE,0){true}
            try { pending.store.prepare(byteArrayOf(2).inputStream(),1,AttachmentKind.IMAGE,0){true};fail() }
            catch(e:IllegalStateException) { assertEquals("photo_cache_full",e.message) }
            assertEquals(TransferState.ENCRYPTED,pending.store.entry(first.id)!!.state)
            assertTrue(File(pending.directory,"upload/${first.id}").exists())
        }
    }
    @Test fun safeEvictionRemovesRedundantCiphertextOnlyAndNeverItsJournal()=runBlocking {
        AttachmentTest.Fixture().use { f ->
            val a=f.person("alice")
            val first=a.store.prepare(byteArrayOf(1).inputStream(),1,AttachmentKind.IMAGE,0){true}
            a.store.upload(first.id,a.bulk){true};a.store.bind(first.id,"c/one")
            File(a.directory,"upload/${first.id}").copyTo(File(a.directory,"download/${first.id}"))
            a.store=AttachmentStore(a.records,a.directory,photoCacheLimit=first.ciphertextLength*2)
            val second=a.store.prepare(byteArrayOf(2).inputStream(),1,AttachmentKind.IMAGE,0){true}
            assertFalse(File(a.directory,"download/${first.id}").exists())
            assertTrue(File(a.directory,"upload/${first.id}").exists())
            assertEquals(setOf("c/one"),a.store.entry(first.id)!!.references)
            assertTrue(File(a.directory,"upload/${second.id}").exists())
        }
    }
    @Test fun corruptTruncatedAndWrongKeyNeverReturnPresentationAndScratchIsEmpty()=runBlocking {
        AttachmentTest.Fixture().use { f ->
            val a=f.person("alice"); val b=f.person("bob")
            val d=a.store.prepare(byteArrayOf(1,2,3).inputStream(),3,AttachmentKind.IMAGE,0){true}
            val original=File(a.directory,"upload/${d.id}").readBytes()
            for(mode in 0..2) {
                val wrong=if(mode==2) AttachmentDescriptor(id=d.id,capability=d.capability,
                    key=d.key.copyOf().also {it[0]=(it[0].toInt() xor 1).toByte()},digest=d.digest,
                    plaintextLength=d.plaintextLength,paddedLength=d.paddedLength,ciphertextLength=d.ciphertextLength,
                    kind=d.kind,disappearingSeconds=0) else d
                val client=object:BlobTransferClient {
                    override suspend fun reserve(descriptor:AttachmentDescriptor):BlobResult=error("unused")
                    override suspend fun upload(descriptor:AttachmentDescriptor,ciphertext:File):BlobResult=error("unused")
                    override suspend fun download(descriptor:AttachmentDescriptor,ciphertext:File) {
                        ciphertext.writeBytes(when(mode) {
                            0 -> original.copyOf().also { it[50]=(it[50].toInt() xor 1).toByte() }
                            1 -> original.copyOf(original.size-1)
                            else -> original
                        })
                    }
                }
                try { b.store.download(wrong,"c/photo",client){true}.close();fail() } catch(_:Exception) {}
                assertTrue(File(b.directory,"scratch").listFiles().orEmpty().isEmpty())
                b.store.remove(d.id)
            }
        }
    }
}
