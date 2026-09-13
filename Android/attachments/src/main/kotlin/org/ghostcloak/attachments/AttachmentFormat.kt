package org.ghostcloak.attachments

import com.google.crypto.tink.Registry
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.proto.AesGcmHkdfStreamingKey
import com.google.crypto.tink.proto.AesGcmHkdfStreamingParams
import com.google.crypto.tink.proto.HashType
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import com.google.crypto.tink.shaded.protobuf.ByteString
import kotlinx.serialization.Serializable
import org.ghostcloak.protocol.NetworkCodec
import java.io.*
import java.security.MessageDigest
import java.security.SecureRandom

/** Contains secrets: never pass to UI, HTTP control bodies, diagnostics or filenames. */
@Serializable
class AttachmentDescriptor(
    val version: Int = 1, val algorithm: String = AttachmentFormat.ALGORITHM,
    val id: String, val capability: ByteArray, val key: ByteArray,
    val digest: ByteArray, val plaintextLength: Long, val paddedLength: Long,
    val ciphertextLength: Long, val kind: AttachmentKind,
    val filename: String? = null, val width: Int? = null, val height: Int? = null,
    val durationMillis: Long? = null, val disappearingSeconds: Int = 0,
) {
    override fun toString() = "AttachmentDescriptor(redacted)"
    fun validate() {
        require(version == 1 && algorithm == AttachmentFormat.ALGORITHM)
        require(AttachmentFormat.validId(id) && capability.size == 32 && key.size == 16 && digest.size == 32)
        require(plaintextLength in 1..kind.maximumBytes)
        require(paddedLength == AttachmentFormat.padded(plaintextLength))
        require(ciphertextLength == AttachmentFormat.encryptedLength(paddedLength))
        require(ciphertextLength <= AttachmentFormat.MAX_CIPHERTEXT)
        require(disappearingSeconds in setOf(0, 30, 300, 3600, 86400, 604800))
        require(filename == null || (filename == AttachmentFormat.sanitizeFilename(filename) && filename.isNotEmpty()))
        require((width == null) == (height == null))
        require(width == null || (kind in setOf(AttachmentKind.IMAGE, AttachmentKind.VIDEO) &&
            width in 1..8192 && height!! in 1..8192 && width.toLong() * height <= 32_000_000))
        require(durationMillis == null || (kind in setOf(AttachmentKind.AUDIO, AttachmentKind.VOICE_NOTE, AttachmentKind.VIDEO) &&
            durationMillis in 1..if (kind == AttachmentKind.VIDEO) 120_000L else 300_000L))
    }
}
@Serializable enum class AttachmentKind(val maximumBytes: Long) {
    IMAGE(10L * 1048576), DOCUMENT(20L * 1048576), AUDIO(4L * 1048576),
    VOICE_NOTE(4L * 1048576), VIDEO(25L * 1048576)
}

