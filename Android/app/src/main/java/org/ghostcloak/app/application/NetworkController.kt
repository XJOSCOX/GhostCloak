package org.ghostcloak.app.application

import org.ghostcloak.app.BuildConfig
import org.ghostcloak.crypto.*
import org.ghostcloak.messaging.*
import org.ghostcloak.protocol.*
import org.ghostcloak.storage.KeystoreDeviceAuth
import org.ghostcloak.transport.*
import java.net.URI

/** Owned by AppRuntime; UI has no URLs, HTTP calls, private keys or raw access tokens. */
class NetworkController(private val records:EndpointRecords,private val engine:SecureSessionEngine) {
    val configured get()=BuildConfig.API_ORIGIN.isNotEmpty()
    private val state by lazy {EndpointNetworkState(records,URI(BuildConfig.API_ORIGIN).host,KeystoreDeviceAuth())}
    private val client by lazy {HttpGhostClient(BuildConfig.API_ORIGIN,state,transport=TransportPolicy.select())}
    private val account by lazy {NetworkAccount(client,state)}
    private val transport by lazy {NetworkMailboxTransport(client,state)}
    private val outbox by lazy {DurableOutbox(records,engine,transport)}
    private var authenticated=false
    private fun device()=records.transaction {records.read("local/device")!!.decodeToString()}
    suspend fun connect(username:String) {
        requireApi(configured,"server_not_configured")
        if(!state.registered()) {
            // A retry first attempts login: registration may have committed before a lost response.
            val registration=state.registration(username,listOf(engine.publicBundle().publicData()))
            try {account.login(registration.accountId,registration.deviceId); state.markRegistered()}
            catch(e:ApiFailure) {
                if(e.status!=401) throw e
                account.register(registration); account.login(registration.accountId,registration.deviceId)
            }
        } else {account.login(state.accountId(),device())}
        authenticated=true
    }
    suspend fun add(username:String,service:ConversationService) {
        requireApi(authenticated,"connect_required",401)
        val e=client.lookup(username); val b=e.bundle
        requireApi(e.deviceId==b.deviceId && e.username==Usernames.normalize(username),"directory_mismatch")
        val card=ContactCard(1,e.accountId,e.username,e.deviceId,b.registrationId,b.identity,b.preKeyId,b.preKey,b.signedId,b.signedKey,b.signature,b.kyberId,b.kyberKey,b.kyberSignature)
        service.importCard(ContactCardCodec.encode(card)); state.remember(e)
    }
    suspend fun send(service:ConversationService,id:String,text:String) {
        requireApi(state.registered(),"connect_required",401); service.sendNetwork(id,text,outbox)
    }
    suspend fun sync(service:ConversationService) {
        requireApi(authenticated,"connect_required",401)
        service.retryNetwork(outbox)
        for(delivery in transport.fetch()) {
            service.acceptNetwork(EnvelopeCodec.decode(delivery.encryptedEnvelope))
            transport.acknowledgeAccepted(listOf(delivery.serverMessageId))
        }
    }
    suspend fun publish() {requireApi(authenticated,"connect_required",401); client.publish(device(),listOf(engine.preKeys.createPublicationBundle().publicData()))}
    suspend fun logout(){account.logout(); authenticated=false}
}
