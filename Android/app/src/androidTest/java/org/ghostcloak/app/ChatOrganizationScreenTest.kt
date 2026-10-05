package org.ghostcloak.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.ghostcloak.app.application.AppState
import org.ghostcloak.app.ui.screens.ContactSecurityScreen
import org.ghostcloak.app.ui.screens.ContactsScreen
import org.ghostcloak.app.ui.screens.ConversationScreen
import org.ghostcloak.app.ui.theme.GhostCloakTheme
import org.ghostcloak.identity.SessionLifecycle
import org.ghostcloak.messaging.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class ChatOrganizationScreenTest {
    @get:Rule val compose=createComposeRule()
    private val id=UUID.randomUUID().toString()
    private val base=ContactStatus(Contact("local","account","Remote name",id),null,SessionLifecycle.ACTIVE)

    @Test fun aliasEditorSetsAndRemovesLocalName() {
        val contact=mutableStateOf(base)
        compose.setContent { GhostCloakTheme {
            ContactSecurityScreen(AppState(loading=false),contact.value,{},{},{},{},{},
                setAlias={alias->contact.value=contact.value.copy(contact=contact.value.contact.copy(localAlias=alias))})
        } }
        compose.onNodeWithText("Set local alias").performClick()
        compose.onNodeWithText("Alias").performTextInput("  My friend  ")
        compose.onNodeWithText("Save").performClick()
        assertTrue(compose.onAllNodesWithText("My friend").fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText("Name from contact: Remote name").assertIsDisplayed()
        compose.onNodeWithText("Edit local alias").performClick()
        compose.onNodeWithText("Alias").performTextClearance()
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Set local alias").assertIsDisplayed()
        compose.onAllNodesWithText("Remote name").assertCountEquals(2)
    }

    @Test fun conversationMenuPinsMutesAndArchives() {
        val contact=mutableStateOf(base)
        compose.setContent { GhostCloakTheme {
            ConversationScreen(AppState(loading=false),contact.value,{},{},{_,_->},{},
                setPinned={contact.value=contact.value.copy(contact=contact.value.contact.copy(pinned=it))},
                setArchived={contact.value=contact.value.copy(contact=contact.value.contact.copy(archived=it))},
                setMuted={contact.value=contact.value.copy(contact=contact.value.contact.copy(muted=it))})
        } }
        compose.onNodeWithContentDescription("Conversation options").performClick()
        compose.onNodeWithText("Pin chat").performClick()
        assertTrue(contact.value.contact.pinned)
        compose.onNodeWithContentDescription("Conversation options").performClick()
        compose.onNodeWithText("Mute chat").performClick()
        assertTrue(contact.value.contact.muted)
        compose.onNodeWithContentDescription("Conversation options").performClick()
        compose.onNodeWithText("Archive chat").performClick()
        assertTrue(contact.value.contact.archived)
        compose.onNodeWithContentDescription("Conversation options").performClick()
        compose.onNodeWithText("Unarchive chat").assertIsDisplayed()
        compose.onNodeWithText("Unmute chat").performClick()
        assertFalse(contact.value.contact.muted)
    }

    @Test fun mainAndArchivedListsSeparateContactsAndAllowUnarchive() {
        val other=ContactStatus(Contact("other","account2","Other",UUID.randomUUID().toString()),null,SessionLifecycle.ACTIVE)
        val contacts=mutableStateOf(listOf(base.copy(contact=base.contact.copy(archived=true)),other))
        var archived=mutableStateOf(false)
        compose.setContent { GhostCloakTheme {
            ContactsScreen(AppState(loading=false,contacts=contacts.value),{},{},archived=archived.value,
                showArchived={archived.value=true},unarchive={peer->contacts.value=contacts.value.map {
                    if(it.contact.remoteDeviceId==peer) it.copy(contact=it.contact.copy(archived=false)) else it
                }},back={archived.value=false})
        } }
        compose.onNodeWithText("Remote name").assertDoesNotExist()
        compose.onNodeWithText("Archived chats").performClick()
        compose.onNodeWithText("Remote name").assertIsDisplayed()
        compose.onNodeWithText("Unarchive Remote name").performClick()
        compose.onNodeWithText("Remote name").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Remote name").assertIsDisplayed()
    }

    @Test fun pinnedChatAppearsAboveMoreRecentChat() {
        val recent=ContactStatus(Contact("recent","account2","Recent",UUID.randomUUID().toString()),null,SessionLifecycle.ACTIVE)
        val pinned=base.copy(contact=base.contact.copy(pinned=true))
        fun preview(status:ContactStatus,time:Long)=Message(UUID.randomUUID().toString(),
            status.contact.remoteDeviceId,Direction.INCOMING,"message",time,MessageState.RECEIVED)
        val previews=mapOf(recent.contact.remoteDeviceId to preview(recent,200L),
            pinned.contact.remoteDeviceId to preview(pinned,100L))
        compose.setContent { GhostCloakTheme {
            ContactsScreen(AppState(loading=false,contacts=listOf(recent,pinned),previews=previews),{},{})
        } }
        val pinnedTop=compose.onNodeWithText("Remote name").getUnclippedBoundsInRoot().top
        val recentTop=compose.onNodeWithText("Recent").getUnclippedBoundsInRoot().top
        val pinnedHeaderTop=compose.onNodeWithText("Pinned").getUnclippedBoundsInRoot().top
        val otherHeaderTop=compose.onNodeWithText("Other chats").getUnclippedBoundsInRoot().top
        assertTrue(pinnedHeaderTop<pinnedTop)
        assertTrue(pinnedTop<otherHeaderTop)
        assertTrue(otherHeaderTop<recentTop)
        compose.onNodeWithContentDescription("Pinned conversation").assertDoesNotExist()
    }

    @Test fun noPinnedSectionWhenNothingIsPinned() {
        compose.setContent { GhostCloakTheme {
            ContactsScreen(AppState(loading=false,contacts=listOf(base)),{},{})
        } }
        compose.onNodeWithText("Pinned").assertDoesNotExist()
        compose.onNodeWithText("Other chats").assertDoesNotExist()
        compose.onNodeWithText("Remote name").assertIsDisplayed()
    }

    @Test fun mutedChatRetainsUnreadIndicator() {
        val muted=base.copy(contact=base.contact.copy(muted=true))
        compose.setContent { GhostCloakTheme {
            ContactsScreen(AppState(loading=false,contacts=listOf(muted),unreadCount=1,
                unreadByConversation=mapOf(id to 1)),{},{})
        } }
        compose.onNodeWithContentDescription("Muted conversation").assertIsDisplayed()
        compose.onNodeWithContentDescription("1 unread message").assertIsDisplayed()
    }
}
