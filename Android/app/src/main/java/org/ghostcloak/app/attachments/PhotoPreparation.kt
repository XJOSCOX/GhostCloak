package org.ghostcloak.app.attachments

import android.graphics.*
import android.os.Build
import java.io.*
import kotlin.math.roundToInt

/** Only bounded private files reach the platform decoder; provider MIME is not trusted. */
object PhotoPreparation {
    const val SOURCE_CAP = 50L * 1048576
    const val OUTPUT_CAP = 10L * 1048576
    const val PIXEL_CAP = 256_000_000L
    const val AXIS_CAP = 32768
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

    private fun bounds(width: Int, height: Int) {
        if (width !in 1..AXIS_CAP || height !in 1..AXIS_CAP || width.toLong()*height > PIXEL_CAP) {
            PhotoDiagnostics.emit(PhotoEvent.PIXELS_OVER)
            PhotoDiagnostics.emit(PhotoEvent.BOUNDS_FAILED)
            throw PhotoFailure(PhotoFailureReason.BOUNDS)
        }
        PhotoDiagnostics.emit(PhotoEvent.PIXELS_OK)
    }

    /** Container allowlist only. Android still validates/decompresses the image. */
    fun inspect(file: File): Boolean {
        if (file.length() > SOURCE_CAP) {
            PhotoDiagnostics.emit(PhotoEvent.SIZE_OVER)
            throw PhotoFailure(PhotoFailureReason.SOURCE_LIMIT)
        }
        PhotoDiagnostics.emit(PhotoEvent.SIZE_OK)
        try {
            RandomAccessFile(file, "r").use { input ->
                val prefix = ByteArray(8); input.readFully(prefix)
                if (prefix.contentEquals(byteArrayOf(-119,80,78,71,13,10,26,10))) {
                    PhotoDiagnostics.emit(PhotoEvent.PNG)
                    var header = false
                    while (input.filePointer < input.length()) {
                        val size = input.readInt().toLong() and 0xffffffffL
                        val type = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
                        require(size <= input.length() - input.filePointer - 4)
                        if (type in setOf("acTL","fcTL","fdAT")) {
                            PhotoDiagnostics.emit(PhotoEvent.ANIMATED)
                            throw PhotoFailure(PhotoFailureReason.ANIMATED)
                        }
                        if (!header) {
                            require(type == "IHDR" && size == 13L)
                            bounds(input.readInt(), input.readInt())
                            input.skipBytes(5); header = true
                        } else input.seek(input.filePointer + size)
                        input.skipBytes(4)
                        if (type == "IEND") { require(size == 0L && input.filePointer == input.length()); return true }
                    }
                } else if (prefix[0] == (-1).toByte() && prefix[1] == (-40).toByte()) {
                    PhotoDiagnostics.emit(PhotoEvent.JPEG)
                    input.seek(2)
                    while (input.filePointer < input.length()) {
                        require(input.readUnsignedByte() == 255)
                        var marker = input.readUnsignedByte()
                        while (marker == 255) marker = input.readUnsignedByte()
                        if (marker == 0xda) return false
                        require(marker !in setOf(0,0xd8,0xd9))
                        val size = input.readUnsignedShort() - 2
                        require(size >= 0 && size <= input.length()-input.filePointer)
                        val end = input.filePointer + size
                        if (marker in 0xc0..0xcf && marker !in setOf(0xc4,0xc8,0xcc)) {
                            require(size >= 6); input.readUnsignedByte()
                            val height = input.readUnsignedShort(); bounds(input.readUnsignedShort(),height)
                        }
                        // APP/EXIF/XMP/gain-map metadata is neither copied nor interpreted as a rejection string.
                        input.seek(end)
                    }
                } else if (prefix.copyOfRange(4,8).toString(Charsets.US_ASCII) == "ftyp") {
                    PhotoDiagnostics.emit(PhotoEvent.OTHER)
                    input.seek(0)
                    val size = input.readInt().toLong() and 0xffffffffL
                    require(size in 16..4096 && size <= input.length() && size % 4 == 0L)
                    input.skipBytes(4)
                    val brands = mutableSetOf<String>()
                    brands += ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
                    input.skipBytes(4)
                    while (input.filePointer < size) brands += ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
                    require(brands.none { it in setOf("msf1","hevc","hevx","avif","avis") })
                    require(brands.any { it in setOf("heic","heix","mif1") })
                    return false // Still HEIF input only; device codec support decides success.
                } else PhotoDiagnostics.emit(PhotoEvent.UNKNOWN)
            }
        } catch (failure: PhotoFailure) { throw failure }
        catch (_: Exception) { throw PhotoFailure(PhotoFailureReason.FORMAT) }
        throw PhotoFailure(PhotoFailureReason.FORMAT)
    }

