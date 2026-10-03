package org.ghostcloak.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun BrandMark(modifier: Modifier = Modifier) {
    AppIcon(Glyph.GHOST,"Ghost Cloak emblem",modifier,tint=MaterialTheme.colorScheme.primary)
}
