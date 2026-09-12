package org.ghostcloak.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.ghostcloak.app.ui.theme.*
import org.junit.Assert.*
import org.junit.Test

class ThemeTest {
    @Test fun darkMatchesReferenceAndTextOnPrimaryRemainsReadable() {
        assertEquals(Color(0xFF94B86F), DarkColors.primary)
        assertEquals(Color(0xFF1A1A1A), DarkColors.background)
        for (scheme in listOf(LightColors, DarkColors)) {
            val first = scheme.primary.luminance()
            val second = scheme.onPrimary.luminance()
            assertTrue((maxOf(first, second) + 0.05f) / (minOf(first, second) + 0.05f) >= 4.5f)
        }
    }

    @Test fun everyMaterialTextRoleUsesTheBundledFont() {
        with(Typography) {
            listOf(displayLarge, displayMedium, displaySmall, headlineLarge, headlineMedium, headlineSmall,
                titleLarge, titleMedium, titleSmall, bodyLarge, bodyMedium, bodySmall,
                labelLarge, labelMedium, labelSmall).forEach { assertEquals(GhostFontFamily, it.fontFamily) }
        }
    }
}
