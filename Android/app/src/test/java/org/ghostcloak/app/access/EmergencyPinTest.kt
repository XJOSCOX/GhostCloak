package org.ghostcloak.app.access

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.ghostcloak.app.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class EmergencyPinTest {
    private class Memory : LockPersistence {
        var bytes: ByteArray? = null
        var fail=false
        override suspend fun read()=bytes?.copyOf()
        override suspend fun write(bytes: ByteArray) { check(!fail); this.bytes=bytes.copyOf() }
    }
    private class Journal : LocalOperationJournal {
        var value=LocalOperationState.NONE
        var fail=false
        override fun read()=value
        override fun write(state: LocalOperationState) { check(!fail); value=state }
    }
    private class Fixture(val memory: Memory=Memory(), enabled: Boolean=true) : AutoCloseable {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        var now=1000L
        val journal=Journal()
        val gate=LocalOperationGate(journal)
        var primitiveCalls=0
        var armCalls=0
        val lock=AppLockController(memory,scope,{now},{1},enabled,{
            armCalls++; gate.armAfterCredential()
        },{ verifier,pin -> primitiveCalls++; verifier.matches(pin) })
        suspend fun ready() { lock.start(); lock.initialize() }
        suspend fun normal(mode: LockMode=LockMode.PIN) {
            if(mode.biometric) assertTrue(lock.completeBiometric(lock.beginBiometric(UnlockPurpose.ENROLL)!!))
            assertTrue(lock.configure(mode,LockTiming.IMMEDIATE,"123456".toCharArray(),"123456".toCharArray()))
        }
        suspend fun manage() { assertTrue(lock.verifyPin("123456".toCharArray(),UnlockPurpose.MANAGE)) }
        suspend fun enroll() { ready(); normal(); manage(); assertTrue(set("654321")) }
        suspend fun set(pin: String, confirm: String=pin, current: String="", ack: Boolean=true)=
            lock.configureEmergency(pin.toCharArray(),confirm.toCharArray(),current.toCharArray(),ack)
        fun locked() { lock.stop(); lock.start(); now+=300_001 }
        override fun close() { scope.cancel() }
    }
    @Test fun disabledByDefaultAndRecentNormalAuthenticationRequired()=runBlocking {
        Fixture().use { f -> f.ready(); assertFalse(f.lock.state.value.emergencyEnabled)
            assertFalse(f.set("654321")); f.normal(); assertFalse(f.set("654321"))
            f.manage(); f.now+=60_001; assertFalse(f.set("654321")) }
    }
    @Test fun enrollmentRequiresAcknowledgementMinimumConfirmationAndDistinctPin()=runBlocking {
        Fixture().use { f -> f.ready(); f.normal(); f.manage()
            assertFalse(f.set("654321",ack=false)); assertFalse(f.set("54321"))
            assertFalse(f.set("654321",confirm="654322")); assertFalse(f.set("123456"))
            assertFalse(f.lock.state.value.emergencyEnabled); assertEquals(LocalOperationState.NONE,f.journal.value)
            assertTrue(f.set("654321")); assertTrue(f.lock.state.value.emergencyEnabled) }
    }
    @Test fun saltedVerifierAndRestartPreserveEnabledNotUnlockAndNeverEncodePlaintext()=runBlocking {
        val memory=Memory()
        Fixture(memory).use { f -> f.enroll()
            val config=LockConfiguration.decode(memory.bytes!!)
            assertEquals(600_000,config.emergency!!.iterations)
            assertFalse(config.verifier!!.salt.contentEquals(config.emergency.salt))
            assertFalse(memory.bytes!!.toString(Charsets.ISO_8859_1).contains("654321")) }
        Fixture(memory).use { f -> f.ready(); assertTrue(f.lock.state.value.emergencyEnabled)
            assertFalse(f.lock.state.value.canShowContent); assertFalse(f.lock.verifyPin("654321".toCharArray()))
            assertEquals(LocalOperationState.ARMED,f.journal.value) }
    }
    @Test fun normalPinUnlocksAndAllWrongNearPrefixAndSuffixPinsNeverArm()=runBlocking {
        Fixture().use { f -> f.enroll(); f.locked()
            for(pin in listOf("654320","654322","054321","6543210","54321","123455","000000")) {
                f.now+=300_001; assertFalse(f.lock.verifyPin(pin.toCharArray()))
                assertEquals(LocalOperationState.NONE,f.journal.value); assertEquals(0,f.armCalls)
                assertFalse(f.lock.state.value.canShowContent)
            }
            f.now+=300_001; assertTrue(f.lock.verifyPin("123456".toCharArray()))
            assertEquals(LocalOperationState.NONE,f.journal.value) }
    }
    @Test fun exactEmergencyNeverUnlocksAndOnlyDurableArmedAllowsSafeCoordinator()=runBlocking {
        Fixture().use { f -> f.enroll(); f.locked()
            assertFalse(f.lock.verifyPin("654321".toCharArray()))
            assertFalse(f.lock.state.value.canShowContent); assertEquals(LocalOperationState.ARMED,f.journal.value)
            var stops=0; val co=LocalOperationCoordinator(f.gate) {stops++}
            co.resume(); co.resume(); assertEquals(1,stops)
            assertEquals(LocalOperationState.KEY_DESTRUCTION_PENDING,f.journal.value) }
    }
    @Test fun emergencyOnlyArmsFromALockedUnlockPresentation()=runBlocking {
        Fixture().use { f -> f.enroll()
            assertFalse(f.lock.verifyPin("654321".toCharArray())); assertEquals(0,f.armCalls)
            f.locked(); assertFalse(f.lock.verifyPin("654321".toCharArray())); assertEquals(1,f.armCalls) }
    }
    @Test fun emergencyPinCannotAuthorizeSettingsOrBiometricEnrollment()=runBlocking {
        Fixture().use { f -> f.enroll()
            assertFalse(f.lock.verifyPin("654321".toCharArray(),UnlockPurpose.MANAGE))
            assertFalse(f.lock.verifyPin("654321".toCharArray(),UnlockPurpose.ENROLL))
            assertFalse(f.lock.state.value.manageGranted); assertEquals(0,f.armCalls) }
    }
    @Test fun biometricOnlyUnlocksNormallyNeverArms()=runBlocking {
        Fixture().use { f -> f.ready(); f.normal(LockMode.COMBINED); f.manage(); assertTrue(f.set("654321")); f.locked()
            assertTrue(f.lock.completeBiometric(f.lock.beginBiometric(UnlockPurpose.UNLOCK)!!))
            assertTrue(f.lock.state.value.canShowContent); assertEquals(LocalOperationState.NONE,f.journal.value) }
    }
    @Test fun normalPinEqualityAndRemovingPinModeRejected()=runBlocking {
        Fixture().use { f -> f.enroll(); f.manage()
            assertFalse(f.lock.configure(LockMode.PIN,LockTiming.IMMEDIATE,"654321".toCharArray(),"654321".toCharArray()))
            assertEquals("Choose a different PIN.",f.lock.state.value.message)
            assertFalse(f.lock.configure(LockMode.OFF,LockTiming.IMMEDIATE,charArrayOf(),charArrayOf()))
            assertTrue(f.lock.verifyPin("123456".toCharArray())); assertEquals(0,f.armCalls) }
    }
    @Test fun changeRequiresNormalAuthenticationAndCurrentEmergencyAndInvalidatesOld()=runBlocking {
        Fixture().use { f -> f.enroll(); assertFalse(f.set("987654",current="654321"))
            f.manage(); assertFalse(f.set("123456",current="654321"))
            assertFalse(f.set("987654",current="654320")); f.now+=300_001; f.manage()
            assertTrue(f.set("987654",current="654321")); f.locked()
            assertFalse(f.lock.verifyPin("654321".toCharArray())); assertEquals(LocalOperationState.NONE,f.journal.value)
            assertFalse(f.lock.verifyPin("987654".toCharArray())); assertEquals(LocalOperationState.ARMED,f.journal.value) }
    }
    @Test fun disableRequiresFreshNormalAuthExplicitConfirmationAndRemovesVerifier()=runBlocking {
        Fixture().use { f -> f.enroll(); assertFalse(f.lock.disableEmergency(true)); f.manage()
            assertFalse(f.lock.disableEmergency(false)); assertTrue(f.lock.disableEmergency(true))
            assertNull(LockConfiguration.decode(f.memory.bytes!!).emergency); f.locked()
            assertFalse(f.lock.verifyPin("654321".toCharArray())); assertEquals(0,f.armCalls)
            assertTrue(f.lock.verifyPin("123456".toCharArray())) }
    }
    @Test fun failedArmingFencesWithoutNormalUnlockOrCoordinatorHandoff()=runBlocking {
        Fixture().use { f -> f.enroll(); f.locked(); f.journal.fail=true
            var stops=0; val co=LocalOperationCoordinator(f.gate) {stops++}
            val watcher=launch(start=CoroutineStart.UNDISPATCHED) { f.gate.state.collect {
                if(it !in setOf(LocalOperationState.NONE,LocalOperationState.CORRUPT)) co.resume()
            } }
            assertFalse(f.lock.verifyPin("654321".toCharArray())); yield()
            assertEquals(LocalOperationState.CORRUPT,f.gate.state.value); assertFalse(f.lock.state.value.canShowContent)
            assertEquals(0,stops); watcher.cancelAndJoin() }
    }
    @Test fun writeFailureAndInvalidConfigurationPreserveStoredBytesFailClosed()=runBlocking {
        Fixture().use { f -> f.enroll(); f.manage(); val before=f.memory.bytes!!.copyOf(); f.memory.fail=true
            assertFalse(f.set("987654",current="654321")); assertArrayEquals(before,f.memory.bytes)
            assertFalse(f.lock.state.value.canShowContent); assertEquals(0,f.armCalls) }
    }
    @Test fun bothEquivalentKdfsRunForWrongNormalAndEmergencyNoSuccessEarlyReturn()=runBlocking {
        for(pin in listOf("000000","123456","654321")) Fixture().use { f ->
            f.enroll(); f.locked(); val before=f.primitiveCalls
            f.lock.verifyPin(pin.toCharArray()); assertEquals(2,f.primitiveCalls-before)
        }
    }
    @Test fun sharedThrottleCannotBeBypassedWithEmergencyOrSettingsAttempts()=runBlocking {
        Fixture().use { f -> f.enroll(); f.locked()
            repeat(3) { assertFalse(f.lock.verifyPin("000000".toCharArray())) }
            val before=f.primitiveCalls; assertFalse(f.lock.verifyPin("654321".toCharArray()))
            assertEquals(before,f.primitiveCalls); assertEquals(0,f.armCalls)
            f.now+=5001; assertFalse(f.lock.verifyPin("654321".toCharArray())); assertEquals(1,f.armCalls) }
    }
    @Test fun mutableCredentialInputsAreClearedAfterEnrollChangeAndVerify()=runBlocking {
        Fixture().use { f -> f.ready(); f.normal(); f.manage()
            val pin="654321".toCharArray(); val confirm=pin.copyOf(); val current=charArrayOf('1')
            assertTrue(f.lock.configureEmergency(pin,confirm,current,true))
            listOf(pin,confirm,current).forEach { assertTrue(it.all { c->c=='\u0000' }) }
            f.locked(); val attempt="123456".toCharArray(); assertTrue(f.lock.verifyPin(attempt)); assertTrue(attempt.all {it=='\u0000'}) }
    }
    @Test fun releaseFeatureFlagNeverAllowsTriggerOrEnrollmentEvenPersistedFromDebug()=runBlocking {
        assertFalse(BuildConfig.EMERGENCY_WIPE_DESTRUCTIVE_READY)
        assertEquals(BuildConfig.DEBUG,BuildConfig.EMERGENCY_PIN_ARMING_ENABLED)
        val memory=Memory(); Fixture(memory).use {it.enroll()}
        Fixture(memory,enabled=false).use { f -> f.ready()
            assertFalse(f.lock.verifyPin("654321".toCharArray())); assertEquals(0,f.armCalls)
            assertTrue(f.lock.verifyPin("123456".toCharArray())); f.manage()
            assertFalse(f.set("987654",current="654321")); assertFalse(f.lock.disableEmergency(true)) }
    }
    @Test fun versionOneRecordsUpgradeWithoutLosingPinThrottleOrIdentityConfiguration() {
        val verifier=PinVerifier.create("123456".toCharArray())
        val bytes=ByteArrayOutputStream().also { DataOutputStream(it).use { out ->
            out.writeInt(1); out.writeInt(LockMode.PIN.ordinal); out.writeInt(LockTiming.SECONDS_30.ordinal)
            out.writeBoolean(true); out.writeInt(verifier.iterations); out.write(verifier.salt); out.write(verifier.value)
            out.writeInt(4); out.writeLong(10000); out.writeLong(10000); out.writeInt(7)
        } }.toByteArray()
        val decoded=LockConfiguration.decode(bytes)
        assertNull(decoded.emergency); assertEquals(4,decoded.failures); assertEquals(7,decoded.boot)
        assertTrue(decoded.verifier!!.matches("123456".toCharArray()))
        assertEquals(decoded,LockConfiguration.decode(decoded.encode()).copy(verifier=decoded.verifier))
    }
    @Test fun processDeathAfterArmedResumesWithoutCredentialOrNormalStateLoading()=runBlocking {
        Fixture().use { f -> f.enroll(); f.locked(); f.lock.verifyPin("654321".toCharArray())
            val restart=LocalOperationGate(f.journal); assertTrue(restart.blocked)
            try { restart.access {fail("opened")}; fail("admitted") } catch(_: LocalOperationBlocked) {}
            LocalOperationCoordinator(restart) {}.resume(); assertEquals(LocalOperationState.KEY_DESTRUCTION_PENDING,f.journal.value) }
    }
}
