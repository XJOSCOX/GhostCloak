package org.ghostcloak.app.access

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class InactivityPeriod(val days: Int, val label: String) {
    OFF(0, "Off"), DAYS_7(7, "7 days"), DAYS_14(14, "14 days"),
    DAYS_30(30, "30 days"), DAYS_60(60, "60 days"), DAYS_90(90, "90 days");

    val millis: Long get() = days * 86_400_000L
}

enum class InactivityAccess { DISABLED, VALID, TIME_UNCERTAIN, UNAVAILABLE, EXPIRED }

internal data class InactivityObservation(val boot: Int, val elapsed: Long, val wall: Long)
internal data class InactivityRecord(val period: InactivityPeriod, val boot: Int,
    val authenticatedElapsed: Long, val authenticatedWall: Long, val highestWall: Long)

internal interface InactivityStore {
    fun read(): InactivityRecord?
    fun write(record: InactivityRecord)
    fun clear()
}

/** Only a matching, known boot count plus elapsedRealtime can authorize destruction.
 * Wall time is retained as bounded evidence, never as independent wipe authority. */
internal class InactivityProtection(
    private val store: InactivityStore,
    private val clock: () -> InactivityObservation,
    private val armSafeExit: () -> Unit,
) {
    private val monitor = Any()
    private var loaded = false
    private var faulted = false
    private var record: InactivityRecord? = null
    private val mutable = MutableStateFlow(InactivityAccess.UNAVAILABLE)
    val state = mutable.asStateFlow()
    val period get() = synchronized(monitor) { record?.period ?: InactivityPeriod.OFF }
    val enabled get() = synchronized(monitor) { if (!loaded) check(); record != null || faulted }
    val normalAccessAllowed get() = mutable.value in setOf(InactivityAccess.DISABLED, InactivityAccess.VALID)
    val needsIsolatedAuthentication get() = !normalAccessAllowed

    fun check(): InactivityAccess = synchronized(monitor) {
        if (faulted) return@synchronized InactivityAccess.UNAVAILABLE
        if (mutable.value == InactivityAccess.EXPIRED) return@synchronized mutable.value
        if (!loaded) {
            try { record = store.read(); loaded = true }
            catch (_: Exception) { faulted = true; mutable.value = InactivityAccess.UNAVAILABLE; return@synchronized mutable.value }
        }
        val active = record ?: run { mutable.value = InactivityAccess.DISABLED; return@synchronized mutable.value }
        val now = try { clock() }
        catch (_: Exception) { faulted = true; mutable.value = InactivityAccess.UNAVAILABLE; return@synchronized mutable.value }
        if (now.boot < 0 || active.boot < 0 || now.boot != active.boot ||
            now.elapsed < active.authenticatedElapsed) {
            mutable.value = InactivityAccess.TIME_UNCERTAIN
            return@synchronized mutable.value
        }
        if (now.elapsed - active.authenticatedElapsed >= active.period.millis) {
            // The existing journal fences all normal state before recovery begins.
            mutable.value = InactivityAccess.UNAVAILABLE
            try { armSafeExit(); mutable.value = InactivityAccess.EXPIRED }
            catch (_: Exception) { faulted = true; mutable.value = InactivityAccess.UNAVAILABLE }
        } else mutable.value = InactivityAccess.VALID
        mutable.value
    }

    /** Called only after an actual normal PIN/biometric match, before granting access. */
    fun authenticated(): Boolean = synchronized(monitor) {
        if (check() !in setOf(InactivityAccess.DISABLED, InactivityAccess.VALID, InactivityAccess.TIME_UNCERTAIN))
            return@synchronized false
        val old = record ?: return@synchronized true
        try {
            val now = clock()
            require(now.boot >= 0 && now.elapsed >= 0 && now.wall > 0)
            val next = InactivityRecord(old.period, now.boot, now.elapsed, now.wall,
                maxOf(old.highestWall, now.wall))
            store.write(next)
            record = next
            mutable.value = InactivityAccess.VALID
            true
        } catch (_: Exception) { faulted = true; mutable.value = InactivityAccess.UNAVAILABLE; false }
    }

    /** Caller must have performed the separate critical-action authentication. */
    fun configure(next: InactivityPeriod): Boolean = synchronized(monitor) {
        if (check() in setOf(InactivityAccess.UNAVAILABLE, InactivityAccess.EXPIRED)) return@synchronized false
        try {
            if (next == InactivityPeriod.OFF) {
                store.clear(); record = null; mutable.value = InactivityAccess.DISABLED
            } else {
                val now = clock()
                require(now.boot >= 0 && now.elapsed >= 0 && now.wall > 0)
                val previous = record
                val value = if (previous == null) InactivityRecord(next, now.boot, now.elapsed, now.wall, now.wall)
                    else previous.copy(period = next, highestWall = maxOf(previous.highestWall, now.wall))
                store.write(value); record = value; mutable.value = InactivityAccess.VALID
                check()
            }
            true
        } catch (_: Exception) { faulted = true; mutable.value = InactivityAccess.UNAVAILABLE; false }
    }
}
