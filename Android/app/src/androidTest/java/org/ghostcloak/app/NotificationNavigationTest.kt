package org.ghostcloak.app

import android.app.Application
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.ghostcloak.app.application.*
import org.ghostcloak.app.ui.navigation.GhostApp
import org.ghostcloak.app.ui.theme.GhostCloakTheme
import org.ghostcloak.identity.RandomIdentifiers
import org.junit.Rule
import org.junit.Test

class NotificationNavigationTest {
    @get:Rule val compose = createComposeRule()
    @Test fun notificationRootRequestReturnsExistingTaskToChats() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val runtime = AppRuntime(context, "https://fixture.invalid", RandomIdentifiers.create(), SyntheticNetwork())
        runBlocking { runtime.use { runtime.create(it, "alice") } }
        val owner = ViewModelStore(); lateinit var model: GhostViewModel
        instrumentation.runOnMainSync {
            model = GhostViewModel(context.applicationContext as Application, runtime); owner.put("notification", model)
        }
        val request = mutableIntStateOf(1)
        try {
            compose.setContent { GhostCloakTheme { GhostApp(model, request.intValue) } }
            compose.waitUntil(20000) { model.state.value.ready && !model.state.value.loading }
            compose.onNodeWithText("Chats").assertIsDisplayed()
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNodeWithText("Privacy").assertExists()
            compose.runOnIdle { request.intValue++ }
            compose.onNodeWithText("Chats").assertIsDisplayed()
            compose.onNodeWithText("Privacy").assertDoesNotExist()
        } finally { instrumentation.runOnMainSync { owner.clear() }; runtime.close() }
    }
}
