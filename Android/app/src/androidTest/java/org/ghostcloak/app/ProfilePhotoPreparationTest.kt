package org.ghostcloak.app

import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import org.ghostcloak.app.attachments.ProfilePhotoPreparation
import org.ghostcloak.messaging.ProfileRules
import org.junit.Assert.*
import org.junit.Test
import java.io.File

@Suppress("DEPRECATION")
class ProfilePhotoPreparationTest {
    @Test fun startupCleanupRemovesAbandonedPlaintextSourceOnly() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val abandoned=ProfilePhotoPreparation.newScratch(context.noBackupFilesDir)
        val unrelated=File.createTempFile("other-private-",".tmp",context.noBackupFilesDir)
        try {
            abandoned.writeText("synthetic photo source")
            ProfilePhotoPreparation.cleanupAbandoned(context.noBackupFilesDir)
            assertFalse(abandoned.exists())
            assertTrue(unrelated.exists())
        } finally {abandoned.delete();unrelated.delete()}
    }
    @Test fun squareBoundedJpegDropsExifAndGps() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val source=File.createTempFile("profile-test-",".jpg",context.noBackupFilesDir)
        try {
            val bitmap=Bitmap.createBitmap(640,320,Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.BLUE)
            source.outputStream().use {assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG,85,it))}
            bitmap.recycle()
            ExifInterface(source).apply {
                setAttribute(ExifInterface.TAG_GPS_LATITUDE,"40/1,0/1,0/1")
                setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF,"N")
                setAttribute(ExifInterface.TAG_MAKE,"fixture-device")
                saveAttributes()
            }
            val started=android.os.SystemClock.elapsedRealtimeNanos()
            val result=ProfilePhotoPreparation.prepare(source)
            android.util.Log.i("GhostCloakProfileFixture","bytes=${result.size} prepare_ms=${(android.os.SystemClock.elapsedRealtimeNanos()-started)/1_000_000}")
            assertTrue(result.size<=ProfileRules.MAX_PHOTO_BYTES)
            assertTrue(ProfilePhotoPreparation.valid(result))
            val encoded=File.createTempFile("profile-result-",".jpg",context.noBackupFilesDir)
            try {
                encoded.writeBytes(result)
                val metadata=ExifInterface(encoded)
                assertNull(metadata.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
                assertNull(metadata.getAttribute(ExifInterface.TAG_MAKE))
                val bounds=android.graphics.BitmapFactory.Options().apply {inJustDecodeBounds=true}
                android.graphics.BitmapFactory.decodeFile(encoded.path,bounds)
                assertEquals(bounds.outWidth,bounds.outHeight)
                assertTrue(bounds.outWidth in 128..256)
            } finally {encoded.delete()}
        } finally {source.delete()}
    }
}
