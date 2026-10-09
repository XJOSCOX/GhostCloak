package org.ghostcloak.transport

/** A constant foreground cadence avoids multiplying multi-party handshake latency.
 *  It also keeps the fetch timing independent of whether encrypted messages arrived.
 *  One request per completed cycle remains below the 60/minute fetch limit.
 */
class ForegroundPolling {
    @Synchronized fun reset() = Unit
    @Synchronized fun completed(@Suppress("UNUSED_PARAMETER") active: Boolean): Long = 2000L
}
