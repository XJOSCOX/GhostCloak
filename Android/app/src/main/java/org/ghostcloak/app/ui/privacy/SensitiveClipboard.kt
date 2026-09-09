package org.ghostcloak.app.ui.privacy

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle

/** Invoked only by an explicit Copy button. The clipboard remains outside endpoint encryption. */
fun copySensitive(context: Context, value: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(sensitiveClip(value))
}

internal fun sensitiveClip(value: String): ClipData = ClipData.newPlainText("Ghost Cloak", value).also(::markSensitive)

internal fun markSensitive(clip: ClipData) {
    clip.description.extras = PersistableBundle().apply {
        putBoolean(if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE", true)
    }
}
