package org.ghostcloak.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.ghostcloak.app.application.*
import org.ghostcloak.identity.RandomIdentifiers
import org.ghostcloak.protocol.*
import org.ghostcloak.storage.EncryptedEndpointStore
import org.ghostcloak.transport.*
import org.junit.Assert.*
import org.junit.Test

class PrekeyMaintenanceTest {
    @Test fun existingIdentityRefillsAfterRestartBackgroundAndForegroundCoalesce()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val endpoint="p-${RandomIdentifiers.create()}";val api=SyntheticNetwork()
        var publishes=0
        val wire=GhostCloakTransport { r ->
            val body=NetworkCodec.decode<ApiRequest>(r.body)
            if(body is ApiRequest.Prekeys && !body.inspect) publishes++
            api.execute(r)
        }
        var app=AppRuntime(context,"https://fixture.invalid",endpoint,wire)
        try {
            app.use {app.create(it,"fixture")}
            val before=app.use {it.open()!!}
            val registration=api.accounts.values.single()
            assertEquals(16,registration.bundles.size)
            val token=api.sessions.keys.single()
            repeat(16) {
                api.execute(TransportRequest(java.net.URI("https://fixture.invalid/v1/directory/lookup"),
                    NetworkCodec.encode<ApiRequest>(ApiRequest.Lookup("fixture")),"Bearer $token"))
            }
            app.close()
            EncryptedEndpointStore.open(context,endpoint).use {records->records.transaction {
                // Advance only the synthetic fixture's maintenance deadline, not production policy.
                records.keys("app/prekey-refill/").filter {it.endsWith("/next")}.forEach {records.write(it,"0".toByteArray())}
            }}
            app=AppRuntime(context,"https://fixture.invalid",endpoint,wire)
            app.initializeBackground()
            coroutineScope { awaitAll(async {app.backgroundSync()},async {app.use {app.syncNetwork(it)}}) }
            assertEquals(1,publishes)
            assertEquals(before.deviceId,app.use {it.open()!!.deviceId})
            assertArrayEquals(before.publicKey,app.use {it.open()!!.publicKey})
            assertEquals(1,api.registrations)
            val response=api.execute(TransportRequest(java.net.URI("https://fixture.invalid/v1/directory/lookup"),
                NetworkCodec.encode<ApiRequest>(ApiRequest.Lookup("fixture")),"Bearer $token"))
            assertNotNull(NetworkCodec.decode<ApiResponse>(response.body).directory)
        } finally {app.close()}
    }
}
