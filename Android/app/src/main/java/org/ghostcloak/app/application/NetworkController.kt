package org.ghostcloak.app.application

import org.ghostcloak.app.BuildConfig
import org.ghostcloak.crypto.*
import org.ghostcloak.messaging.*
import org.ghostcloak.protocol.*
import org.ghostcloak.storage.KeystoreDeviceAuth
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
) {
    val configured get() = origin.isNotEmpty()
    private val state by lazy { EndpointNetworkState(records, URI(origin).host, KeystoreDeviceAuth()) }
    private val client: HttpGhostClient by lazy { HttpGhostClient(origin, state, transport = connection, renewSession = ::renewSession, authenticatedFailure = ::networkFailure) }
    private val account: NetworkAccount by lazy { NetworkAccount(client, state) }
    private val transport by lazy { NetworkMailboxTransport(client, state) }
    private val outbox by lazy { DurableOutbox(records, engine, transport) }
    private val renewalMutex = Mutex()
    private var renewalBlocked = false
    private var loggingOut = false
    private var receiptOffset = 0
    val canAutoSync get() = configured && !renewalBlocked && !loggingOut && state.registered() && state.read() != null
    var status = if (configured) NetworkStatus.NEEDS_CONNECT else NetworkStatus.DISABLED; private set
    private fun device() = records.transaction { records.read("local/device")?.decodeToString() ?: throw ApiFailure(401, "credential_missing") }
    private fun networkFailure(e: ApiFailure) {
        if (e.status == 401) renewalBlocked = true
        status = when (e.status) { 401 -> NetworkStatus.NEEDS_CONNECT; 503 -> NetworkStatus.OFFLINE; else -> NetworkStatus.ERROR }
    }
    private suspend fun renewSession(rejectedToken: String) = renewalMutex.withLock {
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
    private suspend fun <T> operation(block: suspend () -> T): T {
        try { return block() }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: ApiFailure) { networkFailure(e); throw e }
        catch (e: Exception) { status = NetworkStatus.ERROR; throw e }
    }
    suspend fun connect(username: String) = operation {
        requireApi(configured, "server_not_configured")
        status = NetworkStatus.CONNECTING
        if (!state.registered()) {
            // Retry login first: registration may have committed before its response was lost.
            val name = try { Usernames.normalize(username) } catch (_: ApiFailure) { throw ApiFailure(400, "invalid_network_username") }
            val registration = state.registration(name, listOf(engine.publicBundle().publicData()))
            try { account.login(registration.accountId, registration.deviceId); state.markRegistered() }
            catch (e: ApiFailure) {
                if (e.status != 401) throw e
                account.register(registration); account.login(registration.accountId, registration.deviceId)
            }
        } else { account.login(state.accountId(), device()) }
        renewalBlocked = false; status = NetworkStatus.CONNECTED
    }
    suspend fun add(username: String, service: ConversationService) = operation {
        val normalized = Usernames.normalize(username)
        val e = client.lookup(normalized)
        val b = e.bundle
        requireApi(e.deviceId == b.deviceId && e.username == normalized, "directory_mismatch")
        val card = ContactCard(1, e.accountId, e.username, e.deviceId, b.registrationId, b.identity, b.preKeyId, b.preKey,
            b.signedId, b.signedKey, b.signature, b.kyberId, b.kyberKey, b.kyberSignature)
        service.importCard(ContactCardCodec.encode(card)); state.remember(e)
        status = NetworkStatus.CONNECTED
    }
    suspend fun send(service: ConversationService, id: String, text: String): Message = operation {
        requireApi(state.registered(), "connect_required", 401)
        // Preserve the existing durable outbox even when the stored session is expired/offline.
        status = NetworkStatus.SYNCING
        service.sendNetwork(id, text, outbox).also {
            if (it.state == MessageState.SERVER_ACCEPTED) {
                renewalBlocked = false; status = NetworkStatus.CONNECTED
            } else if (status == NetworkStatus.SYNCING) { status = NetworkStatus.ERROR }
            // Preserve request failure status when the outbox retains a pending message.
        }
    }
    suspend fun sync(service: ConversationService) = operation {
        requireApi(canAutoSync, "connect_required", 401)
        suspend fun exchange() {
            status = NetworkStatus.SYNCING
            service.retryNetwork(outbox)
            val seen = mutableListOf<String>()
            for (page in 0 until 16) {
                val response = client.call(ApiRequest.Fetch(includeSenders = true, skipMessageIds = seen.toList()))
                requireApi(response.deliveries.size <= NetworkLimits.BATCH, "invalid_response", 502)
                for (delivery in response.deliveries) {
                    requireApi(delivery.serverMessageId !in seen, "duplicate_delivery", 502)
                    seen.add(delivery.serverMessageId)
                    val envelope = EnvelopeCodec.decode(delivery.encryptedEnvelope)
                    delivery.sender?.let {
                        requireApi(it.deviceId == envelope.senderDeviceId, "sender_mismatch")
                        state.remember(it)
                    }
                    try { service.acceptNetwork(envelope, delivery.sender) }
                    catch (e: AppFailure) { continue }
                    catch (e: CryptoFailure) { if (e.error == CryptoError.StorageFailure) throw e else continue }
                    transport.acknowledgeAccepted(listOf(delivery.serverMessageId))
                }
                if (response.deliveries.size < NetworkLimits.BATCH) break
            }
            val queued = service.queuedSubmissions()
            if (queued.isNotEmpty()) {
                receiptOffset %= queued.size
                val ids = queued.drop(receiptOffset).take(NetworkLimits.BATCH)
                receiptOffset = (receiptOffset + ids.size) % queued.size
                try {
                    val statuses = client.call(ApiRequest.Fetch(submissionIds = ids)).statuses
                    requireApi(statuses.size == ids.size && statuses.map { it.submissionId }.toSet() == ids.toSet(), "invalid_response", 502)
                    service.deliveryStatuses(statuses)
                } catch (e: ApiFailure) { if (e.status != 404) throw e } // Receipt retention may have elapsed.
            }
        }
        exchange()
        status = NetworkStatus.CONNECTED
    }
    suspend fun publish() = operation {
        requireApi(canAutoSync, "connect_required", 401)
        client.publish(device(), listOf(engine.preKeys.createPublicationBundle().publicData()))
        status = NetworkStatus.CONNECTED
    }
    suspend fun logout() {
        loggingOut = true
        try { operation { account.logout() } }
        finally {
            state.save(null)
            renewalBlocked = true
            loggingOut = false
            status = NetworkStatus.NEEDS_CONNECT
        }
    }
}
