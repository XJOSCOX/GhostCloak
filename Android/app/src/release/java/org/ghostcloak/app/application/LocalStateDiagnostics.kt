package org.ghostcloak.app.application

import java.io.File

/** Release performs no diagnostic filesystem probes and emits no diagnostics. */
internal object LocalStateDiagnostics {
    @Suppress("UNUSED_PARAMETER")
    fun <T> open(directory: File, endpoint: String, origin: String, operation: () -> T): T = operation()
}
