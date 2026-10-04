package org.ghostcloak.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.platform.LocalClipboard
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.ghostcloak.app.application.AppState
import org.ghostcloak.app.ui.screens.ConversationScreen
import org.ghostcloak.app.ui.privacy.SensitiveClipboardProvider
import org.ghostcloak.app.ui.theme.GhostCloakTheme
import org.ghostcloak.identity.SessionLifecycle
import org.ghostcloak.messaging.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test

class ViewOnceScreenTest {
    @get:Rule val compose=createComposeRule()
    private val contact=ContactStatus(Contact("local","account","Alice","device"),null,SessionLifecycle.ACTIVE)
    @Test fun textRequiresTapAndCloseRemovesPresentation() {
        val item=mutableStateOf(Message("item","device",Direction.INCOMING,"View Once message",1000,
            MessageState.RECEIVED,viewOnceKind=ViewOnceKind.TEXT,viewOnceState=ViewOnceState.AVAILABLE))
        var reveals=0;var consumes=0
        compose.setContent { GhostCloakTheme {
            ConversationScreen(AppState(loading=false,messages=listOf(item.value)),contact,{},{},{_,_->},{},
                revealText={_,show->reveals++;show("private text")},consume={
                    consumes++;item.value=item.value.copy(body="View Once message expired",viewOnceState=ViewOnceState.CONSUMED)
                })
        } }
        compose.onNodeWithText("private text").assertDoesNotExist()
        compose.onNodeWithText("Tap to view").performClick()
        compose.onNodeWithText("private text").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("private text").assertDoesNotExist()
        compose.onNodeWithText("Tap to view").assertDoesNotExist()
        assertEquals(1,reveals);assertEquals(1,consumes)
    }
    @Test fun photoShowsOnlyProtectedPlaceholder() {
        val item=Message("photo","device",Direction.INCOMING,"View Once photo",1000,
            MessageState.RECEIVED,viewOnceKind=ViewOnceKind.PHOTO,viewOnceState=ViewOnceState.AVAILABLE)
        compose.setContent { GhostCloakTheme {
            ConversationScreen(AppState(loading=false,messages=listOf(item)),contact,{},{},{_,_->},{})
        } }
        compose.onNodeWithText("View Once photo").assertIsDisplayed()
        compose.onNodeWithText("Tap to view").assertIsDisplayed()
        compose.onNodeWithContentDescription("Photo").assertDoesNotExist()
    }
    @Test fun backgroundLifecycleImmediatelyHidesRevealedText() {
        val owner=object:LifecycleOwner { override val lifecycle=LifecycleRegistry(this) }
        compose.runOnUiThread { owner.lifecycle.currentState=Lifecycle.State.STARTED }
        val item=Message("item","device",Direction.INCOMING,"View Once message",1000,
            MessageState.RECEIVED,viewOnceKind=ViewOnceKind.TEXT,viewOnceState=ViewOnceState.AVAILABLE)
        var consumed=0
        compose.setContent { GhostCloakTheme { CompositionLocalProvider(LocalLifecycleOwner provides owner) {
            ConversationScreen(AppState(loading=false,messages=listOf(item)),contact,{},{},{_,_->},{},
                revealText={_,show->show("private text")},consume={consumed++})
        } } }
        compose.onNodeWithText("Tap to view").performClick()
        compose.onNodeWithText("private text").assertIsDisplayed()
        compose.runOnIdle { owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP) }
        compose.onNodeWithText("private text").assertDoesNotExist()
        assertEquals(1,consumed)
    }
    @Test fun repeatedComposerFocusAndTypingDoesNotCrash() {
        compose.setContent { GhostCloakTheme {
            val nativeClipboard=LocalClipboard.current
            SensitiveClipboardProvider {
                assertSame(nativeClipboard,LocalClipboard.current)
                ConversationScreen(AppState(loading=false),contact,{},{},{_,_->},{})
            }
        } }
        repeat(8) {
            compose.onNodeWithText("Write a message…").performClick()
        }
        compose.onNodeWithText("Write a message…").performTextInput("hello")
        compose.onNodeWithText("hello").assertIsDisplayed()
        compose.onNodeWithText("hello").performTouchInput { longClick() }
        repeat(4) { compose.onNodeWithText("hello").performClick() }
    }
}
