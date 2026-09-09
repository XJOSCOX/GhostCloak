package org.ghostcloak.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class LocalDemoTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
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
        compose.onAllNodes(hasText("Contacts") and hasClickAction()).onFirst().performClick()
        compose.onNodeWithText("Bob").performClick()
        compose.waitUntil(20000) { compose.onAllNodesWithText("Write a message…").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(20000) { compose.onNodeWithText("Write a message…").isEnabled() }
        val message = "UI fixture ${System.nanoTime()}"
        compose.onNodeWithText("Write a message…").performTextInput(message)
        compose.onNodeWithText("Send").performClick()
        compose.waitUntil(20000) { compose.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Security").performClick()
        compose.waitUntil(20000) { compose.onAllNodesWithText("Mark verified").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Mark verified").performScrollTo().performClick()
        compose.onNodeWithText("I compared it · Verify").performClick()
        compose.waitUntil(20000) { compose.onAllNodesWithText("✓ Verified").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("‹  Back").performScrollTo().performClick()
        compose.onNodeWithText("‹ Back").performClick()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Return to my identity").performScrollTo().performClick()
        compose.waitUntil(20000) { compose.onAllNodesWithText("Open local demo").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun SemanticsNodeInteraction.isEnabled(): Boolean = try { assertIsEnabled(); true } catch (e: AssertionError) { false }
}
