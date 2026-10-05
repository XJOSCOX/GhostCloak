package org.ghostcloak.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.ghostcloak.app.application.AppState
import org.ghostcloak.app.ui.screens.ContactSecurityScreen
import org.ghostcloak.app.ui.screens.ProfilesScreen
import org.ghostcloak.app.ui.theme.GhostCloakTheme
import org.ghostcloak.messaging.Contact
import org.ghostcloak.messaging.ContactStatus
import org.ghostcloak.messaging.LocalProfile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ProfileUiTest {
    @get:Rule val compose=createComposeRule()
    @Test fun ownProfileControlsShowAboutSharingAndRemoval() {
        var removed=false
        var shared:Boolean?=null
        compose.setContent {GhostCloakTheme {
            ProfilesScreen(AppState(loading=false,ownProfile=LocalProfile(about="Encrypted About",photo=byteArrayOf(1),sharing=true)),
                {},{}, {},{}, {removed=true},{shared=it})
        }}
        compose.onNodeWithText("Encrypted About").assertExists()
        compose.onNodeWithText("Remove profile photo").performClick()
        assertTrue(removed)
        compose.onNodeWithText("Share with accepted contacts").assertExists()
        compose.onNode(isToggleable()).performClick()
        assertEquals(false,shared)
    }
    @Test fun acceptedContactShowsSharedAboutSeparateFromLocalAliasButRequestDoesNot() {
        val contact=Contact("contact","account","Remote name","device",localAlias="My alias")
        val status=ContactStatus(contact,null,null,sharedAbout="Shared About")
        compose.setContent {GhostCloakTheme {
            ContactSecurityScreen(AppState(loading=false,contacts=listOf(status)),status,{},{},{},{},{})
        }}
        compose.onAllNodesWithText("My alias",useUnmergedTree=true).assertCountEquals(2)
        compose.onNodeWithText("SHARED PROFILE NAME").assertExists()
        compose.onNodeWithText("Remote name").assertExists()
        compose.onNodeWithText("Shared About").assertExists()
        compose.onNodeWithText("Profile details do not verify this person's identity.").assertExists()
    }
    @Test fun pendingRequestDoesNotRenderSharedAbout() {
        val status=ContactStatus(Contact("contact","account","Remote name","device",request=true),null,null,
            sharedAbout="Should stay hidden")
        compose.setContent {GhostCloakTheme {
            ContactSecurityScreen(AppState(loading=false,contacts=listOf(status)),status,{},{},{},{},{})
        }}
        compose.onNodeWithText("Should stay hidden").assertDoesNotExist()
        compose.onNodeWithText("SHARED PROFILE NAME").assertDoesNotExist()
    }
}
