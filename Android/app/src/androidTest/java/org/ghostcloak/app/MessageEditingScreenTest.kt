package org.ghostcloak.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import org.ghostcloak.app.application.AppState
import org.ghostcloak.app.ui.screens.ConversationScreen
import org.ghostcloak.app.ui.theme.GhostCloakTheme
import org.ghostcloak.identity.SessionLifecycle
import org.ghostcloak.messaging.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class MessageEditingScreenTest {
    @get:Rule val compose=createComposeRule()
    private val peer=UUID.randomUUID().toString()
    private val id=UUID.randomUUID().toString()
    private val original=Message(id,peer,Direction.OUTGOING,"Old words",1_000,MessageState.SERVER_ACCEPTED,id,
        reactions=listOf(ReactionBadge("👍",false)))
    private val contact=ContactStatus(Contact("local","account","Peer",peer),null,SessionLifecycle.ACTIVE)

    @Test fun editComposerCancelSaveSecondEditSearchReplyAndDelete() {
        val state=mutableStateOf(AppState(loading=false,messages=listOf(original),networkConfigured=true,
            editAvailable=true,deleteAvailable=true))
        var saves=0
        compose.setContent { GhostCloakTheme {
            ConversationScreen(state.value,contact,{},{},{_,_->},{},
                deleteEveryone={target -> state.value=state.value.copy(messages=state.value.messages.map {
                    if(it.localId==target) it.copy(body="",deleted=true) else it })},
                editMessage={target,text,done -> assertEquals(id,target);saves++
                    state.value=state.value.copy(messages=state.value.messages.map {
                        if(it.localId==target) it.copy(body=text,editRevision=saves.toLong(),editStatus=EditRequestStatus.SENT)
                        else it });done()})
        } }
        compose.onNodeWithText("Old words").performTouchInput {longClick()}
        compose.onNodeWithText("Edit").performClick()
        compose.onNodeWithText("Editing message").assertIsDisplayed()
        compose.onNodeWithText("Cancel edit").performClick()
        assertEquals(0,saves)
        compose.onNodeWithText("Old words").performTouchInput {longClick()}
        compose.onNodeWithText("Edit").performClick()
        compose.onNodeWithTag("edit-composer").performTextReplacement("New words")
        compose.onNodeWithText("Save").performClick()
        assertEquals(1,saves)
        compose.onNodeWithText("New words").assertIsDisplayed()
        compose.onNodeWithText("Edited").assertIsDisplayed()
        compose.onNodeWithText("👍").assertExists()
        compose.onNodeWithText("Old words").assertDoesNotExist()
        compose.onNodeWithContentDescription("Search conversation").performClick()
        compose.onNodeWithText("Search this conversation").performTextInput("Old words")
        compose.onNodeWithText("0 results").assertIsDisplayed()
        compose.onNodeWithText("Search this conversation").performTextReplacement("New words")
        compose.onNodeWithText("1 results").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("New words").performTouchInput {longClick()}
        compose.onNodeWithText("Edit").performClick()
        compose.onNodeWithTag("edit-composer").performTextReplacement("Final words")
        compose.onNodeWithText("Save").performClick()
        assertEquals(2,saves)
        compose.onNodeWithText("Final words").performTouchInput {longClick()}
        compose.onNodeWithText("Delete for everyone").performClick()
        compose.onNodeWithText("Delete for everyone",useUnmergedTree=true).performClick()
        compose.onNodeWithText("This message was deleted").assertIsDisplayed()
        compose.onNodeWithText("Final words").assertDoesNotExist()
    }

    @Test fun unsupportedPeerPendingAndViewOnceHideEditAndPendingEditDisablesFurtherEdits() {
        val state=mutableStateOf(AppState(loading=false,messages=listOf(original),networkConfigured=true))
        compose.setContent { GhostCloakTheme {ConversationScreen(state.value,contact,{},{},{_,_->},{})} }
        compose.onNodeWithText("Old words").performTouchInput {longClick()}
        compose.onNodeWithText("Edit").assertDoesNotExist()
        compose.onNodeWithText("Delete for me").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnUiThread {state.value=state.value.copy(editAvailable=true,
            messages=listOf(original.copy(state=MessageState.PENDING)))}
        compose.onNodeWithText("Old words").performTouchInput {longClick()}
        compose.onNodeWithText("Edit").assertDoesNotExist()
        compose.onNodeWithText("Delete for me").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnUiThread {state.value=state.value.copy(messages=listOf(original.copy(
            editRevision=1,editStatus=EditRequestStatus.PENDING)))}
        compose.onNodeWithText("Old words").performTouchInput {longClick()}
        compose.onNodeWithText("Edit").assertDoesNotExist()
        compose.onNodeWithText("Edit pending").assertIsDisplayed()
        compose.onNodeWithText("Delete for me").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnUiThread {state.value=state.value.copy(messages=listOf(original.copy(
            viewOnceKind=ViewOnceKind.TEXT,viewOnceState=ViewOnceState.AVAILABLE)))}
        compose.onNodeWithText("Old words").performTouchInput {longClick()}
        compose.onNodeWithText("Edit").assertDoesNotExist()
    }
}
