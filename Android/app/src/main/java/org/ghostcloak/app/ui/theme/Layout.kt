package org.ghostcloak.app.ui.theme

import androidx.compose.ui.unit.dp

/** Shared page geometry, independent of palette and typography. */
object GhostLayout {
    val pageInset = 16.dp
    val contentGap = 16.dp
    val headerHeight = 56.dp
    val headerIcon = 20.dp
    val unreadDot = 8.dp
    val headerAvatar = 32.dp
    val headerAvatarGap = 8.dp
    val touchTarget = 48.dp
    // IconButton centers a 20 dp glyph in a 48 dp target. Account for that
    // internal space so glyphs and plain titles share the page's visible edge.
    val headerTargetInset = (touchTarget - headerIcon) / 2
    val headerOuterInset = pageInset - headerTargetInset
    val dividerGap = 4.dp
    val maxPageWidth = 680.dp
}
