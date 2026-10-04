package org.ghostcloak.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.ghostcloak.app.application.AppRuntime
import org.ghostcloak.identity.RandomIdentifiers
import org.ghostcloak.protocol.*
import org.ghostcloak.transport.*
import org.junit.Assert.*
import org.junit.Test

class CapabilityDiscoveryTest {
    @Test fun lostAllocationResponseAndRuntimeRecreationReuseOneRecipientPrekey()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val api=SyntheticNetwork();var drop=true
        val wire=GhostCloakTransport {request ->
            val response=api.execute(request)
            if(NetworkCodec.decode<ApiRequest>(request.body) is ApiRequest.Allocate && drop) {
                drop=false;throw java.io.IOException("synthetic lost allocation response")
            }
            response
        }
        val slot=RandomIdentifiers.create();var a=AppRuntime(context,"https://fixture.invalid",slot,wire)
        val b=AppRuntime(context,"https://fixture.invalid",RandomIdentifiers.create(),wire)
        try {
            a.use {a.create(it,"alice")};b.use {b.create(it,"bob")}
            val id=b.ownGhostCloakId()!!;val device=b.use {it.open()!!.deviceId}
            assertEquals(16,api.availablePrekeys(device))
            try {a.use {a.addNetwork(id,it)};fail("Expected lost response")} catch(_:Exception) {}
            assertEquals(15,api.availablePrekeys(device))
            a.close();a=AppRuntime(context,"https://fixture.invalid",slot,wire)
            a.use {it.open();a.addNetwork(id,it)}
            assertEquals(15,api.availablePrekeys(device))
            assertTrue(api.sent.isEmpty())
        } finally {a.close();b.close()}
    }
    @Test fun registeredPhonesDiscoverSupportWithoutReplyAndEncryptedReopenPreservesProof()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val api=SyntheticNetwork();val endpoint=RandomIdentifiers.create()
        var a=AppRuntime(context,"https://fixture.invalid",endpoint,api)
        val b=AppRuntime(context,"https://fixture.invalid",RandomIdentifiers.create(),api)
        try {
            a.use {a.create(it,"alice")};b.use {b.create(it,"bob")}
            val identity=a.use {it.open()!!};val bid=b.use {it.open()!!.deviceId}
            a.use {a.addNetwork(b.ownGhostCloakId()!!,it)}
            assertTrue(a.supportsAttachments(bid))
            assertTrue(api.sent.isEmpty());assertEquals(2,api.registrations)
            a.close();a=AppRuntime(context,"https://fixture.invalid",endpoint,api)
            assertTrue(a.supportsAttachments(bid))
            assertArrayEquals(identity.publicKey,a.use {it.open()!!.publicKey})
            assertEquals(identity.deviceId,a.use {it.open()!!.deviceId})
            a.use {a.logoutNetwork()}
            assertFalse(a.use {a.canAutoSync})
            assertEquals(2,api.registrations)
        } finally {a.close();b.close()}
    }
    @Test fun unknownPeerRefreshAfterUpgradeDoesNotConsumeKeyOrSendMessage()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val api=SyntheticNetwork();var advertise=false;var allocated=0;var refreshes=0
        val wire=GhostCloakTransport {request ->
            val r=NetworkCodec.decode<ApiRequest>(request.body)
            if(r is ApiRequest.Capabilities && !advertise) TransportResponse(400,NetworkLimits.CONTENT_TYPE,NetworkCodec.encode(ApiResponse(error="invalid_schema")))
            else {if(r is ApiRequest.Allocate)allocated++;if(r is ApiRequest.CapabilityLookup)refreshes++;api.execute(request)}
        }
        val a=AppRuntime(context,"https://fixture.invalid",RandomIdentifiers.create(),wire)
        val endpointB=RandomIdentifiers.create()
        var b=AppRuntime(context,"https://fixture.invalid",endpointB,wire)
        try {
            a.use {a.create(it,"alice")};b.use {b.create(it,"bob")}
            a.use {a.addNetwork(b.ownGhostCloakId()!!,it)}
            val bid=b.use {it.open()!!.deviceId}
            assertFalse(a.supportsAttachments(bid))
            // Simulate upgraded process: existing SQLCipher identity/pool, no account reset.
            val identity=b.use {it.open()!!};b.close();advertise=true
            b=AppRuntime(context,"https://fixture.invalid",endpointB,wire)
            b.use {b.syncNetwork(it)}
            assertArrayEquals(identity.publicKey,b.use {it.open()!!.publicKey})
            assertTrue(a.supportsAttachments(bid))
            assertEquals(1,allocated);assertEquals(2,refreshes)
            assertTrue(api.sent.isEmpty());assertEquals(2,api.registrations)
        } finally {a.close();b.close()}
    }
}
