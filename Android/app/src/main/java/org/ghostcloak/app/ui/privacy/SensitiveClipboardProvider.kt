@file:Suppress("DEPRECATION") // Cover legacy text-field copy paths as well as the current Clipboard API.
package org.ghostcloak.app.ui.privacy

import androidx.compose.runtime.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.AnnotatedString

/** Also marks user-selected Copy/Cut in draft and contact-card text fields as sensitive. */
@Composable fun SensitiveClipboardProvider(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val legacy = LocalClipboardManager.current
    val protected = remember(clipboard) {
        object : Clipboard by clipboard {
            override suspend fun setClipEntry(clipEntry: ClipEntry?) {
                clipEntry?.clipData?.let(::markSensitive)
                clipboard.setClipEntry(clipEntry)
            }
        }
    }
    val protectedLegacy = remember(legacy, context) {
        object : ClipboardManager by legacy {
            override fun setText(annotatedString: AnnotatedString) = copySensitive(context, annotatedString.text)
        }
    }
    CompositionLocalProvider(LocalClipboard provides protected, LocalClipboardManager provides protectedLegacy, content = content)
}
