package org.ghostcloak.app.attachments

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PlaintextScratchTest {
    @Test fun failedDeleteBlocksNewPresentationUntilClosedAndRetried() {
        val root = Files.createTempDirectory("ghostcloak-scratch-test").toFile()
        var descriptorOpen = true
        try {
            val scratch = PlaintextScratch(root) { file -> if (descriptorOpen) false else file.delete() }
            val file = scratch.newFile().apply { writeText("synthetic fixture") }
            assertFalse(scratch.delete(file))
            assertFalse(scratch.clean)
            assertThrows(IllegalStateException::class.java) { scratch.newFile() }
            assertFalse(scratch.sweep())
            descriptorOpen = false
            assertTrue(scratch.sweep())
            assertFalse(file.exists())
            assertTrue(scratch.clean)
        } finally { root.deleteRecursively() }
    }

    @Test fun processRecreationSweepsStrandedPlaintextBeforeAnyNewFile() {
        val root = Files.createTempDirectory("ghostcloak-scratch-restart").toFile()
        try {
            val first = PlaintextScratch(root)
            val stranded = first.newFile().apply { writeText("synthetic fixture") }
            val restarted = PlaintextScratch(root)
            assertTrue(restarted.clean)
            assertFalse(stranded.exists())
            assertEquals(emptyList<File>(), root.listFiles()?.toList())
        } finally { root.deleteRecursively() }
    }

    @Test fun unknownDirectoryPreventsClaimingCleanupComplete() {
        val root = Files.createTempDirectory("ghostcloak-scratch-unknown").toFile()
        try {
            File(root,"unknown").mkdir()
            val scratch = PlaintextScratch(root)
            assertFalse(scratch.clean)
            assertThrows(IllegalStateException::class.java) { scratch.newFile() }
        } finally { root.deleteRecursively() }
    }

    @Test fun unlinkExceptionFailsClosedUntilRetry() {
        val root = Files.createTempDirectory("ghostcloak-scratch-exception").toFile()
        var fail = true
        try {
            val scratch = PlaintextScratch(root) { file ->
                if (fail) throw SecurityException("fixture") else file.delete()
            }
            val file = scratch.newFile().apply { writeText("synthetic fixture") }
            assertFalse(scratch.delete(file))
            assertFalse(scratch.clean)
            fail = false
            assertTrue(scratch.sweep())
            assertFalse(file.exists())
        } finally { root.deleteRecursively() }
    }
}
