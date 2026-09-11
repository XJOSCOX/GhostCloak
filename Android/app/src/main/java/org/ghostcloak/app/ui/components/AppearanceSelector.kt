package org.ghostcloak.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import org.ghostcloak.app.ui.theme.*

@Composable fun AppearanceSelector() {
    val appearance = LocalAppearance.current
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            listOf(AppearanceMode.LIGHT,AppearanceMode.DARK,AppearanceMode.AUTOMATIC).forEach { mode ->
                val chosen = mode == appearance.mode
                Surface(onClick={appearance.select(mode)},modifier=Modifier.weight(1f).semantics {selected=chosen;role=Role.RadioButton},
                    shape=MaterialTheme.shapes.medium,
                    border=androidx.compose.foundation.BorderStroke(if(chosen) 2.dp else 1.dp,if(chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    color=MaterialTheme.colorScheme.surface) {
                    Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        Canvas(Modifier.fillMaxWidth().height(58.dp)) {
                            val dark = mode == AppearanceMode.DARK
                            drawRoundRect(if(dark) Color(0xFF242830) else Color(0xFFF0F2F6),cornerRadius=CornerRadius(8f))
                            if(mode==AppearanceMode.AUTOMATIC) drawRect(Color(0xFF242830),Offset(size.width/2,0f),Size(size.width/2,size.height))
                            drawRoundRect(Color(0xFFB8C2D2),Offset(size.width*.12f,size.height*.22f),Size(size.width*.55f,size.height*.17f),CornerRadius(4f))
                            drawRoundRect(Color(0xFF668BDD),Offset(size.width*.4f,size.height*.56f),Size(size.width*.48f,size.height*.2f),CornerRadius(4f))
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
