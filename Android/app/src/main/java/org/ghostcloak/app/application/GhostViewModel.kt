package org.ghostcloak.app.application

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.ghostcloak.crypto.*
import org.ghostcloak.identity.DeviceIdentity
import org.ghostcloak.messaging.*
import org.ghostcloak.transport.TransportFailure

data class AppState(val loading: Boolean = true, val identity: DeviceIdentity? = null,
    val contacts: List<ContactStatus> = emptyList(), val previews: Map<String, Message> = emptyMap(), val messages: List<Message> = emptyList(),
    val error: String? = null, val card: String = "", val fingerprint: String = "",
    val demo: Boolean = false, val protection: String = "", val ready: Boolean = false,
    val networkConfigured:Boolean=false,val networkStatus:NetworkStatus=NetworkStatus.DISABLED) {
    val networkConnected get() = networkStatus == NetworkStatus.CONNECTED
    override fun toString() = "AppState(redacted)"
}

class GhostViewModel internal constructor(application: Application, private val runtime: AppRuntime) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, (application as GhostApplication).runtime)
    private val mutable = MutableStateFlow(AppState(networkConfigured = runtime.networkConfigured, networkStatus = runtime.networkStatus))
    val state = mutable.asStateFlow()
    val developerAvailable get() = runtime.developerAvailable
    private var selected: String? = null
    private var workCount = 0
    init { refresh() }
    private fun run(quiet: Boolean = false, activity: NetworkStatus? = null, block: suspend (ConversationService) -> String? = { null }): kotlinx.coroutines.Job {
        if (!quiet) workCount++
        if (!quiet) mutable.value = mutable.value.copy(loading = true, error = null,
            networkStatus = activity?.takeIf { runtime.networkConfigured && !runtime.inDemo } ?: mutable.value.networkStatus)
        return viewModelScope.launch {
            var failure: String? = null
            try {
                runtime.use { service ->
                    try { failure = block(service) }
                    catch (e: AppFailure) { failure = appError(e.error) }
                    catch (e: CryptoFailure) { failure = cryptoError(e.error) }
                    catch (e: TransportFailure) { failure = if (runtime.networkConfigured && !runtime.inDemo) "Message could not be sent. Try Sync and retry." else "Recipient is not connected to this local simulator. Nothing was delivered." }
                    catch (e: org.ghostcloak.protocol.ApiFailure) { failure = networkError(e) }
                    catch (e: IllegalArgumentException) {failure="The network response or account settings were rejected."}
                    val active = runtime.currentService()
                    val identity = runtime.open(active)
                    val contacts = if (identity != null) active.contacts() else emptyList()
                    mutable.value = mutable.value.copy(identity = identity, contacts = contacts,
                        previews = contacts.mapNotNull { c -> active.messages(c.contact.remoteDeviceId).lastOrNull()?.let { c.contact.remoteDeviceId to it } }.toMap(),
                        messages = selected?.takeIf { id -> contacts.any { it.contact.remoteDeviceId == id } }?.let { active.messages(it) } ?: emptyList(),
                        demo = runtime.inDemo, protection = runtime.protection, ready = true,
                        networkConfigured=runtime.networkConfigured,networkStatus=runtime.networkStatus)
                }
            } catch (e: EndpointStorageFailure) { failure = "Encrypted storage is unavailable. Your identity was not reset. Close the app and investigate before continuing." }
            catch (e: CryptoFailure) { failure = cryptoError(e.error) }
            finally { if (!quiet) workCount--; mutable.value = mutable.value.copy(loading = workCount > 0, error = if (quiet) mutable.value.error else failure) }
        }
    }
    private val foregroundMutex = kotlinx.coroutines.sync.Mutex()
    suspend fun foregroundSync() {
        foregroundMutex.lock()
        try {
            while (true) {
                if (runtime.networkConfigured && !runtime.inDemo) {
                    val job = run(quiet = true) { if (runtime.canAutoSync) runtime.syncNetwork(it); null }
                    try { job.join() } finally { job.cancel() }
                }
                kotlinx.coroutines.delay(4000)
            }
        } finally { foregroundMutex.unlock() }
    }
    fun acceptRequest(id: String) = run { it.acceptRequest(id); null }
    fun deleteRequest(id: String) = run { it.deleteRequest(id); null }
    fun refresh() = run()
    fun connectNetwork() = run(activity = NetworkStatus.CONNECTING) { runtime.connectNetwork(it); null }
    fun syncNetwork() = run(activity = NetworkStatus.SYNCING) { runtime.syncNetwork(it); null }
    fun publishNetwork() = run { runtime.publishNetwork(); null }
    fun logoutNetwork() = run { runtime.logoutNetwork(); null }
    fun addNetwork(username: String, success: () -> Unit) = run {
        runtime.addNetwork(username, it); withContext(Dispatchers.Main) { success() }; null
    }
    fun create(username: String) = run(activity = NetworkStatus.CONNECTING) { runtime.create(it, username); null }
    fun rename(username: String) = run { it.rename(username); null }
    fun select(id: String) { selected = id; mutable.value = mutable.value.copy(messages = emptyList(), fingerprint = ""); refresh() }
    fun exportCard() = run { mutable.value = mutable.value.copy(card = it.exportCard(fresh = true)); null }
    fun importCard(text: String, success: () -> Unit) = run { it.importCard(text); withContext(Dispatchers.Main) { success() }; null }
    fun loadFingerprint(id: String, pending: Boolean) = run { mutable.value = mutable.value.copy(fingerprint = it.fingerprint(id, pending)); null }
    fun verify(id: String, expected: String) = run { it.verify(id, expected); null }
    fun trust(id: String, expected: String) = run { it.trustReplacement(id, expected); null }
    fun block(id: String, blocked: Boolean) = run { it.block(id, blocked); null }
    fun delete(id: String, localId: String) = run { it.delete(id, localId); null }
    fun send(id: String, text: String, success: () -> Unit) = run {
        val message = runtime.send(it, id, text)
        // A saved pending message owns its draft now; Sync retries it without creating a duplicate.
        withContext(Dispatchers.Main) { success() }
        when (message.state) {
            MessageState.PENDING -> "Message saved as pending. It will retry while the app is open."
            MessageState.FAILED -> "Message could not be sent. Review the conversation before trying again."
            else -> null
        }
    }
    fun startDemo() = run { runtime.startDemo(); selected = null; mutable.value = mutable.value.copy(card = "", fingerprint = ""); null }
    fun leaveDemo() = run { runtime.leaveDemo(); selected = null; mutable.value = mutable.value.copy(card = "", fingerprint = ""); null }
    private fun networkError(error: org.ghostcloak.protocol.ApiFailure) = when {
        error.code == "legacy_auth_requires_reset" -> "This identity uses an older account credential. Your keys were preserved; follow the documented development migration."
        error.code == "invalid_network_username" -> "Choose a valid network username in Settings, then connect again. Your identity was preserved."
        error.status == 401 -> "Connect again to renew your session. Your identity is preserved."
        error.status == 409 -> "The request conflicted with existing account information. If creating an account, choose another username in Settings and reconnect. Your identity was preserved."
        error.status == 404 -> "That username could not be found. Check the spelling and try again."
        error.status == 429 -> "Please wait before trying again. Pending messages remain on this device."
        error.status == 503 -> "Could not reach Ghost Cloak. Check your connection and try Sync. Your identity and pending messages are preserved."
        else -> "The network operation could not complete. Pending messages can be retried with Sync."
    }
    private fun appError(error: AppError) = when (error) {
        AppError.FRESH_CARD_REQUIRED -> "Import this person's updated contact card before approving the replacement identity."
        AppError.INVALID_USERNAME -> if (runtime.networkConfigured && !runtime.inDemo) "Use 3–24 letters, numbers or underscores, starting with a letter or number. Some names are reserved." else "Use 1–32 letters, numbers or underscores."
        AppError.INVALID_CARD -> "This contact card is invalid or uses an unsupported version."
        AppError.DUPLICATE_CONTACT -> "This device is already in your contacts."
        AppError.AMBIGUOUS_IDENTITY -> "This card conflicts with an existing identity. It was not imported."
        AppError.EMPTY_MESSAGE -> "Write a message before sending."
        AppError.MESSAGE_TOO_LARGE -> "Message exceeds the 16,384-byte UTF-8 limit. Shorten it and try again."
        AppError.INVALID_TEXT -> "This text contains invalid Unicode."
        AppError.BLOCKED -> "This contact is blocked on this device."
        AppError.LOCAL_CAPACITY -> "Local prototype capacity reached. No existing data was removed."
        AppError.CONTACT_UNAVAILABLE -> "This contact is unavailable."
    }
    private fun cryptoError(error: CryptoError) = when (error) {
        CryptoError.IdentityChanged -> "Security identity changed. Open contact security and compare the new safety number before continuing."
        CryptoError.VerificationFailed -> "The safety number no longer matches. Compare it again."
        CryptoError.UnknownSession, CryptoError.ReauthenticationRequired -> "Session unavailable. Explicit identity validation and reconnection are required."
        CryptoError.StorageFailure -> "Encrypted storage could not be accessed. No identity was reset."
        CryptoError.ResourceLimit -> "Local security-state capacity reached."
        else -> "The encrypted operation was rejected. No unauthenticated message was accepted."
    }
}
