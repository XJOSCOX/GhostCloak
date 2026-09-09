package org.ghostcloak.messaging

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.ghostcloak.crypto.*
import org.ghostcloak.identity.RandomIdentifiers
import org.ghostcloak.protocol.*
import org.ghostcloak.transport.IdempotentMessageTransport

enum class OutboxState { LOCAL, ENCRYPTION_PENDING, CIPHERTEXT_READY, UPLOAD_PENDING, SERVER_ACCEPTED, FAILED }
enum class CrashPoint { AFTER_LOCAL, AFTER_ENCRYPTION, AFTER_CIPHERTEXT, AFTER_SERVER_ACCEPTANCE }
@Serializable
class OutboxEntry(val submissionId: String, val deviceId: String, val state: OutboxState,
    val plaintext: ByteArray = byteArrayOf(), val ciphertext: ByteArray = byteArrayOf(),
    val createdAt: Long, val serverId: String? = null) {
    override fun toString() = "OutboxEntry(state=$state)"
}
/** One process-owned worker per endpoint, just like the existing ConversationService.
 * A committed ENCRYPTION_PENDING marker is never retried after recovery: state may have advanced.
 * Ciphertext retries use the SAME bytes and submission ID, never a second encrypt call.
 */
class DurableOutbox(private val records: EndpointRecords, private val engine: SecureSessionEngine,
    private val transport: IdempotentMessageTransport, private val crash: (CrashPoint) -> Unit = {},
    private val time: () -> Long = System::currentTimeMillis) {
    private val mutex = Mutex()
    private fun key(id: String): String { require(RandomIdentifiers.valid(id)); return "outbox/$id" }
    private fun put(e: OutboxEntry) = records.write(key(e.submissionId), NetworkCodec.encode(e))
    fun get(id: String): OutboxEntry = records.transaction { NetworkCodec.decode(records.read(key(id)) ?: throw ApiFailure(404, "outbox_missing")) }
    fun pendingIds(): List<String> = records.transaction { records.keys("outbox/").map { it.removePrefix("outbox/") } }
    suspend fun enqueue(deviceId: String, plaintext: ByteArray): String = withContext(Dispatchers.IO) { mutex.withLock {
        requireApi(RandomIdentifiers.valid(deviceId) && plaintext.size in 1..EnvelopeCodec.MAX_BODY)
        val id = RandomIdentifiers.create()
        records.transaction { requireApi(records.keys("outbox/").size < 128, "outbox_full", 429); put(OutboxEntry(id, deviceId, OutboxState.LOCAL, plaintext.copyOf(), createdAt = time())) }
        crash(CrashPoint.AFTER_LOCAL)
        id
    } }
    suspend fun process(id: String): OutboxEntry = withContext(Dispatchers.IO) { mutex.withLock {
        var entry = get(id)
        if (entry.state == OutboxState.SERVER_ACCEPTED || entry.state == OutboxState.FAILED) return@withLock entry
        if (entry.state == OutboxState.ENCRYPTION_PENDING || time() - entry.createdAt >= 86400000) {
            // No crypto rollback or automatic resend of an ambiguous/too-old operation.
            entry = OutboxEntry(id, entry.deviceId, OutboxState.FAILED, createdAt = entry.createdAt)
            records.transaction { put(entry) }; return@withLock entry
        }
        if (entry.state == OutboxState.LOCAL) {
            records.transaction { put(OutboxEntry(id, entry.deviceId, OutboxState.ENCRYPTION_PENDING, createdAt = entry.createdAt)) }
            val envelope = try { engine.encrypt(entry.deviceId, entry.plaintext) } finally { entry.plaintext.fill(0) }
            crash(CrashPoint.AFTER_ENCRYPTION)
            entry = OutboxEntry(id, entry.deviceId, OutboxState.CIPHERTEXT_READY, ciphertext = EnvelopeCodec.encode(envelope), createdAt = entry.createdAt)
            records.transaction { put(entry) }
            crash(CrashPoint.AFTER_CIPHERTEXT)
        }
        entry = OutboxEntry(id, entry.deviceId, OutboxState.UPLOAD_PENDING, ciphertext = entry.ciphertext, createdAt = entry.createdAt)
        records.transaction { put(entry) }
        val serverId = transport.submit(id, entry.deviceId, EnvelopeCodec.decode(entry.ciphertext))
        crash(CrashPoint.AFTER_SERVER_ACCEPTANCE)
        entry = OutboxEntry(id, entry.deviceId, OutboxState.SERVER_ACCEPTED, createdAt = entry.createdAt, serverId = serverId)
        records.transaction { put(entry) }; entry
    } }
    fun removeFinished(id: String) = records.transaction { val entry = get(id); require(entry.state in setOf(OutboxState.SERVER_ACCEPTED, OutboxState.FAILED)); records.remove(key(id)) }
}
