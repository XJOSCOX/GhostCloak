package org.ghostcloak.app

import android.os.SystemClock
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.ghostcloak.app.access.*
import org.ghostcloak.app.application.GhostApplication
import org.ghostcloak.crypto.SignalProtocolEngine
import org.ghostcloak.storage.EncryptedEndpointStore
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit two-process acceptance fixture. Run only on a disposable offline AVD. */
class InactivityStartupAndroidTest {
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as GhostApplication
    private fun guard() {check(BuildConfig.DEBUG && BuildConfig.API_ORIGIN.isEmpty() && android.os.Build.HARDWARE=="ranchu")}
    @Test fun seedExistingIdentityAndCrossBootTiming()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("inactivityStartupStage")=="prepare")
        guard()
        val context=app
        assertEquals(InactivityAccess.DISABLED,context.inactivity.check())
        EncryptedEndpointStore.open(context,"local").use { records ->
            SignalProtocolEngine(records).createIdentity("fixture")
            records.transaction {records.write("app/access-lock",LockConfiguration(
                mode=LockMode.PIN,verifier=PinVerifier.create("123456".toCharArray())).encode())}
        }
        val boot=Settings.Global.getInt(context.contentResolver,Settings.Global.BOOT_COUNT,-1)
        assertTrue(boot>0)
        val wall=System.currentTimeMillis()
        DurableInactivityStore(context).write(InactivityRecord(InactivityPeriod.DAYS_30,
            boot-1,SystemClock.elapsedRealtime(),wall,wall))
    }
    @Test fun recreatedProcessGatesOldIdentityUntilNormalPin()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("inactivityStartupStage")=="verify")
        guard()
        val context=app
        assertEquals(InactivityAccess.TIME_UNCERTAIN,context.inactivity.state.value)
        assertFalse(context.inactivity.normalAccessAllowed)
        assertTrue(context.runtime.operationBlocked)
        context.appLock.start(); context.appLock.initialize()
        assertEquals(LockMode.PIN,context.appLock.state.value.mode)
        assertFalse(context.appLock.state.value.canShowContent)
        assertFalse(context.appLock.verifyPin("123457".toCharArray()))
        assertEquals(InactivityAccess.TIME_UNCERTAIN,context.inactivity.state.value)
        assertTrue(context.appLock.verifyPin("123456".toCharArray()))
        assertEquals(InactivityAccess.VALID,context.inactivity.state.value)
        assertTrue(context.appLock.state.value.canShowContent)
        assertNotNull(context.runtime.use {it.open()})
    }
}
