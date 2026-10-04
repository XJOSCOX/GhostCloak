package org.ghostcloak.app

import android.content.*
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.ghostcloak.app.access.*
import org.ghostcloak.storage.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in two-invocation restart probe. Fixed synthetic namespace only, no real Application arming. */
class LocalDestructionRebootTest {
    private val endpoint="safe-exit-reboot-fixture"
    private val auth="ghostcloak.auth."+"b".repeat(64)+".11111111-1111-1111-1111-111111111111"
    private fun fixture(): Context {
        check(BuildConfig.DEBUG && BuildConfig.API_ORIGIN.isEmpty() && android.os.Build.HARDWARE=="ranchu")
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(target.noBackupFilesDir,"safe-exit-reboot-fixture").apply {mkdirs()}
        return object : ContextWrapper(target) {
            private fun directory(name: String)=File(root,name).apply {mkdirs()}
            override fun getNoBackupFilesDir()=directory("noBackup")
            override fun getFilesDir()=directory("files")
            override fun getCacheDir()=directory("cache")
            override fun getDatabasePath(name: String)=if(File(name).isAbsolute) File(name) else File(directory("databases"),name)
            override fun getSharedPreferences(name: String,mode: Int)=baseContext.getSharedPreferences("reboot-fixture-$name",mode)
            override fun getApplicationContext(): Context=this
        }
    }
    private fun keys(): DestructionKeys {
        val real=AndroidDestructionKeys(); val own=setOf("ghost-cloak.db.$endpoint",auth)
        return object : DestructionKeys {
            override fun aliases()=real.aliases().filter {it in own}
            override fun absent(alias: String)=real.absent(alias)
            override fun delete(alias: String) {check(alias in own); real.delete(alias)}
        }
    }
    @Test fun prepareIrreversiblePendingForProcessDeathAndReboot()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("safeExitRebootStage")=="prepare")
        val f=fixture(); val journal=DurableLocalOperationJournal(f); assertEquals(LocalOperationState.NONE,journal.read())
        EncryptedEndpointStore.open(f,endpoint).use {it.transaction {it.write("synthetic-private",byteArrayOf(1,2,3))}}
        DurableInactivityStore(f).write(InactivityRecord(InactivityPeriod.DAYS_7,3,1000L,1_700_000_000_000L,1_700_000_000_000L))
        KeystoreDeviceAuth().publicKey(auth,true)
        val gate=LocalOperationGate(journal); gate.armAfterCredential()
        val engine=AndroidLocalDestruction(f,keys(),{if(it==DestructionCheckpoint.KEY_DELETED) throw java.io.IOException("synthetic_crash")})
        try {LocalOperationCoordinator(gate,engine) {}.resume(); fail("fault missed")} catch(_: java.io.IOException) {}
        assertEquals(LocalOperationState.KEY_DESTRUCTION_PENDING,journal.read())
        assertTrue(keys().absent("ghost-cloak.db.$endpoint")); assertFalse(keys().absent(auth))
    }
    @Test fun resumeAfterProcessDeathAndActualRebootWithoutRegeneration()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("safeExitRebootStage")=="resume")
        val f=fixture(); val journal=DurableLocalOperationJournal(f)
        assertEquals(LocalOperationState.KEY_DESTRUCTION_PENDING,journal.read())
        val gate=LocalOperationGate(journal); assertTrue(gate.blocked)
        assertTrue(keys().absent("ghost-cloak.db.$endpoint")); assertFalse(keys().absent(auth))
        LocalOperationCoordinator(gate,AndroidLocalDestruction(f,keys())) {}.resume()
        assertEquals(LocalOperationState.NONE,journal.read()); assertFalse(gate.blocked)
        assertTrue(keys().aliases().isEmpty()); assertTrue(f.noBackupFilesDir.listFiles()!!.isEmpty())
    }
}
