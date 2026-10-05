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
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class DeleteForEveryoneScreenTest {
    @get:Rule val compose=createComposeRule()
    private val peer=UUID.randomUUID().toString()
    private val messageId=UUID.randomUUID().toString()
    private val message=Message(messageId,peer,Direction.OUTGOING,"P9 visible text",1_000,
        MessageState.SERVER_ACCEPTED,UUID.randomUUID().toString())
    private val contact=ContactStatus(Contact("local","account","Peer",peer),null,SessionLifecycle.ACTIVE)

    @Test fun supportedPeerRequiresConfirmationAndShowsHonestPlaceholder() {
        val state=mutableStateOf(AppState(loading=false,messages=listOf(message),
            networkConfigured=true,deleteAvailable=true))
        var requested:String?=null
        compose.setContent { GhostCloakTheme {
            ConversationScreen(state.value,contact,{},{},{_,_->},{},
                deleteEveryone={id -> requested=id
                    state.value=state.value.copy(messages=listOf(message.copy(body="",deleted=true,
                        deleteStatus=DeleteRequestStatus.PENDING)))})
        } }
        compose.onNodeWithText("P9 visible text").performTouchInput { longClick() }
        compose.onNodeWithText("Delete for me").assertExists()
        compose.onNodeWithText("Delete for everyone").performClick()
        compose.onNodeWithText("Delete for everyone?").assertIsDisplayed()
        compose.onNodeWithText("A blocked recipient may retain the message.",substring=true).assertExists()
        assertEquals(null,requested)
        compose.onNodeWithText("Delete for everyone",useUnmergedTree=true).performClick()
        assertEquals(messageId,requested)
        compose.onNodeWithText("This message was deleted").assertIsDisplayed()
        compose.onNodeWithText("Delete request pending").assertIsDisplayed()
        compose.onNodeWithText("P9 visible text").assertDoesNotExist()
    }

    @Test fun unsupportedPeerAndUnacceptedMessageHaveOnlyLocalDelete() {
        val state=mutableStateOf(AppState(loading=false,messages=listOf(message),
            networkConfigured=true,deleteAvailable=false))
        compose.setContent { GhostCloakTheme {
            ConversationScreen(state.value,contact,{},{},{_,_->},{})
        } }
        compose.onNodeWithText("P9 visible text").performTouchInput { longClick() }
        compose.onNodeWithText("Delete for me").assertExists()
        compose.onNodeWithText("Delete for everyone").assertDoesNotExist()
        compose.onNodeWithText("Delete for me").performClick()
        compose.onNodeWithText("This removes the message from this device only.").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnUiThread { state.value=state.value.copy(deleteAvailable=true,
            messages=listOf(message.copy(state=MessageState.PENDING))) }
        compose.onNodeWithText("P9 visible text").performTouchInput { longClick() }
        compose.onNodeWithText("Delete for everyone").assertDoesNotExist()
    }
}
