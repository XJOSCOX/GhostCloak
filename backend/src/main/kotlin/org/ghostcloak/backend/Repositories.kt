package org.ghostcloak.backend

import kotlinx.serialization.Serializable
import org.ghostcloak.protocol.*

/** Implement with unique constraints and serializable transactions in a relational adapter. */
interface Rows<T> { fun get(id: String): T?; fun put(id: String, row: T); fun remove(id: String); fun all(): List<T>; fun size(): Int }
interface AccountRepository { val accounts: Rows<AccountRow> }
interface DeviceRepository { val devices: Rows<DeviceRow> }
interface PreKeyRepository { val prekeys: Rows<PrekeyRow> }
interface DedupeRepository { val submissions: Rows<SubmissionRow> }
interface MailboxRepository : DedupeRepository { val mailbox: Rows<MailboxRow> }
interface ChallengeRepository { val challenges: Rows<ChallengeRow> }
interface SessionRepository { val sessions: Rows<SessionRow> }
interface BackendDatabase : AccountRepository, DeviceRepository, PreKeyRepository, MailboxRepository, ChallengeRepository, SessionRepository {
    fun accountByGhostCloakId(id: String): AccountRow? = accounts.all().firstOrNull { it.ghostCloakId == id }
    fun deviceByRoutingId(id: String): DeviceRow? = devices.all().firstOrNull { it.routingId == id }
    fun mailboxForRecipient(id: String): List<MailboxRow> = mailbox.all().filter { it.recipientRoutingId == id }
    fun submissionByServerId(id: String): List<SubmissionRow> = submissions.all().filter { it.serverId == id }
    fun countSubmissionsForSender(id: String): Int = submissions.all().count { it.sender == id }
    fun countLiveSubmissions(now: Long, sender: String? = null, legacy: Boolean = false): Int =
        submissions.all().count { row ->
            (sender == null || row.sender == sender) &&
                ((row.id.substringAfter('/', "").startsWith("s3") && !legacy && row.expiresAt >= now) ||
                    (!row.id.substringAfter('/', "").startsWith("s3") && legacy))
        }
    /** Only intrinsically expired V3 receipts may be retired; legacy UUIDs remain durable. */
    fun retireSubmissions(now: Long, limit: Int = 128): Int {
        require(limit in 1..128)
        val ids = submissions.all().filter { it.id.substringAfter('/', "").startsWith("s3") && it.expiresAt < now }
            .sortedWith(compareBy<SubmissionRow> { it.expiresAt }.thenBy { it.id }).take(limit).map { it.id }
        ids.forEach(submissions::remove)
        return ids.size
    }
    fun mailboxUsageForRecipient(id: String): MailboxUsage {
        val rows = mailboxForRecipient(id)
        return MailboxUsage(rows.size, rows.sumOf { it.encryptedEnvelope.size.toLong() })
    }
    fun expiredChallengeIds(now: Long): List<String> = challenges.all().filter { it.challenge.expiresAt <= now }.map { it.challenge.id }
    fun expiredSessionHashes(now: Long): List<String> = sessions.all().filter { it.expiresAt <= now }.map { it.hash }
    fun expiredBlobs(now: Long, limit: Int = 128): List<BlobRow> = blobs.all().filter { it.expires <= now && !it.uploading }.take(limit)
    fun expireMailbox(now:Long, limit:Int=128) {
        mailbox.all().filter { it.expiresAt<=now }.sortedBy {it.expiresAt}.take(limit).forEach { mailbox.remove(it.id) }
    }
    val blobs: Rows<BlobRow>
    val blobBudgets: Rows<BlobBudget>
    val allocations: Rows<AllocationRow>
    fun countAllocations(requester:String, target:String?=null):Int =
        allocations.all().count { it.requester==requester && (target==null || it.target==target) }
    fun expireAllocations(now:Long, limit:Int=128) {
        allocations.all().filter { it.bundle!=null && it.responseExpiresAt<=now }.sortedBy {it.responseExpiresAt}.take(limit).forEach {
            allocations.put(it.id,AllocationRow(it.id,it.requester,it.target,null,it.responseExpiresAt,it.expiresAt))
        }
        allocations.all().filter { it.expiresAt<=now }.sortedBy {it.expiresAt}.take(limit).forEach { allocations.remove(it.id) }
    }
    fun <T> transaction(block: () -> T): T
}
data class MailboxUsage(val count: Int, val bytes: Long)
@Serializable class BlobRow(val id: String, val owner: String, val device: String, val length: Long,
    val digest: ByteArray, val capabilityHash: ByteArray, val created: Long, val expires: Long,
    val complete: Boolean = false, val uploading: Boolean = false) {
    override fun toString() = "BlobRow(redacted)"
}
@Serializable class BlobBudget(val id: String, val minute: Long, val requests: Int, val upload: Long,
    val download: Long, val day: Long) {
    override fun toString() = "BlobBudget(redacted)"
}
@Serializable class AccountRow(val id: String, val ghostCloakId: String, val deviceId: String)
@Serializable class DeviceRow(val id: String, val accountId: String, val routingId: String, val authPublicKey: ByteArray, val identity: ByteArray)
@Serializable class PrekeyRow(val deviceId: String, val pool: List<PublicBundle>, val usedEc: Set<Int>, val usedPq: Set<Int>, val signed: Map<Int, ByteArray>)
@Serializable class ChallengeRow(val challenge: Challenge)
@Serializable class SessionRow(val hash: String, val deviceId: String, val expiresAt: Long)
@Serializable class MailboxRow(val id: String, val recipientRoutingId: String, val encryptedEnvelope: ByteArray, val receivedAt: Long, val expiresAt: Long)
@Serializable class SubmissionRow(val id: String, val sender: String, val digest: ByteArray, val serverId: String, val expiresAt: Long, val acknowledged: Boolean = false,
    val mailboxExpiresAt:Long=0)
