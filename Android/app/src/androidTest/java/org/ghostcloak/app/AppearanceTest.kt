package org.ghostcloak.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import org.ghostcloak.app.ui.screens.SettingsScreen
import org.ghostcloak.app.application.*
import org.ghostcloak.identity.DeviceIdentity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.ghostcloak.app.ui.components.AppearanceSelector
import org.ghostcloak.app.ui.theme.*
import org.junit.*
import org.junit.Assert.*

class AppearanceTest {
    @get:Rule val compose = createComposeRule()
    @Test fun themeSelectionIsImmediateAndPersistsAcrossStoreRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = "appearance-test-${java.util.UUID.randomUUID()}"
        val store = AppearanceStore(context,file)
        assertEquals(AppearanceMode.AUTOMATIC,store.mode.value)
        compose.setContent {
            val mode by store.mode.collectAsState()
            CompositionLocalProvider(LocalAppearance provides AppearanceControl(mode,store::select)) {
                GhostCloakTheme(darkTheme=mode.isDark(false)) { Surface(color=MaterialTheme.colorScheme.background) {SettingsScreen(AppState(loading=false,identity=DeviceIdentity("synthetic", "alex_morgan", "synthetic-device",byteArrayOf()), networkConfigured=true,networkStatus=NetworkStatus.CONNECTED),false,{},{})} }
            }
        }
        for(mode in listOf(AppearanceMode.DARK,AppearanceMode.LIGHT,AppearanceMode.AUTOMATIC)) {
            compose.onNodeWithText(mode.label).performScrollTo().performClick().assertIsSelected()
            assertEquals(mode,AppearanceStore(context,file).mode.value)
            if(mode != AppearanceMode.AUTOMATIC) compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
                java.io.File(context.getExternalFilesDir(null),"organized-settings-${mode.name.lowercase()}.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
                }
            }
        }
        assertFalse(AppearanceMode.LIGHT.isDark(true))
        assertTrue(AppearanceMode.DARK.isDark(false))
        assertTrue(AppearanceMode.AUTOMATIC.isDark(true))
        assertFalse(AppearanceMode.AUTOMATIC.isDark(false))
        context.deleteSharedPreferences(file)
    }
}
