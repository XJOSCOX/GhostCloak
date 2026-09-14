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
    @Test fun longEdgeDownscalesWithoutUpscalingAndPngBecomesOpaqueJpeg()=fixture { root ->
        val source=File(root,"source"); picture(source,true,5000,8)
        val normalized=File(root,"normalized")
        PhotoPreparation.normalize(source,normalized)
        val decoded=PhotoPreparation.decode(normalized)
        try { assertEquals(4096,decoded.width); assertFalse(decoded.hasAlpha()); assertFalse(PhotoPreparation.inspect(normalized)) } finally { decoded.recycle() }
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
    @Test fun progressiveAndHighResolutionFixturesNormalizeWithBoundedOutput()=fixture { root ->
        for(name in listOf("progressive.jpg","50mp.jpg","200mp.jpg")) {
            val source=File(root,"source")
            InstrumentationRegistry.getInstrumentation().context.assets.open("photo-fixtures/$name").use { input -> source.outputStream().use { input.copyTo(it) } }
            val normalized=File(root,"normalized")
            PhotoPreparation.normalize(source,normalized)
            val decoded=PhotoPreparation.decode(normalized)
            try {
                assertTrue(maxOf(decoded.width,decoded.height)<=4096)
                assertEquals(2,decoded.width/decoded.height)
                assertTrue(decoded.allocationByteCount<=4096*4096*4)
                assertFalse(decoded.hasAlpha())
                assertTrue(normalized.length()<=PhotoPreparation.OUTPUT_CAP)
            } finally { decoded.recycle() }
        }
    }
    @Test fun gainMapIsRemovedAndSdrBaseSurvives()=fixture { root ->
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT>=34)
        val source=File(root,"source")
        val base=Bitmap.createBitmap(48,24,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED);setHasAlpha(false) }
        val map=Bitmap.createBitmap(12,6,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        try {
            base.setGainmap(android.graphics.Gainmap(map))
            source.outputStream().use { assertTrue(base.compress(Bitmap.CompressFormat.JPEG,90,it)) }
            val original=android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(source))
            try { assertTrue(original.hasGainmap()) } finally { original.recycle() }
            val output=File(root,"output");PhotoPreparation.normalize(source,output)
            val result=android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(output)) { decoder,_,_ -> decoder.allocator=android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE }
            try { assertFalse(result.hasGainmap());assertTrue(Color.red(result.getPixel(10,10))>200) } finally { result.recycle() }
        } finally { base.recycle();map.recycle() }
    }
    @Test fun hdrTaggedJpegIsNotRejectedByMetadataSubstring()=fixture { root ->
        val source=File(root,"source");picture(source)
        val original=source.readBytes()
        val app="synthetic HDR hdrgm gainmap".toByteArray()
        source.outputStream().use { raw ->
            raw.write(original,0,2)
            DataOutputStream(raw).apply { writeShort(0xffe1);writeShort(app.size+2);write(app) }
            raw.write(original,2,original.size-2)
        }
        val output=File(root,"output");PhotoPreparation.normalize(source,output)
        assertFalse(output.readBytes().toString(Charsets.ISO_8859_1).contains("synthetic HDR"))
    }
    @Test fun malformedPreparationDeletesOutputAndSourceCapUsesActualLength()=fixture { root ->
        val source=File(root,"source").apply { writeBytes(ByteArray(128)) }
        val output=File(root,"output").apply { writeText("old") }
        assertThrows(Exception::class.java) { PhotoPreparation.normalize(source,output) }
        assertFalse(output.exists())
        RandomAccessFile(source,"rw").use { it.setLength(PhotoPreparation.SOURCE_CAP+1) }
        val failure=assertThrows(org.ghostcloak.app.attachments.PhotoFailure::class.java) { PhotoPreparation.inspect(source) }
        assertEquals(org.ghostcloak.app.attachments.PhotoFailureReason.SOURCE_LIMIT,failure.reason)
    }    @Test fun heifInputNormalizesWhenPlatformDecoderSupportsIt()=fixture { root ->
        val source=File(root,"source")
        InstrumentationRegistry.getInstrumentation().context.assets.open("photo-fixtures/still.heic").use { input -> source.outputStream().use { input.copyTo(it) } }
        val platform=try { android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(source)) } catch (_: Exception) { null }
        if(platform==null) {
            assertThrows(org.ghostcloak.app.attachments.PhotoFailure::class.java) { PhotoPreparation.normalize(source,File(root,"output")) }
        } else {
            platform.recycle()
            val output=File(root,"output");PhotoPreparation.normalize(source,output)
            assertFalse(PhotoPreparation.inspect(output))
            val decoded=PhotoPreparation.decode(output)
            try { assertEquals(96,decoded.width);assertEquals(48,decoded.height) } finally { decoded.recycle() }
        }
    }    @Test fun oneShotUnknownSizeSourceIsCopiedOnceAndActualSourceCapIsEnforced()=fixture { root ->
        val source=File(root,"untrusted-name.not-an-image")
        val input=object:FilterInputStream(InstrumentationRegistry.getInstrumentation().context.assets.open("photo-fixtures/progressive.jpg")) {
            override fun available()=0
            override fun markSupported()=false
            override fun reset() { fail("Provider stream must not be reopened/reset") }
        }
        input.use { source.outputStream().use { output -> PhotoPreparation.boundedCopy(input,output,PhotoPreparation.SOURCE_CAP) } }
        PhotoPreparation.normalize(source,File(root,"normalized"))
        var remaining=PhotoPreparation.SOURCE_CAP+1
        val oversized=object:InputStream() {
            override fun read():Int { if(remaining==0L)return -1;remaining--;return 0 }
            override fun read(bytes:ByteArray,offset:Int,length:Int):Int {
                if(remaining==0L)return -1
                val count=minOf(remaining,length.toLong()).toInt();remaining-=count;return count
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            PhotoPreparation.boundedCopy(oversized,object:OutputStream() {
                override fun write(value:Int) {}
                override fun write(bytes:ByteArray,offset:Int,length:Int) {}
            },PhotoPreparation.SOURCE_CAP)
        }
    }    @Test fun oversizedEncodedOutputFailsClosedAndRemovesPartialFile()=fixture { root ->
        val source=File(root,"source")
        val noise=Bitmap.createBitmap(4096,4096,Bitmap.Config.ARGB_8888)
        val random=java.util.Random(731)
        val row=IntArray(4096)
        try {
            repeat(4096) { y ->
                for(x in row.indices) row[x]=0xff000000.toInt() or random.nextInt(0x1000000)
                noise.setPixels(row,0,4096,0,y,4096,1)
            }
            noise.setHasAlpha(false)
            source.outputStream().use { assertTrue(noise.compress(Bitmap.CompressFormat.JPEG,100,it)) }
        } finally { noise.recycle();row.fill(0) }
        assertTrue(source.length()<=PhotoPreparation.SOURCE_CAP)
        val output=File(root,"output")
        val failure=assertThrows(org.ghostcloak.app.attachments.PhotoFailure::class.java) { PhotoPreparation.normalize(source,output) }
        assertEquals(org.ghostcloak.app.attachments.PhotoFailureReason.OUTPUT_LIMIT,failure.reason)
        assertFalse(output.exists())
    }    @Test fun contentProviderPipeIsCopiedOnceThenDecodedFromPrivateFile()=fixture { root ->
        PhotoPipeProvider.opens.set(0)
        PhotoPipeProvider.payload=InstrumentationRegistry.getInstrumentation().context.assets.open("photo-fixtures/progressive.jpg").use {it.readBytes()}
        try {
            val uri=Uri.parse("content://${context.packageName}.photo-pipe-test/synthetic")
            assertEquals("application/octet-stream",context.contentResolver.getType(uri))
            val source=File(root,"staged")
            val descriptor=context.contentResolver.openFileDescriptor(uri,"r")!!
            assertEquals(-1L,descriptor.statSize)
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                source.outputStream().use { output -> PhotoPreparation.boundedCopy(input,output,PhotoPreparation.SOURCE_CAP) }
            }
            PhotoPreparation.normalize(source,File(root,"output"))
            assertEquals(1,PhotoPipeProvider.opens.get())
        } finally {PhotoPipeProvider.payload.fill(0);PhotoPipeProvider.payload=byteArrayOf()}
    }
    @Test fun wideGamutSoftwareNormalizationReopensAsSdrJpegOffMain()=fixture { root ->
        val source=File(root,"source")
        val bitmap=Bitmap.createBitmap(96,48,Bitmap.Config.RGBA_F16,true,android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.DISPLAY_P3))
        try { bitmap.eraseColor(Color.RED);source.outputStream().use {assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))} }
        finally {bitmap.recycle()}
        val platform=android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(source)) { decoder,info,_ ->
            assertTrue(info.colorSpace!!.isWideGamut)
            decoder.allocator=android.graphics.ImageDecoder.ALLOCATOR_HARDWARE
        }
        try { assertEquals(Bitmap.Config.HARDWARE,platform.config) } finally {platform.recycle()}
        val output=File(root,"output")
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                PhotoPreparation.normalize(source,output) { assertNotSame(android.os.Looper.getMainLooper(),android.os.Looper.myLooper()) }
            }
        }
        val decoded=PhotoPreparation.decode(output)
        try { assertNotEquals(Bitmap.Config.HARDWARE,decoded.config);assertFalse(decoded.colorSpace!!.isWideGamut);assertFalse(decoded.hasAlpha()) }
        finally {decoded.recycle()}
        assertFalse(PhotoPreparation.inspect(output))
    }
    @Test fun malformedOptionalExifDoesNotRejectOtherwiseDecodableJpeg()=fixture { root ->
        val source=File(root,"source");picture(source)
        val original=source.readBytes()
        val app="Exif\u0000\u0000invalid-optional-tiff".toByteArray()
        source.outputStream().use { raw ->
            raw.write(original,0,2)
            DataOutputStream(raw).apply {writeShort(0xffe1);writeShort(app.size+2);write(app)}
            raw.write(original,2,original.size-2)
        }
        val output=File(root,"output");PhotoPreparation.normalize(source,output)
        assertFalse(output.readBytes().toString(Charsets.ISO_8859_1).contains("invalid-optional"))
    }}
