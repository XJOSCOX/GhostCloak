package org.ghostcloak.app

import android.content.ContextWrapper
import android.os.Bundle
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import kotlinx.coroutines.*
import org.ghostcloak.app.access.*
import org.ghostcloak.app.application.*
import org.ghostcloak.attachments.*
import org.ghostcloak.crypto.*
import org.ghostcloak.identity.RandomIdentifiers
import org.ghostcloak.messaging.*
import org.ghostcloak.storage.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import android.view.accessibility.AccessibilityNodeInfo

/** Opt-in real Application/Activity acceptance. No shipped arming or fault-injection hook.
 * Only the transport field is replaced by a socket-free protocol fixture before the store opens.
 * Build a signed RELEASE with fixture.invalid origin; host MUST verify the disposable AVD first.
 */
class ReleaseSafeExitAcceptanceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as GhostApplication
    private fun guard(stage: String) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("safeExitReleaseStage") == stage)
        check(!BuildConfig.DEBUG && BuildConfig.API_ORIGIN == "https://fixture.invalid" && android.os.Build.HARDWARE == "ranchu")
        check(BuildConfig.EMERGENCY_PIN_ARMING_ENABLED && BuildConfig.EMERGENCY_WIPE_DESTRUCTIVE_READY)
    }
    private enum class AcceptanceState { APP_UNLOCKED, SETTINGS_AUTH_REQUIRED, SETTINGS_AUTHORIZED, APP_LOCKED, BIOMETRIC_PROMPT_ACTIVE, SAFE_EXIT_TRIGGER_READY }
    private enum class AuthPurposeCategory { APP_UNLOCK, SETTINGS_ENTRY, SAFE_EXIT_ADMIN, NONE }
    private fun textVisible(label: String) = compose.onAllNodesWithText(label, substring = false).fetchSemanticsNodes().isNotEmpty()
    private fun actionVisible(label: String) = compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes().isNotEmpty()
    private fun chatsVisible() = actionVisible("New chat") && actionVisible("Search conversations") && actionVisible("Chat")
    private fun authPurpose(): AuthPurposeCategory = when {
        textVisible("Authenticate to open Settings") -> AuthPurposeCategory.SETTINGS_ENTRY
        textVisible("Confirm access") -> AuthPurposeCategory.SAFE_EXIT_ADMIN
        textVisible("Unlock Ghost Cloak") -> AuthPurposeCategory.APP_UNLOCK
        else -> AuthPurposeCategory.NONE
    }
    private fun screenCategory(): String = when {
        chatsVisible() -> "CHATS"
        textVisible("Authenticate to open Settings") -> "SETTINGS_AUTH"
        textVisible("Unlock Ghost Cloak") -> "APP_LOCK"
        else -> "OTHER"
    }
    private fun safeState(category: String, scenario: ActivityScenario<MainActivity>) {
        var activityCategory = "NONE"
        scenario.onActivity { activityCategory = it.javaClass.simpleName }
        val state=app.appLock.state.value
        milestone("STATE_${category}_ACTIVITY_${activityCategory}_NAV_${screenCategory()}_AUTH_${authPurpose()}_LOCK_${if(state.canShowContent) "OPEN" else "CLOSED"}_SETTINGS_${state.settingsGranted}_JOURNAL_${app.localOperationGate.state.value}_BIO_${systemPromptVisible("Unlock Ghost Cloak")}")
    }
    private fun assertState(expected: AcceptanceState, scenario: ActivityScenario<MainActivity>) {
        safeState(expected.name, scenario)
        val state=app.appLock.state.value
        when(expected) {
            AcceptanceState.APP_UNLOCKED -> { assertTrue(state.canShowContent); assertEquals(AuthPurposeCategory.NONE,authPurpose()); assertTrue(chatsVisible()) }
            AcceptanceState.SETTINGS_AUTH_REQUIRED -> {assertTrue(state.canShowContent); assertEquals(AuthPurposeCategory.SETTINGS_ENTRY,authPurpose()); assertFalse(state.settingsGranted)}
            AcceptanceState.SETTINGS_AUTHORIZED -> {assertTrue(state.canShowContent); assertTrue(state.settingsGranted); assertEquals(AuthPurposeCategory.NONE,authPurpose())}
            AcceptanceState.APP_LOCKED -> {assertFalse(state.canShowContent); assertEquals(AuthPurposeCategory.APP_UNLOCK,authPurpose()); assertFalse(state.settingsGranted)}
            AcceptanceState.BIOMETRIC_PROMPT_ACTIVE -> {assertFalse(state.canShowContent); assertEquals(AuthPurposeCategory.APP_UNLOCK,authPurpose()); assertTrue(systemPromptVisible("Unlock Ghost Cloak"))}
            AcceptanceState.SAFE_EXIT_TRIGGER_READY -> {
                assertFalse(state.canShowContent)
                assertEquals("Safe Exit PIN must be entered on the normal app lock",AuthPurposeCategory.APP_UNLOCK,authPurpose())
                assertFalse(state.settingsGranted)
                assertEquals(LocalOperationState.NONE,app.localOperationGate.state.value)
                assertFalse(systemPromptVisible("Unlock Ghost Cloak"))
            }
        }
    }
    private suspend fun leaveSettingsForChats(scenario: ActivityScenario<MainActivity>) {
        // The navigation destination may be restored when the private subtree is recomposed.
        // Select the public Chat tab explicitly, then observe the resulting screen.
        compose.onNodeWithContentDescription("Chat").performClick()
        withTimeout(10_000) {while(!chatsVisible() || textVisible("Authenticate to open Settings") || app.appLock.state.value.settingsGranted) delay(100)}
        assertState(AcceptanceState.APP_UNLOCKED,scenario)
    }
    private fun milestone(category: String) = instrumentation.sendStatus(2,Bundle().apply {putString("stream","SAFE_EXIT_GATE=$category\n")})
    private fun records(runtime: AppRuntime) = AppRuntime::class.java.getDeclaredField("store").apply {isAccessible=true}.get(runtime) as EncryptedEndpointStore
    private fun attachTransport(runtime: AppRuntime, api: SyntheticNetwork) {
        // No alternate runtime, state engine, crypto owner or coordinator is installed.
        check(AppRuntime::class.java.getDeclaredField("network").apply {isAccessible=true}.get(runtime)==null)
        AppRuntime::class.java.getDeclaredField("connection").apply {isAccessible=true}.set(runtime,api)
    }
    private suspend fun waitFor(test: () -> Boolean) = withTimeout(60_000) {while(!test()) delay(100)}
    private fun systemPromptVisible(title: String): Boolean {
        val root=instrumentation.uiAutomation.rootInActiveWindow ?: return false
        if (root.packageName?.toString()==app.packageName) return false
        fun contains(node: AccessibilityNodeInfo): Boolean {
            if (node.text?.toString()==title) return true
            for(index in 0 until node.childCount) node.getChild(index)?.let {if(contains(it)) return true}
            return false
        }
        return contains(root)
    }
    private suspend fun requireLockedBiometricPrompt(previous: Long) {
        waitFor {app.appLock.state.value.visible && !app.appLock.state.value.canShowContent &&
            app.appLock.state.value.presentation>previous}
        compose.onNodeWithText("Unlock Ghost Cloak",substring=false).assertExists()
        waitFor {systemPromptVisible("Unlock Ghost Cloak")}
    }
    private suspend fun backgroundAndResume(scenario: ActivityScenario<MainActivity>): Long {
        val previous=app.appLock.state.value.presentation
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        waitFor {!app.appLock.state.value.visible && !app.appLock.state.value.canShowContent}
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        return previous
    }
    private fun input(pin: String, button: String = "Unlock") {
        compose.onNodeWithText("PIN",substring=false).performTextInput(pin)
        compose.onNodeWithText(button,substring=false).performScrollTo().performClick()
    }
    private fun shell(command: String) { instrumentation.uiAutomation.executeShellCommand(command).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() } }
    private fun inspectionRoot()=File(app.cacheDir,"safe-exit-probe-inspection")
    private suspend fun fresh() {
        assertFalse(app.localOperationGate.blocked)
        app.runtime.use {assertNull(it.open()); assertTrue(it.contacts().isEmpty()); assertFalse(app.runtime.canAutoSync)}
        assertFalse(app.appLock.state.value.emergencyEnabled)
        assertEquals(LockMode.OFF,app.appLock.state.value.mode)
        assertTrue(app.getSystemService(android.app.NotificationManager::class.java).activeNotifications.isEmpty())
        compose.onNodeWithText("Create identity",substring=false).assertExists()
    }
    /** Actual release biometric gate, run with an empty disposable app before the wipe suite. */
    @Test fun isolatedReleaseBiometricUnlockFailureCancelAndPinFallback()=runBlocking {
        guard("biometric")
        check(BiometricManager.from(app).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)==BiometricManager.BIOMETRIC_SUCCESS)
        withContext(Dispatchers.Main) {
            app.appLock.start(); app.appLock.initialize()
            assertTrue(app.appLock.configure(LockMode.PIN,LockTiming.IMMEDIATE,"123456".toCharArray(),"123456".toCharArray()))
            assertTrue(app.appLock.verifyPin("123456".toCharArray(),UnlockPurpose.MANAGE))
            assertTrue(app.appLock.configureEmergency("654321".toCharArray(),"654321".toCharArray(),charArrayOf(),true))
        }
        ActivityScenario.launch(MainActivity::class.java).use {scenario ->
            waitFor {app.appLock.state.value.canShowContent}
            val enrolled=CompletableDeferred<Boolean>()
            val callbackScope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
            scenario.onActivity {activity ->
                val ticket=app.appLock.beginBiometric(UnlockPurpose.ENROLL)!!
                BiometricPrompt(activity,ContextCompat.getMainExecutor(activity),object:BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result:BiometricPrompt.AuthenticationResult) {
                        callbackScope.launch {enrolled.complete(app.appLock.completeBiometric(ticket))}
                    }
                    override fun onAuthenticationError(errorCode:Int,errString:CharSequence) {enrolled.complete(false)}
                }).authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("Safe Exit acceptance enrollment")
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    .setNegativeButtonText("Cancel").build())
            }
            waitFor {systemPromptVisible("Safe Exit acceptance enrollment")}
            milestone("ISOLATED_BIOMETRIC_ENROLL_PROMPT")
            assertTrue(withTimeout(60_000) {enrolled.await()}); callbackScope.cancel()
            withContext(Dispatchers.Main) {
                assertTrue(app.appLock.verifyPin("123456".toCharArray(),UnlockPurpose.MANAGE))
                assertTrue(app.appLock.configure(LockMode.COMBINED,LockTiming.IMMEDIATE,"123456".toCharArray(),"123456".toCharArray()))
            }
            assertTrue(app.appLock.state.value.canShowContent)
            assertEquals(LocalOperationState.NONE,app.localOperationGate.state.value)
            val aliases=AndroidDestructionKeys().aliases().filter {AndroidLocalDestruction.ownedAlias(it)}.toSet()
            assertTrue(aliases.isNotEmpty())
            val first=backgroundAndResume(scenario)
            requireLockedBiometricPrompt(first)
            milestone("ISOLATED_BIOMETRIC_UNLOCK_PROMPT")
            waitFor {app.appLock.state.value.canShowContent}
            assertEquals(LocalOperationState.NONE,app.localOperationGate.state.value)
            assertTrue(app.appLock.state.value.emergencyEnabled)
            assertEquals(aliases,AndroidDestructionKeys().aliases().filter {AndroidLocalDestruction.ownedAlias(it)}.toSet())
            milestone("isolated_biometric_success_no_wipe")
            val second=backgroundAndResume(scenario)
            requireLockedBiometricPrompt(second)
            milestone("ISOLATED_BIOMETRIC_FAIL_PROMPT")
            waitFor {!systemPromptVisible("Unlock Ghost Cloak")}
            assertFalse(app.appLock.state.value.canShowContent)
            assertEquals(LocalOperationState.NONE,app.localOperationGate.state.value)
            // A failed scan must not auto-reopen. Explicit retry is the next transition.
            compose.onNodeWithText("Use biometric").performClick()
            waitFor {systemPromptVisible("Unlock Ghost Cloak")}
            milestone("ISOLATED_BIOMETRIC_CANCEL_PROMPT")
            shell("input keyevent KEYCODE_BACK")
            waitFor {!systemPromptVisible("Unlock Ghost Cloak")}
            assertFalse(app.appLock.state.value.canShowContent)
            assertEquals(LocalOperationState.NONE,app.localOperationGate.state.value)
            input("123456")
            waitFor {app.appLock.state.value.canShowContent}
            assertTrue(app.appLock.state.value.emergencyEnabled)
            assertEquals(LocalOperationState.NONE,app.localOperationGate.state.value)
            assertEquals(aliases,AndroidDestructionKeys().aliases().filter {AndroidLocalDestruction.ownedAlias(it)}.toSet())
            milestone("isolated_failure_cancel_pin_no_wipe")
        }
    }
    @Test fun actualReleaseOfflineWipeAndOldStateProbes()=runBlocking {
        val stage=InstrumentationRegistry.getArguments().getString("safeExitReleaseStage")
        assumeTrue(stage=="run" || stage=="wipe")
        check(!BuildConfig.DEBUG && BuildConfig.API_ORIGIN == "https://fixture.invalid" && android.os.Build.HARDWARE == "ranchu")
        check(BuildConfig.EMERGENCY_PIN_ARMING_ENABLED && BuildConfig.EMERGENCY_WIPE_DESTRUCTIVE_READY)
        if(stage=="run") check(BiometricManager.from(app).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)==BiometricManager.BIOMETRIC_SUCCESS) {"enroll_disposable_avd_biometric_first"}
        val api=SyntheticNetwork(); val a=app.runtime
        attachTransport(a,api)
        a.use {assertNull(it.open()); a.create(it,"fixturealice")}
        val b=AppRuntime(app,"https://fixture.invalid",RandomIdentifiers.create(),api)
        val c=AppRuntime(app,"https://fixture.invalid",RandomIdentifiers.create(),api)
        val d=AppRuntime(app,"https://fixture.invalid",RandomIdentifiers.create(),api)
        val copied=RetainedProbeStorage.open(instrumentation.context)
        copied.clear()
        var photoMessage=""; var peer=""
        try {
            b.use {b.create(it,"fixturebob")}; c.use {c.create(it,"fixturecharlie")}; d.use {d.create(it,"fixturedave")}
            val aid=a.use {it.open()!!.deviceId}; peer=b.use {it.open()!!.deviceId}
            a.use {a.addNetwork("fixturebob",it); a.send(it,peer,"synthetic outbound")}
            b.use {b.syncNetwork(it); it.acceptRequest(aid); b.send(it,aid,"synthetic inbound")}
            a.use {a.syncNetwork(it); it.acceptRequest(peer); a.addNetwork("fixturecharlie",it)}
            val cid=c.use {it.open()!!.deviceId}
            a.use {it.block(cid,true)}
            d.use {d.addNetwork("fixturealice",it); d.send(it,aid,"synthetic request")}
            a.use {a.syncNetwork(it); a.setDisappearing(it,peer,3600)}
            a.use {
                val r=records(a); val repository=LocalRepository(r)
                val store=AppRuntime::class.java.getDeclaredField("attachments").apply {isAccessible=true}.get(a) as AttachmentStore
                val photo=java.io.ByteArrayOutputStream().also {out -> android.graphics.Bitmap.createBitmap(8,8,android.graphics.Bitmap.Config.ARGB_8888).let {bitmap -> check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,85,out)); bitmap.recycle()}}.toByteArray()
                for(kind in listOf(AttachmentKind.IMAGE,AttachmentKind.DOCUMENT)) {
                    val bytes=if(kind==AttachmentKind.IMAGE) photo else byteArrayOf(1,2,3)
                    val mid=RandomIdentifiers.create()
                    val desc=prepareAcceptanceAttachment(store,bytes,kind,"$peer/$mid")
                    repository.save(Message(mid,peer,Direction.INCOMING,"",System.currentTimeMillis(),MessageState.RECEIVED))
                    repository.attachment(peer,mid,AttachmentFormat.encode(desc))
                    assertEquals(desc.id,repository.attachment(peer,mid)!!.id)
                    assertEquals(TransferState.READY,store.entry(desc.id)!!.state)
                    val cipher=File(app.noBackupFilesDir,"local-attachments/upload/${desc.id}")
                    val clear=File(app.cacheDir,"safe-exit-${kind.name}")
                    AttachmentFormat.decrypt(desc,cipher,clear); assertArrayEquals(bytes,clear.readBytes())
                    copied.copyFrom("${kind.name}.cipher",cipher)
                    assertTrue(copied.exists("${kind.name}.cipher"))
                    assertTrue(copied.bytes("${kind.name}.cipher").isNotEmpty())
                    assertArrayEquals(cipher.readBytes(),copied.bytes("${kind.name}.cipher"))
                    if(kind==AttachmentKind.IMAGE) photoMessage=mid
                    desc.key.fill(0)
                }
                assertTrue(it.contacts().any {row -> row.contact.blocked})
                assertTrue(it.contacts().any {row -> row.contact.request})
                assertTrue(r.transaction {r.keys("network/").isNotEmpty()})
                assertTrue(r.transaction {r.keys("app/notification/").isNotEmpty()})
            }
        } finally {b.close(); c.close(); d.close()}
        val authAliases=AndroidDestructionKeys().aliases().filter {it.startsWith("ghostcloak.auth.")}
        check(authAliases.isNotEmpty())
        authAliases.forEach {check(KeystoreDeviceAuth().sign(it,byteArrayOf(1)).isNotEmpty())}
        val oldService=a.use {assertNotNull(it.open()); it}
        withContext(Dispatchers.Main) {
            app.appLock.start(); app.appLock.initialize()
            assertTrue(app.appLock.configure(LockMode.PIN,LockTiming.IMMEDIATE,"123456".toCharArray(),"123456".toCharArray()))
            assertTrue(app.appLock.verifyPin("123456".toCharArray(),UnlockPurpose.MANAGE))
            assertTrue(app.appLock.configureEmergency("654321".toCharArray(),"654321".toCharArray(),charArrayOf(),true))
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor {app.appLock.state.value.ready}
            if(stage=="run") {
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED); scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            compose.waitUntil(30_000) {!app.appLock.state.value.canShowContent}
            assertState(AcceptanceState.APP_LOCKED,scenario)
            input("123455"); compose.waitUntil(30_000) {!app.appLock.state.value.busy && app.appLock.state.value.message!=null}
            assertFalse(app.appLock.state.value.canShowContent); assertFalse(app.localOperationGate.blocked)
            input("654320"); compose.waitUntil(30_000) {!app.appLock.state.value.busy}
            assertFalse(app.appLock.state.value.canShowContent); assertFalse(app.localOperationGate.blocked)
            input("123456"); compose.waitUntil(30_000) {app.appLock.state.value.canShowContent}; milestone("normal_wrong_near_match")
            compose.onNodeWithContentDescription("Settings").performClick()
            assertState(AcceptanceState.SETTINGS_AUTH_REQUIRED,scenario)
            input("654321","Open Settings"); compose.waitUntil(30_000) {!app.appLock.state.value.busy}
            assertFalse(app.appLock.state.value.settingsGranted); assertFalse(app.localOperationGate.blocked)
            input("123456","Open Settings"); compose.waitUntil(30_000) {app.appLock.state.value.settingsGranted}
            assertState(AcceptanceState.SETTINGS_AUTHORIZED,scenario)
            withContext(Dispatchers.Main) {
                assertFalse(app.appLock.configureEmergency("987654".toCharArray(),"987654".toCharArray(),"654321".toCharArray(),true))
                assertTrue(app.appLock.verifyPin("123456".toCharArray(),UnlockPurpose.MANAGE))
                assertFalse(app.appLock.configureEmergency("987654".toCharArray(),"987654".toCharArray(),"654320".toCharArray(),true))
                assertTrue(app.appLock.configureEmergency("987654".toCharArray(),"987654".toCharArray(),"654321".toCharArray(),true))
                assertTrue(app.appLock.verifyPin("123456".toCharArray(),UnlockPurpose.MANAGE))
                assertFalse(app.appLock.disableEmergency("987654".toCharArray(),false))
                assertFalse(app.appLock.disableEmergency("987653".toCharArray(),true))
                assertTrue(app.appLock.disableEmergency("987654".toCharArray(),true))
                assertTrue(app.appLock.verifyPin("123456".toCharArray(),UnlockPurpose.MANAGE))
                assertTrue(app.appLock.configureEmergency("654321".toCharArray(),"654321".toCharArray(),charArrayOf(),true))
                assertTrue(app.appLock.verifyPin("123456".toCharArray(),UnlockPurpose.MANAGE))
                assertFalse(app.appLock.configure(LockMode.OFF,LockTiming.IMMEDIATE,charArrayOf(),charArrayOf()))
            }
            milestone("settings_dual_credentials")
            val enrolled=CompletableDeferred<Boolean>(); val callbackScope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
            scenario.onActivity {activity ->
                val ticket=app.appLock.beginBiometric(UnlockPurpose.ENROLL)!!
                val prompt=BiometricPrompt(activity,ContextCompat.getMainExecutor(activity),object:BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result:BiometricPrompt.AuthenticationResult) {callbackScope.launch {enrolled.complete(app.appLock.completeBiometric(ticket))}}
                    override fun onAuthenticationError(errorCode:Int,errString:CharSequence) {enrolled.complete(false)}
                })
                prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("Safe Exit acceptance enrollment").setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG).setNegativeButtonText("Cancel").build())
            }
            waitFor {systemPromptVisible("Safe Exit acceptance enrollment")}
            milestone("BIOMETRIC_ENROLL_PROMPT")
            assertTrue(withTimeout(60_000) {enrolled.await()}); callbackScope.cancel()
            withContext(Dispatchers.Main) {assertTrue(app.appLock.configure(LockMode.COMBINED,LockTiming.IMMEDIATE,"123456".toCharArray(),"123456".toCharArray()))}
            assertState(AcceptanceState.SETTINGS_AUTH_REQUIRED,scenario)
            leaveSettingsForChats(scenario)
            val beforeBiometric=backgroundAndResume(scenario)
            requireLockedBiometricPrompt(beforeBiometric)
            assertState(AcceptanceState.BIOMETRIC_PROMPT_ACTIVE,scenario)
            milestone("BIOMETRIC_UNLOCK_PROMPT")
            waitFor {app.appLock.state.value.canShowContent}; assertFalse(app.localOperationGate.blocked)
            assertEquals(LocalOperationState.NONE,app.localOperationGate.state.value)
            assertTrue(app.appLock.state.value.emergencyEnabled)
            milestone("biometric")
            leaveSettingsForChats(scenario)
            // Return to real lock screen. Cancel auto biometric before exact PIN entry.
            val beforeCancel=backgroundAndResume(scenario)
            requireLockedBiometricPrompt(beforeCancel)
            shell("input keyevent KEYCODE_BACK")
            waitFor {!systemPromptVisible("Unlock Ghost Cloak")}
            assertState(AcceptanceState.APP_LOCKED,scenario)
            } else {
                // Isolated destructive E2E starts from the same realistic populated fixture,
                // without inheriting Settings authorization or a biometric callback.
                leaveSettingsForChats(scenario)
                val beforeLock=backgroundAndResume(scenario)
                waitFor {app.appLock.state.value.presentation>beforeLock && !app.appLock.state.value.canShowContent}
                assertState(AcceptanceState.APP_LOCKED,scenario)
                milestone("ISOLATED_WIPE_APP_LOCK_READY")
            }
            shell("cmd connectivity airplane-mode enable"); shell("svc wifi disable"); shell("svc data disable")
            a.use { }
            api.offline=true
            // Retained ciphertext is outside the wiped app sandbox; keys are never exported.
            a.use { records(a).transaction {
                for(name in listOf("local.db","local.db-wal","local.db-shm","local.wrapped")) {
                    val source=File(app.noBackupFilesDir,name)
                    if(source.exists()) copied.copyFrom(name,source)
                }
            } }
            val beforeRoot=copied.materializeDatabase(inspectionRoot())
            val beforeInspection=object:ContextWrapper(app) {override fun getNoBackupFilesDir()=beforeRoot}
            EncryptedEndpointStore.open(beforeInspection,"local").use {saved ->
                assertTrue(saved.transaction {saved.read("local/device")!=null})
                assertTrue(saved.transaction {saved.keys("network/").isNotEmpty()})
            }
            check(beforeRoot.deleteRecursively())
            // syncActive describes queued delivery state, not an in-flight FETCH.
            // The lock gate has already removed the foreground Compose subtree.
            val before=api.requests
            assertState(AcceptanceState.SAFE_EXIT_TRIGGER_READY,scenario)
            val observedStates=java.util.Collections.synchronizedList(mutableListOf<LocalOperationState>())
            // Unconfined observes each synchronous StateFlow transition inline; Main can
            // coalesce two adjacent journal writes before the observer is dispatched.
            val journalObserver=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined).launch(start=CoroutineStart.UNDISPATCHED) {
                app.localOperationGate.state.collect { state ->
                    if (state!=LocalOperationState.CORRUPT && (observedStates.lastOrNull()!=state)) {
                        observedStates.add(state)
                        if (state!=LocalOperationState.NONE) milestone("JOURNAL_${state.name}")
                    }
                }
            }
            milestone("SAFE_EXIT_PIN_ON_APP_LOCK")
            input("654321")
            waitFor {app.localOperationGate.state.value!=LocalOperationState.NONE || app.freshGeneration.value>0}
            milestone("SAFE_EXIT_DESTRUCTION_STARTED")
            waitFor {app.freshGeneration.value>0 && !app.localOperationGate.blocked}
            journalObserver.cancelAndJoin()
            assertEquals(
                LocalOperationState.entries.filter { it!=LocalOperationState.CORRUPT } + LocalOperationState.NONE,
                observedStates.toList(),
            )
            compose.waitUntil(30_000) {app.appLock.state.value.ready}
            fresh(); assertEquals(before,api.requests)
            val afterRoot=copied.materializeDatabase(inspectionRoot())
            val inspection=object:ContextWrapper(app) {override fun getNoBackupFilesDir()=afterRoot}
            try {EncryptedEndpointStore.open(inspection,"local").close(); fail("copied_database_reopened")} catch(_:EndpointStorageFailure) {}
            authAliases.forEach {alias -> assertTrue(AndroidDestructionKeys().absent(alias)); try {KeystoreDeviceAuth().sign(alias,byteArrayOf(1)); fail("old_device_credential_signed")} catch(_:EndpointStorageFailure) {}}
            try {oldService.open(); fail("old_signal_store_reopened")} catch(_:Exception) {}
            try {app.runtime.downloadAttachment(peer,photoMessage); fail("old_attachment_reopened")} catch(_:IllegalStateException) {}
            assertTrue(copied.exists("IMAGE.cipher")); assertTrue(copied.exists("DOCUMENT.cipher"))
            assertTrue(File(app.noBackupFilesDir,"local-attachments/upload").listFiles().orEmpty().isEmpty())
            assertFalse(File(app.cacheDir,"safe-exit-IMAGE").exists()); assertFalse(File(app.cacheDir,"safe-exit-DOCUMENT").exists())
            assertNull(app.runtime.readAppLock()); assertEquals(before,api.requests)
            check(afterRoot.deleteRecursively())
            copied.write("verified","complete".toByteArray())
            milestone("offline_wipe_old_state_probes_zero_network")
        }
    }
    @Test fun actualReleaseFreshAfterProcessRestartRebootAndNetworkReconnect()=runBlocking {
        guard("post")
        val copied=RetainedProbeStorage.open(instrumentation.context)
        check(copied.bytes("verified").decodeToString()=="complete")
        ActivityScenario.launch(MainActivity::class.java).use {
            waitFor {app.appLock.state.value.ready}
            compose.waitUntil(30_000) {try {compose.onNodeWithText("Create identity",substring=false).assertExists(); true} catch(_:AssertionError) {false}}
            fresh()
            shell("cmd connectivity airplane-mode disable"); shell("svc wifi enable"); shell("svc data enable")
            delay(5000); fresh()
            val root=copied.materializeDatabase(inspectionRoot())
            val inspection=object:ContextWrapper(app) {override fun getNoBackupFilesDir()=root}
            try {EncryptedEndpointStore.open(inspection,"local").close(); fail("copied_database_reopened")} catch(_:EndpointStorageFailure) {}
            check(root.deleteRecursively())
            milestone("restart_reboot_reconnect_fresh")
            if(InstrumentationRegistry.getArguments().getString("safeExitFinalProbeCleanup")=="true") copied.clear()
        }
    }
}
