package org.ghostcloak.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.ghostcloak.app.application.AppRuntime
import org.ghostcloak.crypto.EndpointStorageFailure
import org.ghostcloak.crypto.SignalProtocolEngine
import org.ghostcloak.identity.RandomIdentifiers
import org.ghostcloak.storage.EncryptedEndpointStore
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class EndpointCorruptionRuntimeTest {
    @Test fun freshEndpointCanCreateAndReopenIdentity() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val endpoint = "r-${RandomIdentifiers.create()}"
        val first = AppRuntime(context, "", endpoint)
        try {
            first.use { service ->
                assertNull(first.open(service))
                first.create(service, "fixture")
                assertNotNull(first.open(service))
            }
        } finally { first.close() }
        val reopened = AppRuntime(context, "", endpoint)
        try { reopened.use { service -> assertNotNull(reopened.open(service)) } }
        finally { reopened.close() }
    }

    @Test fun existingIdentityWithEmptyDatabaseCannotBecomeNewOnboarding() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val endpoint = "r-${RandomIdentifiers.create()}"
        EncryptedEndpointStore.open(context, endpoint).use { records ->
            SignalProtocolEngine(records).createIdentity("fixture")
        }
        val database = File(context.noBackupFilesDir, "$endpoint.db")
        val wrapped = File(context.noBackupFilesDir, "$endpoint.wrapped")
        val wrappedBefore = wrapped.readBytes()
        database.writeBytes(byteArrayOf())
        val runtime = AppRuntime(context, "", endpoint)
        try {
            repeat(2) {
                try { runtime.use { service -> runtime.open(service) }; fail("Corruption opened as a new identity") }
                catch (expected: EndpointStorageFailure) { }
                assertEquals(0L, database.length())
            }
        } finally { runtime.close() }
        assertArrayEquals(wrappedBefore, wrapped.readBytes())
    }
}
