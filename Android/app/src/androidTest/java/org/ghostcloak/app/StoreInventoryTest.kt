package org.ghostcloak.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.ghostcloak.app.application.LocalStateDiagnostics
import org.ghostcloak.crypto.SignalProtocolEngine
import org.ghostcloak.identity.RandomIdentifiers
import org.ghostcloak.messaging.EndpointNetworkState
import org.ghostcloak.messaging.publicData
import org.ghostcloak.storage.EncryptedEndpointStore
import org.ghostcloak.storage.KeystoreDeviceAuth
import org.junit.Assert.*
import org.junit.Test

class StoreInventoryTest {
    @Test fun existingEncryptedFixtureInventoryLeavesAllRecordsAndCredentialUnchanged() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val endpoint = "i-${RandomIdentifiers.create()}"
        // Isolated synthetic fixture only; never opens the application's local endpoint.
        EncryptedEndpointStore.open(context, endpoint).use { records ->
            val engine = SignalProtocolEngine(records)
            engine.createIdentity("Fixture")
            val state = EndpointNetworkState(records, "ghostcloak.local", KeystoreDeviceAuth())
            state.registration("Fixture", listOf(engine.publicBundle().publicData()))
            state.markRegistered()
            state.save("synthetic-session")
        }
        EncryptedEndpointStore.open(context, endpoint).use { records ->
            val before = records.transaction { records.keys("").associateWith { records.read(it)!! } }
            val logs = mutableListOf<String>()
            val sink = LocalStateDiagnostics.sink
            LocalStateDiagnostics.sink = { logs.add(it); Unit }
            try { LocalStateDiagnostics.inventory(records, "https://ghostcloak.local") }
            finally { LocalStateDiagnostics.sink = sink }
            val after = records.transaction { records.keys("").associateWith { records.read(it)!! } }
            assertEquals(before.keys, after.keys)
            before.forEach { (key, value) -> assertArrayEquals(value, after[key]) }
            for (label in listOf("LOCAL_IDENTITY_KEY", "LOCAL_DEVICE", "LOCAL_USER", "LOCAL_USERNAME",
                "LOCAL_SIGNAL_REGISTRATION", "NETWORK_ACCOUNT", "NETWORK_ROUTING", "NETWORK_REGISTERED_MARKER",
                "NETWORK_TOKEN", "NETWORK_AUTH_ALIAS_RECORD", "NETWORK_AUTH_PUBLIC_RECORD", "REFERENCED_AUTH_KEYSTORE_ENTRY"))
                assertTrue(logs.contains("${label}_PRESENT=true"))
            assertTrue(logs.contains("NETWORK_LEGACY_AUTH_PRIVATE_PRESENT=false"))
            assertTrue(logs.contains("CONTACT_RECORD_COUNT=0"))
            assertTrue(logs.contains("DATABASE_INTEGRITY=NOT_CHECKED"))
            assertFalse(logs.joinToString().contains("synthetic-session"))
        }
    }
}
