package org.ghostcloak.app.foundation

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ghostcloak.crypto.SignalProtocolEngine
import org.ghostcloak.crypto.EndpointStorageFailure
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ghostcloak.storage.EncryptedEndpointStore

/** Application service owns endpoint lifetime; UI receives only public status. */
class FoundationService(private val context: Context) {
    companion object { private val lifecycle = Mutex() }
    suspend fun status(): String = withContext(Dispatchers.IO) {
        lifecycle.withLock {
            try {
                EncryptedEndpointStore.open(context, "local").use { records ->
                    val identity = SignalProtocolEngine(records).createIdentity("Local")
                    "Local device: ${identity.deviceId}\nKeystore protection: ${records.protection}"
                }
            } catch (e: EndpointStorageFailure) { "Local identity storage is unavailable. No identity was reset." }
        }
    }
}
