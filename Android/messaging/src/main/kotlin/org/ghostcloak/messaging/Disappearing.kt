package org.ghostcloak.messaging

import kotlinx.serialization.Serializable
import java.nio.ByteBuffer
import java.security.SecureRandom

enum class DisappearingTimer(val seconds: Int, val label: String) {
    OFF(0, "Off"), SECONDS_30(30, "30 seconds"), MINUTES_5(300, "5 minutes"),
    HOUR(3600, "1 hour"), DAY(86400, "1 day"), WEEK(604800, "1 week");
    companion object { fun from(seconds: Int) = entries.singleOrNull { it.seconds == seconds }
        ?: throw AppFailure(AppError.INVALID_TEXT) }
}

/** Application framing only: these bytes go inside the existing authenticated Signal envelope. */
object ConversationPayload {
    const val MAX_TEXT = 16_368 // 16-byte header within the existing 16KiB encrypted-content limit.
    private val magic = byteArrayOf(-1, 71, 67, 80)
    data class Content(val body: String, val seconds: Int, val control: Boolean)
    fun encode(body: String, seconds: Int, control: Boolean = false): ByteArray {
        DisappearingTimer.from(seconds)
        val text = if (control) { require(body.isEmpty()); byteArrayOf() } else TextRules.encode(body)
        try {
            if (text.size > MAX_TEXT) throw AppFailure(AppError.MESSAGE_TOO_LARGE)
            val size = ((16 + text.size + 255) / 256) * 256
            val bytes = ByteArray(size).also { SecureRandom().nextBytes(it) }
            ByteBuffer.wrap(bytes).put(magic).put(1).put(if (control) 2 else 1).putShort(0)
                .putInt(seconds).putInt(text.size).put(text)
            return bytes
        } finally { text.fill(0) }
    }
    fun decode(bytes: ByteArray): Content = try { decodeChecked(bytes) }
        catch (_: java.nio.charset.CharacterCodingException) { throw AppFailure(AppError.INVALID_TEXT) }
    private fun decodeChecked(bytes: ByteArray): Content {
        if (bytes.isEmpty()) throw AppFailure(AppError.INVALID_TEXT)
        // Old UTF-8 messages are still accepted as ordinary, non-expiring text.
        if (bytes[0] != magic[0]) {
            val body = bytes.decodeToString(throwOnInvalidSequence = true)
            TextRules.encode(body).fill(0); return Content(body, 0, false)
        }
        if (bytes.size !in 256..16384 || bytes.size % 256 != 0) throw AppFailure(AppError.INVALID_TEXT)
        val input = ByteBuffer.wrap(bytes)
        if (!ByteArray(4).also { input.get(it) }.contentEquals(magic) || input.get().toInt() != 1)
            throw AppFailure(AppError.INVALID_TEXT)
        val type = input.get().toInt()
        if (type !in 1..2 || input.short.toInt() != 0) throw AppFailure(AppError.INVALID_TEXT)
        val seconds = input.int; DisappearingTimer.from(seconds)
        val length = input.int
        if (length !in 0..MAX_TEXT || length > input.remaining() || (type == 2 && length != 0) ||
            ((16 + length + 255) / 256) * 256 != bytes.size) throw AppFailure(AppError.INVALID_TEXT)
        val text = ByteArray(length).also { input.get(it) }
        val body = try { text.decodeToString(throwOnInvalidSequence = true) } finally { text.fill(0) }
        if (type == 1) TextRules.encode(body).fill(0)
        return Content(body, seconds, type == 2)
    }
    fun policyText(seconds: Int) = if (seconds == 0) "Disappearing messages turned off"
        else "Disappearing messages set to ${DisappearingTimer.from(seconds).label}"
}

data class ExpiryMoment(val wall: Long, val elapsed: Long, val boot: Int)
/** Wall time never moves backwards while this clock instance observes the same boot. */
class ExpiryClock(private val wall: () -> Long = System::currentTimeMillis,
    private val elapsed: () -> Long = { System.nanoTime() / 1_000_000 }, private val boot: () -> Int = { 0 }) {
    private var last: ExpiryMoment? = null
    @Synchronized fun now(): ExpiryMoment {
        val e = elapsed(); val b = boot(); val previous = last
        val w = if (previous != null && previous.boot == b && e >= previous.elapsed)
            maxOf(wall(), previous.wall + e - previous.elapsed) else wall()
        return ExpiryMoment(w, e, b).also { last = it }
    }
}
@Serializable data class ExpiryDeadline(val wall: Long, val elapsed: Long, val boot: Int) {
    fun reached(now: ExpiryMoment) = now.wall >= wall || (now.boot == boot && now.elapsed >= elapsed)
    companion object {
        fun start(seconds: Int, now: ExpiryMoment): ExpiryDeadline {
            DisappearingTimer.from(seconds); require(seconds > 0)
            return ExpiryDeadline(Math.addExact(now.wall, seconds * 1000L), Math.addExact(now.elapsed, seconds * 1000L), now.boot)
        }
    }
}
