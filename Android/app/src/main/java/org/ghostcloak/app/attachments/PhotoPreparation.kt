package org.ghostcloak.app.attachments

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.os.Build
import java.io.*
import kotlin.math.roundToInt

/** Only bounded, private files reach this decoder. No provider MIME/extension is trusted. */
object PhotoPreparation {
    const val SOURCE_CAP = 20L * 1048576
    const val OUTPUT_CAP = 10L * 1048576
    const val PIXEL_CAP = 32_000_000L
    const val LONG_EDGE = 4096

    fun boundedCopy(input: InputStream, output: OutputStream, maximum: Long, check: () -> Unit = {}): Long {
        val buffer = ByteArray(8192)
        var total = 0L
        try {
            while (true) {
                check()
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= maximum) { "attachment_size" }
                output.write(buffer, 0, count)
            }
            require(total > 0) { "attachment_empty" }
            return total
        } finally { buffer.fill(0) }
    }

    /** Refuse animated PNG, high bit-depth/HDR PNG and JPEG multi-picture/gain-map containers. */
    fun inspect(file: File): Boolean {
        require(file.length() in 1..SOURCE_CAP)
        RandomAccessFile(file, "r").use { input ->
            val prefix = ByteArray(8); input.readFully(prefix)
            if (prefix.contentEquals(byteArrayOf(-119,80,78,71,13,10,26,10))) {
                var header = false
                while (input.filePointer < input.length()) {
                    val size = input.readInt().toLong() and 0xffffffffL
                    val type = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
                    require(size <= input.length() - input.filePointer - 4)
                    require(type !in setOf("acTL","fcTL","fdAT","cICP","iCCP")) { "unsupported_photo" }
                    if (!header) {
                        require(type == "IHDR" && size == 13L)
                        val width = input.readInt(); val height = input.readInt()
                        require(width > 0 && height > 0 && width.toLong()*height <= PIXEL_CAP)
                        require(input.readUnsignedByte() == 8)
                        require(input.readUnsignedByte() in setOf(0,2,4,6))
                        input.skipBytes(3); header = true
                    } else input.seek(input.filePointer + size)
                    input.skipBytes(4)
                    if (type == "IEND") { require(size == 0L && input.filePointer == input.length()); return true }
                }
                error("unsupported_photo")
            }
            require(prefix[0] == (-1).toByte() && prefix[1] == (-40).toByte()) { "unsupported_photo" }
            input.seek(2)
            while (input.filePointer < input.length()) {
                require(input.readUnsignedByte() == 255)
                var marker = input.readUnsignedByte()
                while (marker == 255) marker = input.readUnsignedByte()
                if (marker == 0xda) return false
                require(marker !in setOf(0,0xd8,0xd9))
                val size = input.readUnsignedShort() - 2
                require(size >= 0 && size <= input.length()-input.filePointer)
                val bytes = ByteArray(size).also(input::readFully)
                try {
                    if (marker in 0xe0..0xef) {
                        val metadata = bytes.toString(Charsets.ISO_8859_1)
                        require(!metadata.contains("MPF\u0000") && !metadata.contains("hdrgm",true) &&
                            !metadata.contains("gainmap",true) && !metadata.contains("HDR",true)) { "unsupported_photo" }
                    }
                } finally { bytes.fill(0) }
            }
        }
        error("unsupported_photo")
    }

    fun decode(file: File): Bitmap {
        inspect(file)
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            require(!info.isAnimated && info.size.width > 0 && info.size.height > 0 &&
                info.size.width.toLong()*info.size.height <= PIXEL_CAP)
            require(info.colorSpace?.isWideGamut != true)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            val scale = minOf(1.0, LONG_EDGE.toDouble()/maxOf(info.size.width,info.size.height))
            decoder.setTargetSize(maxOf(1,(info.size.width*scale).roundToInt()),maxOf(1,(info.size.height*scale).roundToInt()))
            decoder.setOnPartialImageListener { false }
        }.also { bitmap ->
            if (Build.VERSION.SDK_INT >= 34 && bitmap.hasGainmap()) { bitmap.recycle(); error("unsupported_photo") }
        }
    }

    fun normalize(source: File, destination: File, check: () -> Unit = {}) {
        check()
        val bitmap = decode(source)
        try {
            check()
            FileOutputStream(destination).use { raw ->
                var total = 0L
                val bounded = object : OutputStream() {
                    override fun write(value: Int) = write(byteArrayOf(value.toByte()))
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        check(); total += length; require(total <= OUTPUT_CAP) { "attachment_size" }
                        raw.write(bytes,offset,length)
                    }
                }
                // Re-encoding pixels never copies the source EXIF/XMP/filename.
                require(bitmap.compress(if(bitmap.hasAlpha()) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,85,bounded))
                check(); raw.fd.sync()
            }
        } catch (failure: Throwable) { destination.delete(); throw failure }
        finally { bitmap.recycle() }
    }
}
