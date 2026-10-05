package org.ghostcloak.app.attachments

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
import kotlin.math.PI
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class VoiceMaskAndroidTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext

    private fun fixture(seconds:Int)=ShortArray(16_000*seconds) { i ->
        ((5000 * sin(2 * PI * 140 * i / 16_000) +
            2500 * sin(2 * PI * 280 * i / 16_000)) *
            (0.6 + 0.4 * sin(2 * PI * 2 * i / 16_000))).toInt().toShort()
    }

    @Test fun eachPresetProducesPlayableEncryptedStandardVoiceNoteAndCleansScratch() {
        val root=File(context.noBackupFilesDir,"voice-mask-test-${UUID.randomUUID()}")
        try {
            val scratch=PlaintextScratch(root)
            for(preset in listOf(VoiceMask.SUBTLE,VoiceMask.STRONG,VoiceMask.SYNTHETIC)) {
                val original=scratch.newFile()
                VoiceMasking.encode(fixture(10),original)
                val masked=scratch.newFile()
                val start=SystemClock.elapsedRealtime()
                val duration=VoiceMasking.transform(original,masked,preset)
                val elapsed=SystemClock.elapsedRealtime()-start
                val maskedBytes=masked.length()
                assertTrue(duration in 8_000..12_000)
                assertTrue(masked.length() in 1..AttachmentKind.VOICE_NOTE.maximumBytes)
                assertFalse(original.readBytes().contentEquals(masked.readBytes()))
                assertTrue(scratch.delete(original))
                assertFalse(original.exists())
                val player=MediaPlayer()
                try {player.setDataSource(masked.absolutePath);player.prepare();assertTrue(player.duration in 8_000..12_000)}
                finally {player.release()}
                val encrypted=scratch.newFile()
                masked.inputStream().use {AttachmentFormat.encrypt(it,encrypted,masked.length(),AttachmentKind.VOICE_NOTE)}
                assertTrue(scratch.delete(masked))
                assertFalse(masked.exists())
                assertTrue(encrypted.exists())
                android.util.Log.i("GhostCloakP7Test","PRESET=${preset.name} seconds=10 transform_ms=$elapsed masked_bytes=$maskedBytes encrypted_bytes=${encrypted.length()}")
                assertTrue(scratch.delete(encrypted))
            }
            val orphan=scratch.newFile().apply {writeText("orphan")}
            assertTrue(PlaintextScratch(root).clean)
            assertFalse(orphan.exists())
        } finally {root.deleteRecursively()}
    }

    @Test fun sixtySecondBenchmarkAndFailedTransformNeverLeavesOutput() {
        val root=File(context.noBackupFilesDir,"voice-mask-benchmark-${UUID.randomUUID()}")
        try {
            val scratch=PlaintextScratch(root)
            val original=scratch.newFile()
            VoiceMasking.encode(fixture(60),original)
            val masked=scratch.newFile()
            val start=SystemClock.elapsedRealtime()
            val duration=VoiceMasking.transform(original,masked,VoiceMask.STRONG)
            val elapsed=SystemClock.elapsedRealtime()-start
            assertTrue(duration in 55_000..65_000)
            android.util.Log.i("GhostCloakP7Test","PRESET=STRONG seconds=60 transform_ms=$elapsed masked_bytes=${masked.length()}")
            assertTrue(scratch.delete(original))
            assertTrue(scratch.delete(masked))
            val invalid=scratch.newFile().apply {writeText("invalid")}
            val output=scratch.newFile()
            assertThrows(Exception::class.java) {VoiceMasking.transform(invalid,output,VoiceMask.STRONG)}
            assertFalse(output.exists())
            assertTrue(scratch.delete(invalid))
        } finally {root.deleteRecursively()}
    }
}