    fun decode(file: File, maximumEdge: Int = LONG_EDGE): Bitmap {
        require(maximumEdge in 1..LONG_EDGE)
        inspect(file)
        try {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                bounds(info.size.width,info.size.height)
                PhotoDiagnostics.emit(PhotoEvent.BOUNDS_OK)
                if (info.isAnimated) {
                    PhotoDiagnostics.emit(PhotoEvent.ANIMATED)
                    throw PhotoFailure(PhotoFailureReason.ANIMATED)
                }
                PhotoDiagnostics.emit(PhotoEvent.STILL)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                val scale = minOf(1.0, maximumEdge.toDouble()/maxOf(info.size.width,info.size.height))
                decoder.setTargetSize(maxOf(1,(info.size.width*scale).roundToInt()),maxOf(1,(info.size.height*scale).roundToInt()))
                decoder.setOnPartialImageListener { false }
            }.also { bitmap ->
                if (maxOf(bitmap.width,bitmap.height)>maximumEdge || bitmap.allocationByteCount.toLong()>maximumEdge.toLong()*maximumEdge*8) {
                    bitmap.recycle(); throw PhotoFailure(PhotoFailureReason.BOUNDS)
                }
                if (Build.VERSION.SDK_INT >= 34) {
                    PhotoDiagnostics.emit(if(bitmap.hasGainmap()) PhotoEvent.HDR else PhotoEvent.HDR_UNKNOWN)
                    // Ultra HDR's base image is SDR; do not carry its enhancement into the output.
                    bitmap.setGainmap(null)
                } else PhotoDiagnostics.emit(PhotoEvent.HDR_UNKNOWN)
                PhotoDiagnostics.emit(PhotoEvent.DECODE_OK)
            }
        } catch (failure: PhotoFailure) { throw failure }
        catch (_: OutOfMemoryError) { throw PhotoFailure(PhotoFailureReason.MEMORY) }
        catch (_: Exception) {
            PhotoDiagnostics.emit(PhotoEvent.DECODE_FAILED)
            throw PhotoFailure(PhotoFailureReason.DECODE)
        }
    }

    fun normalize(source: File, destination: File, check: () -> Unit = {}) {
        var decoded: Bitmap? = null
        var sdr: Bitmap? = null
        try {
            check(); decoded = decode(source); check()
            PhotoDiagnostics.emit(PhotoEvent.NORMALIZE_START)
            sdr = Bitmap.createBitmap(decoded.width,decoded.height,Bitmap.Config.ARGB_8888,false,
                ColorSpace.get(ColorSpace.Named.SRGB))
            Canvas(sdr).apply { drawColor(Color.WHITE); drawBitmap(decoded,0f,0f,null) }
            decoded.recycle(); decoded=null
            FileOutputStream(destination).use { raw ->
                var total = 0L
                var outputLimitExceeded = false
                val bounded = object : OutputStream() {
                    override fun write(value: Int) = write(byteArrayOf(value.toByte()))
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        check(); total += length
                        if(total > OUTPUT_CAP) {
                            outputLimitExceeded = true
                            PhotoDiagnostics.emit(PhotoEvent.OUTPUT_OVER)
                            throw PhotoFailure(PhotoFailureReason.OUTPUT_LIMIT)
                        }
                        raw.write(bytes,offset,length)
                    }
                }
                val encoded = sdr.compress(Bitmap.CompressFormat.JPEG,85,bounded)
                // Some native encoders return false after a stream failure instead of propagating it.
                if(outputLimitExceeded) throw PhotoFailure(PhotoFailureReason.OUTPUT_LIMIT)
                if(!encoded) throw PhotoFailure(PhotoFailureReason.NORMALIZE)
                check(); raw.fd.sync()
            }
            PhotoDiagnostics.emit(PhotoEvent.OUTPUT_OK)
            PhotoDiagnostics.emit(PhotoEvent.NORMALIZE_OK)
        } catch (failure: Throwable) {
            destination.delete(); PhotoDiagnostics.emit(PhotoEvent.NORMALIZE_FAILED)
            if(failure is OutOfMemoryError) throw PhotoFailure(PhotoFailureReason.MEMORY)
            if(failure is IOException) throw PhotoFailure(PhotoFailureReason.NORMALIZE)
            throw failure
        } finally { decoded?.recycle(); sdr?.recycle() }
    }
}
