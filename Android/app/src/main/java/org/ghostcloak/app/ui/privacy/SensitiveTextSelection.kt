package org.ghostcloak.app.ui.privacy

import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.semantics.copyText
import androidx.compose.ui.semantics.cutText
import androidx.compose.ui.semantics.semantics

/** Compose's native Clipboard is intentionally untouched: wrapping it breaks selection toolbar
 * paste checks. Until a supported native clipboard writer hook exists, Copy/Cut are unavailable
 * in sensitive editable fields. Explicit Copy ID buttons use copySensitive instead. */
internal fun Modifier.noSensitiveCopyCut(): Modifier = this
    .filterTextContextMenuComponents { item ->
        item.key !== TextContextMenuKeys.CopyKey && item.key !== TextContextMenuKeys.CutKey
    }
    .onPreviewKeyEvent { event ->
        event.type == KeyEventType.KeyDown &&
            (((event.isCtrlPressed || event.isMetaPressed) &&
                (event.key == Key.C || event.key == Key.X || event.key == Key.Insert)) ||
                (event.isShiftPressed && event.key == Key.Delete))
    }
    .semantics(mergeDescendants = true) {
        copyText { false }
        cutText { false }
    }
