package org.ghostcloak.app

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.ghostcloak.app.access.*
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
        val credentialScope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val credentials=object : LockPersistence {
            var bytes: ByteArray?=null
            override suspend fun read()=bytes?.copyOf()
            override suspend fun write(bytes: ByteArray) {this.bytes=bytes.copyOf()}
        }
        val controller=AppLockController(credentials,credentialScope,android.os.SystemClock::elapsedRealtime,{1},
            armEmergency={app.localOperationGate.armAfterCredential()})
        try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
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
            withContext(Dispatchers.Main) {
                controller.start(); controller.initialize()
                assertTrue(controller.configure(LockMode.PIN,LockTiming.IMMEDIATE,"123456".toCharArray(),"123456".toCharArray()))
                assertTrue(controller.verifyPin("123456".toCharArray(),UnlockPurpose.MANAGE))
                assertTrue(controller.configureEmergency("654321".toCharArray(),"654321".toCharArray(),charArrayOf(),true))
                controller.stop(); controller.start()
                assertFalse(controller.verifyPin("654321".toCharArray()))
                assertFalse(controller.state.value.canShowContent)
            }
            // Observe automatic Application handoff before manually retrying the injected
            // first-close failure. A missing committed-state watcher must fail this probe.
            withContext(Dispatchers.Main) { withTimeout(10000) { while(closes==0) delay(10) } }
            try { app.localOperationCoordinator.resume() } catch (_: java.io.IOException) {}
            app.localOperationCoordinator.resume()
            assertTrue(app.localOperationCoordinator.ownersQuiesced)
            assertEquals(2,closes); assertTrue(closed); assertTrue(joined); assertTrue(marker.exists())
            assertArrayEquals(byteArrayOf(1,2,3),marker.readBytes())
            scenario.onActivity { assertTrue(app.localOperationGate.blocked) }
            try { app.media; fail("media reopened") } catch (_: org.ghostcloak.app.access.LocalOperationBlocked) {}
        } } finally {credentialScope.cancel()}
    }
}
