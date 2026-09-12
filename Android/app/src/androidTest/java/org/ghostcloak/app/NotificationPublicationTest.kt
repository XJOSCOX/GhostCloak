package org.ghostcloak.app

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.ghostcloak.app.application.AndroidLocalNotifications
import org.junit.Assert.*
import org.junit.Test

class NotificationPublicationTest {
    @Test fun operatingSystemReceivesOnlyOneGenericAggregate() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        if (android.os.Build.VERSION.SDK_INT >= 33)
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val publisher = AndroidLocalNotifications(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        try {
            assertTrue(publisher.post(false)); assertTrue(publisher.post(true))
            withTimeout(5000) { while (!publisher.active()) delay(25) }
            val active = manager.activeNotifications.filter { it.id == AndroidLocalNotifications.ID }
            assertEquals(1, active.size)
            val n = active.single().notification
            assertEquals("Ghost Cloak", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertEquals("New message", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            assertEquals(Notification.VISIBILITY_SECRET, n.visibility)
            assertNull(active.single().tag); assertTrue(n.contentIntent.isImmutable)
        } finally { publisher.cancel() }
    }
}
