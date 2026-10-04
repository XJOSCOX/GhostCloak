package org.ghostcloak.protocol

import java.security.SecureRandom
import java.util.Locale

/** Public, uniformly random lookup identifiers. Never an authentication credential. */
object GhostCloakIds {
    const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
    private val canonical = Regex("[$ALPHABET]{12}")
    private val grouped = Regex("[$ALPHABET]{4}-[$ALPHABET]{4}-[$ALPHABET]{4}")

    fun normalize(input: String): String {
        if (input.any { it.code > 0x7f }) throw ApiFailure(400, "invalid_ghostcloak_id")
        val upper = input.uppercase(Locale.ROOT)
        val value = when {
            canonical.matches(upper) -> upper
            grouped.matches(upper) -> upper.replace("-", "")
            else -> throw ApiFailure(400, "invalid_ghostcloak_id")
        }
        return value
    }

    fun valid(value: String): Boolean = canonical.matches(value)
    fun display(value: String): String {
        require(valid(value))
        return "${value.substring(0, 4)}-${value.substring(4, 8)}-${value.substring(8)}"
    }

    fun generate(random: SecureRandom = SecureRandom()): String = buildString(12) {
        repeat(12) {
            var index: Int
            do { index = random.nextInt(32) } while (index == 31)
            append(ALPHABET[index])
        }
    }
}
