package org.ghostcloak.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.ghostcloak.app.application.AppRuntime
import org.ghostcloak.identity.RandomIdentifiers
import org.ghostcloak.messaging.*
import org.ghostcloak.storage.EncryptedEndpointStore
import org.ghostcloak.attachments.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AttachmentUiAccessTest {
    @Test fun realRoomAccessOnMainUsesSnapshotAndStillHonorsLockBackgroundLogoutAndRestart()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val api=SyntheticNetwork()
        val endpoint=RandomIdentifiers.create()
        var unlocked=true
        var runtime=AppRuntime(context,"https://fixture.invalid",endpoint,api,attachmentAccess={unlocked})
        try {
            runtime.use { runtime.create(it,"alice") }
            withContext(Dispatchers.Main) {
                // Reproduce the old predicate's exact underlying failure using real Room/SQLCipher.
                assertThrows(IllegalStateException::class.java) { runtime.canAutoSync }
                runtime.foregroundStarted();runtime.notificationActivityVisible(true)
                assertTrue(runtime.attachmentTransfersAllowed)
                unlocked=false;assertFalse(runtime.attachmentTransfersAllowed)
                unlocked=true;assertTrue(runtime.attachmentTransfersAllowed)
                runtime.foregroundStopped();assertFalse(runtime.attachmentTransfersAllowed)
                runtime.foregroundStarted();runtime.notificationActivityVisible(false)
                assertFalse(runtime.attachmentTransfersAllowed)
            }
            runtime.close()
            runtime=AppRuntime(context,"https://fixture.invalid",endpoint,api,attachmentAccess={unlocked})
            withContext(Dispatchers.Main) {assertFalse(runtime.attachmentTransfersAllowed)}
            runtime.use { }
            withContext(Dispatchers.Main) {
                runtime.foregroundStarted();runtime.notificationActivityVisible(true)
                assertTrue(runtime.attachmentTransfersAllowed)
            }
            runtime.use {runtime.logoutNetwork()}
            withContext(Dispatchers.Main) {assertFalse(runtime.attachmentTransfersAllowed)}
            assertEquals(1,api.registrations)
        } finally {runtime.close()}
    }
    @Test fun mainThreadMessageAccessReflectsCommitExpiryBlockingAndDeletion()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val endpoint=RandomIdentifiers.create()
        var now=1000L
        val clock=ExpiryClock({now},{now},{1})
        val runtime=AppRuntime(context,"https://fixture.invalid",endpoint,SyntheticNetwork(),expiryClock=clock)
        val peer=RandomIdentifiers.create();var message=RandomIdentifiers.create()
        val scratch=File(context.cacheDir,RandomIdentifiers.create())
        try {
            runtime.use { runtime.create(it,"alice") }
            withContext(Dispatchers.IO) {
                EncryptedEndpointStore.open(context,endpoint).use { records ->
                    val repository=LocalRepository(records,clock)
                    repository.save(Contact(peer,peer,"synthetic",peer))
                    val descriptor=AttachmentFormat.encrypt(byteArrayOf(1).inputStream(),scratch,1,AttachmentKind.IMAGE)
                    repository.attachment(peer,message,AttachmentFormat.encode(descriptor))
                    repository.save(Message(message,peer,Direction.INCOMING,"",now,MessageState.RECEIVED,
                        disappearingSeconds=30,expiry=ExpiryDeadline.start(30,clock.now())))
                }
            }
            runtime.use { }
            withContext(Dispatchers.Main) {assertTrue(runtime.attachmentAvailableNow(peer,message))}
            withContext(Dispatchers.IO) {
                EncryptedEndpointStore.open(context,endpoint).use {records ->
                    val repo=LocalRepository(records,clock);repo.save(repo.contact(peer).copy(blocked=true))
                }
            }
            runtime.use { }
            withContext(Dispatchers.Main) {assertFalse(runtime.attachmentAvailableNow(peer,message))}
            withContext(Dispatchers.IO) {
                EncryptedEndpointStore.open(context,endpoint).use {records ->
                    val repo=LocalRepository(records,clock);repo.save(repo.contact(peer).copy(blocked=false))
                }
            }
            runtime.use { }
            withContext(Dispatchers.Main) {assertTrue(runtime.attachmentAvailableNow(peer,message))}
            runtime.use {it.delete(peer,message)}
            withContext(Dispatchers.Main) {assertFalse(runtime.attachmentAvailableNow(peer,message))}
            message=RandomIdentifiers.create();scratch.delete()
            withContext(Dispatchers.IO) {
                EncryptedEndpointStore.open(context,endpoint).use { records ->
                    val repo=LocalRepository(records,clock)
                    val descriptor=AttachmentFormat.encrypt(byteArrayOf(1).inputStream(),scratch,1,AttachmentKind.IMAGE)
                    repo.attachment(peer,message,AttachmentFormat.encode(descriptor))
                    repo.save(Message(message,peer,Direction.INCOMING,"",now,MessageState.RECEIVED,
                        disappearingSeconds=30,expiry=ExpiryDeadline.start(30,clock.now())))
                }
            }
            runtime.use { }
            now+=31_000
            withContext(Dispatchers.Main) {assertFalse(runtime.attachmentAvailableNow(peer,message))}
            runtime.use { }
            withContext(Dispatchers.Main) {assertFalse(runtime.attachmentAvailableNow(peer,message))}
        } finally {runtime.close();scratch.delete()}
    }
}
