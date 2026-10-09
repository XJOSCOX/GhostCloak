package org.ghostcloak.app.application

import kotlinx.coroutines.runBlocking
import org.ghostcloak.crypto.EndpointRecords
import org.ghostcloak.crypto.SignalProtocolEngine
import org.ghostcloak.messaging.Contact
import org.ghostcloak.messaging.ContactStatus
import org.ghostcloak.messaging.ConversationService
import org.ghostcloak.messaging.Direction
import org.ghostcloak.messaging.LocalRepository
import org.ghostcloak.messaging.Message
import org.ghostcloak.messaging.MessageState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationRefreshSnapshotTest {
    private class CountingRecords : EndpointRecords {
        private val values = linkedMapOf<String, ByteArray>()
        var messageKeyScans = 0
            private set
        var messageReads = 0
            private set

        override fun <T> transaction(block: () -> T): T = block()
        override fun read(key: String): ByteArray? {
            if (key.startsWith("app/message/")) messageReads++
            return values[key]?.copyOf()
        }
        override fun write(key: String, value: ByteArray) { values[key] = value.copyOf() }
        override fun remove(key: String) { values.remove(key) }
        override fun keys(prefix: String): List<String> {
            if (prefix.startsWith("app/message/")) messageKeyScans++
            return values.keys.filter { it.startsWith(prefix) }
        }
        fun resetCounts() { messageKeyScans = 0; messageReads = 0 }
    }

    private suspend fun previousRefresh(
        contacts: List<ContactStatus>, selected: String?, load: suspend (String) -> List<Message>
    ): ConversationRefreshSnapshot = ConversationRefreshSnapshot(
        contacts.mapNotNull { status ->
            val id = status.contact.remoteDeviceId
            load(id).lastOrNull()?.let { id to it }
        }.toMap(),
        selected?.takeIf { id -> contacts.any { it.contact.remoteDeviceId == id } }?.let { load(it) } ?: emptyList(),
        selected
    )

    @Test fun selectedConversationUsesOneRepositorySnapshotAndPreservesUiOrdering() = runBlocking {
        val records = CountingRecords()
        val repository = LocalRepository(records)
        val service = ConversationService(SignalProtocolEngine(records), repository)
        val contacts = listOf("alpha", "bravo", "charlie").map { id ->
            ContactStatus(Contact(id, "account-$id", id, id), null, null)
        }
        contacts.forEach { repository.save(it.contact) }
        repository.save(Message("a1", "alpha", Direction.INCOMING, "first", 1, MessageState.RECEIVED))
        repository.save(Message("b2", "bravo", Direction.INCOMING, "latest", 3, MessageState.RECEIVED))
        repository.save(Message("b1", "bravo", Direction.INCOMING, "older", 2, MessageState.RECEIVED))

        records.resetCounts()
        val before = previousRefresh(contacts, "bravo", service::messagesForUi)
        val beforeScans = records.messageKeyScans
        val beforeReads = records.messageReads
        records.resetCounts()
        val after = loadConversationRefreshSnapshot(contacts, "bravo", service::messagesForUi)
        val afterScans = records.messageKeyScans
        val afterReads = records.messageReads

        assertEquals(before.previews, after.previews)
        assertEquals(before.messages, after.messages)
        assertEquals(before.selectedId, after.selectedId)
        assertEquals(listOf("alpha", "bravo"), after.previews.keys.toList())
        assertEquals(listOf("b1", "b2"), after.messages.map { it.localId })
        assertEquals(emptyList<Message>(), after.messagesForSelection("alpha"))
        assertEquals(after.messages, after.messagesForSelection("bravo"))
        assertEquals(8, beforeScans)
        assertEquals(6, afterScans)
        assertTrue(afterReads < beforeReads)
        println("R3_REFRESH_MESSAGE_KEY_SCANS before=$beforeScans after=$afterScans")
        println("R3_REFRESH_MESSAGE_RECORD_READS before=$beforeReads after=$afterReads")
    }

    @Test fun absentSelectionDoesNotLoadAnUnlistedConversation() = runBlocking {
        val contacts = listOf(ContactStatus(Contact("a", "account", "A", "alpha"), null, null))
        val requested = mutableListOf<String>()
        val snapshot = loadConversationRefreshSnapshot(contacts, "not-a-contact") { id ->
            requested += id
            emptyList()
        }
        assertEquals(listOf("alpha"), requested)
        assertEquals(emptyMap<String, Message>(), snapshot.previews)
        assertEquals(emptyList<Message>(), snapshot.messages)
    }

    @Test fun listPreviewsDoNotRequestFullUnselectedHistories() = runBlocking {
        val contacts=listOf("alpha","bravo").map { id ->
            ContactStatus(Contact(id,"account-$id",id,id),null,null)
        }
        val full=mutableListOf<String>()
        val previews=mutableListOf<String>()
        val snapshot=loadConversationRefreshSnapshot(contacts,"bravo",{ id ->
            full+=id
            listOf(Message("$id-1",id,Direction.INCOMING,"one",1,MessageState.RECEIVED),
                Message("$id-2",id,Direction.INCOMING,"two",2,MessageState.RECEIVED))
        },{ id ->
            previews+=id
            Message("$id-2",id,Direction.INCOMING,"two",2,MessageState.RECEIVED)
        })
        assertEquals(listOf("bravo"),full)
        assertEquals(listOf("alpha"),previews)
        assertEquals("alpha-2",snapshot.previews["alpha"]?.localId)
        assertEquals(listOf("bravo-1","bravo-2"),snapshot.messages.map {it.localId})
    }
}
