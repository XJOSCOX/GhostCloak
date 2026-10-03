package org.ghostcloak.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RetainedProbeStorageTest {
    @Test fun testUidCreatesPrivateParentAndCopySurvivesTargetOwnedRemoval() = runBlocking {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        RetainedProbeStorage.open(testContext).use { probes ->
            probes.clear()
            val original = File(target.cacheDir, "synthetic-retained-probe")
            try {
                original.writeBytes(byteArrayOf(1, 2, 3))
                probes.copyFrom("IMAGE.cipher", original)
                assertTrue(probes.exists("IMAGE.cipher"))
                assertArrayEquals(original.readBytes(), probes.bytes("IMAGE.cipher"))
                assertTrue(original.delete())
                assertArrayEquals(byteArrayOf(1, 2, 3), probes.bytes("IMAGE.cipher"))
            } finally { original.delete(); probes.clear() }
            assertFalse(probes.exists("IMAGE.cipher"))
        }
    }
}
