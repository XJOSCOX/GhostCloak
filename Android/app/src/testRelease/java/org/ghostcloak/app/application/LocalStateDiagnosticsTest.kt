package org.ghostcloak.app.application

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LocalStateDiagnosticsTest {
    @Test fun releaseDoesNotProbeFilesystemAndCallsOriginalOperationOnce() {
        val directory = object : File("unused") {
            override fun getPath(): String = error("Diagnostics must not probe release files")
        }
        var calls = 0
        assertEquals(42, LocalStateDiagnostics.open(directory, "local", "") { ++calls; 42 })
        assertEquals(1, calls)
        val failure = IllegalStateException()
        try { LocalStateDiagnostics.open(directory, "local", "") { throw failure } }
        catch (actual: IllegalStateException) { assertSame(failure, actual) }
    }
}
