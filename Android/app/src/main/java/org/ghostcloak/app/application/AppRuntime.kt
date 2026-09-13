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
    private val backgroundEligibility: (Boolean) -> Unit = {},
    private val notifications: LocalNotifications = NoLocalNotifications,
    private val expiryClock: ExpiryClock = ExpiryClock(System::currentTimeMillis, android.os.SystemClock::elapsedRealtime,
        { android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, 0) }),
    private val attachmentAccess: () -> Boolean = { true },
) : AutoCloseable {
    private val expiryChanges = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val expiryRevision: kotlinx.coroutines.flow.StateFlow<Long> get() = expiryChanges
    fun expiryNow() = expiryClock.now()
    suspend fun reconcileLocalExpiry() { if (canOpenExisting()) use { } }
    private val mutex = Mutex()
    private var store: EncryptedEndpointStore? = null
    private var local: ConversationService? = null
    private var network: NetworkController? = null
    private var notificationLedger: NotificationLedger? = null
    private var attachments: org.ghostcloak.attachments.AttachmentStore? = null
    @Volatile private var attachmentContacts:Set<String> = emptySet()
    private val notificationGate = Any()
    @Volatile private var activityVisible = false
    val syncActive get() = network?.syncActive == true
    val fetchRetryDelayMillis get() = network?.fetchRetryDelayMillis ?: 0L
    val canAutoSync get() = !inDemo && network?.canAutoSync == true
    val networkRequiresConnect get() = networkConfigured && !inDemo && network?.canAutoSync != true
    val networkConfigured get() = apiOrigin.isNotEmpty()
    val networkStatus get() = network?.status ?: if (networkConfigured) NetworkStatus.NEEDS_CONNECT else NetworkStatus.DISABLED
    private var demo: DemoSession? = null
    private val router = LocalEncryptedRouter()
    private var attached = false
    @Volatile var syncGeneration = 0L
        private set
    @Volatile private var foreground = false
    private var scheduledEligibility: Boolean? = null
    fun foregroundStarted() = synchronized(notificationGate) { foreground = true; cancelNotificationSafely() }
    fun foregroundStopped() = synchronized(notificationGate) { foreground = false; attachments?.invalidate() }
    fun notificationActivityVisible(visible: Boolean) = synchronized(notificationGate) {
        activityVisible = visible
        if (!visible) attachments?.invalidate()
        if (visible) cancelNotificationSafely()
    }
    internal fun revokeAttachmentAccess() { attachments?.invalidate() }
    private fun attachmentAllowed() = canAutoSync && foreground && activityVisible && attachmentAccess()
    private fun cancelNotificationSafely() { try { notifications.cancel() } catch (_: Exception) { } }
    private fun reconcileNotifications() {
        if (notifications === NoLocalNotifications) return
        try {
            val ledger = notificationLedger ?: return
            if (inDemo || !canAutoSync) { cancelNotificationSafely(); return }
            val entries = ledger.eligible()
            val visible = synchronized(notificationGate) { foreground || activityVisible }
            if (visible) { ledger.suppressAll(); cancelNotificationSafely(); return }
            if (entries.isEmpty()) { cancelNotificationSafely(); return }
            val waiting = entries.filter { it.state != NotificationLedger.ANNOUNCED }
            if (waiting.isEmpty() || !notifications.allowed()) return
            val quiet = waiting.any { it.state == NotificationLedger.POSTING } || notifications.active()
            // Durable reservation precedes OS publication. Recovery posts silently, never another sound.
            ledger.posting(waiting)
            val posted = synchronized(notificationGate) {
                if (foreground || activityVisible) false else notifications.post(quiet)
            }
            if (posted) ledger.announced(waiting)
        } catch (_: Exception) {
            // Optional presentation must never undo acceptance/ACK or leak failure details to logs/UI.
        }
    }
    suspend fun dismissNotifications() {
        if (canOpenExisting()) use { notificationLedger?.dismissPublished() }
    }
    private fun reconcileBackground() {
        val eligible = canAutoSync
        if (scheduledEligibility != eligible) {
            try {
                backgroundEligibility(eligible)
                scheduledEligibility = eligible
            } catch (_: Exception) { BackgroundDiagnostics.emit(BackgroundEvent.WORK_SKIP) }
        }
    }
    private fun canOpenExisting() = context.getSystemService(android.os.UserManager::class.java).isUserUnlocked &&
        java.io.File(context.noBackupFilesDir, "$endpointName.db").exists() &&
        java.io.File(context.noBackupFilesDir, "$endpointName.wrapped").exists()
    internal suspend fun readAppLock(): ByteArray? {
        val db = java.io.File(context.noBackupFilesDir, "$endpointName.db")
        val wrapped = java.io.File(context.noBackupFilesDir, "$endpointName.wrapped")
        if (!db.exists() && !wrapped.exists()) return null
        check(canOpenExisting())
        return use { store!!.transaction { store!!.read("app/access-lock") } }
    }
    internal suspend fun writeAppLock(bytes: ByteArray) = use {
        store!!.transaction { store!!.write("app/access-lock", bytes) }
    }
    suspend fun initializeBackground() = withContext(Dispatchers.IO) {
        val existing = mutex.withLock {
            if (!networkConfigured || !canOpenExisting()) {
                backgroundEligibility(false)
                scheduledEligibility = false
                false
            } else true
        }
        if (existing) use { } // Reconcile without registration or network traffic.
    }
    suspend fun backgroundSync(): BackgroundResult {
        if (!networkConfigured || !canOpenExisting()) return BackgroundResult.SKIP
        val requested = syncGeneration
        return use { service ->
            if (foreground || requested != syncGeneration || !canAutoSync || service.open() == null) BackgroundResult.SKIP
            else if (fetchRetryDelayMillis > 0) BackgroundResult.RETRY
            else {
                kotlinx.coroutines.withTimeout(30_000) { network!!.syncBackground(service) }
                syncGeneration++
                BackgroundResult.SUCCESS
            }
        }
    }
    val developerAvailable get() = DeveloperMode.available
    val inDemo get() = demo != null
    fun currentService() = demo?.service ?: local!!
    val protection get() = if (inDemo) "Isolated local simulator" else store?.protection?.name ?: "Unavailable"
    suspend fun <T> use(block: suspend (ConversationService) -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (local == null) {
                val records = LocalStateDiagnostics.open(context.noBackupFilesDir, endpointName, apiOrigin) {
                    EncryptedEndpointStore.open(context, endpointName)
                }
                try {
                val engine=SignalProtocolEngine(records)
                val cooldown = if (networkConfigured) storedFetchCooldown(records, java.net.URI(apiOrigin).host,
                    android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, 0))
                    else org.ghostcloak.transport.FetchCooldown()
                network=NetworkController(records,engine,apiOrigin,connection,cooldown)
                local = ConversationService(engine, LocalRepository(records, expiryClock))
                notificationLedger = NotificationLedger(records, expiryClock)
                store = records
                attachments = org.ghostcloak.attachments.AttachmentStore(records,java.io.File(context.noBackupFilesDir,"$endpointName-attachments"),
                    AttachmentDiagnostics.observer)
                } catch (e: Exception) { records.close(); throw e }
            }
            if (local!!.reconcileExpiry() > 0) expiryChanges.value++
            try { block(demo?.service ?: local!!) } finally {
                if (local!!.reconcileExpiry() > 0) expiryChanges.value++
                reconcileBackground()
                reconcileNotifications()
                attachmentContacts=local!!.contacts().filter { !it.contact.blocked && !it.contact.request &&
                    it.identity?.trustState!=org.ghostcloak.identity.IdentityTrustState.CHANGED }.map {it.contact.remoteDeviceId}.toSet()
                attachments?.reconcileReferences(
                    store!!.transaction { store!!.keys("app/message/").map { it.removePrefix("app/message/") }.toSet() },
                    store!!.transaction { store!!.keys("outbox/").map { it.removePrefix("outbox/") }.toSet() })
            }
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
            // Only this explicit creation of a brand-new local identity authorizes initial registration.
            // Existing installs without this durable intent are never guessed to be new accounts.
            if (!inDemo) store!!.transaction {
                check(store!!.keys("network/").isEmpty())
                store!!.write("app/new-network-account",byteArrayOf(1))
            }
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
    suspend fun recoverNetwork() {
        org.ghostcloak.protocol.requireApi(foreground && activityVisible && attachmentAccess(),"recovery_failed",401)
        network!!.recover { foreground && activityVisible && attachmentAccess() }
    }
    suspend fun setDisappearing(service: ConversationService, id: String, seconds: Int): Message {
        check(networkConfigured && !inDemo)
        return network!!.setDisappearing(service, id, seconds)
    }
    suspend fun syncNetwork(service: ConversationService, requested: Long? = null) {
        if (requested != null && requested != syncGeneration) return
        network!!.sync(service)
        syncGeneration++
    }
    suspend fun addNetwork(username: String, service: ConversationService) { network!!.add(username, service) }
    suspend fun publishNetwork() { network!!.publish() }
    suspend fun logoutNetwork() {
        attachments?.invalidate()
        try { network!!.logout() }
        finally { notificationLedger?.suppressAll(); cancelNotificationSafely() }
    }
    /** Process owner closes only after all foreground operations have finished (also used by reopen tests). */
    override fun close() { attachments?.invalidate(); attachments=null; store?.close(); store = null; local = null; network = null; notificationLedger = null; attached = false }
    /** Internal synthetic-file foundation only; no picker/recorder/UI entry point. */
    internal suspend fun prepareAttachment(source:java.io.InputStream,length:Long,kind:org.ghostcloak.attachments.AttachmentKind,seconds:Int):org.ghostcloak.attachments.AttachmentDescriptor {
        use { check(attachmentAllowed()) }
        return attachments!!.prepare(source,length,kind,seconds,::attachmentAllowed)
    }
    internal suspend fun uploadAttachment(id:String):org.ghostcloak.attachments.AttachmentDescriptor {
        use { check(attachmentAllowed()) }
        return attachments!!.upload(id,network!!.blobClient(::attachmentAllowed,{check(attachmentAllowed())}),::attachmentAllowed)
    }
    internal suspend fun sendPreparedAttachment(conversation:String,blob:String,peerSupportsAttachments:Boolean):Message=use { service ->
        check(attachmentAllowed())
        val entry=attachments!!.entry(blob) ?: error("attachment_missing")
        check(entry.state==org.ghostcloak.attachments.TransferState.UPLOADED && entry.upload)
        network!!.sendAttachment(service,conversation,entry.descriptor,peerSupportsAttachments) {
            attachments!!.bind(blob,"$conversation/${it.localId}")
        }
    }
    internal suspend fun downloadAttachment(conversation:String,message:String):org.ghostcloak.attachments.VerifiedAttachment {
        val descriptor=use { check(attachmentAllowed()); it.attachment(conversation,message) ?: error("attachment_missing") }
        val allowed = { attachmentAllowed() && conversation in attachmentContacts &&
            LocalRepository(store!!,expiryClock).attachmentAvailable(conversation,message) }
        return attachments!!.download(descriptor,"$conversation/$message",network!!.blobClient(allowed,{check(allowed())}),allowed)
    }
}
