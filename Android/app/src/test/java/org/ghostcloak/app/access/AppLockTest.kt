package org.ghostcloak.app.access

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AppLockTest {
    private class Memory : LockPersistence {
        var bytes: ByteArray? = null
        var fail = false
        override suspend fun read(): ByteArray? { check(!fail); return bytes?.copyOf() }
        override suspend fun write(bytes: ByteArray) { check(!fail); this.bytes = bytes.copyOf() }
    }
    private class Fixture(val memory: Memory = Memory()) : AutoCloseable {
        var now = 1000L; var boot = 1
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val lock = AppLockController(memory, scope, { now }, { boot })
        suspend fun ready() { lock.start(); lock.initialize() }
        suspend fun pin(mode: LockMode = LockMode.PIN, timing: LockTiming = LockTiming.IMMEDIATE) {
            if (mode.biometric) assertTrue(lock.completeBiometric(lock.beginBiometric(UnlockPurpose.ENROLL)!!))
            assertTrue(lock.configure(mode, timing, "824619".toCharArray(), "824619".toCharArray()))
        }
        override fun close() { scope.cancel() }
    }
    @Test fun defaultOffAndFreshProcessNeverRestoresUnlock() = runBlocking {
        val memory = Memory()
        Fixture(memory).use { f ->
            f.ready(); assertEquals(LockMode.OFF, f.lock.state.value.mode); assertTrue(f.lock.state.value.canShowContent)
            f.pin(); assertTrue(f.lock.state.value.canShowContent)
        }
        Fixture(memory).use { f -> f.ready(); assertFalse(f.lock.state.value.canShowContent); assertTrue(f.lock.verifyPin("824619".toCharArray())) }
    }
    @Test fun pinUsesSaltedVettedKdfAndNoPlaintextEncoding() {
        val input = "824619".toCharArray()
        val a = PinVerifier.create(input); val b = PinVerifier.create(input)
        assertEquals(600_000, a.iterations); assertEquals(16, a.salt.size); assertEquals(32, a.value.size)
        assertFalse(a.salt.contentEquals(b.salt)); assertFalse(a.value.contentEquals(b.value))
        assertFalse(a.matches("824618".toCharArray())); assertTrue(a.matches(input))
        for (bad in listOf("", "12345", "abcdef", "123456 ", "1".repeat(65))) assertFalse(PinVerifier.valid(bad.toCharArray()))
        val bytes = LockConfiguration(LockMode.PIN, verifier = a).encode()
        assertFalse(bytes.toString(Charsets.ISO_8859_1).contains("824619"))
        assertTrue(LockConfiguration.decode(bytes).verifier!!.matches(input))
    }
    @Test fun minimumAndConfirmationAreRequiredAndInputsCleared() = runBlocking {
        Fixture().use { f ->
            f.ready()
            val short = "12345".toCharArray()
            assertFalse(f.lock.configure(LockMode.PIN, LockTiming.IMMEDIATE, short, "12345".toCharArray()))
            assertTrue(short.all { it == '\u0000' }); assertNull(f.memory.bytes)
            assertFalse(f.lock.configure(LockMode.PIN, LockTiming.IMMEDIATE, "123456".toCharArray(), "654321".toCharArray()))
            assertNull(f.memory.bytes)
        }
    }
    @Test fun allTimingsUseMonotonicBackgroundDeadlineAndRotationRetainsGrant() {
        for (timing in LockTiming.entries) {
            var now = 10L; val session = LockSession { now }
            session.start(); session.configure(LockMode.PIN, timing, true)
            session.stop(true); now += 400_000; session.start(); assertTrue(session.canShow)
            session.stop(); assertFalse(session.canShow)
            if (timing.millis > 0) {
                now += timing.millis - 1; session.start(); assertTrue(session.canShow)
                session.stop()
            }
            now += timing.millis; session.start(); assertFalse(session.canShow)
        }
    }
    @Test fun wrongPinThrottleSurvivesProcessAndRebootAndIsBounded() = runBlocking {
        val memory = Memory()
        Fixture(memory).use { f ->
            f.ready(); f.pin(); f.lock.stop(); f.lock.start()
            repeat(3) { assertFalse(f.lock.verifyPin("000000".toCharArray())) }
            assertFalse(f.lock.verifyPin("824619".toCharArray())); assertFalse(f.lock.state.value.canShowContent)
        }
        Fixture(memory).use { f ->
            f.boot = 2; f.ready()
            assertFalse(f.lock.verifyPin("824619".toCharArray()))
            f.now += 5000; assertTrue(f.lock.verifyPin("824619".toCharArray()))
            assertEquals(0, LockConfiguration.decode(memory.bytes!!).failures)
        }
        assertEquals(300_000, LockConfiguration.delayAfter(20))
    }
    @Test fun configuredModesRequireBiometricProofAndStaleCallbacksCannotUnlock() = runBlocking {
        for (mode in listOf(LockMode.BIOMETRIC, LockMode.COMBINED)) Fixture().use { f ->
            f.ready()
            assertFalse(f.lock.configure(mode, LockTiming.IMMEDIATE, "824619".toCharArray(), "824619".toCharArray()))
            f.pin(mode); assertEquals(mode, f.lock.state.value.mode)
            f.lock.stop(); f.lock.start()
            val stale = f.lock.beginBiometric(UnlockPurpose.UNLOCK)!!
            f.lock.stop(); f.lock.start(); assertFalse(f.lock.completeBiometric(stale))
            val cancelled = f.lock.beginBiometric(UnlockPurpose.UNLOCK)!!
            f.lock.cancelBiometric(cancelled); assertFalse(f.lock.completeBiometric(cancelled)); assertFalse(f.lock.state.value.canShowContent)
            assertTrue(f.lock.completeBiometric(f.lock.beginBiometric(UnlockPurpose.UNLOCK)!!))
            assertTrue(f.lock.state.value.canShowContent)
        }
    }
    @Test fun changingOrDisablingRequiresFreshConfiguredAuthentication() = runBlocking {
        Fixture().use { f ->
            f.ready(); f.pin(LockMode.COMBINED)
            assertFalse(f.lock.configure(LockMode.OFF, LockTiming.IMMEDIATE, charArrayOf(), charArrayOf()))
            assertTrue(f.lock.completeBiometric(f.lock.beginBiometric(UnlockPurpose.MANAGE)!!))
            // Forgotten old PIN can be replaced only after the configured biometric succeeds.
            assertTrue(f.lock.configure(LockMode.PIN, LockTiming.SECONDS_30, "739184".toCharArray(), "739184".toCharArray()))
            assertTrue(f.lock.verifyPin("739184".toCharArray(), UnlockPurpose.MANAGE))
            f.now += 60_001
            assertFalse(f.lock.configure(LockMode.OFF, LockTiming.IMMEDIATE, charArrayOf(), charArrayOf()))
            assertTrue(f.lock.verifyPin("739184".toCharArray(), UnlockPurpose.MANAGE))
            assertTrue(f.lock.configure(LockMode.OFF, LockTiming.IMMEDIATE, charArrayOf(), charArrayOf()))
            assertNull(LockConfiguration.decode(f.memory.bytes!!).verifier)
        }
    }
    @Test fun corruptOrUnavailableRecordsFailClosedWithoutResetting() = runBlocking {
        Fixture().use { f ->
            f.memory.bytes = byteArrayOf(0); f.ready()
            assertTrue(f.lock.state.value.unavailable); assertFalse(f.lock.state.value.canShowContent)
            assertArrayEquals(byteArrayOf(0), f.memory.bytes)
        }
        Fixture().use { f ->
            f.ready(); f.pin(); val original = f.memory.bytes!!.copyOf(); f.lock.stop(); f.lock.start(); f.memory.fail = true
            assertFalse(f.lock.verifyPin("824619".toCharArray()))
            assertTrue(f.lock.state.value.unavailable); assertFalse(f.lock.state.value.canShowContent)
            assertArrayEquals(original, f.memory.bytes)
        }
    }
    @Test fun pinCompletionFromBeforeBackgroundCannotUnlockResumedApp() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val memory = object : LockPersistence {
            var bytes = LockConfiguration(LockMode.PIN, verifier = PinVerifier.create("824619".toCharArray())).encode()
            override suspend fun read() = bytes.copyOf()
            override suspend fun write(bytes: ByteArray) { entered.complete(Unit); release.await(); this.bytes = bytes.copyOf() }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val lock = AppLockController(memory, scope, { 1000 }, { 1 })
        try {
            lock.start(); lock.initialize()
            val attempt = async { lock.verifyPin("824619".toCharArray()) }
            entered.await(); lock.stop(); lock.start(); release.complete(Unit)
            assertFalse(attempt.await()); assertFalse(lock.state.value.canShowContent)
        } finally { release.complete(Unit); scope.cancel() }
    }
}
