package org.ghostcloak.app.attachments

import android.Manifest
import android.media.MediaPlayer
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.ghostcloak.attachments.AttachmentFormat
import org.ghostcloak.attachments.AttachmentKind
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class VoiceCaptureAndroidTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun grant()=InstrumentationRegistry.getInstrumentation().uiAutomation
        .grantRuntimePermission(context.packageName,Manifest.permission.RECORD_AUDIO)

    @Test fun recordsEncryptsAndDeletesPrivateScratch() {
        grant()
        val root=File(context.noBackupFilesDir,"voice-test-${UUID.randomUUID()}")
        try {
            val scratch=PlaintextScratch(root)
            val capture=VoiceCapture(context,scratch)
            val file=capture.start({}, {})
            assertEquals(root.canonicalPath,file.parentFile?.canonicalPath)
            SystemClock.sleep(1500)
            val recorded=capture.stop()
            assertNotNull(recorded)
            val result=recorded!!
            assertTrue(result.durationMillis in 500..300_000)
            assertTrue(result.bytes in 1..AttachmentKind.VOICE_NOTE.maximumBytes)
            val player=MediaPlayer()
            val playbackStart=SystemClock.elapsedRealtime()
            try {player.setDataSource(file.absolutePath);player.prepare();assertTrue(player.duration>0)}
            finally {player.release()}
            val playbackStartup=SystemClock.elapsedRealtime()-playbackStart
            val encrypted=File(root,"ciphertext")
            val memoryBefore=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()
            val start=SystemClock.elapsedRealtime()
            file.inputStream().use {AttachmentFormat.encrypt(it,encrypted,result.bytes,AttachmentKind.VOICE_NOTE)}
            val encryptMs=SystemClock.elapsedRealtime()-start
            val memoryAfter=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()
            assertTrue(scratch.delete(file))
            assertFalse(file.exists())
            assertTrue(encrypted.exists())
            android.util.Log.i("GhostCloakP4Test","VOICE bytes_per_minute=${result.bytes*60_000/result.durationMillis} encrypt_ms=$encryptMs playback_startup_ms=$playbackStartup heap_delta=${memoryAfter-memoryBefore}")
            assertTrue(scratch.delete(encrypted))
            assertTrue(scratch.sweep())
        } finally {root.deleteRecursively()}
    }

    @Test fun cancelAndRecreationRemoveUnsentAudio() {
        grant()
        val root=File(context.noBackupFilesDir,"voice-cancel-${UUID.randomUUID()}")
        try {
            val scratch=PlaintextScratch(root)
            val capture=VoiceCapture(context,scratch)
            val file=capture.start({}, {})
            SystemClock.sleep(650)
            capture.cancel()
            assertFalse(file.exists())
            val orphan=scratch.newFile().apply {writeText("synthetic")}
            val restarted=PlaintextScratch(root)
            assertTrue(restarted.clean)
            assertFalse(orphan.exists())
        } finally {root.deleteRecursively()}
    }

}
