package org.ghostcloak.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.ghostcloak.app.application.*
import org.ghostcloak.app.ui.screens.*
import org.ghostcloak.app.ui.theme.GhostCloakTheme
import org.ghostcloak.identity.*
import org.ghostcloak.messaging.*
import org.junit.*
import org.junit.Assert.*
import java.io.File

class MessengerDesignTest {
    @get:Rule val compose = createComposeRule()
    private val id = RandomIdentifiers.create()
    private val contact = ContactStatus(Contact(RandomIdentifiers.create(), RandomIdentifiers.create(), "alice", id, request = true),
        RemoteIdentityStatus(IdentityTrustState.UNVERIFIED), SessionLifecycle.ACTIVE)
    private val message = Message(RandomIdentifiers.create(), id, Direction.INCOMING, "Hey Bob, made it home. See you tomorrow?", System.currentTimeMillis(), MessageState.RECEIVED)
    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
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
        compose.onNodeWithText("Encrypted · Unverified").assertIsDisplayed()
        screenshot("organized-conversation-light.png")
        compose.runOnIdle { dark.value=true }
        screenshot("organized-conversation-dark.png")
    }
    @Test fun chatListSeparatesRequestsAndShowsLocalPreviews() {
        val known = contact.copy(contact = contact.contact.copy(contactId = RandomIdentifiers.create(), remoteDeviceId = RandomIdentifiers.create(), displayName = "morgan", request = false))
        val last = message.copy(conversationId = known.contact.remoteDeviceId, direction = Direction.OUTGOING, body = "Sounds good. I'll bring coffee.")
        val extras = listOf("Hannah" to "Thanks for checking in!", "Jordan" to "Let's catch up this weekend.", "Alex" to "Made it. See you soon.", "Nina" to "That sounds like a plan.").map { (name, body) ->
            val c=known.copy(contact=known.contact.copy(contactId=RandomIdentifiers.create(),remoteDeviceId=RandomIdentifiers.create(),displayName=name))
            c to last.copy(conversationId=c.contact.remoteDeviceId,direction=Direction.INCOMING,body=body)
        }
        val dark = mutableStateOf(false)
        compose.setContent { GhostCloakTheme(darkTheme=dark.value) { Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) { ContactsScreen(AppState(loading = false,
            identity = DeviceIdentity(RandomIdentifiers.create(), "bob", RandomIdentifiers.create(), byteArrayOf()),
            contacts = listOf(contact, known)+extras.map {it.first}, previews = mapOf(known.contact.remoteDeviceId to last)+extras.associate {it.first.contact.remoteDeviceId to it.second}), {}, {}) } } }
        compose.onNodeWithText("Requests 1").assertIsDisplayed()
        compose.onNodeWithText("You: Sounds good. I'll bring coffee.").assertIsDisplayed()
        screenshot("organized-chats-light.png")
        compose.onNodeWithText("alice").assertDoesNotExist()
        compose.onNodeWithText("Requests 1").performClick()
        compose.onNodeWithText("morgan").assertDoesNotExist()
        compose.onNodeWithText("alice").assertIsDisplayed()
        compose.onNodeWithText("All").performClick()
        compose.onNodeWithText("Search chats").performTextInput("morg")
        compose.onNodeWithText("alice").assertDoesNotExist()
        compose.onNodeWithText("morgan").assertIsDisplayed()
        compose.onNodeWithContentDescription("Clear search").performClick()
        compose.runOnIdle { dark.value = true }
        screenshot("organized-chats-dark.png")
    }
}
