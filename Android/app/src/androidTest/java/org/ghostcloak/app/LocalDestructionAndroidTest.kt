package org.ghostcloak.app

import android.content.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.ghostcloak.app.access.*
import org.ghostcloak.app.application.*
import org.ghostcloak.app.ui.navigation.GhostApp
import org.ghostcloak.app.ui.theme.GhostCloakTheme
import org.ghostcloak.attachments.AttachmentKind
import org.ghostcloak.crypto.*
import org.ghostcloak.identity.RandomIdentifiers
import org.ghostcloak.storage.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.KeyStore

/** Destruction is restricted to explicit synthetic roots and keys. NEVER uses the Application gate. */
class LocalDestructionAndroidTest {
    @get:Rule val compose=createComposeRule()
    @Before fun requireDisposableEmulator() {
        check(BuildConfig.DEBUG && BuildConfig.API_ORIGIN.isEmpty() && android.os.Build.HARDWARE=="ranchu") {"destructive_tests_require_offline_emulator"}
    }
    private val target get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun offlineSameBootInactivityExpiryUsesExistingRecoveryAndClearsTimingRecord()=runBlocking {
        val f=Fixture(target)
        try {
            f.seedSimple()
            val store=DurableInactivityStore(f)
            var elapsed=1000L
            val policy=InactivityProtection(store,{InactivityObservation(3,elapsed,1_700_000_000_000L)},
                {f.gate.armForProvenInactivity()})
            assertEquals(InactivityAccess.DISABLED,policy.check())
            assertTrue(policy.configure(InactivityPeriod.DAYS_7))
            elapsed+=InactivityPeriod.DAYS_7.millis-1
            assertEquals(InactivityAccess.VALID,policy.check())
            elapsed++
            assertEquals(InactivityAccess.EXPIRED,policy.check())
            assertEquals(LocalOperationState.ARMED,f.journal.read())
            f.restart() // A new process reconstructs the durable gate and coordinator.
            LocalOperationCoordinator(f.gate,f.engine()) {}.resume()
            assertEquals(LocalOperationState.NONE,f.journal.read())
            assertNull(DurableInactivityStore(f).read())
            assertTrue(f.own.all {f.real.absent(it)})
        } finally {f.retireKeys()}
    }
    private class Crash : RuntimeException("synthetic_interruption")
    private class Fixture(context: Context) : ContextWrapper(context), LocalStateAccessOwner {
        val id=RandomIdentifiers.create()
        val root=File(context.noBackupFilesDir,"destruction-fixture-$id").apply {mkdirs()}
        private fun directory(name: String)=File(root,name).apply {mkdirs()}
        override fun getNoBackupFilesDir()=directory("noBackup")
        override fun getFilesDir()=directory("files")
        override fun getCacheDir()=directory("cache")
        override fun getDatabasePath(name: String)=if(File(name).isAbsolute) File(name) else File(directory("databases"),name)
        override fun getSharedPreferences(name: String,mode: Int)=baseContext.getSharedPreferences("destruction-$id-$name",mode)
        override fun getApplicationContext(): Context=this
        var journal=DurableLocalOperationJournal(this)
        var gate=LocalOperationGate(journal)
        override val localStateAccess get()=gate
        val endpoint=RandomIdentifiers.create()
        val auth="ghostcloak.auth."+"a".repeat(64)+"."+RandomIdentifiers.create()
        val unrelated="unrelated-test-$id"
        val own=mutableSetOf("ghost-cloak.db.$endpoint",auth)
        val real=AndroidDestructionKeys()
        val keys=object : DestructionKeys {
            override fun aliases()=real.aliases().filter {it in own || it==unrelated}
            override fun absent(alias: String)=real.absent(alias)
            override fun delete(alias: String) {check(alias in own); real.delete(alias)}
        }
        fun restart() {journal=DurableLocalOperationJournal(this); gate=LocalOperationGate(journal)}
        fun engine(checkpoint: (DestructionCheckpoint)->Unit={},delete: (File)->Boolean={it.delete()})=AndroidLocalDestruction(this,keys,checkpoint,delete)
        suspend fun seedSimple() {
            EncryptedEndpointStore.open(this,endpoint).use {records ->
                SignalProtocolEngine(records).createIdentity("fixture")
                records.transaction {records.write("synthetic",byteArrayOf(1,2,3))}
            }
            KeystoreDeviceAuth(gate).publicKey(auth,true)
            KeystoreDeviceAuth().publicKey(unrelated,true)
            File(noBackupFilesDir,"media-presentation").apply {mkdirs()}.resolve("scratch").writeText("synthetic plaintext")
            File(cacheDir,"noncritical-cache").writeBytes(byteArrayOf(1))
            getSharedPreferences("appearance",0).edit().putString("mode","DARK").commit()
        }
        fun retireKeys() {own.forEach {if(!real.absent(it)) real.delete(it)}; if(!real.absent(unrelated)) real.delete(unrelated)}
    }
    @Test fun offlineExactPinErasesPopulatedIdentityAndShowsFreshOnboardingWithoutReconnect()=runBlocking {
        val f=Fixture(target); val api=SyntheticNetwork()
        val a=AppRuntime(f,"https://fixture.invalid",f.endpoint,api,operationGate=f.gate)
        val bEndpoint=RandomIdentifiers.create(); val cEndpoint=RandomIdentifiers.create()
        val b=AppRuntime(f,"https://fixture.invalid",bEndpoint,api,operationGate=f.gate)
        val c=AppRuntime(f,"https://fixture.invalid",cEndpoint,api,operationGate=f.gate)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val lock=AppLockController(object : LockPersistence {
            override suspend fun read()=a.readAppLock()
            override suspend fun write(bytes: ByteArray)=a.writeAppLock(bytes)
        },scope,android.os.SystemClock::elapsedRealtime,{1},true,{f.gate.armAfterCredential()})
        try {
            f.own+=setOf("ghost-cloak.db.$bEndpoint","ghost-cloak.db.$cEndpoint")
            a.use {a.create(it,"alice")}; b.use {b.create(it,"bob")}; c.use {c.create(it,"charlie")}
            val avatar=android.graphics.Bitmap.createBitmap(128,128,android.graphics.Bitmap.Config.ARGB_8888)
            avatar.eraseColor(android.graphics.Color.BLUE)
            val avatarBytes=java.io.ByteArrayOutputStream().use {output ->
                assertTrue(avatar.compress(android.graphics.Bitmap.CompressFormat.JPEG,70,output));output.toByteArray()
            }
            avatar.recycle()
            val avatarInput=File(f.noBackupFilesDir,"profile-fixture.jpg")
            val normalizedAvatar=try {
                avatarInput.writeBytes(avatarBytes)
                org.ghostcloak.app.attachments.ProfilePhotoPreparation.prepare(avatarInput)
            } finally {avatarInput.delete()}
            a.use {it.setAbout("synthetic profile");it.setProfilePhoto(normalizedAvatar)}
            assertEquals("synthetic profile",a.use {it.localProfile()}.about)
            assertArrayEquals(normalizedAvatar,a.use {it.localProfile()}.photo)
            assertNotNull(a.ownGhostCloakId())
            val aid=a.use {it.open()!!.deviceId}; val bid=b.use {it.open()!!.deviceId}
            a.use {a.addNetwork(b.ownGhostCloakId()!!,it); a.send(it,bid,"synthetic")}; b.use {b.syncNetwork(it)}
            b.use {it.acceptRequest(aid); b.send(it,aid,"reply")}; a.use {a.syncNetwork(it)}
            a.use {it.acceptRequest(bid); it.block(bid,true)}
            c.use {c.addNetwork(a.ownGhostCloakId()!!,it); c.send(it,aid,"request")}; a.use {a.syncNetwork(it)}
            a.use { }
            withContext(Dispatchers.Main) {
                lock.start(); lock.initialize()
                assertTrue(lock.configure(LockMode.PIN,LockTiming.IMMEDIATE,"123456".toCharArray(),"123456".toCharArray()))
                assertTrue(lock.verifyPin("123456".toCharArray(),UnlockPurpose.MANAGE))
                assertTrue(lock.configureEmergency("654321".toCharArray(),"654321".toCharArray(),charArrayOf(),true))
            }
            val cid=c.use {it.open()!!.deviceId}
            f.own+=f.real.aliases().filter {alias->AndroidLocalDestruction.ownedAlias(alias) && alias.startsWith("ghostcloak.auth.") && listOf(aid,bid,cid).any {alias.endsWith(".$it")}}
            val requestCount=api.requests; api.offline=true
            withContext(Dispatchers.Main) {lock.stop(); lock.start(); assertFalse(lock.verifyPin("654321".toCharArray()))}
            assertEquals(LocalOperationState.ARMED,f.journal.read())
            LocalOperationCoordinator(f.gate,f.engine()) {
                withContext(Dispatchers.Main) {lock.quiesce()}
                a.quiesce(); b.quiesce(); c.quiesce()
            }.resume()
            assertFalse(f.gate.blocked); assertEquals(requestCount,api.requests)
            assertTrue(f.own.all {f.real.absent(it)})
            f.restart(); assertFalse(f.gate.blocked)
            val fresh=AppRuntime(f,"https://fixture.invalid",f.endpoint,api,operationGate=f.gate)
            val owner=ViewModelStore(); lateinit var model: GhostViewModel
            try {
                assertNull(fresh.use {it.open()}); assertNull(fresh.ownGhostCloakId()); fresh.initializeBackground()
                assertFalse(fresh.canAutoSync); assertEquals(requestCount,api.requests)
                withContext(Dispatchers.Main) {model=GhostViewModel(target.applicationContext as android.app.Application,fresh); owner.put("fresh",model)}
                compose.setContent {GhostCloakTheme {FixtureAppLock {GhostApp(model)}}}
                compose.waitUntil(20000) {model.state.value.ready && !model.state.value.loading}
                compose.onNodeWithText("Create identity").assertExists()
                assertNull(model.state.value.identity); assertTrue(model.state.value.contacts.isEmpty())
                assertEquals("",model.state.value.ownProfile.about)
                assertNull(model.state.value.ownProfile.photo)
                assertEquals(requestCount,api.requests)
            } finally {withContext(Dispatchers.Main) {owner.clear()}; fresh.close()}
        } finally {scope.cancel(); a.close(); b.close(); c.close(); f.retireKeys()}
    }
    @Test fun keyBoundaryMakesRetainedDbAuthAndAttachmentDescriptorInaccessibleBeforeCleanup()=runBlocking {
        val f=Fixture(target)
        val database=f.noBackupFilesDir.resolve("${f.endpoint}.db")
        f.seedSimple()
        EncryptedEndpointStore.open(f,f.endpoint).use { records ->
            val signal=SignalProtocolEngine(records)
            assertNotNull(signal.createIdentity("fixture"))
            val attachmentStore=org.ghostcloak.attachments.AttachmentStore(records,File(f.noBackupFilesDir,"${f.endpoint}-attachments"))
            val descriptor=attachmentStore.prepare(byteArrayOf(1,2,3).inputStream(),3,AttachmentKind.DOCUMENT,0,"synthetic.txt") {true}
            val clear=File(f.cacheDir,"verified-attachment")
            org.ghostcloak.attachments.AttachmentFormat.decrypt(descriptor,File(f.noBackupFilesDir,"${f.endpoint}-attachments/upload/${descriptor.id}"),clear)
            assertArrayEquals(byteArrayOf(1,2,3),clear.readBytes())
        }
        val copied=File(target.cacheDir,"comparison-${f.id}").apply {mkdirs()}
        database.copyTo(File(copied,"${f.endpoint}.db"))
        f.noBackupFilesDir.resolve("${f.endpoint}.wrapped").copyTo(File(copied,"${f.endpoint}.wrapped"))
        val encrypted=File(f.noBackupFilesDir,"${f.endpoint}-attachments/upload/body").apply {parentFile!!.mkdirs(); writeBytes(byteArrayOf(4,5,6))}
        try {
            f.gate.armAfterCredential()
            try {LocalOperationCoordinator(f.gate,f.engine({if(it==DestructionCheckpoint.KEYS_VERIFIED) throw Crash()})) {}.resume(); fail("fault missed")} catch(_: Crash) {}
            assertEquals(LocalOperationState.KEY_DESTRUCTION_PENDING,f.journal.read())
            assertTrue(database.exists() && encrypted.exists()); assertTrue(f.own.all {f.real.absent(it)})
            val inspection=object : ContextWrapper(target) {override fun getNoBackupFilesDir()=copied}
            try {EncryptedEndpointStore.open(inspection,f.endpoint); fail("old DB reopened")} catch(_: EndpointStorageFailure) {}
            try {KeystoreDeviceAuth().sign(f.auth,byteArrayOf(1)); fail("old device signed")} catch(_: EndpointStorageFailure) {}
            assertTrue(f.own.all {f.real.absent(it)})
            f.restart(); LocalOperationCoordinator(f.gate,f.engine()) {}.resume()
            assertFalse(f.gate.blocked); assertFalse(database.exists()); assertFalse(encrypted.exists())
            assertFalse(f.real.absent(f.unrelated))
        } finally {f.retireKeys()}
    }
    @Test fun interruptionAfterFirstAliasDeletesRemainingKeysOnRecreatedCoordinator()=runBlocking {
        val f=Fixture(target); f.seedSimple()
        try {
            f.gate.armAfterCredential()
            try {LocalOperationCoordinator(f.gate,f.engine({if(it==DestructionCheckpoint.KEY_DELETED) throw Crash()})) {}.resume(); fail("fault missed")} catch(_: Crash) {}
            assertTrue(f.real.absent("ghost-cloak.db.${f.endpoint}")); assertFalse(f.real.absent(f.auth))
            f.restart(); LocalOperationCoordinator(f.gate,f.engine()) {}.resume()
            assertFalse(f.gate.blocked); assertTrue(f.own.all {f.real.absent(it)})
        } finally {f.retireKeys()}
    }
    @Test fun providerFailureKeepsPendingUntilVerifiedDeletionSucceeds()=runBlocking {
        val f=Fixture(target); f.seedSimple(); var fail=true
        val failing=object : DestructionKeys {
            override fun aliases()=f.keys.aliases()
            override fun absent(alias: String)=f.keys.absent(alias)
            override fun delete(alias: String) {if(fail) throw java.security.KeyStoreException("synthetic"); f.keys.delete(alias)}
        }
        try {
            f.gate.armAfterCredential()
            val coordinator=LocalOperationCoordinator(f.gate,AndroidLocalDestruction(f,failing)) {}
            try {coordinator.resume(); fail("provider accepted")} catch(_: java.security.KeyStoreException) {}
            assertEquals(LocalOperationState.KEY_DESTRUCTION_PENDING,f.journal.read()); assertTrue(f.gate.blocked)
            assertFalse(f.real.absent("ghost-cloak.db.${f.endpoint}"))
            fail=false; coordinator.resume(); assertFalse(f.gate.blocked)
        } finally {f.retireKeys()}
    }
    @Test fun failedCacheDeleteContinuesOtherCleanupAndRetriesWithoutOpening()=runBlocking {
        val f=Fixture(target); f.seedSimple(); var fail=true
        try {
            f.gate.armAfterCredential()
            val coordinator=LocalOperationCoordinator(f.gate,f.engine(delete={if(fail && it.name=="noncritical-cache") false else it.delete()})) {}
            try {coordinator.resume(); fail("file accepted")} catch(_: IllegalStateException) {}
            assertEquals(LocalOperationState.STORAGE_CLEANUP_PENDING,f.journal.read()); assertTrue(f.gate.blocked)
            assertTrue(f.own.all {f.real.absent(it)}); assertFalse(f.noBackupFilesDir.resolve("${f.endpoint}.db").exists())
            fail=false; coordinator.resume(); assertFalse(f.gate.blocked)
        } finally {f.retireKeys()}
    }
    @Test fun persistedRestartMatrixNeverRestoresOldBytes()=runBlocking {
        for(stage in LocalOperationState.entries.filter {it!=LocalOperationState.NONE && it!=LocalOperationState.CORRUPT}) {
            val f=Fixture(target); f.seedSimple()
            val fault=DurableLocalOperationJournal(f) {if(it==JournalCheckpoint.READBACK && DurableLocalOperationJournal(f).read()==stage) throw Crash()}
            f.gate=LocalOperationGate(fault)
            try {
                try {f.gate.armAfterCredential(); LocalOperationCoordinator(f.gate,f.engine()) {}.resume(); fail("fault missed")} catch(_: Crash) {}
                assertTrue(f.gate.blocked); assertEquals(stage,DurableLocalOperationJournal(f).read())
                f.restart(); LocalOperationCoordinator(f.gate,f.engine()) {}.resume()
                assertFalse(f.gate.blocked); assertTrue(f.own.all {f.real.absent(it)})
                assertTrue(f.noBackupFilesDir.listFiles()!!.isEmpty())
            } finally {f.retireKeys()}
        }
    }
    @Test fun corruptJournalCannotDeleteKeysOrOpenOnboarding()=runBlocking {
        val f=Fixture(target); f.seedSimple()
        try {
            File(f.noBackupFilesDir,"local-operation.v1").writeText("ambiguous")
            f.restart(); LocalOperationCoordinator(f.gate,f.engine()) {}.resume()
            assertEquals(LocalOperationState.CORRUPT,f.gate.state.value)
            assertFalse(f.real.absent("ghost-cloak.db.${f.endpoint}")); assertTrue(f.gate.blocked)
        } finally {f.retireKeys()}
    }
    @Test fun externalIndependentCopyAndWorkManagerDatabaseArePreserved()=runBlocking {
        val f=Fixture(target); f.seedSimple()
        val external=File(target.cacheDir,"independent-${f.id}").apply {writeText("independent synthetic copy")}
        val workDb=f.noBackupFilesDir.resolve("androidx.work.workdb").apply {writeBytes(byteArrayOf(1,2))}
        val owned=File(f.filesDir,"owned-view-copy").apply {writeText("owned")}
        try {
            f.gate.armAfterCredential(); LocalOperationCoordinator(f.gate,f.engine()) {}.resume()
            assertTrue(external.exists()); assertTrue(workDb.exists()); assertFalse(owned.exists())
            assertFalse(f.real.absent(f.unrelated)); assertFalse(f.gate.blocked)
        } finally {f.retireKeys()}
    }
    @Test fun interruptionDuringFileRemovalResumesWithoutFollowingExternalSymlink()=runBlocking {
        val f=Fixture(target); f.seedSimple()
        val external=File(target.cacheDir,"independent-link-${f.id}").apply {mkdirs()}
        val retained=File(external,"retained").apply {writeText("independent")}
        java.nio.file.Files.createSymbolicLink(File(f.filesDir,"link").toPath(),external.toPath())
        try {
            f.gate.armAfterCredential()
            try {LocalOperationCoordinator(f.gate,f.engine({if(it==DestructionCheckpoint.FILE_DELETED) throw Crash()})) {}.resume(); fail("fault missed")} catch(_: IllegalStateException) {}
            assertEquals(LocalOperationState.STORAGE_CLEANUP_PENDING,f.journal.read())
            assertTrue(f.own.all {f.real.absent(it)}); assertTrue(retained.exists())
            f.restart(); LocalOperationCoordinator(f.gate,f.engine()) {}.resume()
            assertFalse(f.gate.blocked); assertTrue(retained.exists())
        } finally {f.retireKeys()}
    }
    @Test fun installedBackupPolicyExcludesAllSensitiveStorageDomains() {
        assertEquals(0,target.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP)
        for((resource,expected) in listOf(org.ghostcloak.app.R.xml.backup_rules to 5,org.ghostcloak.app.R.xml.data_extraction_rules to 10)) {
            val xml=target.resources.getXml(resource); var count=0
            while(xml.eventType!=org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if(xml.eventType==org.xmlpull.v1.XmlPullParser.START_TAG && xml.name=="exclude") {
                    assertEquals(".",xml.getAttributeValue(null,"path"))
                    assertTrue(xml.getAttributeValue(null,"domain") in setOf("root","file","database","sharedpref","external")); count++
                }
                xml.next()
            }
            xml.close(); assertEquals(expected,count)
        }
    }

}
