package org.ghostcloak.app.application

import android.util.Log
import org.ghostcloak.transport.NetworkDiagnostic

internal object NetworkDiagnostics {
    const val ENABLED = true
    internal var sink: (String) -> Unit = { Log.d("GhostCloakNet", it); Unit }
    private fun write(line: String) { try { sink(line) } catch (_: Exception) { } }
    val observer: ((NetworkDiagnostic) -> Unit)? = { write(it.line()) }
    fun status(before: NetworkStatus, after: NetworkStatus) { if (before != after) write("status $before -> $after") }
    fun cycle(number: Long, start: Boolean, elapsedMs: Long = 0) {
        write("SYNC ${if (start) "START" else "END"} cycle=$number elapsed=${elapsedMs}ms")
    }
}
