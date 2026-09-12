package org.ghostcloak.app.access

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface LockPersistence {
    suspend fun read(): ByteArray?
    suspend fun write(bytes: ByteArray)
}
enum class UnlockPurpose { UNLOCK, MANAGE, ENROLL }
data class LockViewState(
    val ready: Boolean = false, val canShowContent: Boolean = false,
    val mode: LockMode = LockMode.OFF, val timing: LockTiming = LockTiming.IMMEDIATE,
    val busy: Boolean = false, val unavailable: Boolean = false, val message: String? = null,
    val manageGranted: Boolean = false, val biometricConfirmed: Boolean = false,
    val visible: Boolean = false, val presentation: Long = 0,
)

/** Process-owned; UI/lifecycle calls run on Main. Slow KDF/records operations run off Main. */
class AppLockController internal constructor(
    private val persistence: LockPersistence, private val scope: CoroutineScope,
    private val now: () -> Long, private val boot: () -> Int,
) {
    private val session = LockSession(now)
    private var config = LockConfiguration()
    private val operations = Mutex()
    private val mutable = MutableStateFlow(LockViewState())
    val state = mutable.asStateFlow()
    private var initialized = false
    private var management: Pair<Long, Long>? = null
    private var enrollment: Pair<Long, Long>? = null
    private var sequence = 0L
    private var prompt: Triple<Long, Long, UnlockPurpose>? = null
    private var timer: Job? = null
    private var automaticPromptConsumed = false
    private var presentation = 0L
    private fun valid(grant: Pair<Long, Long>?) = grant != null && grant.first == session.generation &&
        now() < grant.second && session.canShow
    private fun update(message: String? = mutable.value.message) {
        mutable.value = mutable.value.copy(ready = initialized, canShowContent = initialized && !mutable.value.unavailable && session.canShow,
            mode = config.mode, timing = config.timing, message = message, visible = session.started, presentation = presentation,
            manageGranted = config.mode == LockMode.OFF || valid(management), biometricConfirmed = valid(enrollment))
    }
    fun start() { timer?.cancel(); session.start(); update(null) }
    fun stop(changingConfiguration: Boolean = false) {
        if (session.started && !changingConfiguration) { automaticPromptConsumed = false; presentation++ }
        session.stop(changingConfiguration); prompt = null; management = null; enrollment = null
        update(null)
        timer?.cancel()
        if (!changingConfiguration) timer = scope.launch { delay(config.timing.millis); session.expire(); update(null) }
    }
    suspend fun initialize() = operations.withLock {
        if (initialized && !mutable.value.unavailable) return@withLock
        try {
            val bytes = persistence.read()
            config = if (bytes == null) LockConfiguration() else try { LockConfiguration.decode(bytes) } finally { bytes.fill(0) }
            if (config.boot != boot() && config.cooldownDuration > 0) {
                config = config.copy(boot = boot(), cooldownUntil = now() + config.cooldownDuration)
                persist(config)
            }
            session.configure(config.mode, config.timing)
            initialized = true; mutable.value = mutable.value.copy(unavailable = false); update(null)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { failClosed() }
    }
    private fun failClosed() {
        session.configure(config.mode, config.timing)
        initialized = true; mutable.value = mutable.value.copy(unavailable = true, busy = false)
        update("App lock is unavailable. Your local data has not been reset.")
    }
    private suspend fun persist(value: LockConfiguration) {
        val bytes = value.encode()
        try { persistence.write(bytes) } finally { bytes.fill(0) }
    }
    private fun grant(purpose: UnlockPurpose, ticket: Long): Boolean {
        if (!session.started || ticket != session.generation) return false
        when (purpose) {
            UnlockPurpose.UNLOCK -> return session.unlock(ticket)
            UnlockPurpose.MANAGE -> { if (!session.canShow) return false; management = ticket to now() + 60_000 }
            UnlockPurpose.ENROLL -> { if (!session.canShow) return false; enrollment = ticket to now() + 60_000 }
        }
        return true
    }
    suspend fun verifyPin(pin: CharArray, purpose: UnlockPurpose = UnlockPurpose.UNLOCK): Boolean {
        try {
            return operations.withLock {
                if (!initialized || mutable.value.unavailable || !config.mode.pin || !session.started || purpose == UnlockPurpose.ENROLL) return@withLock false
                if (config.cooldownUntil > now()) {
                    update("Try again in ${(config.cooldownUntil - now() + 999) / 1000} seconds."); return@withLock false
                }
                val ticket = session.generation
                mutable.value = mutable.value.copy(busy = true)
                try {
                    // Reserve the failed attempt before doing expensive verification. Killing the process
                    // cannot erase the attempt. Never sleep while holding the shared network runtime.
                    val failures = (config.failures + 1).coerceAtMost(20)
                    val wait = LockConfiguration.delayAfter(failures)
                    config = config.copy(failures = failures, cooldownUntil = now() + wait, cooldownDuration = wait, boot = boot())
                    persist(config)
                    val matches = withContext(Dispatchers.Default) { config.verifier!!.matches(pin) }
                    if (matches) {
                        config = config.copy(failures = 0, cooldownUntil = 0, cooldownDuration = 0); persist(config)
                        val accepted = grant(purpose, ticket); update(null); accepted
                    } else { update("Incorrect PIN. ${if (wait > 0) "Try again in ${wait / 1000} seconds." else "Try again."}"); false }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { failClosed(); false }
                finally { mutable.value = mutable.value.copy(busy = false) }
            }
        } finally { pin.fill('\u0000') }
    }
    fun beginBiometric(purpose: UnlockPurpose): Long? {
        if (!initialized || mutable.value.unavailable || !session.started || mutable.value.busy) return null
        if (purpose != UnlockPurpose.ENROLL && !config.mode.biometric) return null
        if (purpose == UnlockPurpose.ENROLL && !session.canShow) return null
        if (prompt != null) return null
        if (purpose == UnlockPurpose.UNLOCK) automaticPromptConsumed = true
        val id = ++sequence; prompt = Triple(id, session.generation, purpose); update(null); return id
    }
    /** Called only by a ready, resumed UI host. Consumption survives rotation and recomposition. */
    internal fun beginAutomaticBiometric(available: Boolean): Long? {
        if (!initialized || mutable.value.unavailable || !session.started || !session.locked ||
            !config.mode.biometric || mutable.value.busy || automaticPromptConsumed) return null
        automaticPromptConsumed = true
        return if (available) beginBiometric(UnlockPurpose.UNLOCK) else null
    }
    fun cancelBiometric(id: Long, error: Boolean = false) {
        if (prompt?.first == id) { prompt = null; update(if (error) "Biometric unavailable. Use your PIN if configured, or check Android security settings." else null) }
    }
    suspend fun completeBiometric(id: Long): Boolean = operations.withLock {
        val attempt = prompt ?: return@withLock false
        if (attempt.first != id) return@withLock false
        prompt = null
        if (attempt.second != session.generation || !session.started) return@withLock false
        try {
            if (config.failures > 0) { config = config.copy(failures = 0, cooldownUntil = 0, cooldownDuration = 0); persist(config) }
            val accepted = grant(attempt.third, attempt.second)
            if (accepted && attempt.third == UnlockPurpose.MANAGE) enrollment = session.generation to now() + 60_000
            update(null); accepted
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { failClosed(); false }
    }
    fun endManagement() { management = null; enrollment = null; prompt = null; update(null) }
    suspend fun configure(mode: LockMode, timing: LockTiming, pin: CharArray, confirmation: CharArray): Boolean {
        try {
            return operations.withLock {
                if (!initialized || mutable.value.unavailable || !session.canShow || (config.mode != LockMode.OFF && !valid(management))) {
                    update("Authenticate again to change app lock."); return@withLock false
                }
                if (mode.biometric && !valid(enrollment)) { update("Confirm your strong biometric first."); return@withLock false }
                if (mode.pin && (!PinVerifier.valid(pin) || !pin.contentEquals(confirmation))) {
                    update("Use matching PINs with 6–64 digits."); return@withLock false
                }
                val ticket = session.generation
                mutable.value = mutable.value.copy(busy = true)
                try {
                    val verifier = if (mode.pin) withContext(Dispatchers.Default) { PinVerifier.create(pin) } else null
                    if (!session.canShow || session.generation != ticket || (config.mode != LockMode.OFF && !valid(management)) || (mode.biometric && !valid(enrollment))) return@withLock false
                    val next = LockConfiguration(mode, timing, verifier)
                    persist(next); config = next
                    session.configure(mode, timing, authenticated = session.started && session.generation == ticket)
                    management = null; enrollment = null; update(null); true
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { failClosed(); false }
                finally { mutable.value = mutable.value.copy(busy = false) }
            }
        } finally { pin.fill('\u0000'); confirmation.fill('\u0000') }
    }
}
