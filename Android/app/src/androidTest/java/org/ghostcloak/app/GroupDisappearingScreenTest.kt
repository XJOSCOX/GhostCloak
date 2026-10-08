package org.ghostcloak.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.ghostcloak.app.application.AppState
import org.ghostcloak.app.ui.screens.GroupInfoActions
import org.ghostcloak.app.ui.screens.GroupInfoScreen
import org.ghostcloak.app.ui.theme.GhostCloakTheme
import org.ghostcloak.messaging.GroupInfo
import org.ghostcloak.messaging.GroupInfoMember
import org.ghostcloak.messaging.GroupLocalStatus
import org.ghostcloak.messaging.GroupManagementStatus
import org.ghostcloak.messaging.GroupPostingModeV1
import org.ghostcloak.messaging.GroupRole
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class GroupDisappearingScreenTest {
    @get:Rule val compose=createComposeRule()
    private val owner=GroupInfoMember("owner-id","owner-device",GroupRole.OWNER,false,true,true)
    private fun info()=GroupInfo("group-id",GroupRole.OWNER,GroupLocalStatus.ACTIVE,
        GroupManagementStatus.READY,listOf(owner),GroupPostingModeV1.EVERYONE,
        pending=false,journalFull=false,invitationPending=false,disappearingCapable=true)
    private fun actions(onTimer:(Int)->Unit)=GroupInfoActions(setup={},retrySetup={},posting={},
        restrict={},unrestrict={},remove={},promote={},demote={},transfer={},
        transferDecision={},leave={},dissolve={},invite={},openChat={},disappearing=onTimer)

    @Test fun timerChangeRequiresConfirmationAndBackCancels() {
        var selected:Int?=null
        compose.setContent {GhostCloakTheme {
            GroupInfoScreen(AppState(loading=false),info(),{},actions {selected=it})
        }}
        compose.onNodeWithTag("group-disappearing-timer").performClick()
        compose.onNodeWithText("30 seconds").performClick()
        compose.onNodeWithText("New messages will use this timer.",substring=true).assertExists()
        assertEquals(null,selected)
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("group-confirm-timer").assertDoesNotExist()
        compose.onNodeWithText("30 seconds").performClick()
        compose.onNodeWithTag("group-confirm-timer").performClick()
        assertEquals(30,selected)
    }
}
