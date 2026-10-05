package org.ghostcloak.testing

import kotlinx.coroutines.runBlocking
import org.ghostcloak.backend.*
import org.ghostcloak.identity.RandomIdentifiers
import org.ghostcloak.protocol.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors

class AbuseResistanceTest {
    @Test fun recoveryWindowBoundaryDoesNotResetWithinTheSameWindowAndOtherMaterialIsIndependent() {
        val limiter = DevelopmentRateLimiter()
        val last = 1_019_999L // final millisecond of one fixed minute
        repeat(10) { assertTrue(limiter.allow(ServerOperation.RECOVER_ISSUE, "D", last)) }
        assertFalse(limiter.allow(ServerOperation.RECOVER_ISSUE, "D", last))
        assertTrue(limiter.allow(ServerOperation.RECOVER_ISSUE, "E", last))
        val next = 1_020_000L
        repeat(10) { assertTrue(limiter.allow(ServerOperation.RECOVER_ISSUE, "D", next)) }
        assertFalse(limiter.allow(ServerOperation.RECOVER_ISSUE, "D", next))
        assertTrue(limiter.allow(ServerOperation.RECOVER_ISSUE, "E", next))
    }

    @Test fun discoveryDoesNotConsumeAndAllocationRetryReturnsTheSameBundle() = runBlocking {
        val f=PrekeyFixture(); f.start()
        val id=f.state.ghostCloakId()
        repeat(120) {
            assertEquals(f.registration.deviceId,f.service.execute(ApiRequest.Lookup(id),f.state.read()).discovery!!.deviceId)
        }
        assertEquals(16,f.inventory().available)
        val request=ApiRequest.Allocate(id,RandomIdentifiers.create())
        val first=f.service.execute(request,f.state.read()).directory!!.bundle.preKeyId
        repeat(10) {assertEquals(first,f.service.execute(request,f.state.read()).directory!!.bundle.preKeyId)}
        assertEquals(15,f.inventory().available)
        val second=f.service.execute(ApiRequest.Allocate(id,RandomIdentifiers.create()),f.state.read()).directory!!.bundle.preKeyId
        assertNotEquals(first,second)
        assertEquals(14,f.inventory().available)
        try {f.service.execute(ApiRequest.Allocate("7K4M9Q2FX8DR",request.allocationId),f.state.read());fail()}
        catch(e:ApiFailure){assertEquals(409,e.status)}
    }

    @Test fun materialScopedChallengeBudgetDoesNotStarveAnotherCaller() {
        val rate=DevelopmentRateLimiter()
        val now=1_000_000L
        repeat(10) {assertTrue(rate.allow(ServerOperation.RECOVER_ISSUE,"attacker-material",now))}
        assertFalse(rate.allow(ServerOperation.RECOVER_ISSUE,"attacker-material",now))
        assertTrue(rate.allow(ServerOperation.RECOVER_ISSUE,"other-material",now))
        repeat(600) {assertTrue(rate.allow(ServerOperation.CHALLENGE_GLOBAL,"all",now))}
        assertFalse(rate.allow(ServerOperation.CHALLENGE_GLOBAL,"all",now))
    }
    @Test fun attackerExhaustsOwnRecoveryBudgetWhileAnotherKeyStillReceivesChallenge() {
        val db=MemoryBackendDatabase();val events=mutableListOf<AbuseEvent>()
        val service=MailboxService(db,rate=DevelopmentRateLimiter(),abuseLog={events.add(it)})
        val device=RandomIdentifiers.create()
        val attacker=RecoveryCredential().pair.public.encoded
        val other=RecoveryCredential().pair.public.encoded
        repeat(10) {assertNotNull(service.execute(ApiRequest.RecoveryIssue(attacker,device)).challenge)}
        try {service.execute(ApiRequest.RecoveryIssue(attacker,device));fail()}
        catch(e:ApiFailure){assertEquals(429,e.status);assertEquals("rate_limited",e.code)}
        assertEquals(listOf(AbuseEvent.RECOVERY_RATE_LIMITED),events)
        assertNotNull(service.execute(ApiRequest.RecoveryIssue(other,device)).challenge)
        assertEquals(11,db.transaction {db.challenges.size()})
    }
    @Test fun requesterTargetCapAndGenericUnavailablePreventRapidDepletion()=runBlocking {
        val f=PrekeyFixture();f.start();val id=f.state.ghostCloakId()
        repeat(4) {f.service.execute(ApiRequest.Allocate(id,RandomIdentifiers.create()),f.state.read())}
        assertEquals(12,f.inventory().available)
        try {f.service.execute(ApiRequest.Allocate(id,RandomIdentifiers.create()),f.state.read());fail()}
        catch(e:ApiFailure){assertEquals(429,e.status)}
        val unknown=try {f.service.execute(ApiRequest.Lookup("7K4M9Q2FX8DR"),f.state.read());fail();""}
            catch(e:ApiFailure){"${e.status}:${e.code}"}
        f.db.transaction {
            val row=f.db.prekeys.get(f.registration.deviceId)!!
            f.db.prekeys.put(f.registration.deviceId,PrekeyRow(row.deviceId,emptyList(),row.usedEc,row.usedPq,row.signed))
        }
        val exhausted=try {f.service.execute(ApiRequest.Lookup(id),f.state.read());fail();""}
            catch(e:ApiFailure){"${e.status}:${e.code}"}
        assertEquals("404:contact_unavailable",unknown)
        assertEquals(unknown,exhausted)
    }
    @Test fun concurrentRetryWithOneIdConsumesExactlyOneBundle()=runBlocking {
        val f=PrekeyFixture();f.start();val request=ApiRequest.Allocate(f.state.ghostCloakId(),RandomIdentifiers.create())
        val workers=Executors.newFixedThreadPool(2)
        try {
            val ids=(1..2).map {workers.submit<Int> {f.service.execute(request,f.state.read()).directory!!.bundle.preKeyId}}.map {it.get()}
            assertEquals(ids[0],ids[1]);assertEquals(15,f.inventory().available)
        } finally {workers.shutdownNow()}
    }
}
