package org.ghostcloak.app.application

import org.ghostcloak.app.BuildConfig
import org.ghostcloak.crypto.*
import org.ghostcloak.messaging.*
import org.ghostcloak.protocol.*
import org.ghostcloak.storage.KeystoreDeviceAuth
import org.ghostcloak.transport.*
import java.net.URI

/** Owned by AppRuntime; UI has no URLs, HTTP calls, private keys or raw access tokens. */
class NetworkController(
    private val records: EndpointRecords,
    private val engine: SecureSessionEngine,
    private val origin: String = BuildConfig.API_ORIGIN,
    connection: GhostCloakTransport = TransportPolicy.select(),
) {
    val configured get() = origin.isNotEmpty()
    private val state by lazy { EndpointNetworkState(records, URI(origin).host, KeystoreDeviceAuth()) }
    private val client by lazy { HttpGhostClient(origin, state, transport = connection) }
    private val account by lazy { NetworkAccount(client, state) }
    private val transport by lazy { NetworkMailboxTransport(client, state) }
    private val outbox by lazy { DurableOutbox(records, engine, transport) }
    private var authenticated = false
    private var receiptOffset = 0
    val canAutoSync get() = configured && state.registered() && state.read() != null
    var status = if (configured) NetworkStatus.NEEDS_CONNECT else NetworkStatus.DISABLED; private set
    private fun device() = records.transaction { records.read("local/device")!!.decodeToString() }
    private suspend fun <T> operation(block: suspend () -> T): T {
        try { return block() }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: ApiFailure) {
            if (e.status == 401 || e.status == 503) authenticated = false
            status = when (e.status) { 401 -> NetworkStatus.NEEDS_CONNECT; 503 -> NetworkStatus.OFFLINE; else -> NetworkStatus.ERROR }
            throw e
        } catch (e: Exception) { status = NetworkStatus.ERROR; throw e }
    }
    suspend fun connect(username: String) = operation {
        requireApi(configured, "server_not_configured")
        authenticated = false; status = NetworkStatus.CONNECTING
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
        authenticated = true; status = NetworkStatus.CONNECTED
    }
    suspend fun add(username: String, service: ConversationService) = operation {
        val normalized = Usernames.normalize(username)
        if (!authenticated) connect(service.open()!!.username)
        val e = try { client.lookup(normalized) } catch (failure: ApiFailure) {
            if (failure.status != 401) throw failure
            connect(service.open()!!.username); client.lookup(normalized)
        }
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
        service.sendNetwork(id, text, outbox).also {
            if (it.state == MessageState.SERVER_ACCEPTED) {
                authenticated = true; status = NetworkStatus.CONNECTED
            } else { status = NetworkStatus.ERROR }
        }
    }
    suspend fun sync(service: ConversationService) = operation {
        if (!authenticated) connect(service.open()!!.username)
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
        try { exchange() } catch (e: ApiFailure) {
            if (e.status != 401) throw e
            connect(service.open()!!.username); exchange()
        }
        status = NetworkStatus.CONNECTED
    }
    suspend fun publish() = operation {
        requireApi(authenticated, "connect_required", 401)
        client.publish(device(), listOf(engine.preKeys.createPublicationBundle().publicData()))
        status = NetworkStatus.CONNECTED
    }
    suspend fun logout() = operation {
        account.logout(); authenticated = false; status = NetworkStatus.NEEDS_CONNECT
    }
}
