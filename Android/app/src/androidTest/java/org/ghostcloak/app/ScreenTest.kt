package org.ghostcloak.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.ghostcloak.app.application.AppState
import org.ghostcloak.app.ui.screens.*
import org.ghostcloak.app.ui.theme.GhostCloakTheme
import org.ghostcloak.identity.*
import org.ghostcloak.messaging.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ScreenTest {
    @get:Rule val compose = createComposeRule()
    private val contact = Contact("fixture-contact", "fixture-user", "Bob", "fixture-device")
    @Test fun explicitClipboardCopyIsMarkedSensitive() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent { GhostCloakTheme { androidx.compose.material3.Text("Clipboard fixture") } }
        compose.runOnIdle {
            // Assert the exact outgoing Android object. The emulator's host clipboard mirror
            // reconstructs incoming ClipData with SUPPRESS_CLIPBOARD_OVERLAY, dropping other flags.
            val outgoing = org.ghostcloak.app.ui.privacy.sensitiveClip("synthetic public card fixture")
            assertTrue(outgoing.description.extras!!.getBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE))
            org.ghostcloak.app.ui.privacy.copySensitive(context, "synthetic public card fixture")
            val received = context.getSystemService(android.content.ClipboardManager::class.java).primaryClip!!
            assertEquals("synthetic public card fixture", received.getItemAt(0).text.toString())
        }
    }
    @Test fun firstLaunchRequiresDeliberateUsernameSubmission() {
        var submitted: String? = null
        compose.setContent { GhostCloakTheme { FirstLaunchScreen(AppState(loading = false, ready = true)) { submitted = it } } }
        compose.onNodeWithText("Create local identity").assertIsNotEnabled()
        compose.onNodeWithText("Username").performTextInput("Alice")
        compose.onNodeWithText("Create local identity").performScrollTo().performClick()
        assertEquals("Alice", submitted)
    }
    @Test fun verificationRequiresConfirmation() {
        var verified = false
        val state = AppState(loading = false, fingerprint = "12345 12345 12345 12345 12345 12345 12345 12345 12345 12345 12345 12345")
        compose.setContent { GhostCloakTheme { ContactSecurityScreen(state,
            ContactStatus(contact, RemoteIdentityStatus(IdentityTrustState.UNVERIFIED), SessionLifecycle.ACTIVE), {}, {}, { verified = true }, {}, {}) } }
        compose.onNodeWithText("Mark verified").performScrollTo().performClick()
        assertFalse(verified)
        compose.onNodeWithText("I compared it · Verify").performClick()
        assertTrue(verified)
    }
    @Test fun changedIdentityBlocksComposerAndSending() {
        var sent = false
        compose.setContent { GhostCloakTheme { ConversationScreen(AppState(loading = false),
            ContactStatus(contact, RemoteIdentityStatus(IdentityTrustState.CHANGED, IdentityTrustState.VERIFIED), SessionLifecycle.REQUIRES_REAUTHENTICATION),
            {}, {}, { _, _ -> sent = true }, {}) } }
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
        compose.onNodeWithText("Write a message…").assertIsNotEnabled()
        compose.onNodeWithText("! Security identity changed").assertIsDisplayed()
        assertFalse(sent)
    }
}
