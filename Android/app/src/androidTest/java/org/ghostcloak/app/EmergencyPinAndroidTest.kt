package org.ghostcloak.app

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.ghostcloak.app.access.*
import org.ghostcloak.app.application.*
import org.ghostcloak.app.ui.theme.GhostCloakTheme
import org.ghostcloak.identity.RandomIdentifiers
import org.ghostcloak.storage.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.KeyStore

/** Only isolated synthetic stores and credentials; never arms the Application's real gate. */
class EmergencyPinAndroidTest {
    @get:Rule val compose=createComposeRule()
    private val target get()=InstrumentationRegistry.getInstrumentation().targetContext
    private class Fixture(context: Context, fault: JournalCheckpoint?=null) : ContextWrapper(context), LocalStateAccessOwner {
        private val directory=File(context.noBackupFilesDir,"emergency-fixture-${RandomIdentifiers.create()}").apply {mkdirs()}
        override fun getNoBackupFilesDir()=directory
        override fun getApplicationContext(): Context=this
        val endpoint=RandomIdentifiers.create()
        val journal=DurableLocalOperationJournal(this) { if(it==fault) throw java.io.IOException("synthetic_journal_failure") }
        val gate=LocalOperationGate(journal)
        override val localStateAccess get()=gate
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val runtime=AppRuntime(this,"",endpoint,operationGate=gate)
        fun controller()=AppLockController(object : LockPersistence {
            override suspend fun read()=runtime.readAppLock()
            override suspend fun write(bytes: ByteArray)=runtime.writeAppLock(bytes)
        },scope,android.os.SystemClock::elapsedRealtime,{1},true,{gate.armAfterCredential()})
        suspend fun enroll(controller: AppLockController) = withContext(Dispatchers.Main) {
            controller.start(); controller.initialize()
            assertTrue(controller.configure(LockMode.PIN,LockTiming.IMMEDIATE,"123456".toCharArray(),"123456".toCharArray()))
            assertTrue(controller.verifyPin("123456".toCharArray(),UnlockPurpose.MANAGE))
            assertTrue(controller.configureEmergency("654321".toCharArray(),"654321".toCharArray(),charArrayOf(),true))
        }
    }
    @Test fun encryptedRecordSurvivesRuntimeRecreationIdentityAndKeystoreUnchanged()=runBlocking {
        val f=Fixture(target); val identity=f.runtime.use {it.create("Synthetic")}
        val aliases=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}.aliases().toList().toSet()
        try {
            val controller=f.controller(); f.enroll(controller)
            val bytes=f.runtime.readAppLock()!!
            assertFalse(bytes.decodeToString().contains("654321")); assertTrue(LockConfiguration.decode(bytes).emergency!=null)
            f.runtime.close()
            val restored=AppRuntime(f,"",f.endpoint,operationGate=f.gate)
            try {
                val lock=AppLockController(object : LockPersistence {
                    override suspend fun read()=restored.readAppLock()
                    override suspend fun write(bytes: ByteArray)=restored.writeAppLock(bytes)
                },f.scope,android.os.SystemClock::elapsedRealtime,{1},true,{f.gate.armAfterCredential()})
                withContext(Dispatchers.Main) { lock.start(); lock.initialize() }
                assertTrue(lock.state.value.emergencyEnabled); assertFalse(lock.state.value.canShowContent)
                assertEquals(identity.deviceId,restored.use {it.open()!!.deviceId})
                assertArrayEquals(identity.publicKey,restored.use {it.open()!!.publicKey})
                assertEquals(aliases,KeyStore.getInstance("AndroidKeyStore").apply {load(null)}.aliases().toList().toSet())
                // Neither on-disk SQLCipher bytes nor wrapped database key expose numeric credentials.
                for(suffix in listOf("db","wrapped")) {
                    val encoded=File(f.noBackupFilesDir,"${f.endpoint}.$suffix").readBytes().toString(Charsets.ISO_8859_1)
                    assertFalse(encoded.contains("654321")); assertFalse(encoded.contains("123456"))
                }
            } finally {restored.close()}
        } finally { f.scope.cancel(); f.runtime.close() }
    }
    @Test fun exactEntryRemovesPrivateUiDurablyArmsAndStopsWithoutDeletingFiles()=runBlocking {
        val f=Fixture(target); f.runtime.use {it.create("Synthetic")}; val controller=f.controller()
        try {
            f.enroll(controller)
            withContext(Dispatchers.Main) {controller.stop(); controller.start()}
            var privateComposed=false
            compose.setContent { GhostCloakTheme { AppLockGate(controller) {privateComposed=true; Text("Synthetic private route")} } }
            compose.onNodeWithText("PIN").performTextInput("654321")
            compose.onNodeWithText("Unlock",useUnmergedTree=true).performClick()
            compose.waitUntil(30000) {f.gate.blocked}
            assertFalse(privateComposed); compose.onNodeWithText("Synthetic private route").assertDoesNotExist()
            assertEquals(LocalOperationState.ARMED,DurableLocalOperationJournal(f).read())
            val coordinator=LocalOperationCoordinator(f.gate) {controller.quiesce(); f.runtime.quiesce()}
            coordinator.resume()
            assertEquals(LocalOperationState.KEY_DESTRUCTION_PENDING,f.journal.read())
            assertTrue(File(f.noBackupFilesDir,"${f.endpoint}.db").exists())
            assertTrue(File(f.noBackupFilesDir,"${f.endpoint}.wrapped").exists())
        } finally {f.scope.cancel(); f.runtime.close()}
    }
    @Test fun eachWriteFileSyncDirectorySyncReadbackFailureCannotHandoffOrUnlock()=runBlocking {
        for(stage in JournalCheckpoint.entries) {
            val f=Fixture(target,stage); f.runtime.use {it.create("Synthetic")}; val controller=f.controller()
            try {
                f.enroll(controller); withContext(Dispatchers.Main) {controller.stop(); controller.start()}
                var stops=0; val coordinator=LocalOperationCoordinator(f.gate) {stops++}
                val watch=launch(start=CoroutineStart.UNDISPATCHED) { f.gate.state.collect {
                    if(it !in setOf(LocalOperationState.NONE,LocalOperationState.CORRUPT)) coordinator.resume()
                } }
                withContext(Dispatchers.Main) {assertFalse(controller.verifyPin("654321".toCharArray()))}
                yield(); assertEquals(0,stops); assertFalse(controller.state.value.canShowContent)
                assertEquals(LocalOperationState.CORRUPT,f.gate.state.value); watch.cancelAndJoin()
                assertTrue(File(f.noBackupFilesDir,"${f.endpoint}.db").exists())
            } finally {f.scope.cancel(); f.runtime.close()}
        }
    }
    @Test fun settingsAcknowledgementControlsEnrollmentAndDisableRequiresConfirmation()=runBlocking {
        val f=Fixture(target); f.runtime.use {it.create("Synthetic")}; val controller=f.controller()
        try {
            withContext(Dispatchers.Main) {
                controller.start(); controller.initialize()
                controller.configure(LockMode.PIN,LockTiming.IMMEDIATE,"123456".toCharArray(),"123456".toCharArray())
                controller.verifyPin("123456".toCharArray(),UnlockPurpose.MANAGE)
            }
            var saved=false
            compose.setContent {GhostCloakTheme {EmergencyWipeSettingsScreen(controller) {saved=true}}}
            compose.onNodeWithText("Disabled").assertExists()
            compose.onNodeWithText("Emergency PIN").performScrollTo().performTextInput("654321")
            compose.onNodeWithText("Confirm emergency PIN").performScrollTo().performTextInput("654321")
            compose.onNodeWithText("Enable emergency PIN").performScrollTo().assertIsNotEnabled()
            compose.onNode(isToggleable()).performScrollTo().performClick()
            compose.onNodeWithText("Enable emergency PIN").performScrollTo().performClick()
            compose.waitUntil(30000) {saved}; assertTrue(controller.state.value.emergencyEnabled)
            withContext(Dispatchers.Main) {controller.verifyPin("123456".toCharArray(),UnlockPurpose.MANAGE)}
            compose.onNodeWithText("Disable Emergency Wipe").performScrollTo().performClick()
            compose.onNodeWithText("Disable Emergency Wipe?").assertExists()
            compose.onNodeWithText("Cancel").performClick(); assertTrue(controller.state.value.emergencyEnabled)
            compose.onNodeWithText("Disable Emergency Wipe").performClick()
            compose.onNodeWithText("Disable",substring=false).performClick()
            compose.waitUntil(30000) {!controller.state.value.emergencyEnabled}
            assertEquals(LocalOperationState.NONE,f.journal.read())
        } finally {f.scope.cancel(); f.runtime.close()}
    }
    @Test fun restartAfterExactPinUsesJournalWithoutOpeningEncryptedRuntime()=runBlocking {
        val f=Fixture(target); f.runtime.use {it.create("Synthetic")}; val controller=f.controller()
        try {
            f.enroll(controller); withContext(Dispatchers.Main) {controller.stop(); controller.start(); controller.verifyPin("654321".toCharArray())}
            val restored=LocalOperationGate(DurableLocalOperationJournal(f))
            assertEquals(LocalOperationState.ARMED,restored.state.value)
            try { restored.access {fail("normal owner initialized")}; fail("opened") } catch(_: LocalOperationBlocked) {}
            LocalOperationCoordinator(restored) {}.resume()
            assertEquals(LocalOperationState.KEY_DESTRUCTION_PENDING,DurableLocalOperationJournal(f).read())
        } finally {f.scope.cancel(); f.runtime.close()}
    }
}
