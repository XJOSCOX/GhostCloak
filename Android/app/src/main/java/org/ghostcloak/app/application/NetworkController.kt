package org.ghostcloak.app.application

import org.ghostcloak.app.BuildConfig
import org.ghostcloak.crypto.*
import org.ghostcloak.messaging.*
import org.ghostcloak.protocol.*
import org.ghostcloak.storage.KeystoreDeviceAuth
import org.ghostcloak.identity.RandomIdentifiers
import org.ghostcloak.transport.*
import java.net.URI
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owned by AppRuntime; UI has no URLs, HTTP calls, private keys or raw access tokens. */
class NetworkController(
    private val records: EndpointRecords,
    private val engine: SecureSessionEngine,
    private val origin: String = BuildConfig.API_ORIGIN,
    connection: GhostCloakTransport = TransportPolicy.select(),
    private val cooldown: FetchCooldown = FetchCooldown(),
    private val requireNormal: () -> Unit = {},
    private val stateAccess: org.ghostcloak.storage.LocalStateAccess? = null,
) {
    val configured get() = origin.isNotEmpty()
    private val state by lazy { EndpointNetworkState(records, URI(origin).host, KeystoreDeviceAuth(stateAccess)) }
    private var backgroundFetches: Int? = null
    private val client: HttpGhostClient by lazy { HttpGhostClient(origin, state, transport = AccountRecoveryDiagnostics.wrap(GhostCloakTransport { request -> requireNormal(); connection.execute(request) }), renewSession = ::renewSession, authenticatedFailure = ::networkFailure, diagnostics = NetworkDiagnostics.observer,
        fetchCooldown = cooldown, beforeFetch = {
            backgroundFetches?.let { count ->
                if (count >= 4) throw BackgroundDeferred()
                backgroundFetches = count + 1
            }
        }) }
    private val account: NetworkAccount by lazy { NetworkAccount(client, state) }
    private val transport by lazy { NetworkMailboxTransport(client, state) }
    private val outbox by lazy { DurableOutbox(records, engine, transport) }
    private val prekeyRefill by lazy { PrekeyRefill(records, engine, URI(origin).host, ::device,
        { client.call(it, reportTransientFailure=false) }, { canAutoSync && cooldown.remainingMillis == 0L },
        diagnostic = PrekeyDiagnostics::emit) }
    private val renewalMutex = Mutex()
    private val capabilities by lazy { CapabilityDiscovery(engine,state,URI(origin).host,client,
        {canAutoSync && cooldown.remainingMillis==0L}) }
    private val renewalBlockKey = "app/renewal-blocked/${URI(origin).host}"
    @Volatile private var renewalBlockCache = records.transaction { records.read(renewalBlockKey) != null }
    private var renewalBlocked:Boolean
        get()=renewalBlockCache
        set(value) {
            records.transaction {
                if (value) records.write(renewalBlockKey, byteArrayOf(1)) else records.remove(renewalBlockKey)
            }
            renewalBlockCache = value
        }
    @Volatile private var loggingOut = false
    private var authGeneration = 0L
    var accountConnectionState:AccountConnectionState = AccountConnectionState.RECOVERY_REQUIRED
        private set
    private var receiptOffset = 0
    var syncActive = false
        private set
    val fetchRetryDelayMillis get() = if (configured) client.fetchRetryDelayMillis else 0L
    val canAutoSync get() = configured && !renewalBlocked && !loggingOut && state.connectionState()==AccountConnectionState.REGISTERED && state.read() != null
    var status = if (configured) NetworkStatus.NEEDS_CONNECT else NetworkStatus.DISABLED
        private set(value) { NetworkDiagnostics.status(field, value); field = value }
    private fun device() = records.transaction { records.read("local/device")?.decodeToString() ?: throw ApiFailure(401, "credential_missing") }
    init {
        AccountRecoveryDiagnostics.path("OPEN")
        AccountRecoveryDiagnostics.snapshot(records, URI(origin).host)
        if(configured) {
            accountConnectionState=state.connectionState()
            if(accountConnectionState==AccountConnectionState.RECOVERY_REQUIRED && records.transaction {records.read("local/device")!=null})
                status=NetworkStatus.RECOVERY_REQUIRED
        }
    }
    private fun networkFailure(e: ApiFailure) {
        if (e.status == 401) renewalBlocked = true
        status = if(e.code in setOf("recovery_required","recovery_failed") || (configured && state.connectionState()==AccountConnectionState.RECOVERY_REQUIRED))
            NetworkStatus.RECOVERY_REQUIRED else when (e.status) { 401 -> NetworkStatus.NEEDS_CONNECT; 429 -> NetworkStatus.RATE_LIMITED; 503 -> NetworkStatus.OFFLINE; else -> NetworkStatus.ERROR }
    }
    private suspend fun renewSession(rejectedToken: String) = renewalMutex.withLock {
        AccountRecoveryDiagnostics.path("SILENT_RENEWAL")
        AccountRecoveryDiagnostics.snapshot(records, URI(origin).host)
        requireApi(canAutoSync, "connect_required", 401)
        // Another operation may already have replaced the rejected access session.
        if (state.read() != rejectedToken) return@withLock
        // Login only: never invoke registration or generate identity/auth credentials here.
        try { account.login(state.accountId(), device()) }
        catch (e: ApiFailure) {
            if (e.code == "legacy_auth_requires_reset") throw ApiFailure(401, e.code)
            throw e
        }
        catch (_: EndpointStorageFailure) { throw ApiFailure(401, "credential_unavailable") }
    }
    private suspend fun <T> operation(category: NetworkOperation, block: suspend () -> T): T {
        val started = System.nanoTime()
        try { requireNormal(); return block() }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: ApiFailure) {
            NetworkDiagnostics.observer?.let { emitNetworkDiagnostic(it, NetworkDiagnostic(category, NetworkEvent.API_FAILURE,
                (System.nanoTime() - started) / 1_000_000, apiStatus = e.status, apiCode = e.code)) }
            networkFailure(e); throw e
        }
        catch (e: Exception) { status = NetworkStatus.ERROR; throw e }
    }
    suspend fun connect() = operation(NetworkOperation.AUTH) {
        requireApi(configured, "server_not_configured")
        status = NetworkStatus.CONNECTING
        AccountRecoveryDiagnostics.snapshot(records, URI(origin).host)
        try {
            if(state.connectionState()==AccountConnectionState.NEW_ACCOUNT) {
                val pending=state.pendingRegistration()
                val registration=pending ?: state.prepareNew(List(16) { engine.publicBundle().publicData() })
                // Only a durable intent made at actual local identity creation allows registration.
                // A retry of that intent first handles a previously lost Register response.
                var loggedIn=false
                if(pending!=null) try {account.login(registration.accountId,registration.deviceId); loggedIn=true}
                    catch(e:ApiFailure) {if(e.status!=401) throw e}
                if(!loggedIn) {account.register(registration);account.login(registration.accountId,registration.deviceId)}
                state.ghostCloakId()
            } else {
                AccountRecoveryDiagnostics.path(if(state.registered()) "CONNECT_REGISTERED_LOGIN" else "CONNECT_UNMARKED_LOGIN_FIRST")
                // Existing or ambiguous state: no credential preparation and no registration fallback.
                account.login(state.accountId(),device())
            }
        } catch(e:ApiFailure) {
            if(e.status in setOf(401,409)) {
                state.requireRecovery();accountConnectionState=AccountConnectionState.RECOVERY_REQUIRED
                throw ApiFailure(401,"recovery_required")
            }
            throw e
        }
        accountConnectionState=AccountConnectionState.REGISTERED
        renewalBlocked = false; status = NetworkStatus.CONNECTED
        prekeyRefill.maintain()
        capabilities.publish()
    }
    suspend fun recover(allowed:()->Boolean)=renewalMutex.withLock {
        requireApi(configured && allowed() && !loggingOut,"recovery_failed",401)
        val generation=authGeneration
        accountConnectionState=AccountConnectionState.RECOVERING;status=NetworkStatus.RECOVERING
        AccountRecoveryDiagnostics.recovery("RECOVERY_START")
        try {
            val public=state.recoveryPublicKey()
            val localDevice=device()
            val challenge=client.unauthenticated(ApiRequest.RecoveryIssue(public,localDevice)).challenge ?: throw ApiFailure(401,"recovery_failed")
            AccountRecoveryDiagnostics.recovery("RECOVERY_CHALLENGE_OK")
            requireApi(allowed() && !loggingOut && generation==authGeneration,"recovery_failed",401)
            val signature=state.signRecovery(challenge,public)
            val binding=try {client.unauthenticated(ApiRequest.RecoveryVerify(public,localDevice,challenge.id,signature)).recovered}
                finally {signature.fill(0)}
            requireApi(binding!=null && allowed() && !loggingOut && generation==authGeneration,"recovery_failed",401)
            AccountRecoveryDiagnostics.recovery("RECOVERY_PROOF_OK")
            records.transaction {
                requireApi(allowed() && !loggingOut && generation==authGeneration,"recovery_failed",401)
                state.commitRecovery(binding!!,public)
            }
            renewalBlockCache=false // Only after the SQLCipher transaction actually committed.
            accountConnectionState=AccountConnectionState.RECOVERED;status=NetworkStatus.CONNECTED
            AccountRecoveryDiagnostics.recovery("RECOVERY_COMMIT_OK")
        } catch(e:Exception) {
            accountConnectionState=state.connectionState()
            status=if(accountConnectionState==AccountConnectionState.LOGGED_OUT) NetworkStatus.NEEDS_CONNECT else NetworkStatus.RECOVERY_REQUIRED
            AccountRecoveryDiagnostics.recovery("RECOVERY_FAILED=UNVERIFIED")
            if(e is kotlinx.coroutines.CancellationException) throw e
            throw ApiFailure(401,"recovery_failed")
        }
    }
    fun ownGhostCloakId():String=state.ghostCloakId()
    fun visibleGhostCloakId():String?=if(state.connectionState()==AccountConnectionState.REGISTERED) state.ghostCloakId() else null
    suspend fun add(ghostCloakId: String, service: ConversationService) {
        val normalized = GhostCloakIds.normalize(ghostCloakId)
        if(service.blockedContacts().any {it.ghostCloakId==normalized}) throw AppFailure(AppError.BLOCKED)
        operation(NetworkOperation.LOOKUP) {
            // Persist before the request: a lost allocation response or process restart reuses
            // the same ID and therefore the same one-time bundle. The record is SQLCipher-only.
            val pendingKey="network/prekey-allocation/${URI(origin).host}/$normalized"
              val (allocationId,retrying)=records.transaction {
                  records.read(pendingKey)?.decodeToString()?.let { encoded ->
                      val parts=encoded.split('|')
                      val created=parts.getOrNull(1)?.toLongOrNull()
                      val age=created?.let {System.currentTimeMillis()-it}
                      requireApi(RandomIdentifiers.valid(parts[0]) && age!=null && age in 0 until 82800000L,
                          "allocation_retry_expired",409)
                      parts[0] to true
                  } ?: RandomIdentifiers.create().let {
                      records.write(pendingKey,"$it|${System.currentTimeMillis()}".encodeToByteArray()); it to false
                  }
              }
            val (e,time) = if(retrying) capabilities.allocate(normalized,allocationId)
                else capabilities.lookup(normalized,allocationId)
            val b = e.bundle
            requireApi(e.deviceId == b.deviceId && e.ghostCloakId == normalized, "directory_mismatch")
            if(service.blockedContacts().any {it.publicUserId==e.accountId || it.remoteDeviceId==e.deviceId}) throw AppFailure(AppError.BLOCKED)
            val card = ContactCard(2, e.accountId, e.ghostCloakId, e.deviceId, b.registrationId, b.identity, b.preKeyId, b.preKey,
                b.signedId, b.signedKey, b.signature, b.kyberId, b.kyberKey, b.kyberSignature)
            service.importCard(ContactCardCodec.encode(card)); state.remember(e)
            capabilities.accept(e,time,service)
            records.transaction {records.remove(pendingKey)}
            status = NetworkStatus.CONNECTED
        }
    }
    suspend fun send(service: ConversationService, id: String, text: String, viewOnce:Boolean=false,
        replyTo:org.ghostcloak.messaging.ReplyReference?=null): Message = operation(NetworkOperation.SEND) {
        requireApi(state.registered(), "connect_required", 401)
        // Preserve the existing durable outbox even when the stored session is expired/offline.
        status = NetworkStatus.SYNCING
        service.sendNetwork(id, text, outbox,viewOnce,replyTo).also {
            if (it.state == MessageState.SERVER_ACCEPTED) {
                renewalBlocked = false; status = NetworkStatus.CONNECTED
            } else if (status == NetworkStatus.SYNCING) { status = NetworkStatus.ERROR }
            // Preserve request failure status when the outbox retains a pending message.
        }
    }
    suspend fun react(service:ConversationService,id:String,target:String,emoji:String?)=operation(NetworkOperation.SEND) {
        requireApi(state.registered(),"connect_required",401)
        service.react(id,target,emoji,outbox)
    }
    suspend fun deleteForEveryone(service:ConversationService,id:String,localId:String)=operation(NetworkOperation.SEND) {
        requireApi(state.registered(),"connect_required",401)
        service.deleteForEveryone(id,localId,outbox)
    }
    suspend fun editMessage(service:ConversationService,id:String,localId:String,text:String)=operation(NetworkOperation.SEND) {
        requireApi(state.registered(),"connect_required",401)
        service.editMessage(id,localId,text,outbox)
    }
    suspend fun retrySubmission(service:ConversationService,id:String,localId:String)=operation(NetworkOperation.SEND) {
        requireApi(state.registered(),"connect_required",401)
        service.retrySubmission(id,localId,outbox)
    }
    /** Local Accept and queued profile intent commit together; network delivery is best effort. */
    suspend fun acceptRequest(service:ConversationService,id:String) {
        service.acceptRequest(id,outbox)
    }
    suspend fun setDisappearing(service: ConversationService, id: String, seconds: Int): Message = operation(NetworkOperation.SEND) {
        requireApi(state.registered(), "connect_required", 401)
        status = NetworkStatus.SYNCING
        service.setDisappearing(id, seconds, outbox).also {
            if (it.state == MessageState.SERVER_ACCEPTED) { renewalBlocked = false; status = NetworkStatus.CONNECTED }
            else if (status == NetworkStatus.SYNCING) status = NetworkStatus.ERROR
        }
    }
    suspend fun sync(service: ConversationService) = operation(NetworkOperation.FETCH) {
        requireApi(canAutoSync, "connect_required", 401)
        suspend fun exchange() {
            status = NetworkStatus.SYNCING
            service.retryNetwork(outbox)
            val queued = service.queuedSubmissions()
            val ids = if (queued.isEmpty()) emptyList() else {
                receiptOffset %= queued.size
                queued.drop(receiptOffset).take(NetworkLimits.BATCH).also {
                    receiptOffset = (receiptOffset + it.size) % queued.size
                }
            }
            syncActive = queued.isNotEmpty()
            val seen = mutableListOf<String>()
            for (page in 0 until 16) {
                val receiptIds = if (page == 0) ids else emptyList()
                var receiptsExpired = false
                val request = ApiRequest.Fetch(includeSenders = true, skipMessageIds = seen.toList(), submissionIds = receiptIds, retention=true)
                val response = try { client.call(request) } catch (e: ApiFailure) {
                    if (e.status != 404 || receiptIds.isEmpty()) throw e
                    // Retention can remove a receipt and reject the entire combined response.
                    receiptsExpired = true
                    client.call(ApiRequest.Fetch(includeSenders = true, skipMessageIds = seen.toList(), retention=true))
                }
                response.serverTime?.let {service.serverReference(it)}
                if (response.deliveries.isNotEmpty()) syncActive = true
                requireApi(response.deliveries.size <= NetworkLimits.BATCH, "invalid_response", 502)
                for (delivery in response.deliveries) {
                    requireApi(delivery.serverMessageId !in seen, "duplicate_delivery", 502)
                    seen.add(delivery.serverMessageId)
                    val envelope = EnvelopeCodec.decode(delivery.encryptedEnvelope)
                    delivery.sender?.let {
                        requireApi(it.deviceId == envelope.senderDeviceId, "sender_mismatch")
                        state.remember(it)
                    }
                    try { service.acceptNetwork(envelope, delivery.sender,delivery.receivedAt) }
                    catch (e: AppFailure) { continue }
                    catch (e: CryptoFailure) { if (e.error == CryptoError.StorageFailure) throw e else continue }
                    transport.acknowledgeAccepted(listOf(delivery.serverMessageId))
                }
                // Inbox commits and ACKs precede receipt validation; invalid statuses cannot undo accepted messages.
                val expectedIds = if (receiptsExpired) emptyList() else receiptIds
                requireApi(response.statuses.size == expectedIds.size && response.statuses.map { it.submissionId }.toSet() == expectedIds.toSet(), "invalid_response", 502)
                service.deliveryStatuses(response.statuses)
                if (response.deliveries.size < NetworkLimits.BATCH) break
            }
        }
        exchange()
        status = NetworkStatus.CONNECTED
        prekeyRefill.maintain()
        capabilities.publish()
    }
    suspend fun syncBackground(service: ConversationService) {
        backgroundFetches = 0
        try { sync(service) } finally { backgroundFetches = null }
    }
    suspend fun publish() = operation(NetworkOperation.PUBLISH) {
        requireApi(canAutoSync, "connect_required", 401)
        prekeyRefill.maintain()
    }
    suspend fun logout() {
        AccountRecoveryDiagnostics.path("LOGOUT")
        loggingOut = true
        authGeneration++
        state.markLoggedOut()
        accountConnectionState=AccountConnectionState.LOGGED_OUT
        try { operation(NetworkOperation.AUTH) { account.logout() } }
        finally {
            state.save(null)
            renewalBlocked = true
            loggingOut = false
            status = NetworkStatus.NEEDS_CONNECT
        }
    }
    internal fun blobClient(allowed: () -> Boolean, checkpoint: () -> Unit) =
        org.ghostcloak.attachments.StreamingBlobClient(origin,client,{ canAutoSync && allowed() },checkpoint)
    internal suspend fun supportsAttachments(service:ConversationService,id:String)=capabilities.refresh(id,service)
    internal suspend fun sendAttachment(service:ConversationService,id:String,descriptor:org.ghostcloak.attachments.AttachmentDescriptor,
        supported:Boolean,viewOnce:Boolean=false,caption:String="",onEnqueued:(Message)->Unit):Message {
        requireApi(canAutoSync,"connect_required",401)
        return service.sendAttachment(id,descriptor,outbox,supported,viewOnce,caption,onEnqueued)
    }
}
