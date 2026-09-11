package org.ghostcloak.app

import org.ghostcloak.app.application.NetworkDiagnostics
import org.ghostcloak.app.application.NetworkStatus
import org.junit.Assert.*
import org.junit.Test

/** Executes against both build variants; release has no Android Log calls. */
class NetworkDiagnosticsPolicyTest {
    @Test fun releaseHasNoDiagnosticObserverOrEmission() {
        assertEquals(BuildConfig.DEBUG, NetworkDiagnostics.ENABLED)
        if (!BuildConfig.DEBUG) {
            assertNull(NetworkDiagnostics.observer)
            NetworkDiagnostics.status(NetworkStatus.CONNECTED, NetworkStatus.OFFLINE)
            NetworkDiagnostics.cycle(1, true)
            NetworkDiagnostics.cycle(1, false, 15000)
        }
    }
}
