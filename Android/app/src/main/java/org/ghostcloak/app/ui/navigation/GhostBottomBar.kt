package org.ghostcloak.app.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import org.ghostcloak.app.ui.components.AppIcon
import org.ghostcloak.app.ui.components.Glyph
import org.ghostcloak.app.ui.theme.GhostDimensions
import org.ghostcloak.app.ui.theme.GhostLayout

/** Compact navigation with a labeled active pill and accessible icon-only destinations. */
@Composable fun GhostBottomBar(route: String?, onNavigate: (String) -> Unit) {
    Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars)
        .padding(horizontal = GhostLayout.pageInset, vertical = GhostDimensions.compact),
        contentAlignment = Alignment.Center) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.widthIn(max = GhostLayout.maxPageWidth).fillMaxWidth()) {
            Row(Modifier.selectableGroup().padding(GhostDimensions.compact),
                verticalAlignment = Alignment.CenterVertically) {
                listOf(Triple("contacts", "Chat", Glyph.CHAT), Triple("people", "Contact", Glyph.CONTACTS),
                    Triple("profiles", "Profiles", Glyph.PERSON), Triple("settings", "Settings", Glyph.SETTINGS)
                ).forEach { (destination, label, glyph) ->
                    val selected = route == destination
                    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    Box(Modifier.weight(if (selected) 1.7f else 1f)
                        .heightIn(min = GhostLayout.touchTarget).clip(MaterialTheme.shapes.large)
                        .selectable(selected = selected, role = Role.Tab, onClick = { onNavigate(destination) })
                        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
                        Row(Modifier.then(if (selected) Modifier.background(MaterialTheme.colorScheme.primaryContainer,
                                MaterialTheme.shapes.large) else Modifier)
                            .padding(horizontal = GhostDimensions.medium, vertical = GhostDimensions.controlGap),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(GhostDimensions.small)) {
                            AppIcon(glyph, modifier = Modifier.size(GhostLayout.headerIcon), tint = color)
                            if (selected) Text(label, color = color, style = MaterialTheme.typography.labelSmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
