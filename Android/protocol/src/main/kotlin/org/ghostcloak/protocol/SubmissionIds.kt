package org.ghostcloak.protocol

import org.ghostcloak.identity.RandomIdentifiers
import java.security.SecureRandom
import java.util.Base64

/** V3 transport ID: "s3" + unpadded base64url of version(1), expiry milliseconds(6 BE), nonce(16).
 * 33 ASCII characters fit the existing sender UUID + '/' + ID database key (70 <= 73).
 */
object SubmissionIds {
    const val MAX_LIFETIME_MILLIS = 14L * 24 * 60 * 60 * 1000
    const val ENCODED_LENGTH = 33
    private const val VERSION: Byte = 3
    private const val MAX_48 = 0xFFFFFFFFFFFFL
    private val random = SecureRandom()
    sealed class Parsed {
        data object LegacyV2 : Parsed()
        data class ExpiringV3(val expiresAt: Long) : Parsed()
    }
    fun create(expiresAt: Long): String {
        require(expiresAt in 1..MAX_48)
        val bytes = ByteArray(23)
        bytes[0] = VERSION
        for (i in 0..5) bytes[1 + i] = (expiresAt ushr (8 * (5 - i))).toByte()
        ByteArray(16).also(random::nextBytes).copyInto(bytes, 7)
        return "s3" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
    fun parse(id: String): Parsed? {
        if (RandomIdentifiers.valid(id)) return Parsed.LegacyV2
        if (id.length != ENCODED_LENGTH || !id.startsWith("s3")) return null
        val bytes = try { Base64.getUrlDecoder().decode(id.substring(2)) } catch (_: IllegalArgumentException) { return null }
        if (bytes.size != 23 || bytes[0] != VERSION ||
            "s3" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) != id) return null
        var expiry = 0L
        for (i in 1..6) expiry = (expiry shl 8) or (bytes[i].toLong() and 0xff)
        if (expiry == 0L) return null
        return Parsed.ExpiringV3(expiry)
    }
}
