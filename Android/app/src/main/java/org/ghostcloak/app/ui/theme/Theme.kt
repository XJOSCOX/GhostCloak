package org.ghostcloak.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary=Color(0xFF365CCB), onPrimary=Color.White, primaryContainer=Color(0xFFE8EDFC), onPrimaryContainer=Color(0xFF233F93),
    secondary=Color(0xFF697181), onSecondary=Color.White, secondaryContainer=Color(0xFFECEFF4), onSecondaryContainer=Color(0xFF3E4758),
    background=Color(0xFFF4F5F8), onBackground=Color(0xFF1E2532), surface=Color.White, onSurface=Color(0xFF1E2532),
    surfaceVariant=Color(0xFFF1F3F7), onSurfaceVariant=Color(0xFF697181), outline=Color(0xFFCDD3DE), outlineVariant=Color(0xFFE5E8EE),
    surfaceContainer=Color(0xFFF9FAFC), surfaceContainerLow=Color(0xFFF4F5F8), surfaceContainerHigh=Color(0xFFEDF0F7), surfaceContainerHighest=Color(0xFFE4E8F2),
    error=Color(0xFFB3261E), onError=Color.White, errorContainer=Color(0xFFFFEDEA), onErrorContainer=Color(0xFF85251F)
)
private val DarkColors = darkColorScheme(
    primary=Color(0xFFA9BEFF), onPrimary=Ink, primaryContainer=Color(0xFF304879), onPrimaryContainer=Color(0xFFF0F4FF),
    secondary=Muted, onSecondary=Ink, secondaryContainer=PanelRaised, onSecondaryContainer=SoftWhite,
    background=Ink, onBackground=SoftWhite, surface=Panel, onSurface=SoftWhite,
    surfaceVariant=PanelRaised, onSurfaceVariant=Muted, outline=Outline, outlineVariant=Color(0xFF303641),
    surfaceContainer=Panel, surfaceContainerLow=Ink, surfaceContainerHigh=PanelRaised, surfaceContainerHighest=PanelRaised,
    error=Danger, onError=Ink, errorContainer=Color(0xFF422624), onErrorContainer=Danger
)

@Composable fun GhostCloakTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme=if(darkTheme) DarkColors else LightColors, typography=Typography,
        shapes=Shapes(small=RoundedCornerShape(10.dp), medium=RoundedCornerShape(16.dp), large=RoundedCornerShape(24.dp)), content=content)
}