object AttachmentFormat {
    const val ALGORITHM = "AES128_GCM_HKDF_1MB"
    const val MAX_CIPHERTEXT = 26L * 1048576
    private const val SEGMENT = 1048576L
    private val random = SecureRandom()
    init { StreamingAeadConfig.register() }
    fun randomBytes(size: Int) = ByteArray(size).also(random::nextBytes)
    fun newId() = randomBytes(32).joinToString("") { "%02x".format(it) }
    fun validId(id: String) = id.matches(Regex("[0-9a-f]{64}"))
    fun padded(length: Long): Long {
        require(length in 1..25L * 1048576)
        val bucket = if (length <= 1048576) 65536L else 1048576L
        return ((length + bucket - 1) / bucket) * bucket
    }
    // Tink format: 24-byte header; each segment has a 16-byte tag.
    fun encryptedLength(length: Long): Long {
        require(length in 1..25L * 1048576)
        return length + 24 + 16 * ((length + 24 + (SEGMENT - 16) - 1) / (SEGMENT - 16))
    }
    fun sanitizeFilename(value: String): String = buildString {
        value.forEach { c ->
            if (!c.isISOControl() && c !in "/\\:" && c !in '\u202a'..'\u202e' && c !in '\u2066'..'\u2069' &&
                !c.isSurrogate() && (toString() + c).toByteArray(Charsets.UTF_8).size <= 128) append(c)
        }
    }.trim().takeUnless { it in setOf("", ".", "..") } ?: "Attachment"
    fun encode(descriptor: AttachmentDescriptor): ByteArray {
        descriptor.validate()
        return NetworkCodec.encode(descriptor).also { require(it.size <= 4096) }
    }
    fun decode(bytes: ByteArray): AttachmentDescriptor {
        require(bytes.size in 1..4096)
        return NetworkCodec.decode<AttachmentDescriptor>(bytes, 4096).also { it.validate() }
    }
    private fun associatedData(id: String): ByteArray {
        require(validId(id))
        return "GhostCloakAttachment/v1".toByteArray(Charsets.US_ASCII) +
            id.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
    @Suppress("DEPRECATION")
    private fun primitive(key: ByteArray): StreamingAead {
        require(key.size == 16)
        // Fixed raw 128-bit key encoding, not arbitrary serialized keysets/algorithms.
        val proto = AesGcmHkdfStreamingKey.newBuilder().setVersion(0).setKeyValue(ByteString.copyFrom(key))
            .setParams(AesGcmHkdfStreamingParams.newBuilder().setCiphertextSegmentSize(1048576)
                .setDerivedKeySize(16).setHkdfHashType(HashType.SHA256)).build()
        return Registry.getPrimitive("type.googleapis.com/google.crypto.tink.AesGcmHkdfStreamingKey", proto.toByteArray(), StreamingAead::class.java)
    }
    /** Destination must be a newly created private quarantine file, never a public/user-selected path. */
    fun encrypt(source: InputStream, destination: File, length: Long, kind: AttachmentKind,
        seconds: Int = 0, checkpoint: () -> Unit = {}): AttachmentDescriptor {
        require(length in 1..kind.maximumBytes && !destination.exists())
        val key = randomBytes(16); val id = newId(); val capability = randomBytes(32)
        val padding = padded(length)
        try {
            FileOutputStream(destination).use { file ->
                // Closing cipher must finalize it without closing the fd before fsync.
                val sink = object : FilterOutputStream(file) { override fun close() { flush() } }
                primitive(key).newEncryptingStream(sink, associatedData(id)).use { output ->
                    copyExactly(source, output, length, checkpoint)
                    require(source.read() == -1)
                    var remaining = padding - length
                    val buffer = ByteArray(8192)
                    try { while (remaining > 0) {
                        checkpoint(); random.nextBytes(buffer)
                        val count = minOf(remaining, buffer.size.toLong()).toInt()
                        output.write(buffer, 0, count); remaining -= count
                    } } finally { buffer.fill(0) }
                }
                file.fd.sync()
            }
            return AttachmentDescriptor(id = id, capability = capability, key = key, digest = digest(destination),
                plaintextLength = length, paddedLength = padding, ciphertextLength = destination.length(),
                kind = kind, disappearingSeconds = seconds).also { it.validate() }
        } catch (failure: Throwable) {
            destination.delete(); key.fill(0); capability.fill(0); throw failure
        }
    }
    /** Caller keeps output quarantined until this returns and its own presentation gate admits access. */
    fun decrypt(descriptor: AttachmentDescriptor, ciphertext: File, destination: File, checkpoint: () -> Unit = {}) {
        descriptor.validate(); require(!destination.exists())
        try {
            require(ciphertext.length() == descriptor.ciphertextLength)
            require(MessageDigest.isEqual(digest(ciphertext), descriptor.digest))
            FileInputStream(ciphertext).use { file ->
                primitive(descriptor.key).newDecryptingStream(file, associatedData(descriptor.id)).use { input ->
                    FileOutputStream(destination).use { output ->
                        copyExactly(input, output, descriptor.plaintextLength, checkpoint)
                        copyExactly(input, object:OutputStream() { override fun write(value:Int) {} ; override fun write(bytes:ByteArray,offset:Int,length:Int) {} }, descriptor.paddedLength - descriptor.plaintextLength, checkpoint)
                        require(input.read() == -1) // Authenticated final segment is mandatory.
                        checkpoint(); output.fd.sync()
                    }
                }
            }
        } catch (failure: Throwable) { destination.delete(); throw failure }
    }
    fun digest(file: File): ByteArray {
        require(file.length() <= MAX_CIPHERTEXT)
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192); var total = 0L
            while (true) { val n = input.read(buffer); if (n < 0) break
                total += n; require(total <= MAX_CIPHERTEXT); hash.update(buffer, 0, n)
            }
        }
        return hash.digest()
    }
    private fun copyExactly(input: InputStream, output: OutputStream, length: Long, checkpoint: () -> Unit) {
        val buffer = ByteArray(8192); var remaining = length
        try { while (remaining > 0) {
            checkpoint(); val n = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            if (n < 0) throw EOFException("attachment_incomplete")
            if (n == 0) continue
            output.write(buffer, 0, n); remaining -= n
        } } finally { buffer.fill(0) }
    }
}
