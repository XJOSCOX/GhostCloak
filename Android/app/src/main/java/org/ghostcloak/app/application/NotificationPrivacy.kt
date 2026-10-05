package org.ghostcloak.app.application

import android.content.Context
import org.ghostcloak.attachments.AttachmentKind
import org.ghostcloak.messaging.NotificationLedger

enum class NotificationPrivacy(val label: String, val explanation: String) {
    MAXIMUM("Maximum privacy", "Shows only that Ghost Cloak has a new message."),
    CONTACT("Contact only", "Shows who sent it, but not the message."),
    CONTENT("Content preview", "Shows message previews when Ghost Cloak is unlocked.");

    companion object {
        private const val PREFS = "notification-permission"
        private const val KEY = "privacy-level"
        fun read(context: Context): NotificationPrivacy = entries.firstOrNull {
            it.name == context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        } ?: MAXIMUM
        fun save(context: Context, level: NotificationPrivacy) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, level.name).apply()
        }
    }
}

/** Values are allowed only into the notification's title/text; no identities in Android metadata. */
internal fun notificationWords(level: NotificationPrivacy, allowed: Boolean,
    candidate: NotificationLedger.Presentation?): Pair<String, String> {
    if (candidate?.request == true) return "Ghost Cloak" to "New message request"
    if (!allowed || level == NotificationPrivacy.MAXIMUM || candidate == null) return "Ghost Cloak" to "New message"
    val name = candidate.name?.replace(Regex("[\\p{Cntrl}\\p{Cf}]"), " ")?.trim()?.take(64)
        ?.takeIf { it.isNotBlank() } ?: "Ghost Cloak"
    if (level == NotificationPrivacy.CONTACT) return name to "New message"
    if (candidate.viewOnce) return name to "View Once message"
    // Never put expiring plaintext into OS notification history.
    if (candidate.disappearing) return name to "New message"
    val caption = candidate.body?.replace(Regex("[\\p{Cntrl}\\p{Cf}]"), " ")?.trim()?.take(100)
        ?.takeIf { it.isNotBlank() }
    val label = caption ?: when (candidate.attachmentKind) {
        AttachmentKind.IMAGE -> "Photo"
        AttachmentKind.DOCUMENT -> "Document"
        AttachmentKind.VOICE_NOTE, AttachmentKind.AUDIO -> "Voice note"
        AttachmentKind.VIDEO -> "Video"
        null -> "New message"
    }
    return name to label
}
