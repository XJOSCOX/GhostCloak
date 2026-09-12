package org.ghostcloak.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import org.ghostcloak.app.ui.theme.GhostLayout

@Composable fun HeaderAction(glyph: Glyph, label: String, action: () -> Unit) {
    IconButton(onClick = action, modifier = Modifier.size(GhostLayout.touchTarget)) {
        AppIcon(glyph, label, Modifier.size(GhostLayout.headerIcon), tint = MaterialTheme.colorScheme.primary)
    }
}

/** Shared by root and detail pages; accessible targets remain larger than the visible icons. */
@Composable fun PageHeader(title: String, subtitle: String? = null, back: (() -> Unit)? = null,
    center: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Column(Modifier.fillMaxWidth().testTag("page-header")) {
        Row(Modifier.fillMaxWidth().heightIn(min = GhostLayout.headerHeight).padding(horizontal = GhostLayout.pageInset),
            verticalAlignment = Alignment.CenterVertically) {
            if (back != null) HeaderAction(Glyph.BACK, "Back", back)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrEmpty()) Text(subtitle, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (center != null) Text(center, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Row(modifier = if (center != null) Modifier.weight(1f) else Modifier, horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically, content = actions)
        }
        HorizontalDivider(Modifier.padding(horizontal = GhostLayout.pageInset), color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(GhostLayout.dividerGap))
    }
}

@Composable fun PageContent(title: String, subtitle: String? = null, back: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().imePadding()) {
        PageHeader(title, subtitle, back)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = GhostLayout.pageInset, vertical = GhostLayout.dividerGap),
            verticalArrangement = Arrangement.spacedBy(GhostLayout.contentGap), content = content)
    }
}
