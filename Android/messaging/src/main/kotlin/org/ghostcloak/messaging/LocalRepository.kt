@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
package org.ghostcloak.messaging

import kotlinx.serialization.*
import kotlinx.serialization.cbor.Cbor
import org.ghostcloak.crypto.EndpointRecords
import org.ghostcloak.crypto.EndpointStorageFailure

/** Only the endpoint's encrypted store may back this repository in the application. */
class LocalRepository(private val records: EndpointRecords, val clock: ExpiryClock = ExpiryClock()) {
    private val format = Cbor { encodeDefaults = true; ignoreUnknownKeys = false }
    private inline fun <reified T> read(key: String): T? = records.read(key)?.let { bytes ->
        try { format.decodeFromByteArray<T>(bytes) }
        catch (e: SerializationException) { throw EndpointStorageFailure() }
        catch (e: IllegalStateException) { throw EndpointStorageFailure() }
        finally { bytes.fill(0) }
    }
    private inline fun <reified T> put(key: String, value: T) {
        val bytes = format.encodeToByteArray(value)
        try { records.write(key, bytes) } finally { bytes.fill(0) }
    }
    fun hasIdentity() = records.transaction { records.read("local/device") != null }
    fun contacts(): List<Contact> = records.transaction { records.keys("app/contact/").map { read<Contact>(it) ?: throw EndpointStorageFailure() } }
    fun contact(id: String) = contacts().firstOrNull { it.remoteDeviceId == id } ?: throw AppFailure(AppError.CONTACT_UNAVAILABLE)
    fun save(contact: Contact) = records.transaction { put("app/contact/${contact.remoteDeviceId}", contact) }
    fun pending(id: String): String? = records.transaction { read<String>("app/pending-card/$id") }
    fun pending(id: String, card: String) = records.transaction { put("app/pending-card/$id", card) }
    fun clearPending(id: String) = records.transaction { records.remove("app/pending-card/$id") }
    fun card(id: String): String? = records.transaction { read<String>("app/card/$id") }
    fun card(id: String, text: String) = records.transaction { put("app/card/$id", text) }
    fun messages(id: String): List<Message> = records.transaction {
        expire(id)
        records.keys("app/message/$id/").map { read<Message>(it) ?: throw EndpointStorageFailure() }.sortedBy { it.timestamp }
    }
    fun policy(id: String): Int = records.transaction { read<Int>("app/disappearing/$id")?.also { DisappearingTimer.from(it) } ?: 0 }
    fun policy(id: String, seconds: Int) = records.transaction { DisappearingTimer.from(seconds); put("app/disappearing/$id", seconds) }
    fun expire(conversationId: String? = null): Int = records.transaction {
        val now = clock.now()
        val prefix = if (conversationId == null) "app/message/" else "app/message/$conversationId/"
        val expired = records.keys(prefix).map { read<Message>(it) ?: throw EndpointStorageFailure() }
            .map { message ->
                // Upgrade old acceptance-started deadlines before any expiry deletion.
                if (message.direction == Direction.OUTGOING && message.state != MessageState.DELIVERED && message.expiry != null)
                    message.copy(expiry = null).also(::save) else message
            }.filter { it.activeExpiry?.reached(now) == true }
        expired.forEach { delete(it.conversationId, it.localId) }
        expired.size
    }
    fun acceptedOutgoing(id: String, localId: String) = records.transaction {
        val message = read<Message>("app/message/$id/$localId") ?: return@transaction
        if (message.direction != Direction.OUTGOING || message.state == MessageState.DELIVERED) return@transaction
        save(message.copy(state = MessageState.SERVER_ACCEPTED, expiry = null))
    }
    fun deliveredOutgoing(id: String, localId: String) = records.transaction {
        val message = read<Message>("app/message/$id/$localId") ?: return@transaction
        if (message.direction != Direction.OUTGOING || message.state != MessageState.SERVER_ACCEPTED) return@transaction
        // A queued deadline from the previous implementation is not a delivery deadline.
        save(message.copy(state = MessageState.DELIVERED, expiry = if (!message.policyEvent && message.disappearingSeconds > 0)
            ExpiryDeadline.start(message.disappearingSeconds, clock.now()) else null))
    }
    fun unreadCount(id: String): Int = records.transaction {
        val seen = read<List<String>>("app/read/$id").orEmpty().toSet()
        messages(id).count { it.direction == Direction.INCOMING && !it.policyEvent && it.localId !in seen }
    }
    fun unreadMessageIds(id: String): Set<String> = records.transaction {
        val seen = read<List<String>>("app/read/$id").orEmpty().toSet()
        messages(id).filter { it.direction == Direction.INCOMING && !it.policyEvent && it.localId !in seen }.map { it.localId }.toSet()
    }
    fun markRead(id: String) = records.transaction {
        val seen = messages(id).filter { it.direction == Direction.INCOMING && !it.policyEvent }.map { it.localId }
        if (read<List<String>>("app/read/$id") != seen) put("app/read/$id", seen)
    }
    fun save(message: Message) = records.transaction {
        val key = "app/message/${message.conversationId}/${message.localId}"
        val existing = records.keys("app/message/")
        if (key !in existing && existing.size >= 5000) throw AppFailure(AppError.LOCAL_CAPACITY)
        put(key, message)
    }
    fun delete(id: String, localId: String) = records.transaction {
        records.remove("app/message/$id/$localId")
        val readKey = "app/read/$id"
        val remaining = read<List<String>>(readKey).orEmpty().filterNot { it == localId }
        if (remaining.isEmpty()) records.remove(readKey) else put(readKey, remaining)
        NotificationLedger.remove(records, id, localId)
        // Keep app/accepted and engine replay evidence. Deletion is not an unsend:
        // unfinished outbox delivery continues, without recreating visible history.
        // Receipt polling is derived from visible SERVER_ACCEPTED messages, not a separate ledger.
    }
    fun clear(id: String) = records.transaction {
        records.keys("app/message/$id/").forEach(records::remove)
        records.remove("app/read/$id")
        NotificationLedger.clear(records, id)
    }
    fun capacity() = records.transaction { if (records.keys("app/message/").size >= 5000) throw AppFailure(AppError.LOCAL_CAPACITY) }
    fun accepted(sender:String,id:String,hash:ByteArray):Boolean=records.transaction {
        val existing=records.read("app/accepted/$sender/$id") ?: return@transaction false
        require(java.security.MessageDigest.isEqual(existing,hash)) {"Envelope receipt conflict"}; true
    }
    fun saveAccepted(message:Message,hash:ByteArray)=records.transaction {
        require(records.keys("app/accepted/").size<10000)
        save(message); records.write("app/accepted/${message.conversationId}/${message.envelopeId}",hash)
        // Same transaction as authenticated content and deduplication; never enqueue on FETCH alone.
        NotificationLedger.accepted(records, message)
    }
}
