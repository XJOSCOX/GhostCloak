package org.ghostcloak.app.application

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File

class LocalStateDiagnosticsTest {
    @Test fun missingStoreIsObservedWithoutCreatingFilesOrRepeatingOperation() = fixture { dir, logs ->
        var calls = 0
        val result = LocalStateDiagnostics.open(dir, "local", "https://api.ghostcloak.org") { ++calls; 42 }
        assertEquals(42, result)
        assertEquals(1, calls)
        assertTrue(dir.listFiles()!!.isEmpty())
        assertTrue(logs.contains("STORE_OPEN_RESULT=missing"))
        assertTrue(logs.contains("DATABASE_EXISTS=false"))
        assertTrue(logs.contains("API_ORIGIN_CATEGORY=staging"))
    }
    @Test fun existingFilesRemainByteIdenticalAndAlternateSlotIsNotSelected() = fixture { dir, logs ->
        File(dir, "local.db").writeBytes(byteArrayOf(1, 2))
        File(dir, "local.wrapped").writeBytes(byteArrayOf(3, 4))
        LocalStateDiagnostics.open(dir, "local", "https://private.example/sensitive") { Unit }
        assertTrue(logs.contains("STORE_OPEN_RESULT=existing"))
        assertArrayEquals(byteArrayOf(1, 2), File(dir, "local.db").readBytes())
        assertArrayEquals(byteArrayOf(3, 4), File(dir, "local.wrapped").readBytes())
        logs.clear()
        LocalStateDiagnostics.open(dir, "private-slot", "") { Unit }
        assertTrue(logs.contains("PROFILE_SLOT=nondefault"))
        assertTrue(logs.contains("ENDPOINT_STORE_EXISTS=false"))
        assertEquals(2, dir.listFiles()!!.size)
        assertFalse(logs.joinToString().contains("private-slot"))
    }
    @Test fun failureIsPreservedWithoutExceptionOrPathLogging() = fixture { dir, logs ->
        File(dir, "local.db").writeBytes(byteArrayOf(7))
        val failure = IllegalStateException("private material")
        try { LocalStateDiagnostics.open(dir, "local", "secret") { throw failure } }
        catch (actual: IllegalStateException) { assertSame(failure, actual) }
        assertEquals("STORE_OPEN_RESULT=error", logs.last())
        assertFalse(logs.joinToString().contains("private material"))
        assertEquals(1, dir.listFiles()!!.size)
    }
    @Test fun brokenLoggingDoesNotChangeOpenResult() = fixture { dir, _ ->
        LocalStateDiagnostics.sink = { throw IllegalStateException() }
        assertEquals(7, LocalStateDiagnostics.open(dir, "local", "") { 7 })
    }
    private fun fixture(block: (File, MutableList<String>) -> Unit) {
        val dir = Files.createTempDirectory("store-diagnostic-test").toFile()
        val old = LocalStateDiagnostics.sink
        val logs = mutableListOf<String>()
        LocalStateDiagnostics.sink = { logs.add(it); Unit }
        try {
            block(dir, logs)
            assertFalse(logs.joinToString().contains(dir.absolutePath))
            assertTrue(logs.all { it.substringBefore('=') in setOf("PACKAGE_NAME", "BUILD_TYPE",
                "API_ORIGIN_CATEGORY", "PROFILE_SLOT", "DATABASE_EXISTS", "WRAPPED_KEY_EXISTS",
                "ENDPOINT_STORE_EXISTS", "STORE_OPEN_RESULT") })
        } finally { LocalStateDiagnostics.sink = old; dir.deleteRecursively() }
    }
}
