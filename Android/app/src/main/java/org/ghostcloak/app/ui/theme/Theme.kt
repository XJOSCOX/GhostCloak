package org.ghostcloak.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable

/** Semantic roles: components consume MaterialTheme, never raw palette values. */
val LightColors = with(GhostPalette) { lightColorScheme(
    primary=LightPrimary, onPrimary=LightSurface, primaryContainer=LightPrimaryContainer, onPrimaryContainer=LightPrimary,
    secondary=LightSecondary, onSecondary=LightSurface, secondaryContainer=LightSecondaryContainer, onSecondaryContainer=LightSecondary,
    tertiary=LightTertiary, onTertiary=LightSurface, tertiaryContainer=LightTertiaryContainer, onTertiaryContainer=LightTertiary,
    background=LightBackground, onBackground=LightText, surface=LightSurface, onSurface=LightText,
    surfaceVariant=LightSurfaceVariant, onSurfaceVariant=LightMuted, outline=LightOutline, outlineVariant=LightDivider,
    surfaceContainer=LightBackground, surfaceContainerLow=LightBackground, surfaceContainerHigh=LightSurfaceHigh, surfaceContainerHighest=LightSurfaceRaised,
    error=LightError, onError=LightSurface, errorContainer=LightErrorContainer, onErrorContainer=LightError
) }
val DarkColors = with(GhostPalette) { darkColorScheme(
    primary=Primary, onPrimary=DarkBackground, primaryContainer=AccentMuted, onPrimaryContainer=Primary,
    secondary=Secondary, onSecondary=DarkBackground, secondaryContainer=DarkSurfaceVariant, onSecondaryContainer=Secondary,
    tertiary=Tertiary, onTertiary=DarkBackground, tertiaryContainer=DarkSurfaceVariant, onTertiaryContainer=Tertiary,
    background=DarkBackground, onBackground=DarkText, surface=DarkSurface, onSurface=DarkText,
    surfaceVariant=DarkSurfaceVariant, onSurfaceVariant=DarkMuted, outline=DarkSurfaceRaised, outlineVariant=DarkDivider,
    surfaceContainer=DarkSurface, surfaceContainerLow=DarkBackground, surfaceContainerHigh=DarkSurfaceHigh, surfaceContainerHighest=DarkSurfaceRaised,
    error=DarkError, onError=DarkBackground, errorContainer=DarkErrorContainer, onErrorContainer=DarkError
) }

@Composable fun GhostCloakTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme=if(darkTheme) DarkColors else LightColors, typography=Typography,
        shapes=GhostShapes, content=content)
}
