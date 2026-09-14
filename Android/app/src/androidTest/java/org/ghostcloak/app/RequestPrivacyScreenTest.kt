package org.ghostcloak.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.ghostcloak.app.application.AppState
import org.ghostcloak.app.ui.screens.ConversationScreen
import org.ghostcloak.app.ui.theme.GhostCloakTheme
import org.ghostcloak.messaging.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RequestPrivacyScreenTest {
    @get:Rule val compose=createComposeRule()
    @Test fun genericRequestOffersSeparateIdentityAcceptDeleteBlockActions() {
        val contact=ContactStatus(Contact("fixture","fixture","synthetic","fixture",request=true),null,null)
        var identity=0;var accepted=0;var deleted=0;var blocked=0
        compose.setContent {GhostCloakTheme {ConversationScreen(AppState(loading=false),contact,{}, {identity++}, {_,_->}, {},
            accept={accepted++},reject={deleted++},block={blocked++})}}
        compose.onAllNodesWithText("New message request").assertCountEquals(2)
        compose.onNodeWithText("View identity / Verify").performClick()
        compose.onNodeWithText("Accept",substring=false).performClick()
        compose.onNodeWithText("Delete",substring=false).performClick()
        compose.onNodeWithText("Block",substring=false).performClick()
        assertEquals(listOf(1,1,1,1),listOf(identity,accepted,deleted,blocked))
        compose.onNodeWithText("secret test").assertDoesNotExist()
        compose.onNodeWithText("private-document.pdf").assertDoesNotExist()
    }
}
