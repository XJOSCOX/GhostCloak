package org.ghostcloak.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.semantics.*
import org.ghostcloak.app.ui.theme.GhostDimensions
import org.ghostcloak.app.ui.theme.*

@Composable fun AppearanceSelector() {
    val appearance = LocalAppearance.current
    Column(verticalArrangement=Arrangement.spacedBy(GhostDimensions.medium)) {
        Row(horizontalArrangement=Arrangement.spacedBy(GhostDimensions.controlGap)) {
            listOf(AppearanceMode.LIGHT,AppearanceMode.DARK,AppearanceMode.AUTOMATIC).forEach { mode ->
                val chosen = mode == appearance.mode
                Surface(onClick={appearance.select(mode)},modifier=Modifier.weight(1f).semantics {selected=chosen;role=Role.RadioButton},
                    shape=MaterialTheme.shapes.medium,
                    border=androidx.compose.foundation.BorderStroke(if(chosen) GhostDimensions.micro else GhostDimensions.hairline,if(chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    color=MaterialTheme.colorScheme.surface) {
                    Column(Modifier.padding(GhostDimensions.controlGap),verticalArrangement=Arrangement.spacedBy(GhostDimensions.controlGap)) {
                        Canvas(Modifier.fillMaxWidth().height(GhostDimensions.previewHeight)) {
                            val dark = mode == AppearanceMode.DARK
                            drawRoundRect(if(dark) DarkColors.background else LightColors.background,cornerRadius=CornerRadius(8f))
                            if(mode==AppearanceMode.AUTOMATIC) drawRect(DarkColors.background,Offset(size.width/2,0f),Size(size.width/2,size.height))
                            drawRoundRect(LightColors.outline,Offset(size.width*.12f,size.height*.22f),Size(size.width*.55f,size.height*.17f),CornerRadius(4f))
                            drawRoundRect(DarkColors.primary,Offset(size.width*.4f,size.height*.56f),Size(size.width*.48f,size.height*.2f),CornerRadius(4f))
                        }
                        Text(mode.label,style=MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        Text(if(appearance.mode==AppearanceMode.AUTOMATIC) "Follows your device's light or dark appearance." else "Always use ${appearance.mode.label.lowercase()} appearance.",
            style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
