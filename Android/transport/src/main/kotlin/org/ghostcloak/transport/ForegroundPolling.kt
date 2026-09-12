package org.ghostcloak.transport

/** One request per completed cycle: at most ~30/minute while active, ~12/minute when idle. */
class ForegroundPolling {
    private var emptyCycles = 0
    @Synchronized fun reset() { emptyCycles = 0 }
    @Synchronized fun completed(active: Boolean): Long {
        if (active) emptyCycles = 0 else emptyCycles++
        return when { emptyCycles < 4 -> 2000L; emptyCycles < 8 -> 3000L; else -> 5000L }
    }
}
