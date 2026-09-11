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
    fun <T> transaction(block: () -> T): T
}
@Serializable class AccountRow(val id: String, val username: String, val deviceId: String)
@Serializable class DeviceRow(val id: String, val accountId: String, val routingId: String, val authPublicKey: ByteArray, val identity: ByteArray)
@Serializable class PrekeyRow(val deviceId: String, val pool: List<PublicBundle>, val usedEc: Set<Int>, val usedPq: Set<Int>, val signed: Map<Int, ByteArray>)
@Serializable class ChallengeRow(val challenge: Challenge)
@Serializable class SessionRow(val hash: String, val deviceId: String, val expiresAt: Long)
@Serializable class MailboxRow(val id: String, val recipientRoutingId: String, val encryptedEnvelope: ByteArray, val receivedAt: Long, val expiresAt: Long)
@Serializable class SubmissionRow(val id: String, val sender: String, val digest: ByteArray, val serverId: String, val expiresAt: Long, val acknowledged: Boolean = false)
@Serializable private class DatabaseState(
    val accounts: MutableMap<String, AccountRow> = mutableMapOf(), val devices: MutableMap<String, DeviceRow> = mutableMapOf(),
    val prekeys: MutableMap<String, PrekeyRow> = mutableMapOf(), val challenges: MutableMap<String, ChallengeRow> = mutableMapOf(),
    val sessions: MutableMap<String, SessionRow> = mutableMapOf(), val mailbox: MutableMap<String, MailboxRow> = mutableMapOf(),
    val submissions: MutableMap<String, SubmissionRow> = mutableMapOf())

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
    @Synchronized override fun <T> transaction(block: () -> T): T {
        val before = NetworkCodec.encode(state)
        return try { block() } catch (e: Throwable) {
            state = NetworkCodec.decode(before, Int.MAX_VALUE); throw e
        }
    }
    /** Test/inspection fixture: all database tables, including hashed sessions, no endpoint state. */
    @Synchronized fun dump(): ByteArray = NetworkCodec.encode(state)
}
