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
    @Test fun firstLaunchRequiresDeliberateDisplayNameSubmission() {
        var submitted: String? = null
        compose.setContent { GhostCloakTheme { FirstLaunchScreen(AppState(loading = false, ready = true)) { submitted = it } } }
        compose.onNodeWithText("Create local identity").assertIsNotEnabled()
        compose.onNodeWithText("Display name").performTextInput("Alice")
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
    @Test fun verifiedContactNoLongerOffersMarkVerified() {
        val trust = androidx.compose.runtime.mutableStateOf(IdentityTrustState.UNVERIFIED)
        val state = AppState(loading = false, fingerprint = "12345 12345 12345 12345 12345 12345 12345 12345 12345 12345 12345 12345")
        compose.setContent { GhostCloakTheme { ContactSecurityScreen(state,
            ContactStatus(contact, RemoteIdentityStatus(trust.value), SessionLifecycle.ACTIVE),
            {}, {}, { trust.value = IdentityTrustState.VERIFIED }, {}, {}) } }
        compose.onNodeWithText("Mark verified").performScrollTo().performClick()
        compose.onNodeWithText("I compared it · Verify").performClick()
        compose.onNodeWithText("✓ Verified").assertIsDisplayed()
        compose.onNodeWithText("Mark verified").assertDoesNotExist()
        compose.onNodeWithText("You marked this identity verified after comparing its safety number. Ghost Cloak will warn you if the identity changes.").assertExists()
    }
    @Test fun contactDetailsKeepPublicIdVisibleAfterRemoteNameAndAlias() {
        val named=contact.copy(displayName="Robert",localAlias="Bob - Work",ghostCloakId="7K4M9Q2FX8DR")
        compose.setContent { GhostCloakTheme { ContactSecurityScreen(AppState(loading=false),
            ContactStatus(named,RemoteIdentityStatus(IdentityTrustState.UNVERIFIED),SessionLifecycle.ACTIVE),
            {},{},{},{},{}) } }
        // The alias is shown in both the heading and the local-name detail.
        compose.onAllNodesWithText("Bob - Work").onFirst().assertExists()
        compose.onNodeWithText("GHOST CLOAK ID").assertExists()
        compose.onNodeWithText("7K4M-9Q2F-X8DR").assertExists()
    }
    @Test fun conversationSecurityIconTracksVerificationState() {
        val trust = androidx.compose.runtime.mutableStateOf(IdentityTrustState.UNVERIFIED)
        var securityOpened = false
        compose.setContent { GhostCloakTheme { ConversationScreen(AppState(loading = false),
            ContactStatus(contact, RemoteIdentityStatus(trust.value), SessionLifecycle.ACTIVE),
            {}, { securityOpened = true }, { _, _ -> }, {}) } }
        compose.onNodeWithContentDescription("Unverified contact · Security").assertExists().performClick()
        assertTrue(securityOpened)
        compose.runOnIdle { trust.value = IdentityTrustState.VERIFIED }
        compose.onNodeWithContentDescription("Verified contact · Security").assertExists()
        compose.onNodeWithContentDescription("Unverified contact · Security").assertDoesNotExist()
        compose.runOnIdle { trust.value = IdentityTrustState.CHANGED }
        compose.onNodeWithContentDescription("Identity changed · Security").assertExists()
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
