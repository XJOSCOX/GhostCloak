package org.ghostcloak.app.access

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ProtectedSettingsTest {
    private class Memory : LockPersistence {
        var bytes: ByteArray?=null
        override suspend fun read()=bytes?.copyOf()
        override suspend fun write(bytes: ByteArray) {this.bytes=bytes.copyOf()}
    }
    private class Fixture(val memory: Memory=Memory()) : AutoCloseable {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        var now=1000L; var arms=0
        val lock=AppLockController(memory,scope,{now},{1},true,{arms++})
        suspend fun ready() {lock.start(); lock.initialize()}
        suspend fun normal(mode: LockMode=LockMode.PIN,timing: LockTiming=LockTiming.IMMEDIATE) {
            ready()
            if(mode.biometric) lock.completeBiometric(lock.beginBiometric(UnlockPurpose.ENROLL)!!)
            assertTrue(lock.configure(mode,timing,"123456".toCharArray(),"123456".toCharArray()))
        }
        suspend fun manage() {assertTrue(lock.verifyPin("123456".toCharArray(),UnlockPurpose.MANAGE))}
        suspend fun enable(mode: LockMode=LockMode.PIN) {
            normal(mode); manage()
            assertTrue(lock.configureEmergency("654321".toCharArray(),"654321".toCharArray(),charArrayOf(),true))
        }
        suspend fun settings() {assertTrue(lock.verifyPin("123456".toCharArray(),UnlockPurpose.SETTINGS))}
        override fun close() {scope.cancel()}
    }
    @Test fun unlockedAppStillRequiresFreshSettingsAuthAndSafeExitPinNeverGrantsOrArms()=runBlocking {
        Fixture().use {f->f.enable(); assertTrue(f.lock.state.value.canShowContent); assertFalse(f.lock.state.value.settingsGranted)
            assertFalse(f.lock.verifyPin("654321".toCharArray(),UnlockPurpose.SETTINGS)); assertEquals(0,f.arms)
            assertFalse(f.lock.verifyPin("123455".toCharArray(),UnlockPurpose.SETTINGS)); assertFalse(f.lock.state.value.settingsGranted)
            f.settings(); assertTrue(f.lock.state.value.settingsGranted); assertFalse(f.lock.state.value.manageGranted)}
    }
    @Test fun appLockOffDoesNotInventSettingsAuthentication()=runBlocking {
        Fixture().use {f->f.ready(); assertTrue(f.lock.state.value.settingsGranted)
            assertFalse(f.lock.configureEmergency("654321".toCharArray(),"654321".toCharArray(),charArrayOf(),true))}
    }
    @Test fun settingsSessionHasFixedThreeMinuteAgeAndRefreshDoesNotExtendIt()=runBlocking {
        Fixture().use {f->f.normal(); f.settings(); f.now+=179_999; f.lock.refreshAuthorization()
            assertTrue(f.lock.state.value.settingsGranted); f.now++; f.lock.refreshAuthorization()
            assertFalse(f.lock.state.value.settingsGranted); assertTrue(f.lock.state.value.canShowContent)}
    }
    @Test fun leavingSettingsRevokesImmediatelyIncludingSensitiveGrants()=runBlocking {
        Fixture().use {f->f.normal(); f.settings(); f.manage(); f.lock.leaveSettings()
            assertFalse(f.lock.state.value.settingsGranted); assertFalse(f.lock.state.value.manageGranted)}
    }
    @Test fun backgroundInvalidatesSettingsEvenIfGlobalDelayedLockHasNotExpired()=runBlocking {
        Fixture().use {f->f.normal(timing=LockTiming.MINUTES_5); f.settings(); f.lock.stop(); f.now++; f.lock.start()
            assertTrue(f.lock.state.value.canShowContent); assertFalse(f.lock.state.value.settingsGranted)}
    }
    @Test fun processRecreationRestoresCredentialsNeverSettingsAuthorization()=runBlocking {
        val memory=Memory(); Fixture(memory).use {it.normal(); it.settings()}
        Fixture(memory).use {f->f.ready(); assertFalse(f.lock.state.value.canShowContent)
            assertTrue(f.lock.verifyPin("123456".toCharArray())); assertFalse(f.lock.state.value.settingsGranted)}
    }
    @Test fun settingsBiometricDoesNotAuthorizeSensitiveActionsOrPinEnrollment()=runBlocking {
        Fixture().use {f->f.normal(LockMode.COMBINED)
            assertTrue(f.lock.completeBiometric(f.lock.beginBiometric(UnlockPurpose.SETTINGS)!!))
            assertTrue(f.lock.state.value.settingsGranted); assertFalse(f.lock.state.value.manageGranted)
            assertFalse(f.lock.state.value.biometricConfirmed)
            assertFalse(f.lock.configure(LockMode.OFF,LockTiming.IMMEDIATE,charArrayOf(),charArrayOf()))}
    }
    @Test fun settingsSessionAloneCannotEnableChangeDisableOrWeakenLock()=runBlocking {
        Fixture().use {f->f.enable(); f.settings()
            assertFalse(f.lock.configureEmergency("987654".toCharArray(),"987654".toCharArray(),"654321".toCharArray(),true))
            assertFalse(f.lock.disableEmergency("654321".toCharArray(),true))
            assertFalse(f.lock.configure(LockMode.OFF,LockTiming.IMMEDIATE,charArrayOf(),charArrayOf()))}
    }
    @Test fun disableRequiresBothCredentialsAndConfirmationNoNormalOnlyRecovery()=runBlocking {
        Fixture().use {f->f.enable(); assertFalse(f.lock.disableEmergency("654321".toCharArray(),true))
            f.manage(); assertFalse(f.lock.disableEmergency(charArrayOf(),true))
            assertFalse(f.lock.disableEmergency("654320".toCharArray(),true)); f.now+=300_001; f.manage()
            assertFalse(f.lock.disableEmergency("654321".toCharArray(),false))
            assertTrue(f.lock.disableEmergency("654321".toCharArray(),true)); assertFalse(f.lock.state.value.emergencyEnabled)
            assertEquals(0,f.arms)}
    }
    @Test fun biometricNormalAuthenticationStillRequiresCurrentSafeExitSecret()=runBlocking {
        Fixture().use {f->f.enable(LockMode.COMBINED)
            assertTrue(f.lock.completeBiometric(f.lock.beginBiometric(UnlockPurpose.MANAGE)!!))
            assertFalse(f.lock.disableEmergency(charArrayOf(),true))
            assertFalse(f.lock.configureEmergency("987654".toCharArray(),"987654".toCharArray(),charArrayOf(),true))
            f.now+=300_001; assertTrue(f.lock.completeBiometric(f.lock.beginBiometric(UnlockPurpose.MANAGE)!!))
            assertTrue(f.lock.disableEmergency("654321".toCharArray(),true)); assertEquals(0,f.arms)}
    }
    @Test fun twoStepAdminGrantIsShortLivedActionScopedAndConsumedByChange()=runBlocking {
        Fixture().use {f->f.enable(); f.manage(); assertTrue(f.lock.verifyEmergencyAdministration("654321".toCharArray()))
            assertTrue(f.lock.state.value.emergencyAdministrationGranted)
            assertTrue(f.lock.configureEmergency("987654".toCharArray(),"987654".toCharArray(),charArrayOf(),true))
            assertFalse(f.lock.state.value.emergencyAdministrationGranted); f.manage()
            assertFalse(f.lock.verifyEmergencyAdministration("654321".toCharArray()))
            assertTrue(f.lock.verifyEmergencyAdministration("987654".toCharArray())); f.now+=60_001; f.lock.refreshAuthorization()
            assertFalse(f.lock.state.value.emergencyAdministrationGranted)
            assertFalse(f.lock.disableEmergency(charArrayOf(),true))}
    }
    @Test fun staleAdminProofCannotSurviveFreshNormalGrantOrBackground()=runBlocking {
        Fixture().use {f->f.enable(); f.manage(); f.lock.verifyEmergencyAdministration("654321".toCharArray())
            f.manage(); assertFalse(f.lock.state.value.emergencyAdministrationGranted)
            f.lock.verifyEmergencyAdministration("654321".toCharArray()); f.lock.stop(); f.lock.start()
            assertFalse(f.lock.state.value.emergencyAdministrationGranted); assertFalse(f.lock.disableEmergency(charArrayOf(),true))}
    }
    @Test fun appLockDisableBlockedUntilSafeExitDisabledAndEqualityStillRejected()=runBlocking {
        Fixture().use {f->f.enable(); f.manage()
            assertFalse(f.lock.configure(LockMode.OFF,LockTiming.IMMEDIATE,charArrayOf(),charArrayOf()))
            assertEquals("Disable Safe Exit before turning off App Lock.",f.lock.state.value.message)
            assertFalse(f.lock.configure(LockMode.PIN,LockTiming.IMMEDIATE,"654321".toCharArray(),"654321".toCharArray()))
            assertEquals("Choose a different PIN.",f.lock.state.value.message)
            assertTrue(f.lock.disableEmergency("654321".toCharArray(),true)); f.manage()
            assertTrue(f.lock.configure(LockMode.OFF,LockTiming.IMMEDIATE,charArrayOf(),charArrayOf()))}
    }
    @Test fun canceledRouteCannotAcceptLatePinOrBiometricCompletion()=runBlocking {
        for(purpose in listOf(UnlockPurpose.SETTINGS,UnlockPurpose.MANAGE)) {
            val entered=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>()
            val memory=object : LockPersistence {
                var bytes: ByteArray?=null; var paused=false
                override suspend fun read()=bytes?.copyOf()
                override suspend fun write(bytes: ByteArray) {
                    if(paused) {entered.complete(Unit); release.await()}
                    this.bytes=bytes.copyOf()
                }
            }
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
            val lock=AppLockController(memory,scope,{1000},{1})
            try {
                lock.start(); lock.initialize(); lock.configure(LockMode.PIN,LockTiming.IMMEDIATE,"123456".toCharArray(),"123456".toCharArray())
                memory.paused=true
                val attempt=async {lock.verifyPin("123456".toCharArray(),purpose)}
                entered.await(); lock.leaveSettings(); release.complete(Unit)
                assertFalse(attempt.await()); assertFalse(lock.state.value.settingsGranted); assertFalse(lock.state.value.manageGranted)
            } finally {release.complete(Unit); scope.cancel()}
        }
        Fixture().use {f->f.normal(LockMode.COMBINED)
            val id=f.lock.beginBiometric(UnlockPurpose.SETTINGS)!!; f.lock.leaveSettings()
            assertFalse(f.lock.completeBiometric(id)); assertFalse(f.lock.state.value.settingsGranted)}
    }
    @Test fun lateBiometricPersistenceCannotBorrowNewSettingsChallengeEpoch()=runBlocking {
        val entered=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>()
        var paused=false; var bytes: ByteArray?=null
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val lock=AppLockController(object : LockPersistence {
            override suspend fun read()=bytes?.copyOf()
            override suspend fun write(value: ByteArray) {
                if(paused) {entered.complete(Unit); release.await()}
                bytes=value.copyOf()
            }
        },scope,{1000},{1})
        try {
            lock.start(); lock.initialize()
            assertTrue(lock.completeBiometric(lock.beginBiometric(UnlockPurpose.ENROLL)!!))
            assertTrue(lock.configure(LockMode.COMBINED,LockTiming.IMMEDIATE,"123456".toCharArray(),"123456".toCharArray()))
            assertFalse(lock.verifyPin("123455".toCharArray(),UnlockPurpose.SETTINGS))
            val id=lock.beginBiometric(UnlockPurpose.SETTINGS)!!
            paused=true
            val completion=async {lock.completeBiometric(id)}
            entered.await(); lock.leaveSettings()
            val next=lock.beginBiometric(UnlockPurpose.SETTINGS)!!
            release.complete(Unit)
            assertFalse(completion.await()); assertFalse(lock.state.value.settingsGranted)
            paused=false
            assertTrue(lock.completeBiometric(next)); assertTrue(lock.state.value.settingsGranted)
        } finally {release.complete(Unit); scope.cancel()}
    }
    @Test fun administrationUsesSharedThrottleAndClearsCurrentPinBuffers()=runBlocking {
        Fixture().use {f->f.enable(); f.manage()
            repeat(3) {val wrong="654320".toCharArray(); assertFalse(f.lock.verifyEmergencyAdministration(wrong)); assertTrue(wrong.all {it=='\u0000'})}
            assertFalse(f.lock.verifyEmergencyAdministration("654321".toCharArray())); f.now+=5001
            val current="654321".toCharArray(); assertTrue(f.lock.verifyEmergencyAdministration(current)); assertTrue(current.all {it=='\u0000'})
            val input=charArrayOf(); assertTrue(f.lock.disableEmergency(input,true)); assertEquals(0,f.arms)}
    }
}
