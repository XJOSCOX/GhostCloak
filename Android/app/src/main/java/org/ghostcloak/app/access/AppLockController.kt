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
enum class UnlockPurpose { UNLOCK, MANAGE, ENROLL, SETTINGS }
data class LockViewState(
    val ready: Boolean = false, val canShowContent: Boolean = false,
    val mode: LockMode = LockMode.OFF, val timing: LockTiming = LockTiming.IMMEDIATE,
    val busy: Boolean = false, val unavailable: Boolean = false, val message: String? = null,
    val manageGranted: Boolean = false, val biometricConfirmed: Boolean = false,
    val visible: Boolean = false, val presentation: Long = 0,
    val emergencyEnabled: Boolean = false,
    val settingsGranted: Boolean = false, val emergencyAdministrationGranted: Boolean = false,
    val inactivityPeriod: InactivityPeriod = InactivityPeriod.OFF,
)

/** Process-owned; UI/lifecycle calls run on Main. Slow KDF/records operations run off Main. */
class AppLockController internal constructor(
    private val persistence: LockPersistence, private val scope: CoroutineScope,
    private val now: () -> Long, private val boot: () -> Int,
    internal val emergencyArmingEnabled: Boolean = org.ghostcloak.app.BuildConfig.EMERGENCY_PIN_ARMING_ENABLED,
    private val armEmergency: (() -> Unit)? = null,
    // Test seam counts equivalent primitive invocations, not wall-clock timing.
    private val checkPin: (PinVerifier, CharArray) -> Boolean = { verifier, pin -> verifier.matches(pin) },
    private val inactivityAuthenticated: suspend () -> Boolean = { true },
    private val inactivityEnabled: () -> Boolean = { false },
    private val inactivityPeriod: () -> InactivityPeriod = { InactivityPeriod.OFF },
    private val configureInactivityStore: (InactivityPeriod) -> Boolean = { false },
) {
    private val session = LockSession(now)
    private var config = LockConfiguration()
    private val operations = Mutex()
    private val mutable = MutableStateFlow(LockViewState())
    val state = mutable.asStateFlow()
    private var initialized = false
    private var management: Pair<Long, Long>? = null
    private var managementTimer: Job? = null
    private var settingsEpoch=0L
    private var managementEpoch=0L
    private fun authorizationEpoch(purpose: UnlockPurpose) = when(purpose) {
        UnlockPurpose.SETTINGS -> settingsEpoch
        UnlockPurpose.MANAGE, UnlockPurpose.ENROLL -> managementEpoch
        UnlockPurpose.UNLOCK -> 0L
    }
    private var promptEpoch=0L
    private var settingsAuthorization: Pair<Long, Long>? = null
    private var settingsTimer: Job? = null
    private var emergencyAdministration: Triple<Long, Long, PinVerifier>? = null
    companion object { internal const val SETTINGS_AUTH_MILLIS = 180_000L }
    private fun validEmergencyAdministration(): Boolean = valid(management) && emergencyAdministration?.let {
        it.first == session.generation && now() < it.second && it.third === config.emergency
    } == true
    fun leaveSettings() {
        settingsEpoch++; settingsAuthorization=null; settingsTimer?.cancel(); settingsTimer=null
        endManagement()
    }
    internal fun refreshAuthorization() = update()
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
            mode = config.mode, timing = config.timing, emergencyEnabled = config.emergency != null, message = message, visible = session.started, presentation = presentation,
            manageGranted = config.mode == LockMode.OFF || valid(management), biometricConfirmed = valid(enrollment),
            settingsGranted = initialized && !mutable.value.unavailable && session.canShow &&
                (config.mode == LockMode.OFF || valid(settingsAuthorization)),
            emergencyAdministrationGranted = validEmergencyAdministration())
        mutable.value = mutable.value.copy(inactivityPeriod = inactivityPeriod())
    }
    internal suspend fun quiesce() {
        scope.coroutineContext[Job]!!.cancelAndJoin()
        operations.withLock {
            timer=null; settingsTimer=null; settingsAuthorization=null; emergencyAdministration=null; prompt=null; management=null; enrollment=null; config=LockConfiguration()
            mutable.value=LockViewState(unavailable=true)
        }
    }
    fun start() { timer?.cancel(); session.start(); update(null) }
    /** Revoke a prior in-memory grant before showing a protected time-uncertain screen. */
    fun requireFreshUnlock() {
        if (config.mode == LockMode.OFF) return
        session.configure(config.mode, config.timing)
        management=null; settingsAuthorization=null; enrollment=null; emergencyAdministration=null; prompt=null
        automaticPromptConsumed=false; presentation++
        update(null)
    }
    fun stop(changingConfiguration: Boolean = false) {
        if (session.started && !changingConfiguration) { automaticPromptConsumed = false; presentation++ }
        session.stop(changingConfiguration); managementTimer?.cancel(); settingsAuthorization=null; settingsTimer?.cancel(); emergencyAdministration=null; prompt = null; management = null; enrollment = null
        update(null)
        timer?.cancel()
        if (!changingConfiguration) timer = scope.launch { delay(config.timing.millis); session.expire(); update(null) }
    }
    suspend fun initialize() = operations.withLock {
        if (initialized && !mutable.value.unavailable) return@withLock
        try {
            val bytes = persistence.read()
            config = if (bytes == null) LockConfiguration() else try { LockConfiguration.decode(bytes) } finally { bytes.fill(0) }
            check(!inactivityEnabled() || config.mode != LockMode.OFF)
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
        settingsAuthorization=null; settingsTimer?.cancel(); emergencyAdministration=null; management=null; enrollment=null; prompt=null
        session.configure(config.mode, config.timing)
        initialized = true; mutable.value = mutable.value.copy(unavailable = true, busy = false)
        update("App lock is unavailable. Your local data has not been reset.")
    }
    private suspend fun persist(value: LockConfiguration) {
        val bytes = value.encode()
        try { persistence.write(bytes) } finally { bytes.fill(0) }
    }
    private fun grant(purpose: UnlockPurpose, ticket: Long, epoch: Long): Boolean {
        if (!session.started || ticket != session.generation || epoch != authorizationEpoch(purpose)) return false
        when (purpose) {
            UnlockPurpose.UNLOCK -> return session.unlock(ticket)
            UnlockPurpose.MANAGE -> {
                if (!session.canShow) return false
                emergencyAdministration=null; management = ticket to now() + 60_000
                managementTimer?.cancel(); managementTimer=scope.launch {delay(60_000); update(null)}
            }
            UnlockPurpose.SETTINGS -> {
                if (!session.canShow) return false
                settingsAuthorization=ticket to now()+SETTINGS_AUTH_MILLIS
                settingsTimer?.cancel()
                settingsTimer=scope.launch { delay(SETTINGS_AUTH_MILLIS); update(null) }
            }
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
                val epoch=authorizationEpoch(purpose)
                mutable.value = mutable.value.copy(busy = true)
                try {
                    // Reserve the failed attempt before doing expensive verification. Killing the process
                    // cannot erase the attempt. Never sleep while holding the shared network runtime.
                    val failures = (config.failures + 1).coerceAtMost(20)
                    val wait = LockConfiguration.delayAfter(failures)
                    config = config.copy(failures = failures, cooldownUntil = now() + wait, cooldownDuration = wait, boot = boot())
                    persist(config)
                    val (matches, emergencyMatches) = withContext(Dispatchers.Default) {
                        // No normal-success early return: enabled dual credentials always cost two KDFs.
                        val normal = checkPin(config.verifier!!, pin)
                        val emergency = config.emergency?.let { checkPin(it, pin) } ?: false
                        normal to emergency
                    }
                    if (session.generation != ticket || !session.started) return@withLock false
                    if (emergencyMatches && purpose == UnlockPurpose.UNLOCK && session.locked && emergencyArmingEnabled) {
                        // Never grant normal access, even if storage/arming fails. No further records
                        // operation after arming closes the runtime fence. Handoff is a durable-state
                        // observer on the independent recovery scope, not this lock mutex/scope.
                        failClosed()
                        withContext(Dispatchers.IO) { checkNotNull(armEmergency).invoke() }
                        false
                    } else if (matches) {
                        config = config.copy(failures = 0, cooldownUntil = 0, cooldownDuration = 0); persist(config)
                        if (purpose == UnlockPurpose.UNLOCK && !inactivityAuthenticated()) {
                            failClosed(); return@withLock false
                        }
                        val accepted = grant(purpose, ticket, epoch); update(null); accepted
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
        val id = ++sequence; promptEpoch=authorizationEpoch(purpose); prompt = Triple(id, session.generation, purpose); update(null); return id
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
        val epoch=promptEpoch
        prompt = null
        if (attempt.second != session.generation || !session.started) return@withLock false
        try {
            if (config.failures > 0) { config = config.copy(failures = 0, cooldownUntil = 0, cooldownDuration = 0); persist(config) }
            if (attempt.third == UnlockPurpose.UNLOCK && !inactivityAuthenticated()) {
                failClosed(); return@withLock false
            }
            val accepted = grant(attempt.third, attempt.second, epoch)
            if (accepted && attempt.third == UnlockPurpose.MANAGE) enrollment = session.generation to now() + 60_000
            update(null); accepted
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { failClosed(); false }
    }
    fun endManagement() { managementEpoch++; managementTimer?.cancel(); managementTimer=null; emergencyAdministration=null; management = null; enrollment = null; prompt = null; update(null) }
    /** Recent normal MANAGE authentication is mandatory; an emergency match here never arms. */
    suspend fun configureEmergency(pin: CharArray, confirmation: CharArray, current: CharArray, acknowledged: Boolean): Boolean {
        try {
            return operations.withLock {
                if (!emergencyArmingEnabled || !initialized || mutable.value.unavailable || !config.mode.pin || !valid(management)) {
                    update("Confirm your current unlock method first. PIN app lock is required."); return@withLock false
                }
                if (!acknowledged) { update("Acknowledge that this cannot be undone."); return@withLock false }
                if (!PinVerifier.valid(pin) || !pin.contentEquals(confirmation)) {
                    update("Use matching PINs with 6–64 digits."); return@withLock false
                }
                val ticket = session.generation
                mutable.value = mutable.value.copy(busy = true)
                try {
                    // Change requires the current emergency credential too. Persist the shared
                    // throttle reservation first; settings cannot become an unthrottled oracle.
                    val failures = (config.failures + 1).coerceAtMost(20)
                    if (config.cooldownUntil > now()) { update("Try again later."); return@withLock false }
                    val wait = LockConfiguration.delayAfter(failures)
                    config = config.copy(failures=failures, cooldownUntil=now()+wait, cooldownDuration=wait, boot=boot())
                    persist(config)
                    val next = withContext(Dispatchers.Default) {
                        val equal = checkPin(config.verifier!!, pin)
                        val currentOk = config.emergency?.let { validEmergencyAdministration() || checkPin(it, current) } ?: true
                        if (equal || !currentOk) null else PinVerifier.create(pin)
                    }
                    if (next == null) { update("Choose a different PIN or confirm the current PIN."); return@withLock false }
                    if (session.generation != ticket || !valid(management)) return@withLock false
                    val value = config.copy(emergency=next, failures=0, cooldownUntil=0, cooldownDuration=0)
                    persist(value)
                    val previous=config.emergency; config=value
                    previous?.salt?.fill(0); previous?.value?.fill(0)
                    management=null; enrollment=null; emergencyAdministration=null; update(null); true
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { failClosed(); false }
                finally { mutable.value=mutable.value.copy(busy=false) }
            }
        } finally { pin.fill('\u0000'); confirmation.fill('\u0000'); current.fill('\u0000') }
    }
    /** Explicit administration challenge: never a normal unlock or wipe trigger. */
    suspend fun verifyEmergencyAdministration(current: CharArray): Boolean {
        try {
            return operations.withLock {
                emergencyAdministration=null
                if (!emergencyArmingEnabled || !initialized || mutable.value.unavailable || config.emergency == null || !valid(management)) return@withLock false
                mutable.value=mutable.value.copy(busy=true)
                try {
                    if (!reserveAdministrationAttempt()) return@withLock false
                    val ticket=session.generation
                    val verifier=config.emergency!!
                    val matches=withContext(Dispatchers.Default) {checkPin(verifier,current)}
                    if (!matches) {update("Incorrect PIN. Try again."); return@withLock false}
                    if (ticket != session.generation || !valid(management)) return@withLock false
                    val next=config.copy(failures=0,cooldownUntil=0,cooldownDuration=0)
                    persist(next); config=next
                    if (ticket != session.generation || !valid(management)) return@withLock false
                    emergencyAdministration=Triple(ticket,management!!.second,verifier); update(null); true
                } catch(e: CancellationException) {throw e}
                catch(_: Exception) {failClosed(); false}
                finally {mutable.value=mutable.value.copy(busy=false)}
            }
        } finally {current.fill('\u0000')}
    }
    private suspend fun reserveAdministrationAttempt(): Boolean {
        if(config.cooldownUntil>now()) {update("Try again later."); return false}
        val failures=(config.failures+1).coerceAtMost(20)
        val wait=LockConfiguration.delayAfter(failures)
        config=config.copy(failures=failures,cooldownUntil=now()+wait,cooldownDuration=wait,boot=boot())
        persist(config); return true
    }
    /** Both credentials plus confirmation; normal/biometric authentication alone is insufficient. */
    suspend fun disableEmergency(current: CharArray, confirmed: Boolean): Boolean {
        try {
            return operations.withLock {
                if (!emergencyArmingEnabled || !initialized || mutable.value.unavailable || config.emergency == null || !valid(management) || !confirmed) return@withLock false
                mutable.value=mutable.value.copy(busy=true)
                try {
                    val ticket=session.generation
                    if(!reserveAdministrationAttempt()) return@withLock false
                    val matches=validEmergencyAdministration() || withContext(Dispatchers.Default) {checkPin(config.emergency!!,current)}
                    if(!matches) {update("Incorrect PIN. Try again."); return@withLock false}
                    if(ticket != session.generation || !valid(management)) return@withLock false
                    val next=config.copy(emergency=null,failures=0,cooldownUntil=0,cooldownDuration=0)
                    persist(next)
                    val previous=config.emergency; config=next
                    previous?.salt?.fill(0); previous?.value?.fill(0)
                    management=null; enrollment=null; emergencyAdministration=null; update(null); true
                } catch(e: CancellationException) {throw e}
                catch(_: Exception) {failClosed(); false}
                finally {mutable.value=mutable.value.copy(busy=false)}
            }
        } finally {current.fill('\u0000')}
    }
    suspend fun configure(mode: LockMode, timing: LockTiming, pin: CharArray, confirmation: CharArray): Boolean {
        try {
            return operations.withLock {
                if (!initialized || mutable.value.unavailable || !session.canShow || (config.mode != LockMode.OFF && !valid(management))) {
                    update("Authenticate again to change app lock."); return@withLock false
                }
                if (mode == LockMode.OFF && inactivityEnabled()) {
                    update("Disable Inactive Device Protection before turning off App Lock."); return@withLock false
                }
                if (mode.biometric && !valid(enrollment)) { update("Confirm your strong biometric first."); return@withLock false }
                if (mode.pin && (!PinVerifier.valid(pin) || !pin.contentEquals(confirmation))) {
                    update("Use matching PINs with 6–64 digits."); return@withLock false
                }
                val ticket = session.generation
                mutable.value = mutable.value.copy(busy = true)
                try {
                    if (config.emergency != null && (!mode.pin || withContext(Dispatchers.Default) { checkPin(config.emergency!!, pin) })) {
                        update(if (!mode.pin) "Disable Safe Exit before turning off App Lock." else "Choose a different PIN."); return@withLock false
                    }
                    val verifier = if (mode.pin) withContext(Dispatchers.Default) { PinVerifier.create(pin) } else null
                    if (!session.canShow || session.generation != ticket || (config.mode != LockMode.OFF && !valid(management)) || (mode.biometric && !valid(enrollment))) return@withLock false
                    val next = LockConfiguration(mode, timing, verifier, emergency = config.emergency)
                    persist(next); config = next
                    session.configure(mode, timing, authenticated = session.started && session.generation == ticket)
                    management = null; enrollment = null; update(null); true
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { failClosed(); false }
                finally { mutable.value = mutable.value.copy(busy = false) }
            }
        } finally { pin.fill('\u0000'); confirmation.fill('\u0000') }
    }

    /** A fresh MANAGE proof is separate from Settings access and shared with Safe Exit administration. */
    suspend fun configureInactivity(next: InactivityPeriod, acknowledged: Boolean, confirmedDisable: Boolean): Boolean = operations.withLock {
        val current = inactivityPeriod()
        if (!initialized || mutable.value.unavailable || config.mode == LockMode.OFF || !session.canShow || !valid(management)) {
            update("Authenticate again to manage Inactive Device Protection."); return@withLock false
        }
        if (current == InactivityPeriod.OFF && next != InactivityPeriod.OFF && !acknowledged) {
            update("Acknowledge possible permanent local data loss."); return@withLock false
        }
        if (next == InactivityPeriod.OFF && !confirmedDisable) {
            update("Confirm disabling Inactive Device Protection."); return@withLock false
        }
        if (current != InactivityPeriod.OFF && (next == InactivityPeriod.OFF || next.days > current.days) &&
            config.emergency != null && !validEmergencyAdministration()) {
            update("Confirm your current Safe Exit PIN first."); return@withLock false
        }
        mutable.value = mutable.value.copy(busy = true)
        try {
            val saved = withContext(Dispatchers.IO) { configureInactivityStore(next) }
            if (!saved) { failClosed(); return@withLock false }
            management=null; emergencyAdministration=null; enrollment=null
            update(null); true
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { failClosed(); false }
        finally { mutable.value = mutable.value.copy(busy = false) }
    }
}
