package org.ghostcloak.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelStore
import org.ghostcloak.app.application.*
import org.ghostcloak.app.ui.navigation.GhostApp
import org.ghostcloak.app.ui.theme.GhostCloakTheme
import org.ghostcloak.identity.RandomIdentifiers
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test

class LocalDemoTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var runtime: AppRuntime
    private val owner = ViewModelStore()
    @Before fun isolatedLocalRuntime() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        runtime = AppRuntime(context, apiOrigin = "", endpointName = "ui-${RandomIdentifiers.create()}")
        lateinit var model: GhostViewModel
        instrumentation.runOnMainSync {
            model = GhostViewModel(context.applicationContext as android.app.Application, runtime)
            owner.put("local-demo", model)
        }
        compose.setContent { GhostCloakTheme { GhostApp(model) } }
    }
    @After fun closeRuntime() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { owner.clear() }
        runtime.close()
    }
    @Test fun appNavigationRunsIsolatedEncryptedDemo() {
        compose.waitUntil(20000) { compose.onAllNodesWithText("Settings").fetchSemanticsNodes().isNotEmpty() ||
            compose.onAllNodesWithText("Create local identity").fetchSemanticsNodes().isNotEmpty() }
        if (compose.onAllNodesWithText("Create local identity").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("Username").performTextInput("LocalTest")
            compose.waitUntil(20000) { compose.onNodeWithText("Create local identity").isEnabled() }
            compose.onNodeWithText("Create local identity").performScrollTo().performClick()
        }
        compose.waitUntil(20000) { compose.onAllNodesWithText("Settings").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Open local demo").performScrollTo().performClick()
        compose.waitUntil(20000) { compose.onAllNodesWithText("Return to my identity").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Contact").performClick()
        compose.onNodeWithText("Contacts").assertIsDisplayed()
        compose.onNodeWithText("Profiles").performClick()
        compose.onNodeWithText("Your profile").assertIsDisplayed()
        compose.onNodeWithText("Edit").performClick()
        compose.onNodeWithText("Edit your name").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onAllNodes(hasText("Chat") and hasClickAction()).onFirst().performClick()
        compose.onNodeWithText("Bob").performClick()
        compose.waitUntil(20000) { compose.onAllNodesWithText("Write a message…").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(20000) { compose.onNodeWithText("Write a message…").isEnabled() }
        val message = "UI fixture ${System.nanoTime()}"
        compose.onNodeWithText("Write a message…").performTextInput(message)
        compose.onNodeWithContentDescription("Send").performClick()
        compose.waitUntil(20000) { compose.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Security").performClick()
        compose.waitUntil(20000) { compose.onAllNodesWithText("Mark verified").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Mark verified").performScrollTo().performClick()
        compose.waitUntil(20000) { compose.onAllNodesWithText("I compared it · Verify").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("I compared it · Verify").performClick()
        compose.waitUntil(20000) { compose.onAllNodesWithText("✓ Verified").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Back").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Return to my identity").performScrollTo().performClick()
        compose.waitUntil(20000) { compose.onAllNodesWithText("Open local demo").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun SemanticsNodeInteraction.isEnabled(): Boolean = try { assertIsEnabled(); true } catch (e: AssertionError) { false }
}
