package org.ghostcloak.testing

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.ghostcloak.crypto.*
import org.ghostcloak.identity.*
import org.ghostcloak.protocol.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class HardeningTest {
    private class Fixture {
        val aStore = MemoryRecords(); val bStore = MemoryRecords()
        var a = SignalProtocolEngine(aStore); var b = SignalProtocolEngine(bStore)
        lateinit var alice: DeviceIdentity; lateinit var bob: DeviceIdentity
        suspend fun setup() {
            alice = a.createIdentity("Alice"); bob = b.createIdentity("Bob")
            a.establishSession(b.publicBundle())
        }
        suspend fun packet(text: String = "fixture") = a.encrypt(bob.deviceId, text.encodeToByteArray())
        suspend fun roundTrip() { b.decrypt(packet()).fill(0); a.decrypt(b.encrypt(alice.deviceId, byteArrayOf(1))).fill(0) }
        suspend fun replacement(): Pair<SignalProtocolEngine, RemoteKeyBundle> {
            val records = MemoryRecords()
            val engine = SignalProtocolEngine(records)
            engine.createIdentity("Bob")
            // Test-only simulation of a hostile directory binding a replacement key to Bob's device ID.
            records.write("local/device", bob.deviceId.encodeToByteArray())
            return engine to engine.publicBundle()
        }
    }
    private suspend fun reject(error: CryptoError? = null, block: suspend () -> Unit): CryptoFailure {
        try { block(); fail("Security operation unexpectedly succeeded") }
        catch (e: CryptoFailure) { if (error != null) assertEquals(error, e.error); return e }
        error("Unreachable")
    }
    @Test fun firstContactIsUnverifiedAndVerifiedStateSurvivesRestart() = runBlocking<Unit> {
        val p = Fixture().apply { setup() }
        assertEquals(IdentityTrustState.UNVERIFIED, p.a.getRemoteIdentityStatus(p.bob.deviceId)!!.trustState)
        val code = p.a.getRemoteFingerprint(p.bob.deviceId)
        reject(CryptoError.VerificationFailed) { p.a.verifyRemoteIdentity(p.bob.deviceId, "wrong") }
        p.a.verifyRemoteIdentity(p.bob.deviceId, code)
        p.a = SignalProtocolEngine(p.aStore)
        assertEquals(IdentityTrustState.VERIFIED, p.a.getRemoteIdentityStatus(p.bob.deviceId)!!.trustState)
        assertEquals(code, p.a.getRemoteFingerprint(p.bob.deviceId))
    }
    @Test fun verifiedReplacementPersistsHighPriorityChangeAndCannotAutoRegainTrust() = runBlocking<Unit> {
        val p = Fixture().apply { setup() }; val code = p.a.getRemoteFingerprint(p.bob.deviceId)
        p.a.verifyRemoteIdentity(p.bob.deviceId, code)
        val (_, replacement) = p.replacement()
        reject(CryptoError.IdentityChanged) { p.a.establishSession(replacement) }
        val event = p.a.events.first() as SecurityEvent.RemoteIdentityChanged
        assertEquals(SecurityPriority.HIGH, event.priority)
        p.a = SignalProtocolEngine(p.aStore)
        assertEquals(RemoteIdentityStatus(IdentityTrustState.CHANGED, IdentityTrustState.VERIFIED), p.a.getRemoteIdentityStatus(p.bob.deviceId))
        reject(CryptoError.IdentityChanged) { p.a.establishSession(replacement) }
        reject(CryptoError.IdentityChanged) { p.a.establishSession(p.b.publicBundle()) }
        reject(CryptoError.IdentityChanged) { p.a.verifyRemoteIdentity(p.bob.deviceId, code) }
        reject(CryptoError.IdentityChanged) { p.packet() }
        assertEquals(code, p.a.getRemoteFingerprint(p.bob.deviceId))
    }
    @Test fun unverifiedReplacementNeedsExactExplicitApprovalAndFreshSession() = runBlocking<Unit> {
        val p = Fixture().apply { setup() }; val old = p.a.getRemoteFingerprint(p.bob.deviceId)
        val (replacement, bundle) = p.replacement()
        reject(CryptoError.IdentityChanged) { p.a.establishSession(bundle) }
        assertEquals(SecurityPriority.NORMAL, (p.a.events.first() as SecurityEvent.RemoteIdentityChanged).priority)
        val candidate = p.a.getPendingFingerprint(p.bob.deviceId)
        assertNotEquals(old, candidate)
        reject(CryptoError.VerificationFailed) { p.a.trustNewIdentity(p.bob.deviceId, old) }
        p.a = SignalProtocolEngine(p.aStore)
        assertEquals(IdentityTrustState.CHANGED, p.a.getRemoteIdentityStatus(p.bob.deviceId)!!.trustState)
        assertEquals(candidate, p.a.getPendingFingerprint(p.bob.deviceId))
        p.a.trustNewIdentity(p.bob.deviceId, candidate)
        assertEquals(IdentityTrustState.UNVERIFIED, p.a.getRemoteIdentityStatus(p.bob.deviceId)!!.trustState)
        reject(CryptoError.ReauthenticationRequired) { p.a.establishSession(bundle) }
        p.a.reestablishSession(bundle, candidate)
        assertEquals("new identity", replacement.decrypt(p.packet("new identity")).decodeToString())
        assertEquals(candidate, replacement.getRemoteFingerprint(p.alice.deviceId))
        assertEquals(candidate, SignalProtocolEngine(p.aStore).getRemoteFingerprint(p.bob.deviceId))
    }
    @Test fun incomingReplacementAlsoRecordsPersistentChangedState() = runBlocking<Unit> {
        val p = Fixture().apply { setup(); roundTrip() }
        val (replacement, _) = p.replacement()
        replacement.establishSession(p.a.publicBundle())
        reject(CryptoError.IdentityChanged) { p.a.decrypt(replacement.encrypt(p.alice.deviceId, byteArrayOf(4))) }
        assertEquals(IdentityTrustState.CHANGED, SignalProtocolEngine(p.aStore).getRemoteIdentityStatus(p.bob.deviceId)!!.trustState)
    }
    @Test fun bothDestroyedEndpointsCanExplicitlyReconnectButOldPrekeyCannotReactivate() = runBlocking<Unit> {
        val p = Fixture().apply { setup() }
        val accepted = p.packet("old accepted"); val delayed = p.packet("old delayed")
        p.b.decrypt(accepted).fill(0)
        val code = p.a.getRemoteFingerprint(p.bob.deviceId)
        p.a.destroySession(p.bob.deviceId); p.b.destroySession(p.alice.deviceId)
        p.b = SignalProtocolEngine(p.bStore)
        assertEquals(SessionLifecycle.DESTROYED, p.b.getSessionLifecycle(p.alice.deviceId))
        reject(CryptoError.UnknownSession) { p.b.decrypt(accepted) }
        reject(CryptoError.UnknownSession) { p.b.decrypt(delayed) }
        reject(CryptoError.ReauthenticationRequired) { p.a.establishSession(p.b.publicBundle()) }
        reject(CryptoError.VerificationFailed) { p.b.prepareReestablishment(p.alice.deviceId, "wrong") }
        val fresh = p.b.prepareReestablishment(p.alice.deviceId, code)
        assertEquals(SessionLifecycle.REQUIRES_REAUTHENTICATION, p.b.getSessionLifecycle(p.alice.deviceId))
        reject { p.b.decrypt(delayed) }
        assertEquals(SessionLifecycle.REQUIRES_REAUTHENTICATION, p.b.getSessionLifecycle(p.alice.deviceId))
        p.a.reestablishSession(fresh, code)
        assertEquals("fresh", p.b.decrypt(p.packet("fresh")).decodeToString())
        p.a.decrypt(p.b.encrypt(p.alice.deviceId, byteArrayOf(9))).fill(0)
        assertEquals(SessionLifecycle.ACTIVE, p.b.getSessionLifecycle(p.alice.deviceId))
        reject(CryptoError.Replay) { p.b.decrypt(accepted) }
        reject { p.b.decrypt(delayed) }
        assertEquals("still fresh", p.b.decrypt(p.packet("still fresh")).decodeToString())
    }
    @Test fun destroyLegacyMarkerMigratesWithoutResurrection() = runBlocking<Unit> {
        val p = Fixture().apply { setup(); roundTrip() }
        p.bStore.remove("lifecycle/${p.alice.deviceId}")
        p.bStore.write("destroyed/${p.alice.deviceId}", byteArrayOf(1))
        assertEquals(SessionLifecycle.DESTROYED, p.b.getSessionLifecycle(p.alice.deviceId))
        reject(CryptoError.UnknownSession) { p.b.decrypt(p.packet()) }
    }
    @Test fun legacyPinIsMigratedToUnverified() = runBlocking<Unit> {
        val p = Fixture().apply { setup() }; p.aStore.remove("trust-state/${p.bob.deviceId}")
        assertEquals(IdentityTrustState.UNVERIFIED, SignalProtocolEngine(p.aStore).getRemoteIdentityStatus(p.bob.deviceId)!!.trustState)
    }
    @Test fun prekeyAndNormalReplayRejectedAfterRestartAndDelayedDelivery() = runBlocking<Unit> {
        val p = Fixture().apply { setup() }; val prekey = p.packet(); val delayed = p.packet("delayed")
        p.b.decrypt(prekey).fill(0)
        p.a.decrypt(p.b.encrypt(p.alice.deviceId, byteArrayOf(0))).fill(0)
        val normal = p.packet("normal"); assertEquals(2, normal.messageType)
        p.b.decrypt(normal).fill(0)
        p.b = SignalProtocolEngine(p.bStore)
        reject(CryptoError.Replay) { p.b.decrypt(prekey) }
        reject(CryptoError.Replay) { p.b.decrypt(normal) }
        assertEquals("delayed", p.b.decrypt(delayed).decodeToString())
        reject(CryptoError.Replay) { p.b.decrypt(delayed) }
    }
    @Test fun regularMessageAllExternalBindingsAndPayloadMutationsFailAtomically() = runBlocking<Unit> {
        val p = Fixture().apply { setup(); roundTrip() }; val e = p.packet()
        fun altered(sender: String = e.senderDeviceId, recipient: String = e.recipientDeviceId,
            id: String = e.envelopeId, version: Int = 1, type: Int = e.messageType, bytes: ByteArray = e.encryptedPayload) =
            EncryptedEnvelope(version, id, sender, recipient, type, bytes)
        val changes = listOf(altered(sender = RandomIdentifiers.create()), altered(recipient = RandomIdentifiers.create()),
            altered(id = RandomIdentifiers.create()), altered(version = 2), altered(type = 3),
            altered(bytes = e.encryptedPayload.copyOf(e.encryptedPayload.size - 1)),
            altered(bytes = e.encryptedPayload + byteArrayOf(9, 8)),
            altered(bytes = e.encryptedPayload.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }))
        changes.forEach { changed -> reject { p.b.decrypt(changed) } }
        assertEquals("fixture", p.b.decrypt(e).decodeToString())
        reject(CryptoError.Replay) { p.b.decrypt(e) }
    }
    @Test fun storageFailureHasDistinctCategoryAndWipesUnreturnedPlaintext() = runBlocking<Unit> {
        val p = Fixture().apply { setup() }; val e = p.packet()
        p.bStore.inspectCommitBuffer = true; p.bStore.storageFailureAtCommit = true
        val failure = reject(CryptoError.StorageFailure) { p.b.decrypt(e) }
        assertEquals(FailureSite.COMMIT, failure.site)
        assertNotNull(p.bStore.commitBuffer)
        assertTrue(p.bStore.commitBuffer!!.all { it == 0.toByte() })
        p.bStore.storageFailureAtCommit = false
        assertEquals("fixture", p.b.decrypt(e).decodeToString())
    }
    @Test fun badCallerArgumentIsNotAuthenticationFailure() = runBlocking<Unit> {
        val p = Fixture().apply { setup() }
        try { p.a.encrypt(p.bob.deviceId, ByteArray(EnvelopeCodec.MAX_BODY + 1)); fail() }
        catch (expected: IllegalArgumentException) { }
    }
    @Test fun corruptedLocalRecordIsStorageFailureNotNetworkAuthenticationFailure() = runBlocking<Unit> {
        val p = Fixture().apply { setup() }
        p.aStore.write("session/${p.bob.deviceId}/1", byteArrayOf(0x7f))
        val e = reject(CryptoError.StorageFailure) { p.packet() }
        assertEquals(FailureSite.LOCAL_STATE, e.site)
    }
    @Test fun usernameRenameDoesNotChangeIdentityOrSafetyNumber() = runBlocking<Unit> {
        val p = Fixture().apply { setup(); roundTrip() }; val code = p.a.getRemoteFingerprint(p.bob.deviceId)
        val renamed = p.b.renameLocalUser("Robert")
        assertEquals(p.bob.deviceId, renamed.deviceId); assertArrayEquals(p.bob.publicKey, renamed.publicKey)
        assertEquals(code, p.a.getRemoteFingerprint(renamed.deviceId))
        val stranger = SignalProtocolEngine(MemoryRecords()).createIdentity("Robert")
        assertNotEquals(renamed.deviceId, stranger.deviceId)
        assertFalse(renamed.publicKey.contentEquals(stranger.publicKey))
        assertNull(p.a.getRemoteIdentityStatus(stranger.deviceId))
    }
    private class TestClock(var time: Instant = Instant.parse("2026-09-09T00:00:00Z")) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant() = time
    }
    @Test fun prekeyNamespacesAndRetirementPreserveDelayedWindow() = runBlocking<Unit> {
        val clock = TestClock(); val bStore = MemoryRecords()
        val b = SignalProtocolEngine(bStore, clock = clock)
        val a = SignalProtocolEngine(MemoryRecords()); a.createIdentity("Alice"); val bob = b.createIdentity("Bob")
        val bundle = b.preKeys.createPublicationBundle()
        assertEquals(3, setOf(bundle.preKeyId, bundle.signedId, bundle.kyberId).size)
        a.establishSession(bundle); val delayed = a.encrypt(bob.deviceId, byteArrayOf(6))
        clock.time = clock.time.plus(Duration.ofDays(29))
        assertEquals(0, b.preKeys.retireExpiredKeys())
        assertArrayEquals(byteArrayOf(6), b.decrypt(delayed))
        assertEquals(0, b.preKeys.inventory().unusedBundles)
        val replenished = b.preKeys.replenishIfNeeded(); assertEquals(1, replenished.size)
        assertTrue(b.preKeys.replenishIfNeeded().isEmpty())
        clock.time = clock.time.plus(Duration.ofDays(1))
        assertEquals(1, b.preKeys.retireExpiredKeys())
        assertFalse(bStore.keys("signed/").contains("signed/${bundle.signedId}"))
        assertEquals(1, b.preKeys.inventory().retainedSignedKeys)
    }
    @Test fun excessivelyDelayedPrekeyFailsAfterExplicitRetirement() = runBlocking<Unit> {
        val clock = TestClock(); val b = SignalProtocolEngine(MemoryRecords(), clock = clock)
        val a = SignalProtocolEngine(MemoryRecords()); a.createIdentity("Alice"); val bob = b.createIdentity("Bob")
        a.establishSession(b.publicBundle()); val packet = a.encrypt(bob.deviceId, byteArrayOf(1))
        clock.time = clock.time.plus(Duration.ofDays(31)); assertEquals(1, b.preKeys.retireExpiredKeys())
        reject { b.decrypt(packet) }
    }
    @Test fun boundedPoolFailsExplicitlyWithoutDeletingLiveKeys() = runBlocking<Unit> {
        val b = SignalProtocolEngine(MemoryRecords(), preKeyPolicy = PreKeyPolicy(maximumRetained = 2))
        b.createIdentity("Bob"); b.publicBundle(); b.publicBundle()
        reject(CryptoError.ResourceLimit) { b.publicBundle() }
        assertEquals(2, b.preKeys.inventory().retainedSignedKeys)
    }
}
