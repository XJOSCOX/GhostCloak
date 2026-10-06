package org.ghostcloak.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import android.app.Notification
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.ghostcloak.app.application.AppState
import org.ghostcloak.app.application.AndroidLocalNotifications
import org.ghostcloak.app.ui.screens.ContactsScreen
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

    @Test fun validatedInvitationShowsSafeDetailsAndExplicitActions() {
        val id=GroupIds.create()
        val contact=ContactStatus(Contact("local","account","Peer","device",localAlias="Friend"),
            null,SessionLifecycle.ACTIVE)
        var accepted="";var declined=""
        compose.setContent {GhostCloakTheme {
            ContactsScreen(AppState(loading=false,networkConfigured=true,contacts=listOf(contact),
                groupInvitations=listOf(GroupMembershipTransport.Invitation(id,"device",2,false))),
                {},{},directory=true,acceptGroupInvite={accepted=it},declineGroupInvite={declined=it})
        }}
        compose.onNodeWithText("Group invitation").assertExists()
        compose.onNodeWithText("From Friend · 2 current members").assertExists()
        compose.onNodeWithText("Inviter unverified").assertExists()
        compose.onNodeWithText("account").assertDoesNotExist()
        compose.onNodeWithText("device").assertDoesNotExist()
        compose.onNodeWithText("Accept").performClick()
        assertEquals(id,accepted)
        compose.onNodeWithText("Decline").performClick()
        assertEquals(id,declined)
    }

    @Test fun pendingAcceptanceDoesNotOfferSecondAccept() {
        val id=GroupIds.create()
        val contact=ContactStatus(Contact("local","account","Peer","device"),null,SessionLifecycle.ACTIVE)
        compose.setContent {GhostCloakTheme {
            ContactsScreen(AppState(loading=false,networkConfigured=true,contacts=listOf(contact),
                groupInvitations=listOf(GroupMembershipTransport.Invitation(id,"device",1,true))),
                {},{},directory=true)
        }}
        compose.onNodeWithText("Waiting for membership confirmation…").assertExists()
        compose.onNodeWithText("Accept").assertDoesNotExist()
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
        compose.onNodeWithText("Send").performClick()
        assertEquals("next",sent)
    }
}
