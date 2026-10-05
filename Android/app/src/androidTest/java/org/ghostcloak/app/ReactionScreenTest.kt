package org.ghostcloak.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import org.ghostcloak.app.application.AppState
import org.ghostcloak.app.ui.screens.ConversationScreen
import org.ghostcloak.app.ui.screens.MessageBubble
import org.ghostcloak.app.ui.theme.GhostCloakTheme
import org.ghostcloak.identity.SessionLifecycle
import org.ghostcloak.messaging.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class ReactionScreenTest {
    @get:Rule val compose=createComposeRule()
    private val peer=UUID.randomUUID().toString()
    private val target=UUID.randomUUID().toString()
    private val contact=ContactStatus(Contact("local","account","Alice",peer),null,SessionLifecycle.ACTIVE)
    private val original=Message(target,peer,Direction.INCOMING,"A message",1_000,MessageState.RECEIVED,target)

    @Test fun longPressReactChangeRemoveAndDetailsWithoutIds() {
        val message=mutableStateOf(original)
        compose.setContent { GhostCloakTheme {
            ConversationScreen(AppState(loading=false,networkConfigured=true,reactionsAvailable=true,
                messages=listOf(message.value)),contact,{},{},{_,_->},{},
                react={_,emoji->message.value=message.value.copy(reactions=if(emoji==null) emptyList() else listOf(ReactionBadge(emoji,true)))})
        } }
        compose.onNodeWithText("A message").performTouchInput {longClick()}
        compose.onNodeWithText("React").performClick()
        compose.onNodeWithText("👍").performClick()
        compose.onNodeWithText("👍 · You").assertIsDisplayed()
        compose.onNodeWithText("A message").performTouchInput {longClick()}
        compose.onNodeWithText("React").performClick()
        compose.onNodeWithText("😂").performClick()
        compose.onNodeWithText("😂 · You").assertIsDisplayed()
        compose.onNodeWithText("A message").performTouchInput {longClick()}
        compose.onNodeWithText("React").performClick()
        compose.onNodeWithText("Remove my reaction").performClick()
        compose.onNodeWithText("😂 · You").assertDoesNotExist()
        compose.onNodeWithText("A message").performTouchInput {longClick()}
        compose.onNodeWithText("Details").performClick()
        compose.onNodeWithText("Direction: Received").assertIsDisplayed()
        compose.onNodeWithText("Status: Received").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText(target,substring=true).fetchSemanticsNodes().isEmpty())
    }

    @Test fun pendingRetryAndTerminalFailedStateAreDistinct() {
        val pending=original.copy(direction=Direction.OUTGOING,state=MessageState.PENDING,envelopeId=null)
        var attempts=0
        val message=mutableStateOf(pending)
        compose.setContent {GhostCloakTheme {
            MessageBubble(message.value,onDelete={},onRetry=if(message.value.state==MessageState.PENDING) {{attempts++}} else null)
        }}
        compose.onNodeWithText("A message").performTouchInput {longClick()}
        compose.onNodeWithText("Retry pending message").performClick()
        assertEquals(1,attempts)
        compose.runOnUiThread {message.value=message.value.copy(state=MessageState.FAILED)}
        compose.onNodeWithText("A message").performTouchInput {longClick()}
        compose.onNodeWithText("Retry pending message").assertDoesNotExist()
        compose.onNodeWithText("Retry unavailable for this attempt").assertExists()
        compose.onNodeWithText("Details").performClick()
        compose.onNodeWithText("Status: Not delivered · this attempt cannot be retried safely").assertIsDisplayed()
    }

    @Test fun viewOnceHasNoReactionAction() {
        val view=original.copy(body="View Once message",viewOnceKind=ViewOnceKind.TEXT,
            viewOnceState=ViewOnceState.CONSUMED)
        compose.setContent {GhostCloakTheme {
            ConversationScreen(AppState(loading=false,networkConfigured=true,reactionsAvailable=true,messages=listOf(view)),
                contact,{},{},{_,_->},{})
        }}
        compose.onNodeWithText("View Once message").performTouchInput {longClick()}
        compose.onNodeWithText("React").assertDoesNotExist()
    }
}
