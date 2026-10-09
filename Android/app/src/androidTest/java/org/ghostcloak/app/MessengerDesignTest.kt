package org.ghostcloak.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.ghostcloak.app.application.*
import org.ghostcloak.app.ui.screens.*
import org.ghostcloak.app.ui.navigation.GhostBottomBar
import org.ghostcloak.app.ui.theme.GhostCloakTheme
import org.ghostcloak.identity.*
import org.ghostcloak.messaging.*
import org.junit.*
import org.junit.Assert.*
import java.io.File

class MessengerDesignTest {
    @get:Rule val compose = createComposeRule()
    private val id = RandomIdentifiers.create()
    private val contact = ContactStatus(Contact(RandomIdentifiers.create(), RandomIdentifiers.create(), "alice", id, request = true, ghostCloakId = "7K4M9Q2FX8DR"),
        RemoteIdentityStatus(IdentityTrustState.UNVERIFIED), SessionLifecycle.ACTIVE)
    private val message = Message(RandomIdentifiers.create(), id, Direction.INCOMING, "Hey Bob, made it home. See you tomorrow?", System.currentTimeMillis(), MessageState.RECEIVED)
    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    @Test fun unreadDotBelongsToItsConversationAndClearsWhenRead() {
        val unread = mutableStateOf(2)
        var opened: String? = null
        compose.setContent { GhostCloakTheme { org.ghostcloak.app.ui.components.ChatRow(contact, message,
            { opened = it; unread.value = 0 }, unreadCount = unread.value) } }
        compose.onNodeWithContentDescription("2 unread messages", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("7K4M-9Q2F-X8DR").performClick()
        assertEquals(id, opened)
        compose.onNodeWithContentDescription("2 unread messages", useUnmergedTree = true).assertDoesNotExist()
    }
    @Test fun chatTabShowsLiveUnreadCountFromContactsPage() {
        val unread=mutableStateOf(2)
        compose.setContent {GhostCloakTheme {GhostBottomBar("people",unread.value,{})}}
        compose.onNodeWithContentDescription("Chat, 2 unread messages",useUnmergedTree=true)
            .assertIsDisplayed()
        compose.onNodeWithText("2").assertIsDisplayed()
        compose.runOnIdle {unread.value=100}
        compose.onNodeWithContentDescription("Chat, 100 unread messages",useUnmergedTree=true)
            .assertIsDisplayed()
        compose.onNodeWithText("99+").assertIsDisplayed()
        compose.runOnIdle {unread.value=0}
        compose.onNodeWithContentDescription("Chat",useUnmergedTree=true).assertIsDisplayed()
        compose.onNodeWithText("99+").assertDoesNotExist()
    }
    @Test fun contactRowOpensConversation() {
        val accepted=contact.copy(contact=contact.contact.copy(request=false))
        var opened:String?=null
        compose.setContent {GhostCloakTheme {
            ContactsScreen(AppState(loading=false,contacts=listOf(accepted)),{},
                {opened=it},directory=true)
        }}
        compose.onNodeWithText(accepted.contact.visibleName).performClick()
        assertEquals(id,opened)
    }
    @Test fun conversationHeaderOpensContactDetails() {
        val accepted=contact.copy(contact=contact.contact.copy(request=false))
        var details=0
        compose.setContent {GhostCloakTheme {
            ConversationScreen(AppState(loading=false),accepted,{}, {details++},
                {_,_->},{})
        }}
        compose.onNodeWithTag("contact-header").performClick()
        assertEquals(1,details)
    }
    @Test fun directChatOpensAtLastSeenRowAndOnlyReportsVisibleRows() {
        val accepted=contact.copy(contact=contact.contact.copy(request=false))
        val messages=(1..50).map {index -> Message(RandomIdentifiers.create(),id,
            Direction.INCOMING,"message $index",index.toLong(),MessageState.RECEIVED) }
        val visible=mutableSetOf<String>()
        compose.setContent {GhostCloakTheme {
            ConversationScreen(AppState(loading=false,messages=messages,
                directOpening=ChatOpening(id,1,messages[10].localId)),accepted,{}, {},{_,_->},{},
                markVisibleRead={visible+=it})
        }}
        compose.waitForIdle()
        compose.onNodeWithText("message 10").assertIsDisplayed()
        compose.onNodeWithText("message 50").assertDoesNotExist()
        assertTrue(messages[9].localId in visible)
        assertFalse(messages.last().localId in visible)
    }
    @Test fun unreadHeaderReplacesChatsAndMovesSearchLeftUntilAllMessagesAreRead() {
        val unread = mutableStateOf(1)
        compose.setContent { GhostCloakTheme { ContactsScreen(AppState(loading = false, unreadCount = unread.value), {}, {}) } }
        compose.onNodeWithText("Chats").assertDoesNotExist()
        compose.onNodeWithText("1 new message").assertIsDisplayed()
        val leftSearch = compose.onNodeWithContentDescription("Search conversations").getUnclippedBoundsInRoot()
        val count = compose.onNodeWithText("1 new message").getUnclippedBoundsInRoot()
        val composeAction = compose.onNodeWithContentDescription("New chat").getUnclippedBoundsInRoot()
        assertTrue(leftSearch.right < count.left)
        assertTrue(count.right < composeAction.left)
        compose.runOnIdle { unread.value = 24 }
        compose.onNodeWithText("24 new messages").assertIsDisplayed()
        compose.runOnIdle { unread.value = 0 }
        compose.onNodeWithText("Chats").assertIsDisplayed()
        compose.onNodeWithText("24 new messages").assertDoesNotExist()
        assertTrue(compose.onNodeWithContentDescription("Search conversations").getUnclippedBoundsInRoot().left > leftSearch.left)
        compose.onNodeWithContentDescription("New chat").assertIsDisplayed()
    }
    @Test fun rootAndDetailPagesShareHeaderGeometryAndBackAction() {
        val page = mutableStateOf(0)
        var backs = 0
        compose.setContent { GhostCloakTheme { Surface { when (page.value) {
            0 -> ContactsScreen(AppState(loading = false), {}, {})
            1 -> SettingsScreen(AppState(loading = false), false, {}, {})
            2 -> AddContactScreen(AppState(loading = false), { backs++ }, {}, import = {})
            else -> ConversationScreen(AppState(loading = false), contact, { backs++ }, {}, { _, _ -> }, {})
        } } } }
        val header = compose.onNodeWithTag("page-header").getUnclippedBoundsInRoot()
        for (index in 1..3) {
            compose.runOnIdle { page.value = index }
            assertEquals(header, compose.onNodeWithTag("page-header").getUnclippedBoundsInRoot())
            if (index >= 2) compose.onNodeWithContentDescription("Back").performClick()
        }
        assertEquals(2, backs)
    }
    @Test fun requestRequiresAcceptanceButNotVerificationToReply() {
        val status = mutableStateOf(contact)
        var accepted = false
        val dark = mutableStateOf(false)
        val conversation = listOf(message,
            message.copy(localId=RandomIdentifiers.create(),direction=Direction.OUTGOING,body="Absolutely. Coffee at 10?",state=MessageState.DELIVERED),
            message.copy(localId=RandomIdentifiers.create(),body="Perfect. The little place around the corner?"),
            message.copy(localId=RandomIdentifiers.create(),direction=Direction.OUTGOING,body="That one. See you there!",state=MessageState.DELIVERED))
        compose.setContent { GhostCloakTheme(darkTheme=dark.value) { Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) { ConversationScreen(
            AppState(loading = false, messages = if(status.value.contact.request) listOf(message) else conversation), status.value, {}, {}, { _, _ -> }, {},
            accept = { accepted = true; status.value = contact.copy(contact = contact.contact.copy(request = false)) }) } } }
        compose.onNodeWithText("Write a message…").assertIsNotEnabled()
        compose.onNodeWithText("Accept").assertIsDisplayed()
        screenshot("organized-request-light.png")
        compose.onNodeWithText("Accept").performClick()
        compose.onNodeWithText("Write a message…").assertIsEnabled()
        assertTrue(accepted)
        compose.onNodeWithText("Encrypted · Unverified").assertDoesNotExist()
        compose.onNodeWithContentDescription("Unverified contact · Security").assertIsDisplayed()
        screenshot("organized-conversation-light.png")
        compose.runOnIdle { dark.value=true }
        screenshot("organized-conversation-dark.png")
    }
    @Test fun chatHeaderHasComposeActionAndRequestsStayReachableWithoutFilters() {
        val known = contact.copy(contact = contact.contact.copy(contactId = RandomIdentifiers.create(), remoteDeviceId = RandomIdentifiers.create(), displayName = "morgan", request = false))
        val last = message.copy(conversationId = known.contact.remoteDeviceId, direction = Direction.OUTGOING, body = "Sounds good. I'll bring coffee.")
        val extras = listOf("Hannah" to "Thanks for checking in!", "Jordan" to "Let's catch up this weekend.", "Alex" to "Made it. See you soon.", "Nina" to "That sounds like a plan.").map { (name, body) ->
            val c=known.copy(contact=known.contact.copy(contactId=RandomIdentifiers.create(),remoteDeviceId=RandomIdentifiers.create(),displayName=name))
            c to last.copy(conversationId=c.contact.remoteDeviceId,direction=Direction.INCOMING,body=body)
        }
        val dark = mutableStateOf(false)
        compose.setContent { GhostCloakTheme(darkTheme=dark.value) { Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) { ContactsScreen(AppState(loading = false,
            identity = DeviceIdentity(RandomIdentifiers.create(), "bob", RandomIdentifiers.create(), byteArrayOf()),
            unreadCount = 3, unreadByConversation = mapOf(known.contact.remoteDeviceId to 3), contacts = listOf(contact, known)+extras.map {it.first}, previews = mapOf(known.contact.remoteDeviceId to last)+extras.associate {it.first.contact.remoteDeviceId to it.second}), {}, {}) } } }
        compose.onNodeWithText("Ghost Cloak").assertDoesNotExist()
        compose.onNodeWithText("Chats").assertDoesNotExist()
        compose.onNodeWithText("Search chats").assertDoesNotExist()
        compose.onNodeWithText("All").assertDoesNotExist()
        compose.onNodeWithText("Requests 1").assertDoesNotExist()
        compose.onNodeWithContentDescription("New chat").assertHasClickAction()
        compose.onNodeWithText("You: Sounds good. I'll bring coffee.").assertIsDisplayed()
        compose.onNodeWithText("7K4M-9Q2F-X8DR").assertIsDisplayed()
        compose.onNodeWithText("3 new messages").assertIsDisplayed()
        compose.onNodeWithContentDescription("Search conversations").performClick()
        compose.onNodeWithText("Search conversations").performTextInput("morgan")
        compose.onAllNodesWithText("morgan").assertCountEquals(2) // Search field plus matching conversation.
        compose.onNodeWithText("7K4M-9Q2F-X8DR").assertDoesNotExist()
        compose.onNodeWithContentDescription("Search conversations").performClick()
        compose.onNodeWithText("7K4M-9Q2F-X8DR").assertIsDisplayed()
        screenshot("organized-chats-light.png")
        compose.runOnIdle { dark.value = true }
        screenshot("organized-chats-dark.png")
    }
}
