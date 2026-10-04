package org.ghostcloak.app

import android.content.ContextWrapper
import android.os.SystemClock
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import org.ghostcloak.app.access.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Two explicit invocations around an actual disposable-AVD reboot. */
class InactivityRebootTest {
    private fun fixture(): Pair<ContextWrapper,File> {
        check(BuildConfig.DEBUG && BuildConfig.API_ORIGIN.isEmpty() && android.os.Build.HARDWARE=="ranchu")
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(base.noBackupFilesDir,"inactivity-reboot-fixture").apply {mkdirs()}
        return object: ContextWrapper(base) {override fun getNoBackupFilesDir()=root} to root
    }
    private fun observation(context: ContextWrapper)=InactivityObservation(
        Settings.Global.getInt(context.contentResolver,Settings.Global.BOOT_COUNT,-1),
        SystemClock.elapsedRealtime(),System.currentTimeMillis())
    @Test fun prepareBeforeActualReboot() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("inactivityStage")=="prepare")
        val (context,root)=fixture()
        check(root.listFiles().isNullOrEmpty())
        val now=observation(context); assumeTrue(now.boot>=0)
        val policy=InactivityProtection(DurableInactivityStore(context),{observation(context)},{error("unexpected_arm")})
        assertEquals(InactivityAccess.DISABLED,policy.check())
        assertTrue(policy.configure(InactivityPeriod.DAYS_7))
        assertEquals(InactivityAccess.VALID,policy.check())
    }
    @Test fun resumeAfterActualRebootRequiresNormalOwnerProof() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("inactivityStage")=="resume")
        val (context,root)=fixture()
        val store=DurableInactivityStore(context)
        var arms=0
        val policy=InactivityProtection(store,{observation(context)},{arms++})
        try {
            assertEquals(InactivityAccess.TIME_UNCERTAIN,policy.check())
            assertFalse(policy.normalAccessAllowed)
            assertEquals(0,arms)
            assertTrue(policy.authenticated()) // Pure timing proof; AppLock match is tested separately.
            assertEquals(InactivityAccess.VALID,policy.check())
            assertEquals(0,arms)
        } finally {root.listFiles()?.forEach {it.delete()};root.delete()}
    }
}
