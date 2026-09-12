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
    private var until = 0L
    private var failures = 0
    val remainingMillis: Long @Synchronized get() = (until - clockMillis()).coerceAtLeast(0)
    @Synchronized fun rejected(retryAfterMillis: Long?) {
        val fallback = 15000L shl failures.coerceAtMost(2)
        failures++
        val delay = (retryAfterMillis ?: fallback).coerceAtLeast(2000L)
        val now = clockMillis()
        until = maxOf(until, now + delay.coerceAtMost(Long.MAX_VALUE - now))
    }
    @Synchronized fun succeeded() { failures = 0; until = 0 }
}
