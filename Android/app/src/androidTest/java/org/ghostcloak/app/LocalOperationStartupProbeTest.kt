package org.ghostcloak.app

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.testing.TestListenableWorkerBuilder
import org.ghostcloak.app.application.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in second-process probe. Harness installs an ARMED/QUIESCING/corrupt journal in a
 * disposable AVD, force-stops/relaunches, then invokes this class. Never arms or clears state. */
class LocalOperationStartupProbeTest {
    @Test fun actualApplicationActivityAndWorkerCannotInitializeSensitiveOwners() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("localOperationStartupProbe") == "true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val app=context.applicationContext as GhostApplication
        assertTrue(app.localOperationGate.blocked)
        assertFalse(app.sensitiveOwnersInitialized)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { assertFalse(app.sensitiveOwnersInitialized) }
        }
        kotlinx.coroutines.runBlocking {
            val worker=TestListenableWorkerBuilder<BackgroundSyncWorker>(context).build()
            assertEquals(androidx.work.ListenableWorker.Result.success(),worker.doWork())
        }
        assertFalse(app.sensitiveOwnersInitialized)
    }
}
