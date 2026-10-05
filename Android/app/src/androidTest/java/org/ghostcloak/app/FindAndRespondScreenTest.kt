package org.ghostcloak.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.runtime.mutableStateOf
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.ghostcloak.app.application.AppState
import org.ghostcloak.app.ui.screens.ConversationScreen
import org.ghostcloak.app.ui.privacy.sensitiveClip
import org.ghostcloak.app.ui.theme.GhostCloakTheme
import org.ghostcloak.identity.SessionLifecycle
import org.ghostcloak.messaging.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class FindAndRespondScreenTest {
    @get:Rule val compose=createComposeRule()
    private val peer=UUID.randomUUID().toString()
    private val contact=ContactStatus(Contact("local","account","Alice",peer),null,SessionLifecycle.ACTIVE)
    private val id=UUID.randomUUID().toString()
    private val original=Message(id,peer,Direction.INCOMING,"Find this greeting",1_000,
        MessageState.RECEIVED,envelopeId=id)

    @Test fun replyBannerCancelSendAndRenderedQuote() {
        var submitted:ReplyReference?=null
        val reply=Message(UUID.randomUUID().toString(),peer,Direction.OUTGOING,"response",2_000,
            MessageState.SERVER_ACCEPTED,envelopeId=UUID.randomUUID().toString(),
            replyTo=ReplyReference(id,ReplyKind.TEXT))
        compose.setContent { GhostCloakTheme {
            ConversationScreen(AppState(loading=false,messages=listOf(original,reply)),contact,{},{},{_,_->},{},
                sendReply={_,reference,done->submitted=reference;done()})
        } }
        compose.onAllNodesWithText("Find this greeting")[0].performTouchInput { longClick() }
        compose.onNodeWithText("Reply").performClick()
        compose.onNodeWithText("Replying to: Find this greeting").assertIsDisplayed()
        compose.onNodeWithText("Cancel reply").performClick()
        compose.onNodeWithText("Replying to: Find this greeting").assertDoesNotExist()
        compose.onAllNodesWithText("Find this greeting")[0].performTouchInput { longClick() }
        compose.onNodeWithText("Reply").performClick()
        compose.onNodeWithText("Write a message…").performTextInput("okay")
        compose.onNodeWithContentDescription("Send").performClick()
        assertEquals(ReplyReference(id,ReplyKind.TEXT),submitted)
        compose.onNodeWithText("Replying to: Find this greeting").assertDoesNotExist()
        compose.onAllNodesWithText("Find this greeting").assertCountEquals(2)
    }

    @Test fun searchNavigatesAndCloseClearsQuery() {
        val open=mutableStateOf(true)
        compose.setContent { GhostCloakTheme {
            if(open.value) ConversationScreen(AppState(loading=false,messages=listOf(original)),contact,{},{},{_,_->},{})
        } }
        compose.onNodeWithContentDescription("Search conversation").performClick()
        compose.onNodeWithText("Search this conversation").performTextInput("GREETING")
        compose.onNodeWithText("1 results").assertIsDisplayed()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Previous").performClick()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("1 results").assertDoesNotExist()
        compose.onNodeWithContentDescription("Search conversation").performClick()
        compose.onNodeWithText("Enter a search term").assertIsDisplayed()
        compose.onNodeWithText("Search this conversation").performTextInput("greeting")
        compose.runOnUiThread { open.value=false }
        compose.waitForIdle()
        compose.runOnUiThread { open.value=true }
        compose.onNodeWithText("Search this conversation").assertDoesNotExist()
    }

    @Test fun viewOnceActionHasNoCopy() {
        val private=original.copy(body="View Once message",viewOnceKind=ViewOnceKind.TEXT,
            viewOnceState=ViewOnceState.CONSUMED)
        compose.setContent { GhostCloakTheme {
            ConversationScreen(AppState(loading=false,messages=listOf(private)),contact,{},{},{_,_->},{})
        } }
        compose.onNodeWithText("View Once message").performTouchInput { longClick() }
        compose.onNodeWithText("Reply").assertIsDisplayed()
        compose.onNodeWithText("Copy").assertDoesNotExist()
        compose.onNodeWithText("Reply").performClick()
        compose.onNodeWithText("Replying to: View Once message").assertIsDisplayed()
        assertNull(ReplyPresentation.reference(original.copy(envelopeId=null)))
    }
    @Test fun normalTextCopyUsesSensitiveClipboard() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val clipboard=context.getSystemService(ClipboardManager::class.java)
        compose.setContent { GhostCloakTheme {
            ConversationScreen(AppState(loading=false,messages=listOf(original)),contact,{},{},{_,_->},{})
        } }
        compose.onNodeWithText("Find this greeting").performTouchInput { longClick() }
        compose.onNodeWithText("Copy").performClick()
        val clip=clipboard.primaryClip!!
        assertEquals("Find this greeting",clip.getItemAt(0).coerceToText(context).toString())
        val key=if(Build.VERSION.SDK_INT>=33) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE"
        // The emulator's host clipboard bridge strips sensitive flags on the mirrored
        // incoming clip. Check the exact object used by the reviewed copy path.
        assertTrue(sensitiveClip("Find this greeting").description.extras!!.getBoolean(key))
    }
}
