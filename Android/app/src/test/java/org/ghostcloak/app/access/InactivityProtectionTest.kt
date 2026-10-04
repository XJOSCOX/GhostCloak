package org.ghostcloak.app.access

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.*

class InactivityProtectionTest {
    private class Memory : InactivityStore {
        var record: InactivityRecord? = null
        var failWrite = false
        override fun read() = record
        override fun write(record: InactivityRecord) { if(failWrite) error("injected"); this.record=record }
        override fun clear() { record=null }
    }
    private class Fixture(val memory: Memory = Memory()) {
        var now = InactivityObservation(4, 1000L, 1_700_000_000_000L)
        var arms = 0
        val policy = InactivityProtection(memory, { now }, { arms++ })
        fun enable(period: InactivityPeriod = InactivityPeriod.DAYS_30) {
            assertEquals(InactivityAccess.DISABLED,policy.check())
            assertTrue(policy.configure(period))
        }
    }
    @Test fun sameBootBoundaryUsesMonotonicClockAndArmsOnce() {
        val f=Fixture(); f.enable()
        val anchor=f.now.elapsed
        f.now=f.now.copy(elapsed=anchor+InactivityPeriod.DAYS_30.millis-1,
            wall=f.now.wall-100_000_000_000L)
        assertEquals(InactivityAccess.VALID,f.policy.check())
        f.now=f.now.copy(elapsed=anchor+InactivityPeriod.DAYS_30.millis)
        assertEquals(InactivityAccess.EXPIRED,f.policy.check())
        assertEquals(InactivityAccess.EXPIRED,f.policy.check())
        assertEquals(1,f.arms)
        assertFalse(f.policy.authenticated())
    }
    @Test fun rebootAlwaysRequiresAuthenticationAndNeverUsesWallForDestruction() {
        for(wall in listOf(1L,1_700_000_000_000L,4_000_000_000_000L)) {
            val f=Fixture();f.enable()
            f.now=InactivityObservation(5, 10L, wall)
            val recreated=InactivityProtection(f.memory,{f.now},{f.arms++})
            assertEquals(InactivityAccess.TIME_UNCERTAIN,recreated.check())
            assertFalse(recreated.normalAccessAllowed)
            assertEquals(0,f.arms)
            assertTrue(recreated.authenticated())
            assertTrue(recreated.normalAccessAllowed)
            assertEquals(5,f.memory.record!!.boot)
        }
    }
    @Test fun processRecreationSameBootPreservesDeadlineAndBackgroundDoesNotRefresh() {
        val f=Fixture();f.enable(InactivityPeriod.DAYS_7)
        val anchor=f.memory.record!!.authenticatedElapsed
        f.now=f.now.copy(elapsed=anchor+InactivityPeriod.DAYS_7.millis-1)
        val recreated=InactivityProtection(f.memory,{f.now},{f.arms++})
        repeat(10) {assertEquals(InactivityAccess.VALID,recreated.check())}
        assertEquals(anchor,f.memory.record!!.authenticatedElapsed)
        f.now=f.now.copy(elapsed=anchor+InactivityPeriod.DAYS_7.millis)
        assertEquals(InactivityAccess.EXPIRED,recreated.check())
        assertEquals(1,f.arms)
    }
    @Test fun writeFailureRemainsClosedAndNoAuthGrant() {
        val f=Fixture();f.enable()
        f.memory.failWrite=true
        assertFalse(f.policy.authenticated())
        assertEquals(InactivityAccess.UNAVAILABLE,f.policy.check())
        assertFalse(f.policy.normalAccessAllowed)
        f.memory.failWrite=false
        assertEquals(InactivityAccess.UNAVAILABLE,f.policy.check())
    }
    @Test fun periodChangeDoesNotResetAnchor() {
        val f=Fixture();f.enable(InactivityPeriod.DAYS_90)
        val anchor=f.memory.record!!.authenticatedElapsed
        f.now=f.now.copy(elapsed=anchor+InactivityPeriod.DAYS_7.millis)
        assertTrue(f.policy.configure(InactivityPeriod.DAYS_7))
        assertEquals(anchor,f.memory.record!!.authenticatedElapsed)
        assertEquals(InactivityAccess.EXPIRED,f.policy.state.value)
    }
    @Test fun pinUnlockRefreshesBeforeGrantButWrongPinAndManagementDoNot() = runBlocking {
        val f=Fixture()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val memory=object: LockPersistence {
            var bytes: ByteArray?=null
            override suspend fun read()=bytes?.copyOf()
            override suspend fun write(bytes: ByteArray) {this.bytes=bytes.copyOf()}
        }
        val controller=AppLockController(memory,scope,{f.now.elapsed},{f.now.boot},
            inactivityAuthenticated={f.policy.authenticated()},inactivityEnabled={f.policy.enabled})
        try {
            controller.start();controller.initialize()
            assertTrue(controller.configure(LockMode.PIN,LockTiming.IMMEDIATE,"824619".toCharArray(),"824619".toCharArray()))
            f.enable()
            controller.stop();controller.start()
            f.now=f.now.copy(elapsed=f.now.elapsed+60_000)
            val anchor=f.memory.record!!.authenticatedElapsed
            assertFalse(controller.verifyPin("824618".toCharArray()))
            assertEquals(anchor,f.memory.record!!.authenticatedElapsed)
            assertTrue(controller.verifyPin("824619".toCharArray()))
            assertEquals(f.now.elapsed,f.memory.record!!.authenticatedElapsed)
            f.now=f.now.copy(elapsed=f.now.elapsed+60_000)
            assertTrue(controller.verifyPin("824619".toCharArray(),UnlockPurpose.MANAGE))
            assertNotEquals(f.now.elapsed,f.memory.record!!.authenticatedElapsed)
        } finally {scope.cancel()}
    }
    @Test fun crossBootPinCannotGrantIfAnchorWriteFails() = runBlocking {
        val f=Fixture()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val memory=object: LockPersistence {
            var bytes: ByteArray?=null
            override suspend fun read()=bytes?.copyOf()
            override suspend fun write(bytes: ByteArray) {this.bytes=bytes.copyOf()}
        }
        val controller=AppLockController(memory,scope,{f.now.elapsed},{f.now.boot},
            inactivityAuthenticated={f.policy.authenticated()},inactivityEnabled={f.policy.enabled})
        try {
            controller.start();controller.initialize()
            assertTrue(controller.configure(LockMode.PIN,LockTiming.IMMEDIATE,"824619".toCharArray(),"824619".toCharArray()))
            f.enable()
            controller.stop();controller.start()
            f.now=InactivityObservation(5,100L,f.now.wall+10_000)
            assertEquals(InactivityAccess.TIME_UNCERTAIN,f.policy.check())
            f.memory.failWrite=true
            assertFalse(controller.verifyPin("824619".toCharArray()))
            assertFalse(controller.state.value.canShowContent)
            assertEquals(InactivityAccess.UNAVAILABLE,f.policy.state.value)
        } finally {scope.cancel()}
    }
    @Test fun administrationNeedsFreshNormalProofAcknowledgementAndBlocksAppLockOff() = runBlocking {
        val f=Fixture()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val memory=object: LockPersistence {
            var bytes: ByteArray?=null
            override suspend fun read()=bytes?.copyOf()
            override suspend fun write(bytes: ByteArray) {this.bytes=bytes.copyOf()}
        }
        val controller=AppLockController(memory,scope,{f.now.elapsed},{f.now.boot},
            inactivityAuthenticated={f.policy.authenticated()},inactivityEnabled={f.policy.enabled},
            inactivityPeriod={f.policy.period},configureInactivityStore={f.policy.configure(it)})
        try {
            controller.start();controller.initialize()
            assertTrue(controller.configure(LockMode.PIN,LockTiming.IMMEDIATE,"824619".toCharArray(),"824619".toCharArray()))
            assertFalse(controller.configureInactivity(InactivityPeriod.DAYS_7,true,false))
            assertTrue(controller.verifyPin("824619".toCharArray(),UnlockPurpose.MANAGE))
            assertFalse(controller.configureInactivity(InactivityPeriod.DAYS_7,false,false))
            assertTrue(controller.configureInactivity(InactivityPeriod.DAYS_7,true,false))
            assertEquals(InactivityPeriod.DAYS_7,f.policy.period)
            assertTrue(controller.verifyPin("824619".toCharArray(),UnlockPurpose.MANAGE))
            assertFalse(controller.configure(LockMode.OFF,LockTiming.IMMEDIATE,charArrayOf(),charArrayOf()))
            assertFalse(controller.configureInactivity(InactivityPeriod.OFF,false,false))
            assertTrue(controller.configureInactivity(InactivityPeriod.OFF,false,true))
            assertEquals(InactivityAccess.DISABLED,f.policy.state.value)
        } finally {scope.cancel()}
    }
    @Test fun extendingOrDisablingWithSafeExitRequiresCurrentSafeExitPin() = runBlocking {
        val f=Fixture()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val memory=object: LockPersistence {
            var bytes: ByteArray?=null
            override suspend fun read()=bytes?.copyOf()
            override suspend fun write(bytes: ByteArray) {this.bytes=bytes.copyOf()}
        }
        val controller=AppLockController(memory,scope,{f.now.elapsed},{f.now.boot},true,{},
            inactivityAuthenticated={f.policy.authenticated()},inactivityEnabled={f.policy.enabled},
            inactivityPeriod={f.policy.period},configureInactivityStore={f.policy.configure(it)})
        try {
            controller.start();controller.initialize()
            assertTrue(controller.configure(LockMode.PIN,LockTiming.IMMEDIATE,"824619".toCharArray(),"824619".toCharArray()))
            assertTrue(controller.verifyPin("824619".toCharArray(),UnlockPurpose.MANAGE))
            assertTrue(controller.configureEmergency("654321".toCharArray(),"654321".toCharArray(),charArrayOf(),true))
            assertTrue(controller.verifyPin("824619".toCharArray(),UnlockPurpose.MANAGE))
            assertTrue(controller.configureInactivity(InactivityPeriod.DAYS_7,true,false))
            assertTrue(controller.verifyPin("824619".toCharArray(),UnlockPurpose.MANAGE))
            assertFalse(controller.configureInactivity(InactivityPeriod.DAYS_90,false,false))
            assertEquals(InactivityPeriod.DAYS_7,f.policy.period)
            assertTrue(controller.verifyEmergencyAdministration("654321".toCharArray()))
            assertTrue(controller.configureInactivity(InactivityPeriod.DAYS_90,false,false))
            assertTrue(controller.verifyPin("824619".toCharArray(),UnlockPurpose.MANAGE))
            assertFalse(controller.configureInactivity(InactivityPeriod.OFF,false,true))
            assertTrue(controller.verifyEmergencyAdministration("654321".toCharArray()))
            assertTrue(controller.configureInactivity(InactivityPeriod.OFF,false,true))
        } finally {scope.cancel()}
    }
}
