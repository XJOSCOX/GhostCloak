package org.ghostcloak.app.attachments

import android.content.*
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.ghostcloak.app.application.GhostApplication
import org.ghostcloak.attachments.*
import java.io.File

data class MediaUi(val conversation: String = "", val message: String? = null,
    val filename: String = "", val photo: Boolean = false, val bytes: Long = 0,
    val busy: Boolean = false, val error: String? = null, val ready: Boolean = false,
    val preview: Bitmap? = null, val sending: Boolean = false, val uploadPrepared: Boolean = false) {
    override fun toString() = "MediaUi(redacted)"
}

/** One foreground presentation owner; no credential or descriptor enters Compose state. */
class AttachmentPresentation(private val app: GhostApplication) {
    private val supportNotConfirmed = "Attachment support hasn't been confirmed yet. Ask this contact to send you a short text from their updated Ghost Cloak app, then try again. Sending them a text is not enough."
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(MediaUi())
    val state = mutable.asStateFlow()
    private val session = MutableStateFlow(0L)
    val presentationSession = session.asStateFlow()
    private var transferEligible = false
    private val root = File(app.noBackupFilesDir,"media-presentation").apply { mkdirs() }
    private var file: File? = null
    private var blob: String? = null
    private var job: Job? = null
    private val inputGate=Any()
    @Volatile private var sourceSignal: android.os.CancellationSignal? = null
    @Volatile private var activeInput: java.io.InputStream? = null
    @Volatile private var epoch = 0L
    @Volatile private var visible = false
    @Volatile private var lease: Pair<Uri,File>? = null
    @Volatile private var leaseDeadline = 0L
    @Volatile private var leaseMessage: Pair<String,String>? = null
    val photos = InlinePhotos(scope, ::allowed, app.runtime::attachmentAvailableNow) { conversation, message, verifying ->
        val target=temporary()
        try {
            app.runtime.downloadAttachment(conversation,message).use { verified ->
                withContext(Dispatchers.IO) { target.outputStream().use(verified::copyTo) }
            }
            currentCoroutineContext().ensureActive(); check(allowed()); verifying()
            withContext(Dispatchers.IO) { PhotoPreparation.decode(target,512) }.also {
                currentCoroutineContext().ensureActive(); check(allowed())
                check(app.runtime.attachmentAvailableNow(conversation,message))
            }
        } finally { target.delete() }
    }
    init { root.listFiles()?.filter { it.isFile }?.forEach { it.delete() } }
    internal val presentationVisible get() = visible
    private fun allowed() = visible && app.appLock.state.value.canShowContent && app.runtime.attachmentTransfersAllowed
    private fun check(expected: Long) { check(expected == epoch && allowed()) { "attachment_unavailable" } }
    private fun check(expected: Long, trace: PhotoTrace?) {
        try { check(expected) }
        catch(failure: Exception) { trace?.begin(PhotoOperation.ACCESS_CHECK);throw failure }
    }
    private fun temporary() = File(root,AttachmentFormat.newId())
    fun start() { visible = true; session.value++; revokeLease() }
    private fun sweepPresentation() { root.listFiles()?.filter { it.isFile && it!=lease?.second }?.forEach { it.delete() } }
    fun stop() { visible = false; photos.clear(); clear(); sweepPresentation() }
    fun locked() { photos.clear(); clear(); revokeLease(); sweepPresentation() }
    fun clear() {
        val revoked=synchronized(inputGate) {
            epoch++
            (sourceSignal to activeInput).also { sourceSignal=null;activeInput=null }
        }
        job?.cancel(); job=null
        revoked.first?.cancel()
        try { revoked.second?.close() } catch (_: Exception) {}
        file?.delete(); file=null; blob=null
        // Removing references prevents the UI from presenting it after background/lock.
        mutable.value=MediaUi()
    }
    fun cancel() {
        val orphan = blob
        clear()
        if (orphan != null) scope.launch { try { app.runtime.discardAttachment(orphan) } catch (_: Exception) {} }
    }
    private fun launch(trace: PhotoTrace? = null, block: suspend (Long)->Unit) {
        job?.cancel()
        val expected = epoch
        job = scope.launch {
            try { if(trace!=null) trace.step(PhotoOperation.ACCESS_CHECK) { check(expected) } else check(expected); block(expected) }
            catch (failure: CancellationException) { throw failure }
            catch (failure: OutOfMemoryError) {
                trace?.failed(failure)
                if(mutable.value.photo) PhotoDiagnostics.failed(PhotoFailureReason.MEMORY)
                if(expected==epoch) { file?.delete();file=null;mutable.value=mutable.value.copy(busy=false,ready=false,preview=null,sending=mutable.value.uploadPrepared,error=PhotoFailureReason.MEMORY.userMessage) }
            }
            catch (failure: Exception) {
                trace?.failed(failure)
                if (expected == epoch && mutable.value.photo && mutable.value.message==null && blob==null) {
                    if(failure.message=="attachment_size") PhotoDiagnostics.emit(PhotoEvent.SIZE_OVER)
                    PhotoDiagnostics.failed(when {
                        failure is PhotoFailure -> failure.reason
                        failure.message=="attachment_size" -> PhotoFailureReason.SOURCE_LIMIT
                        failure is java.io.IOException || failure is SecurityException -> PhotoFailureReason.READ
                        else -> PhotoFailureReason.NORMALIZE
                    })
                }
                if (expected == epoch && blob == null && mutable.value.message == null) { file?.delete(); file=null }
                if (expected == epoch) mutable.value=mutable.value.copy(busy=false,ready=false,
                    sending=mutable.value.sending && blob!=null,
                    error=when {
                        failure is org.ghostcloak.protocol.ApiFailure && failure.status==404 -> "Attachment unavailable or expired."
                        mutable.value.message!=null -> "Download failed · Retry"
                        blob!=null -> "Upload failed · Retry"
                        failure is PhotoFailure -> failure.reason.userMessage
                        mutable.value.photo && failure.message=="attachment_size" -> PhotoFailureReason.SOURCE_LIMIT.userMessage
                        mutable.value.photo && (failure is java.io.IOException || failure is SecurityException) -> PhotoFailureReason.READ.userMessage
                        failure.message=="attachment_size" -> "File is too large. Maximum document size is 20 MiB."
                        mutable.value.photo -> PhotoFailureReason.NORMALIZE.userMessage
                        failure.message=="attachment_empty" -> "This document is empty. Choose another document."
                        failure.message=="attachment_unavailable" -> "Document preparation was interrupted because the app wasn't ready. Keep this conversation open and choose the document again."
                        failure is SecurityException -> "Access to this document was denied. Choose it again from the system picker."
                        failure is java.io.IOException -> "Couldn't read this document. Make sure it is downloaded and available, then choose it again."
                        else -> "Couldn't prepare this document. Choose it again."
                    })
            }
        }
    }
    fun select(conversation: String, uri: Uri, photo: Boolean) {
        clear()
        mutable.value=MediaUi(conversation=conversation,photo=photo,busy=true)
        if(photo) PhotoDiagnostics.emit(PhotoEvent.START)
        val trace=PhotoTrace(document=!photo)
        launch(trace) { expected ->
            trace?.begin(PhotoOperation.ELIGIBILITY_CHECK)
            if (!app.runtime.supportsAttachments(conversation)) {
                mutable.value=mutable.value.copy(busy=false,error=supportNotConfirmed)
                return@launch
            }
            trace?.ok(PhotoOperation.ELIGIBILITY_CHECK)
            val staged=temporary()
            var prepared: File? = null
            try {
                val name = withContext(Dispatchers.IO) {
                    trace?.begin(PhotoOperation.ACCESS_CHECK)
                    check(expected,trace)
                    trace?.ok()
                    trace?.begin(PhotoOperation.INPUT_OPEN)
                    require(uri.scheme == "content")
                    val label = if (photo) "Photo" else try {
                        app.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {
                            if(it.moveToFirst()) AttachmentFormat.sanitizeFilename(it.getString(0).orEmpty()) else "Attachment"
                        } ?: "Attachment"
                    } catch (_: Exception) { "Attachment" }
                    val signal=android.os.CancellationSignal()
                    synchronized(inputGate) { check(expected,trace);sourceSignal=signal }
                    val descriptor=app.contentResolver.openFileDescriptor(uri,"r",signal)
                        ?: if(photo) throw PhotoFailure(PhotoFailureReason.READ) else error("attachment_missing")
                    val input=android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)
                    trace?.ok(PhotoOperation.INPUT_OPEN)
                    trace?.begin(PhotoOperation.INPUT_COPY)
                    try { input.use {
                        synchronized(inputGate) { check(expected,trace);activeInput=input }
                        staged.outputStream().use { output ->
                        PhotoPreparation.boundedCopy(input,output,if(photo) PhotoPreparation.SOURCE_CAP else AttachmentKind.DOCUMENT.maximumBytes) { check(expected,trace) }
                    } } } finally {
                        synchronized(inputGate) {
                            if(activeInput===input) activeInput=null
                            if(sourceSignal===signal) sourceSignal=null
                        }
                    }
                    trace?.ok(PhotoOperation.INPUT_COPY)
                    if (photo) {
                        prepared=temporary()
                        PhotoPreparation.normalizeTraced(staged,prepared!!,trace!!) { check(expected,trace) }
                        staged.delete()
                    } else prepared=staged
                    label
                }
                check(expected,trace)
                file=prepared
                val preview=if(photo) withContext(Dispatchers.IO) { PhotoPreparation.decodeTraced(prepared!!,512,trace!!) } else null
                check(expected,trace)
                mutable.value=mutable.value.copy(filename=name,bytes=prepared!!.length(),preview=preview,busy=false,ready=true)
                if(photo) PhotoDiagnostics.emit(PhotoEvent.OK)
            } finally {
                staged.takeIf { it != file }?.delete()
                prepared?.takeIf { it != file }?.delete()
                // No persistable URI grant is taken. Drop any transient read grant when practical.
                try { app.revokeUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            }
        }
    }
    fun send(refresh: ()->Unit) {
        val before=mutable.value
        if (before.conversation.isEmpty() || before.message != null || before.busy || (!before.ready && !before.uploadPrepared)) return
        mutable.value=before.copy(busy=true,error=null,sending=before.photo)
        launch { expected ->
            if(!app.runtime.supportsAttachments(before.conversation)) {
                mutable.value=mutable.value.copy(busy=false,error=supportNotConfirmed)
                return@launch
            }
            if(blob==null) {
                val source=file ?: error("attachment_missing")
                val duration=app.runtime.attachmentDuration(before.conversation)
                val descriptor=app.runtime.prepareAttachment(source.inputStream(),source.length(),
                    if(before.photo) AttachmentKind.IMAGE else AttachmentKind.DOCUMENT,duration,
                    if(before.photo) null else before.filename)
                check(expected); blob=descriptor.id;file?.delete();file=null
                mutable.value=mutable.value.copy(uploadPrepared=true)
            }
            val id=blob!!
            app.runtime.uploadAttachment(id); check(expected)
            app.runtime.sendPreparedAttachment(before.conversation,id,true,requireNegotiatedSupport=true)
            check(expected); clear(); refresh()
        }
    }
    fun download(conversation: String, message: String, photo: Boolean, filename: String, refresh: ()->Unit = {}) {
        clear()
        mutable.value=MediaUi(conversation=conversation,message=message,filename=filename,photo=photo,busy=true)
        launch { expected ->
            val target=temporary()
            try {
                app.runtime.downloadAttachment(conversation,message).use { verified ->
                    withContext(Dispatchers.IO) { target.outputStream().use(verified::copyTo) }
                }
                check(expected)
                // Framework decoders see bytes only after full digest/AEAD/finality verification.
                val preview=if(photo) withContext(Dispatchers.IO) { PhotoPreparation.decode(target) } else null
                check(expected)
                check(app.runtime.attachmentAvailableNow(conversation,message))
                if(photo) target.delete() else file=target
                mutable.value=mutable.value.copy(busy=false,ready=true,bytes=target.length(),preview=preview)
                refresh()
            } finally { if(file!=target) target.delete() }
        }
    }
    fun reconcile(messages: Set<String>) {
        val current=mutable.value
        if(current.message!=null && current.message !in messages) clear()
    }
    fun reconcileStored() {
        val eligible=allowed()
        if(eligible!=transferEligible) { transferEligible=eligible; session.value++ }
        photos.reconcile()
        val current=mutable.value
        if(current.message!=null && !app.runtime.attachmentAvailableNow(current.conversation,current.message)) clear()
        leaseMessage?.let { if(!app.runtime.attachmentAvailableNow(it.first,it.second)) revokeLease() }
    }
    private fun revokeLease() {
        lease?.let { (uri,source) -> try { app.revokeUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION) } finally { source.delete() } }
        lease=null; leaseMessage=null
    }
    /** Explicit external-view handoff only; it may retain a copy outside our control. */
    suspend fun documentIntent(): Intent {
        val expected=epoch
        val current=mutable.value
        check(allowed() && current.ready && !current.photo && current.message!=null)
        check(app.runtime.attachmentStillAvailable(current.conversation,current.message))
        check(expected)
        revokeLease()
        val source=file ?: error("attachment_missing")
        val uri=Uri.parse("content://${app.packageName}.attachment-view/${AttachmentFormat.newId()}")
        lease=uri to source; file=null
        leaseMessage=current.conversation to current.message
        leaseDeadline=android.os.SystemClock.elapsedRealtime()+60_000
        scope.launch { delay(60_000); if(lease?.first==uri) revokeLease() }
        // Only a bounded signature hint; neither the filename nor provider MIME is trusted.
        val view=Intent(Intent.ACTION_VIEW).setDataAndType(uri,DocumentType.sniff(source))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        view.clipData=ClipData.newRawUri("Attachment",uri)
        return Intent.createChooser(view,"Open document").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    internal fun leaseFile(uri: Uri): File {
        val pair=lease ?: throw java.io.FileNotFoundException()
        val reference=leaseMessage ?: throw java.io.FileNotFoundException()
        if(pair.first!=uri || android.os.SystemClock.elapsedRealtime()>=leaseDeadline ||
            !app.appLock.state.value.canShowContent || !app.runtime.attachmentAvailableNow(reference.first,reference.second))
            throw java.io.FileNotFoundException()
        return pair.second
    }
}