/** Short-lived public-bundle retry evidence; requester/target relationship is sensitive metadata. */
@Serializable class AllocationRow(val id:String, val requester:String, val target:String,
    val bundle:PublicBundle?, val responseExpiresAt:Long, val expiresAt:Long)
@Serializable private class DatabaseState(
    val accounts: MutableMap<String, AccountRow> = mutableMapOf(), val devices: MutableMap<String, DeviceRow> = mutableMapOf(),
    val prekeys: MutableMap<String, PrekeyRow> = mutableMapOf(), val challenges: MutableMap<String, ChallengeRow> = mutableMapOf(),
    val sessions: MutableMap<String, SessionRow> = mutableMapOf(), val mailbox: MutableMap<String, MailboxRow> = mutableMapOf(),
    val submissions: MutableMap<String, SubmissionRow> = mutableMapOf(), val allocations: MutableMap<String, AllocationRow> = mutableMapOf(),
    val blobs: MutableMap<String, BlobRow> = mutableMapOf(), val blobBudgets: MutableMap<String, BlobBudget> = mutableMapOf())

/** Bounded local development adapter only. All access must occur inside transaction. */
class MemoryBackendDatabase : BackendDatabase {
    private var state = DatabaseState()
    private fun <T> rows(map: () -> MutableMap<String, T>) = object : Rows<T> {
        private fun checked() = map().also { check(Thread.holdsLock(this@MemoryBackendDatabase)) }
        override fun get(id: String) = checked()[id]
        override fun put(id: String, row: T) { checked()[id] = row }
        override fun remove(id: String) { checked().remove(id) }
        override fun all() = checked().values.toList()
        override fun size() = checked().size
    }
    override val accounts = rows { state.accounts }; override val devices = rows { state.devices }
    override val prekeys = rows { state.prekeys }; override val challenges = rows { state.challenges }
    override val sessions = rows { state.sessions }; override val mailbox = rows { state.mailbox }
    override val submissions = rows { state.submissions }
    override val allocations = rows { state.allocations }
    override val blobs = rows { state.blobs }; override val blobBudgets = rows { state.blobBudgets }
    @Synchronized override fun <T> transaction(block: () -> T): T {
        val before = NetworkCodec.encode(state)
        return try { block() } catch (e: Throwable) {
            state = NetworkCodec.decode(before, Int.MAX_VALUE); throw e
        }
    }
    /** Test/inspection fixture: all database tables, including hashed sessions, no endpoint state. */
    @Synchronized fun dump(): ByteArray = NetworkCodec.encode(state)
}
