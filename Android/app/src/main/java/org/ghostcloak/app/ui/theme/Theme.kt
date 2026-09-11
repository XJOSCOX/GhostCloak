package org.ghostcloak.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary=Color(0xFF118654), onPrimary=Color.White, primaryContainer=Color(0xFFDDF5E8), onPrimaryContainer=Color(0xFF11623E),
    secondary=Color(0xFF607169), onSecondary=Color.White, secondaryContainer=Color(0xFFECF1EE), onSecondaryContainer=Color(0xFF334D40),
    background=Color.White, onBackground=Color(0xFF17221C), surface=Color(0xFFF5F7F6), onSurface=Color(0xFF17221C),
    surfaceVariant=Color(0xFFF0F3F1), onSurfaceVariant=Color(0xFF647169), outline=Color(0xFFCBD5CF), outlineVariant=Color(0xFFE8EDE9),
    surfaceContainer=Color(0xFFF5F7F6), surfaceContainerLow=Color.White, surfaceContainerHigh=Color(0xFFEDF2EF), surfaceContainerHighest=Color(0xFFE4ECE7),
    error=Color(0xFFB3261E), onError=Color.White, errorContainer=Color(0xFFFFEDEA), onErrorContainer=Color(0xFF85251F)
)
private val DarkColors = darkColorScheme(
    primary=Mint, onPrimary=Ink, primaryContainer=Color(0xFF174D38), onPrimaryContainer=Color(0xFFE4FFF0),
    secondary=Muted, onSecondary=Ink, secondaryContainer=PanelRaised, onSecondaryContainer=SoftWhite,
    background=Ink, onBackground=SoftWhite, surface=Panel, onSurface=SoftWhite,
    surfaceVariant=PanelRaised, onSurfaceVariant=Muted, outline=Outline, outlineVariant=Color(0xFF26332D),
    surfaceContainer=Panel, surfaceContainerLow=Ink, surfaceContainerHigh=PanelRaised, surfaceContainerHighest=PanelRaised,
    error=Danger, onError=Ink, errorContainer=Color(0xFF422624), onErrorContainer=Danger
)

@Composable fun GhostCloakTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme=if(darkTheme) DarkColors else LightColors, typography=Typography,
        shapes=Shapes(small=RoundedCornerShape(10.dp), medium=RoundedCornerShape(16.dp), large=RoundedCornerShape(24.dp)), content=content)
}
