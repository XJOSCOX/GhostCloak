package org.ghostcloak.app

import org.junit.Assert.*
import org.junit.Test
import java.net.URI

/** Runs against BOTH debug and release generated BuildConfig classes. */
class BuildConfigurationTest {
    @Test fun originIsScopedToTheBuildType() {
        val expected = if (BuildConfig.DEBUG) {
            System.getProperty("ghostcloak.test.debugOverride") ?: "https://api.ghostcloak.org"
        } else {
            System.getProperty("ghostcloak.test.releaseOverride") ?: ""
        }
        assertEquals(expected, BuildConfig.API_ORIGIN)
        if (!BuildConfig.DEBUG && BuildConfig.API_ORIGIN.isNotEmpty()) {
            assertNotEquals("api.ghostcloak.org", URI(BuildConfig.API_ORIGIN).host)
        }
    }
}
