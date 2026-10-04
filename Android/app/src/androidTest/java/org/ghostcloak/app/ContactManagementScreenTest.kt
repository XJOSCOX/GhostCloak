package org.ghostcloak.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.ghostcloak.app.application.AppState
import org.ghostcloak.app.ui.screens.ContactSecurityScreen
import org.ghostcloak.app.ui.theme.GhostCloakTheme
import org.ghostcloak.messaging.Contact
import org.ghostcloak.messaging.ContactStatus
import org.ghostcloak.identity.SessionLifecycle
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ContactManagementScreenTest {
    @get:Rule val compose=createComposeRule()

    @Test fun acceptedActionsRequireTheirOwnConfirmation() {
        val contact=ContactStatus(Contact("local","account","Alex","device",ghostCloakId="7K4M9Q2FX8DR",localAlias="Friend"),null,SessionLifecycle.ACTIVE)
        var deleted=0;var removed=0;var blocked=0
        compose.setContent { GhostCloakTheme {
            ContactSecurityScreen(AppState(loading=false),contact,{}, {}, {}, {}, {if(it) blocked++},
                clear={deleted++},remove={removed++})
        } }
        compose.onNodeWithText("LOCAL ALIAS").assertExists()
        compose.onNodeWithText("7K4M-9Q2F-X8DR").assertExists()
        compose.onNodeWithText("Delete conversation").performScrollTo().performClick()
        compose.onNodeWithText("Delete this conversation?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0,deleted)
        compose.onNodeWithText("Delete conversation").performScrollTo().performClick()
        compose.onNodeWithText("Delete").performClick()
        assertEquals(1,deleted)
        compose.onNodeWithText("Remove contact").performScrollTo().performClick()
        compose.onNodeWithText("Remove this contact?").assertIsDisplayed()
        compose.onNodeWithText("Remove").performClick()
        assertEquals(1,removed)
        compose.onNodeWithText("Block contact").performScrollTo().performClick()
        compose.onNodeWithText("Block this contact?").assertIsDisplayed()
        compose.onNodeWithText("Block").performClick()
        assertEquals(1,blocked)
    }
    @Test fun removedContactAliasStaysHiddenOnNewRequest() {
        val contact=ContactStatus(Contact("local","account","Alex","device",request=true,
            ghostCloakId="7K4M9Q2FX8DR",localAlias="Private alias"),null,SessionLifecycle.ACTIVE)
        compose.setContent { GhostCloakTheme {
            ContactSecurityScreen(AppState(loading=false),contact,{}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("LOCAL ALIAS").assertDoesNotExist()
        compose.onNodeWithText("Private alias").assertDoesNotExist()
        compose.onNodeWithText("Remove contact").assertDoesNotExist()
    }
}
