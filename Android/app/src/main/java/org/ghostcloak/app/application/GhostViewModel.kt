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
    val cachedAttachments: Set<String> = emptySet(),
    val disappearingPolicies: Map<String, Int> = emptyMap(),
    val unreadExpiries: Map<String, List<ExpiryDeadline>> = emptyMap(),
    val unreadCount: Int = 0, val unreadByConversation: Map<String, Int> = emptyMap(), val contacts: List<ContactStatus> = emptyList(), val previews: Map<String, Message> = emptyMap(), val messages: List<Message> = emptyList(),
    val error: String? = null, val errorImportant: Boolean = false, val errorTransient: Boolean = false, val card: String = "", val fingerprint: String = "",
    val demo: Boolean = false, val protection: String = "", val ready: Boolean = false,
    val networkRequiresConnect:Boolean=true, val networkConfigured:Boolean=false,val networkStatus:NetworkStatus=NetworkStatus.DISABLED) {
    val networkConnected get() = networkStatus == NetworkStatus.CONNECTED
    override fun toString() = "AppState(redacted)"
}

class GhostViewModel internal constructor(application: Application, private val runtime: AppRuntime) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, (application as GhostApplication).runtime)
    private val mutable = MutableStateFlow(AppState(networkConfigured = runtime.networkConfigured, networkStatus = runtime.networkStatus))
    val state = mutable.asStateFlow()
    val developerAvailable get() = runtime.developerAvailable
    @Volatile private var selected: String? = null
    private var workCount = 0
    init {
        refresh()
        viewModelScope.launch { runtime.expiryRevision.collect { if (it > 0) run(quiet = true) } }
    }
    fun expiryNow() = runtime.expiryNow()
    private fun run(quiet: Boolean = false, activity: NetworkStatus? = null, block: suspend (ConversationService) -> String? = { null }): kotlinx.coroutines.Job {
        if (!quiet) workCount++
        if (!quiet) mutable.value = mutable.value.copy(loading = true, error = null, errorImportant = false, errorTransient = false,
            networkStatus = activity?.takeIf { runtime.networkConfigured && !runtime.inDemo } ?: mutable.value.networkStatus)
        return viewModelScope.launch {
            var failure: String? = null
            var important = false
            var transient = false
            var networkRecovered = false
            try {
                runtime.use { service ->
                    try { failure = block(service) }
                    catch (e: AppFailure) { failure = appError(e.error) }
                    catch (e: CryptoFailure) { failure = cryptoError(e.error); important = true }
                    catch (e: TransportFailure) { failure = if (runtime.networkConfigured && !runtime.inDemo) "Message could not be sent. Try Sync and retry." else "Recipient is not connected to this local simulator. Nothing was delivered." }
                    catch (e: org.ghostcloak.protocol.ApiFailure) { failure = networkError(e); transient = e.status == 429 || e.status == 503 }
                    catch (e: IllegalArgumentException) {important = true; failure="The network response or account settings were rejected."}
                    networkRecovered = quiet && failure == null && runtime.canAutoSync && runtime.networkStatus == NetworkStatus.CONNECTED
                    val active = runtime.currentService()
                    val identity = runtime.open(active)
                    val contacts = if (identity != null) active.contacts() else emptyList()
                    selected?.takeIf { id -> contacts.any { it.contact.remoteDeviceId == id } }?.let { active.markRead(it) }
                    val unread = if (identity != null) active.unreadCounts() else emptyMap()
                    mutable.value = mutable.value.copy(identity = identity, contacts = contacts, unreadCount = unread.values.sum(), unreadByConversation = unread,
                        cachedAttachments = runtime.cachedAttachments(selected),
                        disappearingPolicies = if (identity != null) active.policies() else emptyMap(),
                        unreadExpiries = if (identity != null) active.unreadExpiries() else emptyMap(),
                        previews = contacts.mapNotNull { c -> active.messagesForUi(c.contact.remoteDeviceId).lastOrNull()?.let { c.contact.remoteDeviceId to it } }.toMap(),
                        messages = selected?.takeIf { id -> contacts.any { it.contact.remoteDeviceId == id } }?.let { active.messagesForUi(it) } ?: emptyList(),
                        demo = runtime.inDemo, protection = runtime.protection, ready = true,
                        networkRequiresConnect=runtime.networkRequiresConnect,networkConfigured=runtime.networkConfigured,networkStatus=runtime.networkStatus)
                }
            } catch (e: EndpointStorageFailure) { important = true; failure = "Encrypted storage is unavailable. Your identity was not reset. Close the app and investigate before continuing." }
            catch (e: CryptoFailure) { failure = cryptoError(e.error); important = true }
            finally {
                if (!quiet) workCount--
                val previous = mutable.value
                val recovered = networkRecovered && previous.errorTransient
                // Routine polling stays quiet; critical failures remain visible until acted on.
                val publish = !quiet || (important && failure != null)
                mutable.value = previous.copy(loading = workCount > 0,
                    error = if (publish) failure else if (recovered) null else previous.error,
                    errorImportant = if (publish) important else if (recovered) false else previous.errorImportant,
                    errorTransient = if (publish) transient else if (recovered) false else previous.errorTransient)
            }
        }
    }
    private var foregroundCycle = 0L
    private val polling = org.ghostcloak.transport.ForegroundPolling()
    private val pollingWake = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private val foregroundMutex = kotlinx.coroutines.sync.Mutex()
    suspend fun foregroundSync() {
        foregroundMutex.lock()
        runtime.foregroundStarted()
        polling.reset()
        try {
            while (true) {
                if (runtime.networkConfigured && !runtime.inDemo) {
                    val cycle = ++foregroundCycle
                    val started = System.nanoTime()
                    NetworkDiagnostics.cycle(cycle, true)
                    val requested = runtime.syncGeneration
                    val job = run(quiet = true) { if (runtime.canAutoSync) runtime.syncNetwork(it, requested); null }
                    try { job.join() } finally {
                        job.cancel()
                        NetworkDiagnostics.cycle(cycle, false, (System.nanoTime() - started) / 1_000_000)
                    }
                }
                // Wait after completion; lifecycle restart begins with an immediate cycle.
                val interval = polling.completed(runtime.syncActive)
                // A send wakes idle waiting, but never bypasses a known FETCH cooldown.
                val waitingSince = System.nanoTime()
                kotlinx.coroutines.withTimeoutOrNull(interval) { pollingWake.receive() }
                kotlinx.coroutines.delay((2000L - (System.nanoTime() - waitingSince) / 1_000_000).coerceAtLeast(0))
                while (runtime.fetchRetryDelayMillis > 0) kotlinx.coroutines.delay(runtime.fetchRetryDelayMillis)
            }
        } finally { runtime.foregroundStopped(); foregroundMutex.unlock() }
    }
    fun acceptRequest(id: String) = run { it.acceptRequest(id); null }
    fun deleteRequest(id: String) = run { it.deleteRequest(id); null }
    fun refresh() = run()
    fun connectNetwork(): kotlinx.coroutines.Job {
        val recovery=state.value.networkStatus==NetworkStatus.RECOVERY_REQUIRED
        return run(activity=if(recovery) NetworkStatus.RECOVERING else NetworkStatus.CONNECTING) {
            if(recovery) runtime.recoverNetwork() else runtime.connectNetwork(it)
            null
        }
    }
    fun syncNetwork(): kotlinx.coroutines.Job {
        val requested = runtime.syncGeneration
        return run(activity = NetworkStatus.SYNCING) { runtime.syncNetwork(it, requested); null }
    }
    fun publishNetwork() = run { runtime.publishNetwork(); null }
    fun logoutNetwork() = run { runtime.logoutNetwork(); null }
    fun addNetwork(username: String, success: () -> Unit) = run {
        runtime.addNetwork(username, it); withContext(Dispatchers.Main) { success() }; null
    }
    fun create(username: String) = run(activity = NetworkStatus.CONNECTING) { runtime.create(it, username); null }
    fun rename(username: String) = run { it.rename(username); null }
    fun select(id: String) { selected = id; mutable.value = mutable.value.copy(messages = emptyList(), fingerprint = ""); refresh() }
    fun leaveConversation(id: String) { if (selected == id) selected = null }
    fun exportCard() = run { mutable.value = mutable.value.copy(card = it.exportCard(fresh = true)); null }
    fun importCard(text: String, success: () -> Unit) = run { it.importCard(text); withContext(Dispatchers.Main) { success() }; null }
    fun loadFingerprint(id: String, pending: Boolean) = run { mutable.value = mutable.value.copy(fingerprint = it.fingerprint(id, pending)); null }
    fun verify(id: String, expected: String) = run { it.verify(id, expected); null }
    fun trust(id: String, expected: String) = run { it.trustReplacement(id, expected); null }
    fun block(id: String, blocked: Boolean) = run { it.block(id, blocked); null }
    fun delete(id: String, localId: String) = run { it.delete(id, localId); null }
    fun clearConversation(id: String) = run { it.clearConversation(id); null }
    fun setDisappearing(id: String, seconds: Int) = run {
        val message = runtime.setDisappearing(it, id, seconds)
        pollingWake.trySend(Unit)
        if (message.state == MessageState.PENDING) "Timer applies locally. The encrypted update is pending and will retry during sync."
        else if (message.state == MessageState.FAILED) "Timer applies locally, but the update failed. Choose the timer again to retry."
        else null
    }
    fun send(id: String, text: String, success: () -> Unit) = run {
        val message = runtime.send(it, id, text)
        polling.reset()
        pollingWake.trySend(Unit)
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
        error.code == "recovery_required" -> "Ghost Cloak found an existing device identity but its account connection needs to be restored."
        error.code == "recovery_failed" -> "Account recovery could not be verified."
        error.code == "legacy_auth_requires_reset" -> "This identity uses an older account credential. Your keys were preserved; follow the documented development migration."
        error.code == "invalid_network_username" -> "Choose a valid network username in Settings, then connect again. Your identity was preserved."
        error.status == 401 -> "Connect again to renew your session. Your identity is preserved."
        error.code == "contact_unavailable" -> "That contact is temporarily unavailable. Try again shortly."
        error.status == 409 -> "The request conflicted with existing information. Your account and history were preserved."
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
        AppError.MESSAGE_TOO_LARGE -> "Message is too large. Shorten it and try again."
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
