package org.ghostcloak.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.ghostcloak.app.application.AppState
import org.ghostcloak.app.ui.screens.*
import org.ghostcloak.app.ui.theme.GhostCloakTheme
import org.ghostcloak.messaging.*
import org.ghostcloak.identity.SessionLifecycle
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class GroupInfoScreenTest {
    @get:Rule val compose=createComposeRule()
    private val owner=GroupInfoMember("owner-id","owner-device",GroupRole.OWNER,false,true,true)
    private val admin=GroupInfoMember("admin-id","admin-device",GroupRole.ADMIN,false,false,false)
    private val member=GroupInfoMember("member-id","member-device",GroupRole.MEMBER,false,false,false)
    private val state=AppState(loading=false,contacts=listOf(
        ContactStatus(Contact("admin-contact","admin-account","Bob","admin-device"),null,
            SessionLifecycle.ACTIVE),
        ContactStatus(Contact("member-contact","member-account","Carol","member-device"),null,
            SessionLifecycle.ACTIVE)))
    private fun info(status:GroupManagementStatus=GroupManagementStatus.READY,
        role:GroupRole=GroupRole.OWNER,pending:Boolean=false)=GroupInfo(
        "group-id",role,GroupLocalStatus.ACTIVE,status,listOf(owner,admin,member),
        GroupPostingModeV1.EVERYONE,pending,false,false)
    private fun actions(onRemove:(String)->Unit={},onSetup:()->Unit={},onRetry:()->Unit={}):GroupInfoActions=GroupInfoActions(
        setup=onSetup,retrySetup=onRetry,posting={},restrict={},unrestrict={},remove=onRemove,promote={},demote={},
        transfer={},transferDecision={},leave={},dissolve={},invite={},openChat={})

    @Test fun ownerMemberControlsRequireConfirmationAndDoNotExposeIds() {
        var removed:String?=null
        compose.setContent {GhostCloakTheme {
            GroupInfoScreen(state,info(),{},actions(onRemove={removed=it}))
        }}
        compose.onNodeWithText("You").assertExists()
        compose.onNodeWithText("Owner").assertExists()
        compose.onNodeWithText("Admin").assertExists()
        compose.onNodeWithText("Member").assertExists()
        compose.onNodeWithText("Carol").performClick()
        compose.onNodeWithText("Make admin").assertExists()
        compose.onNodeWithText("Restrict from sending").assertExists()
        compose.onNodeWithText("Remove from group").performClick()
        compose.onNodeWithText("Messages they already received cannot be recalled.",substring=true)
            .assertExists()
        compose.onNodeWithText("Confirm").performClick()
        assertEquals("member-id",removed)
        compose.onNodeWithText("member-id",substring=true).assertDoesNotExist()
    }

    @Test fun memberAndPendingStatesHideManagement() {
        compose.setContent {GhostCloakTheme {
            GroupInfoScreen(state,info(role=GroupRole.MEMBER,pending=true),{},actions())
        }}
        compose.onNodeWithText("Group update pending…").assertExists()
        compose.onNodeWithText("Carol").performClick()
        compose.onNodeWithText("Remove from group").assertDoesNotExist()
        compose.onNodeWithText("Make admin").assertDoesNotExist()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithTag("group-add-member").assertDoesNotExist()
    }

    @Test fun setupAndLockedStatesAreExplicit() {
        var setups=0
        val status=mutableStateOf(GroupManagementStatus.NOT_CONFIGURED)
        compose.setContent {GhostCloakTheme {
            GroupInfoScreen(state,info(status=status.value),{},
                actions(onSetup={setups++}))
        }}
        compose.onNodeWithTag("group-setup").performClick()
        assertEquals(1,setups)
        compose.runOnUiThread {status.value=GroupManagementStatus.LEGACY_INCOMPLETE}
        compose.onNodeWithText("earlier development version",substring=true).assertExists()
        compose.onNodeWithTag("group-setup").assertDoesNotExist()
        compose.onNodeWithTag("group-posting-mode").assertDoesNotExist()
    }

    @Test fun setupShowsProgressContinuationAndErrorsOnTheSamePage() {
        val status=mutableStateOf(GroupManagementStatus.NOT_CONFIGURED)
        val error=mutableStateOf<String?>(null)
        var retries=0
        compose.setContent {GhostCloakTheme {
            GroupInfoScreen(state.copy(error=error.value),info(status=status.value),{},actions(onRetry={retries++}))
        }}
        compose.onNodeWithTag("group-setup").assertExists()
        compose.runOnUiThread {status.value=GroupManagementStatus.SETTING_UP_BASELINE}
        compose.onNodeWithText("signed group-security approval",substring=true).assertExists()
        compose.onNodeWithTag("group-setup-retry").performClick()
        assertEquals(1,retries)
        compose.onNodeWithTag("group-setup").assertDoesNotExist()
        compose.runOnUiThread {status.value=GroupManagementStatus.BASELINE_READY}
        compose.onNodeWithText("Continue group setup").assertExists()
        compose.runOnUiThread {error.value="Waiting for updated group members."}
        compose.onNodeWithText("Waiting for updated group members.").assertExists()
    }

    @Test fun groupHeaderOpensInfo() {
        var opened=0
        val conversation=GroupMembershipTransport.Conversation("group-id",GroupLocalStatus.ACTIVE,
            3,mapOf(owner.memberId to owner.deviceId,admin.memberId to admin.deviceId,
                member.memberId to member.deviceId),emptyList(),info=info())
        compose.setContent {GhostCloakTheme {
            GroupConversationScreen(state,conversation,{}, {_,_->},{},openInfo={opened++})
        }}
        compose.onNodeWithTag("group-info-entry").performClick()
        assertEquals(1,opened)
    }
}
