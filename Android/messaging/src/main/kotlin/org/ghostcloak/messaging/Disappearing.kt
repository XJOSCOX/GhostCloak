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
    private val profileMarker = "GhostCloak/profile/v1!".toByteArray(Charsets.US_ASCII)
    const val MAX_TEXT = 16_368 // 16-byte header within the existing 16KiB encrypted-content limit.
    private val magic = byteArrayOf(-1, 71, 67, 80)
    class Content(val body: String, val seconds: Int, val control: Boolean, val attachment: ByteArray? = null,
        val supportsAttachments: Boolean = false, val displayName: String? = null) {
        override fun toString() = "Content(redacted)"
    }
    private fun profileBytes(displayName:String?):ByteArray = displayName?.let {
        TextRules.displayName(it)
        val name=it.encodeToByteArray()
        profileMarker + byteArrayOf(name.size.toByte()) + name
    } ?: byteArrayOf()
    fun encodeAttachment(descriptor: org.ghostcloak.attachments.AttachmentDescriptor, displayName:String?=null): ByteArray {
        val encoded = org.ghostcloak.attachments.AttachmentFormat.encode(descriptor)
        try {
            val profile=profileBytes(displayName)
            val size=((16+encoded.size+profile.size+255)/256)*256
            if(size>16384) throw AppFailure(AppError.MESSAGE_TOO_LARGE)
            val bytes=ByteArray(size).also { SecureRandom().nextBytes(it) }
            ByteBuffer.wrap(bytes).put(magic).put(1).put(3).putShort(0)
                .putInt(descriptor.disappearingSeconds).putInt(encoded.size).put(encoded)
            if(profile.isNotEmpty()) profile.copyInto(bytes,16+encoded.size)
            return bytes
        } finally { encoded.fill(0) }
    }
    fun encode(body: String, seconds: Int, control: Boolean = false, displayName:String?=null): ByteArray {
        DisappearingTimer.from(seconds)
        val text = if (control) { require(body.isEmpty()); byteArrayOf() } else TextRules.encode(body)
        try {
            if (text.size > MAX_TEXT) throw AppFailure(AppError.MESSAGE_TOO_LARGE)
            val profile=profileBytes(displayName)
            val extension=if(16+text.size+attachmentSupport.size+profile.size<=16384) attachmentSupport else byteArrayOf()
            val size = ((16 + text.size + extension.size + profile.size + 255) / 256) * 256
            if(size>16384) throw AppFailure(AppError.MESSAGE_TOO_LARGE)
            val bytes = ByteArray(size).also { SecureRandom().nextBytes(it) }
            ByteBuffer.wrap(bytes).put(magic).put(1).put(if (control) 2 else 1).putShort(0)
                .putInt(seconds).putInt(text.size).put(text)
            if (extension.isNotEmpty()) extension.copyInto(bytes, 16 + text.size)
            if(profile.isNotEmpty()) profile.copyInto(bytes,16+text.size+extension.size)
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
        if (length !in 0..MAX_TEXT || length > input.remaining() || (type == 2 && length != 0)) throw AppFailure(AppError.INVALID_TEXT)
        val text = ByteArray(length).also { input.get(it) }
        var offset=16+length
        val supports = bytes.size-offset>=attachmentSupport.size &&
            bytes.copyOfRange(offset,offset+attachmentSupport.size).contentEquals(attachmentSupport)
        if(supports) offset+=attachmentSupport.size
        val name = if(bytes.size-offset>=profileMarker.size+1 &&
            bytes.copyOfRange(offset,offset+profileMarker.size).contentEquals(profileMarker)) {
            val count=bytes[offset+profileMarker.size].toInt() and 255
            if(count !in 1..128 || bytes.size-offset-profileMarker.size-1<count) throw AppFailure(AppError.INVALID_TEXT)
            val decoded=bytes.copyOfRange(offset+profileMarker.size+1,offset+profileMarker.size+1+count)
                .decodeToString(throwOnInvalidSequence=true)
            TextRules.displayName(decoded)
            decoded
        } else null
        val minimum=16+length+(if(supports) attachmentSupport.size else 0)+
            (if(name!=null) profileMarker.size+1+name.encodeToByteArray().size else 0)
        if(((minimum+255)/256)*256!=bytes.size)
            throw AppFailure(AppError.INVALID_TEXT)
        if (type == 3) {
            try {
                val descriptor=org.ghostcloak.attachments.AttachmentFormat.decode(text)
                require(descriptor.disappearingSeconds==seconds)
                return Content("",seconds,false,text,displayName=name)
            } catch (_: Exception) { text.fill(0); throw AppFailure(AppError.INVALID_TEXT) }
        }
        val body = try { text.decodeToString(throwOnInvalidSequence = true) } finally { text.fill(0) }
        if (type == 1) TextRules.encode(body).fill(0)
        return Content(body, seconds, type == 2, supportsAttachments = supports,displayName=name)
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
