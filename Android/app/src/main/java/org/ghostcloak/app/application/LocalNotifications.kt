package org.ghostcloak.app.application

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import org.ghostcloak.app.MainActivity
import org.ghostcloak.app.R

interface LocalNotifications {
    fun allowed(): Boolean
    fun active(): Boolean
    fun post(quiet: Boolean): Boolean
    fun cancel()
}

/** Tests/local simulators opt out. Production injects the Android publisher from GhostApplication. */
object NoLocalNotifications : LocalNotifications {
    override fun allowed() = false
    override fun active() = false
    override fun post(quiet: Boolean) = false
    override fun cancel() = Unit
}

class AndroidLocalNotifications(private val context: Context) : LocalNotifications {
    companion object {
        const val CHANNEL = "ghostcloak-messages"
        const val ID = 1
        const val OPEN_CHATS = "org.ghostcloak.app.OPEN_CHATS"
        const val DISMISS = "org.ghostcloak.app.DISMISS_MESSAGES"
        fun rootIntent(context: Context) = Intent(context, MainActivity::class.java).apply {
            action = OPEN_CHATS
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
    }
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    fun createChannel() {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Ghost Cloak messages", NotificationManager.IMPORTANCE_DEFAULT).apply {
            lockscreenVisibility = Notification.VISIBILITY_SECRET
            setShowBadge(false)
        })
    }
    override fun allowed(): Boolean {
        createChannel()
        return manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL).importance != NotificationManager.IMPORTANCE_NONE
    }
    override fun active() = manager.activeNotifications.any { it.id == ID && it.tag == null }
    fun build(quiet: Boolean): Notification = NotificationCompat.Builder(context, CHANNEL)
        .setSmallIcon(R.drawable.ic_notification_message)
        .setContentTitle("Ghost Cloak").setContentText("New message")
        .setVisibility(NotificationCompat.VISIBILITY_SECRET)
        .setLocalOnly(true).setShowWhen(false).setWhen(0)
        .setOnlyAlertOnce(true).setSilent(quiet).setAutoCancel(true)
        .setContentIntent(PendingIntent.getActivity(context, 0, rootIntent(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .setDeleteIntent(PendingIntent.getBroadcast(context, 0, Intent(context, NotificationDismissReceiver::class.java).setAction(DISMISS),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .build()
    override fun post(quiet: Boolean): Boolean {
        if (!allowed()) return false
        return try { manager.notify(ID, build(quiet)); true } catch (_: SecurityException) { false }
    }
    override fun cancel() { manager.cancel(ID) }
}

class NotificationDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AndroidLocalNotifications.DISMISS) return
        val pending = goAsync()
        (context.applicationContext as GhostApplication).dismissNotifications { pending.finish() }
    }
}
