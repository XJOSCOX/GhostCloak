package org.ghostcloak.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import android.app.Notification
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.ghostcloak.app.application.AppState
import org.ghostcloak.app.application.AndroidLocalNotifications
import org.ghostcloak.app.ui.screens.ContactsScreen
import org.ghostcloak.app.ui.screens.CreateGroupScreen
import org.ghostcloak.app.ui.screens.GroupConversationScreen
import org.ghostcloak.app.ui.theme.GhostCloakTheme
import org.ghostcloak.identity.SessionLifecycle
import org.ghostcloak.messaging.Contact
import org.ghostcloak.messaging.ContactStatus
import org.ghostcloak.messaging.GroupIds
import org.ghostcloak.messaging.GroupMembershipTransport
import org.ghostcloak.messaging.GroupLocalStatus
import org.ghostcloak.messaging.GroupChatMessage
import org.ghostcloak.messaging.GroupRecipient
import org.ghostcloak.messaging.GroupRecipientState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class GroupInvitationScreenTest {
    @get:Rule val compose=createComposeRule()

    @Test fun createGroupMenuOpensDedicatedPage() {
        var opened=0
        compose.setContent {GhostCloakTheme {
            ContactsScreen(AppState(loading=false),{},{},showCreateGroup={opened++})
        }}
        compose.onNodeWithContentDescription("Chat options").performClick()
        compose.onNodeWithText("Create group").performClick()
        assertEquals(1,opened)
        compose.onNodeWithText("Search contacts").assertDoesNotExist()
    }

    @Test fun createGroupPageExplainsMissingAuthenticatedPeerSupportAndOpensChat() {
        val contact=ContactStatus(Contact("local","account","Peer","device"),null,SessionLifecycle.ACTIVE)
        var opened="";var created=""
        compose.setContent {GhostCloakTheme {
            CreateGroupScreen(AppState(loading=false,contacts=listOf(contact)),{},
                {created=it},{opened=it})
        }}
        compose.onNodeWithText("Search contacts").assertExists()
        compose.onNodeWithText("Waiting for group support confirmation").assertExists()
        compose.onNodeWithText("No contacts are ready yet.",substring=true).assertExists()
        compose.onNodeWithText("Open chat").performClick()
        assertEquals("device",opened)
        assertEquals("",created)
    }

    @Test fun createGroupPageSearchesAndSelectsAuthenticatedCapableContact() {
        val contact=ContactStatus(Contact("local","account","Peer","device"),null,
            SessionLifecycle.ACTIVE,groupCapable=true)
        val other=ContactStatus(Contact("other","account-2","Other","device-2"),null,
            SessionLifecycle.ACTIVE,groupCapable=true)
        var created=""
        compose.setContent {GhostCloakTheme {
            CreateGroupScreen(AppState(loading=false,contacts=listOf(contact,other)),{},
                {created=it},{})
        }}
        compose.onNodeWithText("Search contacts").performTextInput("pee")
        compose.onNodeWithText("Other").assertDoesNotExist()
        compose.onNodeWithText("Peer").performClick()
        assertEquals("device",created)
    }

    @Test fun validatedInvitationIsActionableOnItsGroupPage() {
        val id=GroupIds.create();val groupId=GroupIds.create();val own=GroupIds.create()
        val contact=ContactStatus(Contact("local","account","Peer","device",localAlias="Friend"),
            null,SessionLifecycle.ACTIVE)
        val group=GroupMembershipTransport.Conversation(groupId,GroupLocalStatus.INVITED,1,
            mapOf(own to "device"),emptyList())
        var accepted="";var declined=""
        compose.setContent {GhostCloakTheme {
            GroupConversationScreen(AppState(loading=false,networkConfigured=true,contacts=listOf(contact),
                groupInvitations=listOf(GroupMembershipTransport.Invitation(id,"device",1,false,groupId))),
                group,{},{_,_->},{},acceptInvitation={accepted=it},declineInvitation={declined=it})
        }}
        compose.onNodeWithText("Group invitation").assertExists()
        compose.onNodeWithText("Not a member yet").assertExists()
        compose.onNodeWithText("You were invited to this group. Accept to join.").assertExists()
        compose.onNodeWithText("From Friend").assertExists()
        compose.onNodeWithText("Inviter unverified").assertExists()
        compose.onNodeWithText("account").assertDoesNotExist()
        compose.onNodeWithText("device").assertDoesNotExist()
        compose.onNodeWithText("Accept invitation").performClick()
        assertEquals(id,accepted)
        compose.onNodeWithText("Decline").performClick()
        assertEquals(id,declined)
    }

    @Test fun pendingAcceptanceDoesNotOfferSecondAccept() {
        val id=GroupIds.create();val groupId=GroupIds.create();val own=GroupIds.create()
        val contact=ContactStatus(Contact("local","account","Peer","device"),null,SessionLifecycle.ACTIVE)
        val group=GroupMembershipTransport.Conversation(groupId,GroupLocalStatus.INVITED,1,
            mapOf(own to "device"),emptyList())
        compose.setContent {GhostCloakTheme {
            GroupConversationScreen(AppState(loading=false,networkConfigured=true,contacts=listOf(contact),
                groupInvitations=listOf(GroupMembershipTransport.Invitation(id,"device",1,true,groupId))),
                group,{},{_,_->},{})
        }}
        compose.onNodeWithText("Acceptance sent. Waiting for signed membership confirmation.").assertExists()
        compose.onNodeWithText("Accept invitation").assertDoesNotExist()
    }
    @Test fun creatorSeesInvitationPendingInsteadOfGenericOneMemberStatus() {
        val groupId=GroupIds.create();val own=GroupIds.create()
        val group=GroupMembershipTransport.Conversation(groupId,GroupLocalStatus.ACTIVE,1,
            mapOf(own to "own-device"),emptyList(),invitationPending=true)
        compose.setContent {GhostCloakTheme {
            GroupConversationScreen(AppState(loading=false),group,{},{_,_->},{})
        }}
        compose.onNodeWithText("Invitation sent. Waiting for the contact to accept.").assertExists()
    }
    @Test fun invitationNotificationIsAlwaysGenericAndSecret() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val notice=AndroidLocalNotifications(context).buildInvitation(false)
        assertEquals("Ghost Cloak",notice.extras.getCharSequence(Notification.EXTRA_TITLE))
        assertEquals("New group invitation",notice.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertEquals(Notification.VISIBILITY_SECRET,notice.visibility)
    }
    @Test fun groupEntryOpensTextChat() {
        val groupId=GroupIds.create();val own=GroupIds.create();val peer=GroupIds.create()
        val contact=ContactStatus(Contact("local","account","Peer","device"),null,SessionLifecycle.ACTIVE,
            groupCapable=true)
        val group=GroupMembershipTransport.Conversation(groupId,GroupLocalStatus.ACTIVE,2,
            mapOf(own to "own-device",peer to "device"),listOf(GroupChatMessage(groupId,GroupIds.create(),2,
                own,true,"Hello group",1,listOf(GroupRecipient("device",GroupRecipientState.UNAVAILABLE)))))
        var opened=""
        compose.setContent {GhostCloakTheme {
            ContactsScreen(AppState(loading=false,contacts=listOf(contact),groups=listOf(group)),{},{},
                openGroup={opened=it})
        }}
        compose.onNodeWithText("Group conversation").performClick()
        assertEquals(groupId,opened)
    }
    @Test fun textChatShowsPartialFanoutHonestly() {
        val groupId=GroupIds.create();val own=GroupIds.create();val peer=GroupIds.create()
        val contact=ContactStatus(Contact("local","account","Peer","device"),null,SessionLifecycle.ACTIVE,
            groupCapable=true)
        val group=GroupMembershipTransport.Conversation(groupId,GroupLocalStatus.ACTIVE,2,
            mapOf(own to "own-device",peer to "device"),listOf(GroupChatMessage(groupId,GroupIds.create(),2,
                own,true,"Hello group",1,listOf(GroupRecipient("device",GroupRecipientState.UNAVAILABLE)))))
        var sent=""
        compose.setContent {GhostCloakTheme {
            GroupConversationScreen(AppState(loading=false,contacts=listOf(contact)),group,{},
                {text,done->sent=text;done()}, {})
        }}
        compose.onNodeWithText("Hello group").assertExists()
        compose.onNodeWithText("Sent to 0 of 1; 1 unavailable").assertExists()
        compose.onNodeWithText("Message group").performTextInput("next")
        compose.onNodeWithContentDescription("Send").performClick()
        assertEquals("next",sent)
    }

    @Test fun groupComposerRemainsVisibleWhileTyping() {
        val groupId=GroupIds.create();val own=GroupIds.create();val peer=GroupIds.create()
        val group=GroupMembershipTransport.Conversation(groupId,GroupLocalStatus.ACTIVE,2,
            mapOf(own to "own-device",peer to "device"),emptyList())
        compose.setContent {GhostCloakTheme {
            GroupConversationScreen(AppState(loading=false),group,{},{_,_->},{})
        }}
        compose.onNodeWithTag("group-composer").performClick()
        compose.onNodeWithTag("group-composer").performTextInput("Hello")
        compose.onNodeWithTag("group-composer").assertIsDisplayed()
        compose.onNodeWithContentDescription("Send").assertIsEnabled()
    }
}
