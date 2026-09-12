package org.ghostcloak.messaging

import org.ghostcloak.crypto.EndpointRecords
import org.ghostcloak.crypto.EndpointStorageFailure

/** References stay in encrypted records, never in Android notification or intent metadata. */
class NotificationLedger(private val records: EndpointRecords, private val clock: ExpiryClock = ExpiryClock()) {
    data class Entry internal constructor(internal val key: String, val state: Int) {
        override fun toString() = "NotificationEntry(redacted)"
    }
    companion object {
        private const val PREFIX = "app/notification/"
        internal fun remove(records: EndpointRecords, conversationId: String, localId: String) {
            records.remove("$PREFIX$conversationId/$localId")
        }
        internal fun clear(records: EndpointRecords, conversationId: String) {
            records.keys("$PREFIX$conversationId/").forEach(records::remove)
        }
        const val PENDING = 1
        const val POSTING = 2
        const val ANNOUNCED = 3
        internal fun accepted(records: EndpointRecords, message: Message) {
            if (message.direction == Direction.INCOMING && !message.policyEvent)
                records.write("$PREFIX${message.conversationId}/${message.localId}", byteArrayOf(PENDING.toByte()))
        }
    }
    private fun state(key: String): Int {
        val bytes = records.read(key) ?: throw EndpointStorageFailure()
        if (bytes.size != 1 || bytes[0].toInt() !in PENDING..ANNOUNCED) throw EndpointStorageFailure()
        return bytes[0].toInt()
    }
    /** Prune before publication so read, deleted and blocked messages cannot cause an alert. */
    fun eligible(): List<Entry> = records.transaction {
        val repository = LocalRepository(records, clock)
        val unread = repository.contacts().filter { !it.blocked }
            .associate { it.remoteDeviceId to repository.unreadMessageIds(it.remoteDeviceId) }
        records.keys(PREFIX).mapNotNull { key ->
            val parts = key.removePrefix(PREFIX).split('/')
            if (parts.size != 2) throw EndpointStorageFailure()
            if (parts[1] !in unread[parts[0]].orEmpty()) { records.remove(key); null }
            else Entry(key, state(key))
        }
    }
    fun posting(entries: List<Entry>) = records.transaction {
        entries.forEach { if (records.read(it.key) != null) records.write(it.key, byteArrayOf(POSTING.toByte())) }
    }
    fun announced(entries: List<Entry>) = records.transaction {
        entries.forEach { if (records.read(it.key) != null) records.write(it.key, byteArrayOf(ANNOUNCED.toByte())) }
    }
    fun suppressAll() = records.transaction { records.keys(PREFIX).forEach(records::remove) }
    /** Swipe/tap never changes read markers. New, not-yet-published arrivals remain eligible. */
    fun dismissPublished() = records.transaction {
        records.keys(PREFIX).filter { state(it) != PENDING }.forEach(records::remove)
    }
}
