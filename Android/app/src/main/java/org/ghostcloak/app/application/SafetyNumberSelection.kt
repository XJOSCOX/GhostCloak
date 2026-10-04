package org.ghostcloak.app.application

/** A safety number is only usable for the exact contact and trust mode that requested it. */
data class SafetyNumberPresentation(
    val remoteDeviceId: String,
    val contactId: String,
    val pending: Boolean,
    val fingerprint: String = ""
) {
    fun visibleFor(remoteDeviceId: String, contactId: String, pending: Boolean): String =
        if (this.remoteDeviceId == remoteDeviceId && this.contactId == contactId && this.pending == pending)
            fingerprint else ""
}

/** Generation invalidates results from earlier navigation, including a previous visit to the same contact. */
internal class SafetyNumberSelection {
    private var generation = 0L
    private var active: SafetyNumberPresentation? = null

    @Synchronized fun begin(remoteDeviceId: String, contactId: String, pending: Boolean): Pair<Long, SafetyNumberPresentation> {
        generation++
        return generation to SafetyNumberPresentation(remoteDeviceId, contactId, pending).also { active = it }
    }

    @Synchronized fun complete(request: Long, value: SafetyNumberPresentation): SafetyNumberPresentation? {
        if (request != generation || active != value.copy(fingerprint = "")) return null
        return value.also { active = it }
    }

    @Synchronized fun leave(remoteDeviceId: String): Boolean {
        if (active?.remoteDeviceId != remoteDeviceId) return false
        generation++
        active = null
        return true
    }

    @Synchronized fun clear() { generation++; active = null }

    @Synchronized fun approves(remoteDeviceId: String, contactId: String, pending: Boolean, expected: String): Boolean =
        expected.isNotEmpty() && active?.visibleFor(remoteDeviceId, contactId, pending) == expected
}
