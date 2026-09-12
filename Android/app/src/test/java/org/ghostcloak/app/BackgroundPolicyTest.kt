package org.ghostcloak.app

import org.ghostcloak.app.application.*
import org.ghostcloak.crypto.EndpointRecords
import org.junit.Assert.*
import org.junit.Test

class BackgroundPolicyTest {
    private class Records : EndpointRecords {
        private val values = mutableMapOf<String, ByteArray>()
        override fun <T> transaction(block: () -> T): T = block()
        override fun read(key: String) = values[key]?.copyOf()
        override fun write(key: String, value: ByteArray) { values[key] = value.copyOf() }
        override fun remove(key: String) { values.remove(key) }
        override fun keys(prefix: String) = values.keys.filter { it.startsWith(prefix) }
    }

    @Test fun persistedCooldownSurvivesReopenAndConservativelyRebasesOnReboot() {
        val records = Records(); var elapsed = 100_000L
        val original = storedFetchCooldown(records, "fixture.invalid", 1) { elapsed }
        original.rejected(60_000)
        elapsed += 10_000
        val reopened = storedFetchCooldown(records, "fixture.invalid", 1) { elapsed }
        assertEquals(50_000, reopened.remainingMillis)
        // Clock changes cannot affect this policy: it never reads wall time.
        elapsed = 1000
        val rebooted = storedFetchCooldown(records, "fixture.invalid", 2) { elapsed }
        assertEquals(50_000, rebooted.remainingMillis)
        elapsed += 49_999
        assertEquals(1, rebooted.remainingMillis)
        elapsed++
        assertEquals(0, rebooted.remainingMillis)
        rebooted.succeeded()
        assertTrue(records.keys("app/fetch-cooldown/").isEmpty())
    }

    @Test fun fallbackProgressionAndAudienceRemainSeparateAcrossReopen() {
        val records = Records(); var elapsed = 100L
        var cooldown = storedFetchCooldown(records, "one.invalid", 1) { elapsed }
        cooldown.rejected(null); assertEquals(15_000, cooldown.remainingMillis)
        elapsed += 15_000
        cooldown = storedFetchCooldown(records, "one.invalid", 1) { elapsed }
        cooldown.rejected(null); assertEquals(30_000, cooldown.remainingMillis)
        assertEquals(0, storedFetchCooldown(records, "two.invalid", 1) { elapsed }.remainingMillis)
    }

    @Test fun releaseDiagnosticsAreNoOpAndEventVocabularyIsClosed() {
        assertEquals(BuildConfig.DEBUG, BackgroundDiagnostics.ENABLED)
        assertEquals(setOf("WORK_START", "WORK_SKIP", "WORK_SUCCESS", "WORK_RETRY", "WORK_STOP"), BackgroundEvent.entries.map { it.name }.toSet())
        if (!BuildConfig.DEBUG) BackgroundEvent.entries.forEach(BackgroundDiagnostics::emit)
    }
}
