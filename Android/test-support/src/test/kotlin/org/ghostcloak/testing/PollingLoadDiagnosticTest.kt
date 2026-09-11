package org.ghostcloak.testing

import org.ghostcloak.backend.DevelopmentRateLimiter
import org.ghostcloak.backend.ServerOperation
import org.junit.Assert.*
import org.junit.Test

/** Diagnostic model of the unchanged 60/minute fixed-window FETCH limit. */
class PollingLoadDiagnosticTest {
    @Test fun receiptPollingCanExplainSixteenSecondRecoveryAtMinuteBoundary() {
        val rate = DevelopmentRateLimiter()
        // One inbox and one receipt request, 225 ms each, then the 1,000 ms pause.
        repeat(30) { cycle ->
            assertTrue(rate.allow(ServerOperation.FETCH, "synthetic", cycle * 1450L))
            assertTrue(rate.allow(ServerOperation.FETCH, "synthetic", cycle * 1450L + 225))
        }
        val exhaustedAt = 30 * 1450L
        assertFalse(rate.allow(ServerOperation.FETCH, "synthetic", exhaustedAt))
        assertFalse(rate.allow(ServerOperation.FETCH, "synthetic", 59_999))
        assertEquals(16_500L, 60_000L - exhaustedAt)
        assertTrue(rate.allow(ServerOperation.FETCH, "synthetic", 60_000))
    }

    @Test fun oneIdleFetchPerSecondFitsTheSameLimit() {
        val rate = DevelopmentRateLimiter()
        repeat(120) { assertTrue(rate.allow(ServerOperation.FETCH, "synthetic", it * 1000L)) }
    }
}
