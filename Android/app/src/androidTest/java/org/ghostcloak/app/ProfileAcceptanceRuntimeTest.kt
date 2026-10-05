package org.ghostcloak.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.ghostcloak.app.application.AppRuntime
import org.ghostcloak.identity.RandomIdentifiers
import org.junit.Assert.*
import org.junit.Test

class ProfileAcceptanceRuntimeTest {
    @Test fun acceptUsesProductionRuntimeOutboxAndSyncUpdatesVisibleContact()=runBlocking {
        val wire=SyntheticNetwork()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val a=AppRuntime(context,"https://fixture.invalid",RandomIdentifiers.create(),wire)
        val bEndpoint=RandomIdentifiers.create()
        val b=AppRuntime(context,"https://fixture.invalid",bEndpoint,wire)
        try {
            a.use {a.create(it,"Alice")}; b.use {b.create(it,"Bob")}
            val bid=b.use {it.open()!!.deviceId}
            val aid=a.use {it.open()!!.deviceId}
            a.use {a.addNetwork(b.ownGhostCloakId()!!,it)}
            a.use {a.send(it,bid,"First contact")}
            b.use {b.syncNetwork(it)}
            assertTrue(b.use {it.contacts().single().contact.request})
            assertNotEquals("Bob",a.use {it.contacts().single().contact.visibleName})
            b.use {b.acceptRequest(it,aid)}
            assertFalse(b.use {it.contacts().single().contact.request})
            b.close()
            val resumed=AppRuntime(context,"https://fixture.invalid",bEndpoint,wire)
            try {
                resumed.use {resumed.syncNetwork(it)}
                assertTrue(wire.sent.all {bytes -> !bytes.toString(Charsets.ISO_8859_1).contains("Bob")})
                a.use {a.syncNetwork(it)}
                assertEquals("Bob",a.use {it.contacts().single().contact.visibleName})
                assertEquals(1,a.use {it.contacts().size})
                assertTrue(a.use {it.messages(bid).all {m -> m.direction==org.ghostcloak.messaging.Direction.OUTGOING}})
            } finally {resumed.close()}
        } finally {a.close();b.close()}
    }
    @Test fun acceptedProfilePhotoAndAboutSyncEncryptedAndRemove()=runBlocking {
        val wire=SyntheticNetwork()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val a=AppRuntime(context,"https://fixture.invalid",RandomIdentifiers.create(),wire)
        val b=AppRuntime(context,"https://fixture.invalid",RandomIdentifiers.create(),wire)
        val source=java.io.File.createTempFile("profile-runtime-",".jpg",context.noBackupFilesDir)
        try {
            val image=android.graphics.Bitmap.createBitmap(128,128,android.graphics.Bitmap.Config.ARGB_8888)
            image.eraseColor(android.graphics.Color.GREEN)
            source.outputStream().use {assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.JPEG,75,it))}
            image.recycle()
            val photo=org.ghostcloak.app.attachments.ProfilePhotoPreparation.prepare(source)
            a.use {a.create(it,"Alice")};b.use {b.create(it,"Bob");it.setAbout("Private status");it.setProfilePhoto(photo)}
            val aid=a.use {it.open()!!.deviceId};val bid=b.use {it.open()!!.deviceId}
            a.use {a.addNetwork(b.ownGhostCloakId()!!,it);a.send(it,bid,"Introduction")}
            b.use {b.syncNetwork(it)}
            assertTrue(b.use {it.contacts().single().contact.request})
            assertNull(b.use {it.contacts().single().sharedAbout})
            b.use {b.acceptRequest(it,aid);b.syncNetwork(it);b.syncNetwork(it)}
            a.use {a.syncNetwork(it)}
            val received=a.use {it.contacts().single()}
            assertEquals("Private status",received.sharedAbout)
            assertArrayEquals(photo,received.sharedPhoto)
            assertTrue(wire.sent.all {bytes -> !bytes.toString(Charsets.ISO_8859_1).contains("Private status")})
            b.use {it.setAbout("");it.setProfilePhoto(null);b.syncNetwork(it);b.syncNetwork(it)}
            a.use {a.syncNetwork(it)}
            assertNull(a.use {it.contacts().single().sharedAbout})
            assertNull(a.use {it.contacts().single().sharedPhoto})
        } finally {source.delete();a.close();b.close()}
    }
}
