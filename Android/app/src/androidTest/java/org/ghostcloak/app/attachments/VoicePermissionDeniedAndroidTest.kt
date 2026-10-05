package org.ghostcloak.app.attachments

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Run in a fresh instrumentation process after the disposable AVD has denied RECORD_AUDIO. */
@RunWith(AndroidJUnit4::class)
class VoicePermissionDeniedAndroidTest {
    @Test fun deniedMicrophoneLeavesNoScratch() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(PackageManager.PERMISSION_DENIED,context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        val root=File(context.noBackupFilesDir,"voice-denied-${UUID.randomUUID()}")
        try {
            val scratch=PlaintextScratch(root)
            val capture=VoiceCapture(context,scratch)
            assertThrows(Exception::class.java) {capture.start({}, {})}
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally {root.deleteRecursively()}
    }
}
