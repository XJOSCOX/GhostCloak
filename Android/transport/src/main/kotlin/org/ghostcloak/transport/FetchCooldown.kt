package org.ghostcloak.transport

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** HTTP metadata only. Never used as identity or written to diagnostics. */
fun parseRetryAfter(value: String?, nowMillis: Long = System.currentTimeMillis()): Long? {
    if (value == null) return null
    value.trim().toLongOrNull()?.let { return if (it >= 0) it.coerceAtMost(Long.MAX_VALUE / 1000) * 1000 else null }
    return try { (ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - nowMillis).coerceAtLeast(0) }
    catch (_: Exception) { null }
}

class FetchCooldown(private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 }) {
    // Installed by the Android owner after restoring encrypted scheduling state.
    var checkpoint: (Long, Int) -> Unit = { _, _ -> }
    private var until = 0L
    private var failures = 0
    val remainingMillis: Long @Synchronized get() = (until - clockMillis()).coerceAtLeast(0)
    @Synchronized fun rejected(retryAfterMillis: Long?) {
        val fallback = 15000L shl failures.coerceAtMost(2)
        failures = (failures + 1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val delay = (retryAfterMillis ?: fallback).coerceAtLeast(2000L)
        val now = clockMillis()
        until = maxOf(until, now + delay.coerceAtMost(Long.MAX_VALUE - now))
        checkpoint(until, failures)
    }
    @Synchronized fun succeeded() { failures = 0; until = 0; checkpoint(until, failures) }
    @Synchronized fun restore(deadline: Long, count: Int) { until = deadline; failures = count.coerceAtLeast(0) }
}
