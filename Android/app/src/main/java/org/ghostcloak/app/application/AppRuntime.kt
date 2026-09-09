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

class AppRuntime(private val context: Context) {
    private val mutex = Mutex()
    private var store: EncryptedEndpointStore? = null
    private var local: ConversationService? = null
    private var network: NetworkController? = null
    val networkConfigured get()=org.ghostcloak.app.BuildConfig.API_ORIGIN.isNotEmpty()
    var networkConnected=false; private set
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
                val records = EncryptedEndpointStore.open(context, "local")
                store = records
                val engine=SignalProtocolEngine(records)
                local = ConversationService(engine, LocalRepository(records))
                network=NetworkController(records,engine)
            }
            block(demo?.service ?: local!!)
        }
    }
    suspend fun open(service: ConversationService): DeviceIdentity? = service.open().also { identity ->
        if (!inDemo && identity != null && !attached) { service.attach(router.register(identity.deviceId)); attached = true }
    }
    suspend fun startDemo() { if (demo == null) demo = DeveloperMode.create(context) }
    fun leaveDemo() { demo = null }
    suspend fun send(service: ConversationService, id: String, text: String) {
        if(!inDemo && networkConfigured) {network!!.send(service,id,text); return}
        val message = service.send(id, text)
        demo?.deliver(message)
    }
    suspend fun connectNetwork(service:ConversationService) {network!!.connect(service.open()!!.username); networkConnected=true}
    suspend fun syncNetwork(service:ConversationService) {network!!.sync(service)}
    suspend fun addNetwork(username:String,service:ConversationService) {network!!.add(username,service)}
    suspend fun publishNetwork(){network!!.publish()}
    suspend fun logoutNetwork(){network!!.logout(); networkConnected=false}
}
