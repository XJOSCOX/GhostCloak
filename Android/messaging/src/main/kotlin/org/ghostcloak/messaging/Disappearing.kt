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
    // Authenticated padding extension. Older decoders already ignore padding.
    // Not user text: a quoted/copied advertisement can never establish support.
    private val attachmentSupport = "GhostCloak/padding/attachments/v1!".toByteArray(Charsets.US_ASCII)
    const val MAX_TEXT = 16_368 // 16-byte header within the existing 16KiB encrypted-content limit.
    private val magic = byteArrayOf(-1, 71, 67, 80)
    class Content(val body: String, val seconds: Int, val control: Boolean, val attachment: ByteArray? = null,
        val supportsAttachments: Boolean = false) {
        override fun toString() = "Content(redacted)"
    }
    fun encodeAttachment(descriptor: org.ghostcloak.attachments.AttachmentDescriptor): ByteArray {
        val encoded = org.ghostcloak.attachments.AttachmentFormat.encode(descriptor)
        try {
            val bytes=ByteArray(((16+encoded.size+255)/256)*256).also { SecureRandom().nextBytes(it) }
            ByteBuffer.wrap(bytes).put(magic).put(1).put(3).putShort(0)
                .putInt(descriptor.disappearingSeconds).putInt(encoded.size).put(encoded)
            return bytes
        } finally { encoded.fill(0) }
    }
    fun encode(body: String, seconds: Int, control: Boolean = false): ByteArray {
        DisappearingTimer.from(seconds)
        val text = if (control) { require(body.isEmpty()); byteArrayOf() } else TextRules.encode(body)
        try {
            if (text.size > MAX_TEXT) throw AppFailure(AppError.MESSAGE_TOO_LARGE)
            val size = ((16 + text.size + 255) / 256) * 256
            val bytes = ByteArray(size).also { SecureRandom().nextBytes(it) }
            ByteBuffer.wrap(bytes).put(magic).put(1).put(if (control) 2 else 1).putShort(0)
                .putInt(seconds).putInt(text.size).put(text)
            if (bytes.size - 16 - text.size >= attachmentSupport.size)
                attachmentSupport.copyInto(bytes, 16 + text.size)
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
        if (type !in 1..3 || input.short.toInt() != 0) throw AppFailure(AppError.INVALID_TEXT)
        val seconds = input.int; DisappearingTimer.from(seconds)
        val length = input.int
        if (length !in 0..MAX_TEXT || length > input.remaining() || (type == 2 && length != 0) ||
            ((16 + length + 255) / 256) * 256 != bytes.size) throw AppFailure(AppError.INVALID_TEXT)
        val text = ByteArray(length).also { input.get(it) }
        val supports = input.remaining() >= attachmentSupport.size &&
            ByteArray(attachmentSupport.size).also { input.get(it) }.contentEquals(attachmentSupport)
        if (type == 3) {
            try {
                val descriptor=org.ghostcloak.attachments.AttachmentFormat.decode(text)
                require(descriptor.disappearingSeconds==seconds)
                return Content("",seconds,false,text)
            } catch (_: Exception) { text.fill(0); throw AppFailure(AppError.INVALID_TEXT) }
        }
        val body = try { text.decodeToString(throwOnInvalidSequence = true) } finally { text.fill(0) }
        if (type == 1) TextRules.encode(body).fill(0)
        return Content(body, seconds, type == 2, supportsAttachments = supports)
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
