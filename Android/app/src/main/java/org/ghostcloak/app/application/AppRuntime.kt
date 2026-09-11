package org.ghostcloak.app.application

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.ghostcloak.app.developer.DeveloperMode
import org.ghostcloak.crypto.SignalProtocolEngine
import org.ghostcloak.identity.DeviceIdentity
import org.ghostcloak.messaging.*
import org.ghostcloak.storage.EncryptedEndpointStore
import org.ghostcloak.transport.LocalEncryptedRouter

class AppRuntime internal constructor(
    private val context: Context,
    private val apiOrigin: String = org.ghostcloak.app.BuildConfig.API_ORIGIN,
    private val endpointName: String = "local",
    private val connection: org.ghostcloak.transport.GhostCloakTransport = org.ghostcloak.transport.TransportPolicy.select(),
) : AutoCloseable {
    private val mutex = Mutex()
    private var store: EncryptedEndpointStore? = null
    private var local: ConversationService? = null
    private var network: NetworkController? = null
    val canAutoSync get() = !inDemo && network?.canAutoSync == true
    val networkRequiresConnect get() = networkConfigured && !inDemo && network?.canAutoSync != true
    val networkConfigured get() = apiOrigin.isNotEmpty()
    val networkStatus get() = network?.status ?: if (networkConfigured) NetworkStatus.NEEDS_CONNECT else NetworkStatus.DISABLED
    private var demo: DemoSession? = null
    private val router = LocalEncryptedRouter()
    private var attached = false
    val developerAvailable get() = DeveloperMode.available
    val inDemo get() = demo != null
    fun currentService() = demo?.service ?: local!!
    val protection get() = if (inDemo) "Isolated local simulator" else store?.protection?.name ?: "Unavailable"
    suspend fun <T> use(block: suspend (ConversationService) -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (local == null) {
                val records = EncryptedEndpointStore.open(context, endpointName)
                store = records
                val engine=SignalProtocolEngine(records)
                local = ConversationService(engine, LocalRepository(records))
                network=NetworkController(records,engine,apiOrigin,connection)
            }
            block(demo?.service ?: local!!)
        }
    }
    suspend fun open(service: ConversationService): DeviceIdentity? = service.open().also { identity ->
        if (!inDemo && !networkConfigured && identity != null && !attached) { service.attach(router.register(identity.deviceId)); attached = true }
    }
    suspend fun startDemo() { if (demo == null) demo = DeveloperMode.create(context) }
    fun leaveDemo() { demo = null }
    suspend fun create(service: ConversationService, username: String) {
        if (service.open() == null) {
            // Validate both existing local and server rules before generating any identity material.
            val name = if (networkConfigured && !inDemo) {
                try { org.ghostcloak.protocol.Usernames.normalize(username) }
                catch (_: org.ghostcloak.protocol.ApiFailure) { throw AppFailure(AppError.INVALID_USERNAME) }
            } else username
            service.create(name)
        }
        if (networkConfigured && !inDemo) connectNetwork(service)
    }
    suspend fun send(service: ConversationService, id: String, text: String): Message {
        if (!inDemo && networkConfigured) return network!!.send(service, id, text)
        val message = service.send(id, text)
        demo?.deliver(message)
        return message
    }
    suspend fun connectNetwork(service: ConversationService) { network!!.connect(service.open()!!.username) }
    suspend fun syncNetwork(service: ConversationService) { network!!.sync(service) }
    suspend fun addNetwork(username: String, service: ConversationService) { network!!.add(username, service) }
    suspend fun publishNetwork() { network!!.publish() }
    suspend fun logoutNetwork() { network!!.logout() }
    /** Process owner closes only after all foreground operations have finished (also used by reopen tests). */
    override fun close() { store?.close(); store = null; local = null; network = null; attached = false }
}