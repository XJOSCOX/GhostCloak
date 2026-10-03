package org.ghostcloak.app

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.ghostcloak.app.access.LocalOperationTestHooks
import org.ghostcloak.app.application.GhostApplication
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in live shutdown probe only on a disposable AVD. Leaves journal armed, never clears it.
 * Reflection supplies a synthetic stalled picker stream to the actual media owner's scope. */
class LocalOperationLiveProbeTest {
    @Test fun liveUiMediaInputAndScopeAreQuiescedWithoutDeletingStagedFile() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("localOperationLiveProbe") == "true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val app=context.applicationContext as GhostApplication
        assertEquals("",BuildConfig.API_ORIGIN)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var closed=false; var joined=false; var closes=0
            val started=CompletableDeferred<Unit>()
            val marker=File(context.noBackupFilesDir,"media-presentation/non-destructive-probe")
            withContext(Dispatchers.Main) {
                val media=app.media
                marker.writeBytes(byteArrayOf(1,2,3))
                val source=object : java.io.InputStream() {
                    override fun read() = -1
                    override fun close() { closes++; if(closes==1) throw java.io.IOException("synthetic_close"); closed=true }
                }
                media.javaClass.getDeclaredField("activeInput").apply { isAccessible=true }.set(media,source)
                val scope=media.javaClass.getDeclaredField("scope").apply { isAccessible=true }.get(media) as CoroutineScope
                scope.launch { try { started.complete(Unit); awaitCancellation() } finally { joined=true } }
            }
            started.await()
            LocalOperationTestHooks.arm(app.localOperationGate)
            try { app.localOperationCoordinator.resume() } catch (_: java.io.IOException) {}
            app.localOperationCoordinator.resume()
            assertTrue(app.localOperationCoordinator.ownersQuiesced)
            assertEquals(2,closes); assertTrue(closed); assertTrue(joined); assertTrue(marker.exists())
            assertArrayEquals(byteArrayOf(1,2,3),marker.readBytes())
            scenario.onActivity { assertTrue(app.localOperationGate.blocked) }
            try { app.media; fail("media reopened") } catch (_: org.ghostcloak.app.access.LocalOperationBlocked) {}
        }
    }
}
