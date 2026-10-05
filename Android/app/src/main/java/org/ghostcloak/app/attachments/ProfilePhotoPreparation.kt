package org.ghostcloak.app.attachments

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.ghostcloak.messaging.ProfileRules
import java.io.ByteArrayOutputStream
import java.io.File

/** Re-encoding pixels into a small JPEG drops source EXIF, GPS and gallery metadata. */
object ProfilePhotoPreparation {
    private const val SCRATCH_PREFIX="profile-input-"
    fun newScratch(directory:File):File=File.createTempFile(SCRATCH_PREFIX,".tmp",directory)
    /** A killed picker/decoder must not leave the user's original photo in private scratch. */
    fun cleanupAbandoned(directory:File) {
        directory.listFiles()?.filter {it.name.startsWith(SCRATCH_PREFIX) && it.name.endsWith(".tmp")}
            ?.forEach {check(it.isFile && it.delete()) {"profile_scratch_cleanup_failed"}}
    }
    fun prepare(source:File):ByteArray {
        val decoded=PhotoPreparation.decode(source,256)
        try {
            val side=minOf(decoded.width,decoded.height)
            val crop=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888)
            try {
                Canvas(crop).apply {
                    drawColor(Color.WHITE)
                    drawBitmap(decoded,android.graphics.Rect((decoded.width-side)/2,(decoded.height-side)/2,
                        (decoded.width+side)/2,(decoded.height+side)/2),
                        android.graphics.Rect(0,0,256,256),Paint(Paint.FILTER_BITMAP_FLAG))
                }
                for(edge in listOf(256,224,192,160,128)) {
                    val image=if(edge==256) crop else Bitmap.createScaledBitmap(crop,edge,edge,true)
                    try {
                        for(quality in listOf(82,70,58,45,35)) {
                            val out=ByteArrayOutputStream()
                            if(image.compress(Bitmap.CompressFormat.JPEG,quality,out)) {
                                val bytes=stripMetadata(out.toByteArray())
                                if(bytes.size<=ProfileRules.MAX_PHOTO_BYTES && valid(bytes)) return bytes
                            }
                        }
                    } finally {if(image!==crop) image.recycle()}
                }
            } finally {crop.recycle()}
        } finally {decoded.recycle()}
        throw IllegalArgumentException("Profile photo is too detailed for the private profile limit")
    }
    private fun stripMetadata(jpeg:ByteArray):ByteArray {
        require(jpeg.size>=4 && jpeg[0]==0xff.toByte() && jpeg[1]==0xd8.toByte())
        val out=ByteArrayOutputStream(jpeg.size)
        out.write(jpeg,0,2)
        var at=2
        while(at+4<=jpeg.size) {
            val start=at
            require(jpeg[at++]==0xff.toByte())
            while(at<jpeg.size && jpeg[at]==0xff.toByte()) at++
            require(at+2<jpeg.size)
            val marker=jpeg[at++].toInt() and 255
            val length=((jpeg[at].toInt() and 255) shl 8) or (jpeg[at+1].toInt() and 255)
            require(length>=2 && at+length<=jpeg.size)
            if(marker==0xda) {
                out.write(jpeg,start,jpeg.size-start)
                return out.toByteArray()
            }
            if(marker !in 0xe1..0xef && marker!=0xfe) out.write(jpeg,start,at+length-start)
            at+=length
        }
        throw IllegalArgumentException("Malformed encoded profile photo")
    }
    fun valid(bytes:ByteArray):Boolean = try {
        ProfileRules.photo(bytes)
        val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        if(bounds.outMimeType!="image/jpeg" || bounds.outWidth !in 1..256 || bounds.outHeight!=bounds.outWidth) false
        else BitmapFactory.decodeByteArray(bytes,0,bytes.size)?.let { bitmap ->
            try {bitmap.width==bounds.outWidth && bitmap.height==bounds.outHeight}
            finally {bitmap.recycle()}
        } ?: false
    } catch (_:Exception) {false} catch (_:OutOfMemoryError) {false}
}
