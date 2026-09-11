package org.ghostcloak.app.application

import org.ghostcloak.transport.NetworkDiagnostic

/** Release has no Logcat implementation or diagnostic observer. */
internal object NetworkDiagnostics {
    const val ENABLED = false
    val observer: ((NetworkDiagnostic) -> Unit)? = null
    fun status(before: NetworkStatus, after: NetworkStatus) = Unit
    fun cycle(number: Long, start: Boolean, elapsedMs: Long = 0) = Unit
}
