package org.ghostcloak.protocol

/** An internal QR text format, not an Android deep link or authentication proof. */
object GhostCloakContactQr {
    private const val PREFIX = "ghostcloak://contact/v1/"
    const val MAX_LENGTH = 36

    fun encode(id: String): String {
        val canonical = GhostCloakIds.normalize(id)
        return PREFIX + canonical
    }

    /** Deliberately exact: no URI decoding, redirects, parameters, or trailing data. */
    fun decode(payload: String): String? {
        if (payload.length != PREFIX.length + 12 || payload.length > MAX_LENGTH || !payload.startsWith(PREFIX)) return null
        val id = payload.substring(PREFIX.length)
        return runCatching { GhostCloakIds.normalize(id) }.getOrNull()?.takeIf { it == id }
    }
}
