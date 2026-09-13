package org.ghostcloak.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.ghostcloak.app.application.*
import org.ghostcloak.identity.RandomIdentifiers
import org.ghostcloak.messaging.*
import org.ghostcloak.protocol.*
import org.ghostcloak.storage.*
import org.ghostcloak.transport.*
import org.junit.Assert.*
import org.junit.Test

class AccountRecoveryTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun contaminatedPhoneRecoversOriginalBindingWithoutTouchingPrivateState()=runBlocking {
        val name="r-${RandomIdentifiers.create()}";val api=SyntheticNetwork()
        val requests=mutableListOf<ApiRequest>()
        val transport=GhostCloakTransport { requests.add(NetworkCodec.decode(it.body));api.execute(it) }
        var app=AppRuntime(context,"https://fixture.invalid",name,transport)
        val bob=AppRuntime(context,"https://fixture.invalid","r-${RandomIdentifiers.create()}",api)
        try {
            app.use {app.create(it,"alice")};val identity=app.use {it.open()!!}
            bob.use {bob.create(it,"bob")}
            app.use {app.addNetwork("bob",it)};bob.use {bob.addNetwork("alice",it)}
            val bobId=bob.use {it.open()!!.deviceId}
            bob.use {bob.send(it,identity.deviceId,"before recovery")};app.use {app.syncNetwork(it)}
            val original=api.accounts.values.single {it.username=="alice"};app.close()
            val retained=EncryptedEndpointStore.open(context,name).use {records->records.transaction {
                val prefix=records.keys("network/").single {it.endsWith("/registered")}.removeSuffix("registered")
                records.remove(prefix+"registered");records.remove(prefix+"token")
                records.write(prefix+"account",RandomIdentifiers.create().toByteArray())
                records.write(prefix+"routing",RandomIdentifiers.create().toByteArray())
                records.write("app/renewal-blocked/fixture.invalid",byteArrayOf(1))
                // Opaque fixtures prove recovery only writes its allowlisted network binding records.
                for(key in listOf("test/history","test/contact","test/verification","test/policy","test/outbox","test/attachment","test/lock")) records.write(key,byteArrayOf(4,5,6))
                records.keys("").filter {it.startsWith("local/") || it.startsWith("test/") || it.startsWith("app/contact/") || it.startsWith("app/message/")}.associateWith {records.read(it)!!}
            }}
            requests.clear();app=AppRuntime(context,"https://fixture.invalid",name,transport)
            app.use { };assertEquals(NetworkStatus.RECOVERY_REQUIRED,app.networkStatus)
            try {app.use {app.connectNetwork(it)};fail()}catch(e:ApiFailure){assertEquals("recovery_required",e.code)}
            assertTrue(requests.none {it is ApiRequest.Register});assertEquals(2,api.registrations)
            app.close();app=AppRuntime(context,"https://fixture.invalid",name,transport)
            app.use { };assertFalse(app.canAutoSync)
            assertEquals(BackgroundResult.SKIP,app.backgroundSync())
            app.foregroundStarted();app.notificationActivityVisible(true)
            app.use {app.recoverNetwork()}
            assertEquals(NetworkStatus.CONNECTED,app.networkStatus);assertTrue(app.canAutoSync)
            assertEquals(identity.deviceId,app.use {it.open()!!.deviceId})
            assertArrayEquals(identity.publicKey,app.use {it.open()!!.publicKey})
            app.close()
            EncryptedEndpointStore.open(context,name).use {records->
                val state=EndpointNetworkState(records,"fixture.invalid",KeystoreDeviceAuth())
                assertEquals(original.accountId,state.accountId());assertTrue(state.registered());assertNotNull(state.read())
                assertArrayEquals(original.authPublicKey,state.recoveryPublicKey())
                assertNull(records.read("app/renewal-blocked/fixture.invalid"))
                retained.forEach {(key,bytes)->assertArrayEquals(bytes,records.read(key))}
            }
            app=AppRuntime(context,"https://fixture.invalid",name,transport);app.use {app.syncNetwork(it)}
            assertEquals("before recovery",app.use {it.messages(bobId).single().body})
            app.use {app.send(it,bobId,"after recovery")};bob.use {bob.syncNetwork(it)}
            assertEquals("after recovery",bob.use {it.messages(identity.deviceId).last().body})
            assertEquals(2,api.registrations);assertEquals(2,api.accounts.size)
        } finally {app.close();bob.close()}
    }
    @Test fun missingMarkerOrTokenUsesExistingLoginAndLogoutNeverRecoversAutomatically()=runBlocking {
        for(suffix in listOf("registered","token")) {
            val name="r-${RandomIdentifiers.create()}";val api=SyntheticNetwork();val calls=mutableListOf<ApiRequest>()
            val transport=GhostCloakTransport {calls.add(NetworkCodec.decode(it.body));api.execute(it)}
            var app=AppRuntime(context,"https://fixture.invalid",name,transport)
            try {
                app.use {app.create(it,"alice")};app.close()
                EncryptedEndpointStore.open(context,name).use {records->records.transaction {records.keys("network/").filter {it.endsWith("/$suffix")}.forEach(records::remove)}}
                calls.clear();app=AppRuntime(context,"https://fixture.invalid",name,transport)
                app.use {app.connectNetwork(it)}
                assertTrue(calls.none {it is ApiRequest.Register || it is ApiRequest.RecoveryIssue})
                app.use {app.logoutNetwork()};app.close();calls.clear()
                app=AppRuntime(context,"https://fixture.invalid",name,transport)
                app.initializeBackground();assertEquals(BackgroundResult.SKIP,app.backgroundSync());assertFalse(app.canAutoSync)
                assertTrue(calls.isEmpty())
                app.use {app.connectNetwork(it)};assertTrue(app.canAutoSync)
            } finally {app.close()}
        }
    }
    @Test fun failedProofAndLockDuringRecoveryNeverCommitOrRegister()=runBlocking {
        val name="r-${RandomIdentifiers.create()}";val api=SyntheticNetwork();var allow=true;var intercept=false
        val transport=GhostCloakTransport {request->
            val r=NetworkCodec.decode<ApiRequest>(request.body)
            if(intercept && r is ApiRequest.RecoveryVerify) allow=false
            api.execute(request)
        }
        val app=AppRuntime(context,"https://fixture.invalid",name,transport,attachmentAccess={allow})
        try {
            app.use {app.create(it,"alice")}
            app.foregroundStarted();app.notificationActivityVisible(true)
            val before=EncryptedEndpointStore.open(context,name).use {records->records.transaction {records.keys("network/").associateWith {records.read(it)!!}}}
            intercept=true
            try {app.use {app.recoverNetwork()};fail()}catch(e:ApiFailure){assertEquals("recovery_failed",e.code)}
            EncryptedEndpointStore.open(context,name).use {records->before.forEach {(key,value)->assertArrayEquals(value,records.read(key))}}
            assertEquals(1,api.registrations)
        } finally {app.close()}
    }
}
