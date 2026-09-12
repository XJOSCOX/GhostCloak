package org.ghostcloak.app.access

import java.io.*
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

enum class LockMode(val label: String, val pin: Boolean, val biometric: Boolean) {
    OFF("Off", false, false), BIOMETRIC("Biometric", false, true), PIN("PIN", true, false),
    COMBINED("Biometric + PIN fallback", true, true)
}
enum class LockTiming(val label: String, val millis: Long) {
    IMMEDIATE("Immediately", 0), SECONDS_30("After 30 seconds", 30_000), MINUTE("After 1 minute", 60_000), MINUTES_5("After 5 minutes", 300_000)
}

internal class PinVerifier(val salt: ByteArray, val value: ByteArray, val iterations: Int = ITERATIONS) {
    companion object {
        const val ITERATIONS = 600_000
        fun valid(pin: CharArray) = pin.size in 6..64 && pin.all { it in '0'..'9' }
        fun create(pin: CharArray): PinVerifier {
            require(valid(pin))
            val salt = ByteArray(16).also(SecureRandom()::nextBytes)
            return PinVerifier(salt, derive(pin, salt, ITERATIONS))
        }
        private fun derive(pin: CharArray, salt: ByteArray, iterations: Int): ByteArray {
            val spec = PBEKeySpec(pin, salt, iterations, 256)
            return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
            finally { spec.clearPassword() }
        }
    }
    fun matches(pin: CharArray): Boolean {
        if (!valid(pin)) return false
        val candidate = derive(pin, salt, iterations)
        return try { MessageDigest.isEqual(value, candidate) } finally { candidate.fill(0) }
    }
    override fun toString() = "PinVerifier(redacted)"
}

internal data class LockConfiguration(
    val mode: LockMode = LockMode.OFF, val timing: LockTiming = LockTiming.IMMEDIATE,
    val verifier: PinVerifier? = null, val failures: Int = 0,
    val cooldownUntil: Long = 0, val cooldownDuration: Long = 0, val boot: Int = 0,
) {
    override fun toString() = "LockConfiguration(redacted)"
    fun encode(): ByteArray = ByteArrayOutputStream().also { buffer ->
        DataOutputStream(buffer).use { out ->
            out.writeInt(1); out.writeInt(mode.ordinal); out.writeInt(timing.ordinal)
            out.writeBoolean(verifier != null)
            verifier?.let { out.writeInt(it.iterations); out.write(it.salt); out.write(it.value) }
            out.writeInt(failures); out.writeLong(cooldownUntil); out.writeLong(cooldownDuration); out.writeInt(boot)
        }
    }.toByteArray()
    companion object {
        fun decode(bytes: ByteArray): LockConfiguration = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(bytes.size <= 128 && input.readInt() == 1)
            val mode = LockMode.entries[input.readInt()]; val timing = LockTiming.entries[input.readInt()]
            val pin = if (input.readBoolean()) {
                val iterations = input.readInt(); require(iterations == PinVerifier.ITERATIONS)
                PinVerifier(ByteArray(16).also(input::readFully), ByteArray(32).also(input::readFully), iterations)
            } else null
            val config = LockConfiguration(mode, timing, pin, input.readInt(), input.readLong(), input.readLong(), input.readInt())
            require(mode.pin == (pin != null) && config.failures in 0..20 && config.cooldownUntil >= 0 &&
                config.cooldownDuration in 0..300_000 && input.available() == 0)
            config
        }
        fun delayAfter(failures: Int): Long = if (failures < 3) 0 else (5_000L shl (failures - 3).coerceAtMost(6)).coerceAtMost(300_000)
    }
}

/** No grant is persisted. Time is monotonic and rechecked on resume even if timers never ran. */
internal class LockSession(private val now: () -> Long) {
    var mode = LockMode.OFF; private set
    var timing = LockTiming.IMMEDIATE; private set
    var locked = true; private set
    var started = false; private set
    var generation = 0L; private set
    private var backgroundAt: Long? = null
    val canShow get() = started && (mode == LockMode.OFF || !locked)
    fun configure(mode: LockMode, timing: LockTiming, authenticated: Boolean = false) {
        this.mode = mode; this.timing = timing; locked = mode != LockMode.OFF && !authenticated
        generation++; backgroundAt = null
    }
    fun start() {
        backgroundAt?.let { if (now() - it >= timing.millis) locked = mode != LockMode.OFF }
        started = true; backgroundAt = null
    }
    fun stop(changingConfiguration: Boolean = false) {
        started = false; generation++
        if (!changingConfiguration) { backgroundAt = now(); if (timing == LockTiming.IMMEDIATE) locked = mode != LockMode.OFF }
    }
    fun expire() { backgroundAt?.let { if (!started && now() - it >= timing.millis) { locked = mode != LockMode.OFF; generation++ } } }
    fun unlock(ticket: Long): Boolean {
        if (!started || generation != ticket) return false
        locked = false; return true
    }
    fun invalidate() { generation++ }
}
