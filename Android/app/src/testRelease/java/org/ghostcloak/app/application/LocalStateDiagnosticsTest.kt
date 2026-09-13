package org.ghostcloak.app.application

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LocalStateDiagnosticsTest {
    @Test fun releaseInventoryDoesNotAccessRecords() {
        val records = object : org.ghostcloak.crypto.EndpointRecords {
            override fun <T> transaction(block: () -> T): T = error("Forbidden")
            override fun read(key: String): ByteArray? = error("Forbidden")
            override fun keys(prefix: String): List<String> = error("Forbidden")
            override fun write(key: String, value: ByteArray): Unit = error("Forbidden")
            override fun remove(key: String): Unit = error("Forbidden")
        }
        LocalStateDiagnostics.inventory(records, "")
    }
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
