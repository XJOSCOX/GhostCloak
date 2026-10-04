@file:Suppress("DEPRECATION") // Preserve legacy text-field copy paths.
package org.ghostcloak.app.ui.privacy

import androidx.compose.runtime.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.AnnotatedString

/** Marks legacy text-field Copy/Cut as sensitive without replacing Compose's native Clipboard.
 * Current Compose text selection casts LocalClipboard to its Android implementation when showing
 * Paste. Delegating that interface breaks the cast and crashes on repeated editor taps.
 */
@Composable fun SensitiveClipboardProvider(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val legacy = LocalClipboardManager.current
    val protectedLegacy = remember(legacy, context) {
        object : ClipboardManager by legacy {
            override fun setText(annotatedString: AnnotatedString) = copySensitive(context, annotatedString.text)
        }
    }
    CompositionLocalProvider(LocalClipboardManager provides protectedLegacy, content = content)
}
