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
        compose.setContent { GhostCloakTheme { Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) { ConversationScreen(
            AppState(loading = false, messages = listOf(message)), status.value, {}, {}, { _, _ -> }, {},
            accept = { accepted = true; status.value = contact.copy(contact = contact.contact.copy(request = false)) }) } } }
        compose.onNodeWithText("Write a message…").assertIsNotEnabled()
        compose.onNodeWithText("Accept").assertIsDisplayed()
        screenshot("e1-request.png")
        compose.onNodeWithText("Accept").performClick()
        compose.onNodeWithText("Write a message…").assertIsEnabled()
        assertTrue(accepted)
        compose.onNodeWithText("Encrypted · Unverified").assertIsDisplayed()
        screenshot("e1-conversation.png")
    }
    @Test fun chatListSeparatesRequestsAndShowsLocalPreviews() {
        val known = contact.copy(contact = contact.contact.copy(contactId = RandomIdentifiers.create(), remoteDeviceId = RandomIdentifiers.create(), displayName = "morgan", request = false))
        val last = message.copy(conversationId = known.contact.remoteDeviceId, direction = Direction.OUTGOING, body = "Sounds good. I'll bring coffee.")
        compose.setContent { GhostCloakTheme { Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) { ContactsScreen(AppState(loading = false,
            identity = DeviceIdentity(RandomIdentifiers.create(), "bob", RandomIdentifiers.create(), byteArrayOf()),
            contacts = listOf(contact, known), previews = mapOf(known.contact.remoteDeviceId to last)), {}, {}) } } }
        compose.onNodeWithText("MESSAGE REQUESTS - 1").assertIsDisplayed()
        compose.onNodeWithText("You: Sounds good. I'll bring coffee.").assertIsDisplayed()
        screenshot("e1-chats.png")
    }
}
