package org.ghostcloak.app

import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import org.ghostcloak.app.attachments.PhotoPreparation
import org.ghostcloak.attachments.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*

@Suppress("DEPRECATION")
class PhotoPreparationTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture(block:(File)->Unit) {
        val root=File(context.noBackupFilesDir,"photo-test-${AttachmentFormat.newId()}").apply { mkdirs() }
        try { block(root) } finally { root.deleteRecursively() }
    }
    private fun picture(file:File,png:Boolean=false,width:Int=48,height:Int=24) {
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.RED)
        if(!png) bitmap.setHasAlpha(false)
        file.outputStream().use { assertTrue(bitmap.compress(if(png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,95,it)) }
        bitmap.recycle()
    }
    @Test fun jpegStripsLocationDeviceAndTimestampAndBakesOrientation()=fixture { root ->
        val source=File(root,"source"); picture(source)
        ExifInterface(source).apply {
            setAttribute(ExifInterface.TAG_MAKE,"synthetic-make")
            setAttribute(ExifInterface.TAG_MODEL,"synthetic-model")
            setAttribute(ExifInterface.TAG_DATETIME,"2020:01:02 03:04:05")
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL,"2020:01:02 03:04:05")
            setAttribute(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_ROTATE_90.toString())
            setAttribute(ExifInterface.TAG_GPS_LATITUDE,"40/1,0/1,0/1")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF,"N")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE,"70/1,0/1,0/1")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF,"W")
            saveAttributes()
        }
        val normalized=File(root,"normalized")
        PhotoPreparation.normalize(source,normalized)
        val exif=ExifInterface(normalized)
        for(tag in listOf(ExifInterface.TAG_MAKE,ExifInterface.TAG_MODEL,ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,ExifInterface.TAG_GPS_LATITUDE,ExifInterface.TAG_GPS_LONGITUDE)) assertNull(exif.getAttribute(tag))
        val decoded=PhotoPreparation.decode(normalized)
        try { assertEquals(24,decoded.width);assertEquals(48,decoded.height);assertTrue(Color.red(decoded.getPixel(12,24))>200) }
        finally { decoded.recycle() }
        assertTrue(normalized.length()<=PhotoPreparation.OUTPUT_CAP)
    }
    @Test fun longEdgeDownscalesWithoutUpscalingAndPngKeepsAlpha()=fixture { root ->
        val source=File(root,"source"); picture(source,true,5000,8)
        val normalized=File(root,"normalized")
        PhotoPreparation.normalize(source,normalized)
        val decoded=PhotoPreparation.decode(normalized)
        try { assertEquals(4096,decoded.width); assertTrue(decoded.hasAlpha()) } finally { decoded.recycle() }
        picture(source,true,12,6);PhotoPreparation.normalize(source,normalized)
        val small=PhotoPreparation.decode(normalized)
        try { assertEquals(12,small.width);assertEquals(6,small.height) } finally { small.recycle() }
    }
    @Test fun malformedUnsupportedAndAnimatedSourcesFailClosed()=fixture { root ->
        val source=File(root,"source")
        for(bytes in listOf("GIF89a".toByteArray(),ByteArray(128),byteArrayOf(-1,-40,1,2,3,4,5,6))) {
            source.writeBytes(bytes)
            assertThrows(Exception::class.java) { PhotoPreparation.decode(source) }
        }
        picture(source,true)
        val bytes=source.readBytes()
        val animation=ByteArrayOutputStream().apply {
            write(bytes,0,33)
            DataOutputStream(this).apply { writeInt(8);writeBytes("acTL");writeLong(1);writeInt(0) }
            write(bytes,33,bytes.size-33)
        }.toByteArray()
        source.writeBytes(animation)
        assertThrows(IllegalArgumentException::class.java) { PhotoPreparation.inspect(source) }
    }
    @Test fun pixelBombIsRejectedBeforeDecode()=fixture { root ->
        val source=File(root,"source");picture(source,true)
        RandomAccessFile(source,"rw").use { it.seek(16);it.writeInt(100000);it.writeInt(100000) }
        assertThrows(IllegalArgumentException::class.java) { PhotoPreparation.inspect(source) }
    }
    @Test fun unknownSizeCopyEnforcesActualBytesAndDocumentsStayOpaque() {
        val bytes=ByteArray(1000) { it.toByte() }
        val output=ByteArrayOutputStream()
        assertEquals(1000L,PhotoPreparation.boundedCopy(bytes.inputStream(),output,1000))
        assertArrayEquals(bytes,output.toByteArray())
        assertThrows(IllegalArgumentException::class.java) { PhotoPreparation.boundedCopy(bytes.inputStream(),ByteArrayOutputStream(),999) }
        for(prefix in listOf("%PDF-malformed","PK-zip-bomb")) {
            val binary=prefix.toByteArray()+bytes
            val copied=ByteArrayOutputStream()
            PhotoPreparation.boundedCopy(binary.inputStream(),copied,binary.size.toLong())
            assertArrayEquals(binary,copied.toByteArray())
        }
    }
    @Test fun corruptedOrTruncatedBlobNeverReachesPhotoDecoder()=fixture { root ->
        val photo=File(root,"photo");picture(photo)
        val encrypted=File(root,"encrypted")
        val descriptor=AttachmentFormat.encrypt(photo.inputStream(),encrypted,photo.length(),AttachmentKind.IMAGE)
        val original=encrypted.readBytes()
        for(bytes in listOf(original.copyOf(original.size-1),original.copyOf().also { it[it.lastIndex]=(it.last().toInt() xor 1).toByte() })) {
            encrypted.writeBytes(bytes)
            val plaintext=File(root,"plaintext")
            var decoded=false
            try { AttachmentFormat.decrypt(descriptor,encrypted,plaintext);decoded=true;PhotoPreparation.decode(plaintext);fail() } catch (_: Exception) {}
            assertFalse(decoded);assertFalse(plaintext.exists())
        }
        encrypted.writeBytes(original)
        descriptor.key[0]=(descriptor.key[0].toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { AttachmentFormat.decrypt(descriptor,encrypted,File(root,"wrong")) }
        assertFalse(File(root,"wrong").exists())
    }
    @Test fun providerCannotMapArbitraryPrivateFilesOrGrantWrites() {
        val info=context.packageManager.resolveContentProvider("${context.packageName}.attachment-view",0)!!
        assertFalse(info.exported); assertTrue(info.grantUriPermissions)
        for(path in listOf("local.db","../local.db",AttachmentFormat.newId())) {
            val uri=Uri.parse("content://${context.packageName}.attachment-view/$path")
            for(mode in listOf("r","w","rw")) assertThrows(Exception::class.java) { context.contentResolver.openFileDescriptor(uri,mode) }
        }
    }
    @Test fun documentRoutingIgnoresSpoofedExtensionsAndNeverParsesTheBody()=fixture { root ->
        val fakePdf=File(root,"document.pdf").apply { writeText("PK-zip-or-arbitrary-binary") }
        assertEquals("application/octet-stream",org.ghostcloak.app.attachments.DocumentType.sniff(fakePdf))
        val fakeExtension=File(root,"document.exe").apply { writeText("%PDF-malformed body intentionally not parsed") }
        assertEquals("application/pdf",org.ghostcloak.app.attachments.DocumentType.sniff(fakeExtension))
    }
}
