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
    val contacts: List<ContactStatus> = emptyList(), val messages: List<Message> = emptyList(),
    val error: String? = null, val card: String = "", val fingerprint: String = "",
    val demo: Boolean = false, val protection: String = "", val ready: Boolean = false) {
    override fun toString() = "AppState(redacted)"
}

class GhostViewModel(application: Application) : AndroidViewModel(application) {
    private val runtime = (application as GhostApplication).runtime
    private val mutable = MutableStateFlow(AppState())
    val state = mutable.asStateFlow()
    val developerAvailable get() = runtime.developerAvailable
    private var selected: String? = null
    private var workCount = 0
    init { refresh() }
    private fun run(block: suspend (ConversationService) -> Unit = {}) {
        workCount++
        mutable.value = mutable.value.copy(loading = true, error = null)
        viewModelScope.launch {
            var failure: String? = null
            try {
                runtime.use { service ->
                    try { block(service) }
                    catch (e: AppFailure) { failure = appError(e.error) }
                    catch (e: CryptoFailure) { failure = cryptoError(e.error) }
                    catch (e: TransportFailure) { failure = "Recipient is not connected to this local simulator. Nothing was delivered." }
                    val active = runtime.currentService()
                    val identity = runtime.open(active)
                    val contacts = if (identity != null) active.contacts() else emptyList()
                    mutable.value = mutable.value.copy(identity = identity, contacts = contacts,
                        messages = selected?.takeIf { id -> contacts.any { it.contact.remoteDeviceId == id } }?.let { active.messages(it) } ?: emptyList(),
                        demo = runtime.inDemo, protection = runtime.protection, ready = true)
                }
            } catch (e: EndpointStorageFailure) { failure = "Encrypted storage is unavailable. Your identity was not reset. Close the app and investigate before continuing." }
            catch (e: CryptoFailure) { failure = cryptoError(e.error) }
            finally { workCount--; mutable.value = mutable.value.copy(loading = workCount > 0, error = failure) }
        }
    }
    fun refresh() = run()
    fun create(username: String) = run { it.create(username) }
    fun rename(username: String) = run { it.rename(username) }
    fun select(id: String) { selected = id; mutable.value = mutable.value.copy(messages = emptyList(), fingerprint = ""); refresh() }
    fun exportCard() = run { mutable.value = mutable.value.copy(card = it.exportCard(fresh = true)) }
    fun importCard(text: String, success: () -> Unit) = run { it.importCard(text); withContext(Dispatchers.Main) { success() } }
    fun loadFingerprint(id: String, pending: Boolean) = run { mutable.value = mutable.value.copy(fingerprint = it.fingerprint(id, pending)) }
    fun verify(id: String, expected: String) = run { it.verify(id, expected) }
    fun trust(id: String, expected: String) = run { it.trustReplacement(id, expected) }
    fun block(id: String, blocked: Boolean) = run { it.block(id, blocked) }
    fun delete(id: String, localId: String) = run { it.delete(id, localId) }
    fun send(id: String, text: String, success: () -> Unit) = run { runtime.send(it, id, text); withContext(Dispatchers.Main) { success() } }
    fun startDemo() = run { runtime.startDemo(); selected = null; mutable.value = mutable.value.copy(card = "", fingerprint = "") }
    fun leaveDemo() = run { runtime.leaveDemo(); selected = null; mutable.value = mutable.value.copy(card = "", fingerprint = "") }
    private fun appError(error: AppError) = when (error) {
        AppError.FRESH_CARD_REQUIRED -> "Import this person's updated contact card before approving the replacement identity."
        AppError.INVALID_USERNAME -> "Use 1–32 letters, numbers or underscores."
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
