package org.ghostcloak.app.attachments

import android.content.*
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.media.MediaPlayer
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.ghostcloak.app.application.GhostApplication
import org.ghostcloak.attachments.*
import java.io.File

data class MediaUi(val conversation: String = "", val message: String? = null,
    val filename: String = "", val photo: Boolean = false, val bytes: Long = 0,
    val busy: Boolean = false, val error: String? = null, val ready: Boolean = false,
    val preview: Bitmap? = null, val sending: Boolean = false, val uploadPrepared: Boolean = false,
    val viewOnce:Boolean=false,val voice:Boolean=false,val recording:Boolean=false,
    val durationMillis:Long=0,val caption:String="",val voiceMask:VoiceMask=VoiceMask.OFF,
    val voicePreviewPlaying:Boolean=false) {
    override fun toString() = "MediaUi(redacted)"
}

data class VoicePlaybackUi(val conversation:String="",val message:String="",val playing:Boolean=false,
    val busy:Boolean=false,val progressMillis:Long=0,val durationMillis:Long=0,val error:Boolean=false)

/** One foreground presentation owner; no credential or descriptor enters Compose state. */
class AttachmentPresentation(private val app: GhostApplication) {
    private val supportNotConfirmed = "This contact hasn't advertised attachment support. Update their Ghost Cloak app and let it connect, then try again. Text messages still work."
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(MediaUi())
    val state = mutable.asStateFlow()
    private val voiceMutable=MutableStateFlow(VoicePlaybackUi())
    val voicePlayback=voiceMutable.asStateFlow()
    private val session = MutableStateFlow(0L)
    val presentationSession = session.asStateFlow()
    private var transferEligible = false
    private val scratch = PlaintextScratch(File(app.noBackupFilesDir,"media-presentation"))
    private val voiceCapture=VoiceCapture(app,scratch)
    private var file: File? = null
    private var maskedFile: File? = null
    private var composePlayer: MediaPlayer? = null
    private var blob: String? = null
    private var job: Job? = null
    private var recorderTicker:Job?=null
    private var player:MediaPlayer?=null
    private var playbackFile:File?=null
    private var playbackJob:Job?=null
    private var playbackTicker:Job?=null
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
        } finally { scratch.delete(target) }
    }
    internal val presentationVisible get() = visible
    private fun allowed() = scratch.clean && !app.localOperationGate.blocked && visible && app.appLock.state.value.canShowContent && app.runtime.attachmentTransfersAllowed
    private fun check(expected: Long) { check(expected == epoch && allowed()) { "attachment_unavailable" } }
    private fun check(expected: Long, trace: PhotoTrace?) {
        try { check(expected) }
        catch(failure: Exception) { trace?.begin(PhotoOperation.ACCESS_CHECK);throw failure }
    }
    private fun temporary() = scratch.newFile()
    private suspend fun transformVoice(original:File,preset:VoiceMask,expected:Long):File {
        val target=temporary()
        try {
            withContext(Dispatchers.Default) { VoiceMasking.transform(original,target,preset) }
            check(expected)
            return target
        } catch (failure:Throwable) {
            scratch.delete(target)
            throw failure
        }
    }
    fun start() {
        app.localOperationGate.requireNormal()
        revokeLease()
        if (!scratch.clean) scratch.sweep()
        visible = scratch.clean
        session.value++
    }
    fun stop() { if(app.localOperationGate.blocked) return; visible = false; photos.clear(); clear(); scratch.sweep() }
    fun locked() { if(app.localOperationGate.blocked) return; visible = false; photos.clear(); clear(); scratch.sweep() }
    fun clear() {
        if(app.localOperationGate.blocked) return
        recorderTicker?.cancel();recorderTicker=null;voiceCapture.cancel()
        stopComposePreview()
        stopVoicePlayback()
        val consumed=mutable.value.takeIf {it.viewOnce && it.message!=null}
        val revoked=synchronized(inputGate) {
            epoch++
            (sourceSignal to activeInput).also { sourceSignal=null;activeInput=null }
        }
        job?.cancel(); job=null
        revoked.first?.cancel()
        try { revoked.second?.close() } catch (_: Exception) {}
        revokeLease()
        file?.let(scratch::delete); file=null; blob=null
        maskedFile?.let(scratch::delete);maskedFile=null
        // Removing references prevents the UI from presenting it after background/lock.
        mutable.value=MediaUi()
        consumed?.let { current -> scope.launch {
            try { app.runtime.consumeViewOnce(current.conversation,requireNotNull(current.message)) }
            catch (_: Exception) { /* Durable REVEALING remains fail-closed on restart. */ }
        } }
    }
    fun startVoiceRecording(conversation:String) {
        app.localOperationGate.requireNormal()
        clear()
        mutable.value=MediaUi(conversation=conversation,voice=true,busy=true)
        launch { expected ->
            if(!app.runtime.supportsAttachments(conversation) || !app.runtime.supportsMedia(conversation)) {
                mutable.value=mutable.value.copy(busy=false,error="This contact needs an updated Ghost Cloak app for voice notes.")
                return@launch
            }
            check(expected)
            try {
                val preset=app.runtime.privacyDefaults().voiceMask
                check(expected)
                mutable.value=mutable.value.copy(voiceMask=when(preset) {
                    org.ghostcloak.messaging.VoiceMaskPreference.ORIGINAL -> VoiceMask.OFF
                    org.ghostcloak.messaging.VoiceMaskPreference.SUBTLE -> VoiceMask.SUBTLE
                    org.ghostcloak.messaging.VoiceMaskPreference.STRONG -> VoiceMask.STRONG
                    org.ghostcloak.messaging.VoiceMaskPreference.SYNTHETIC -> VoiceMask.SYNTHETIC
                })
                file=voiceCapture.start(::stopVoiceRecording,::cancel)
                check(expected)
                mutable.value=mutable.value.copy(busy=false,recording=true)
                recorderTicker=scope.launch { while(isActive && voiceCapture.recording) {
                    delay(250)
                    mutable.value=mutable.value.copy(durationMillis=voiceCapture.elapsedMillis)
                } }
            } catch (failure:Exception) {
                voiceCapture.cancel();file?.let(scratch::delete);file=null
                if(expected==epoch) mutable.value=mutable.value.copy(busy=false,recording=false,error="Could not start recording. Check microphone access and try again.")
            }
        }
    }
    fun stopVoiceRecording() {
        recorderTicker?.cancel();recorderTicker=null
        val result=voiceCapture.stop()
        if(result==null) {
            file=null
            mutable.value=mutable.value.copy(recording=false,ready=false,error="Recording was too short or too large. Try again.")
        } else {
            file=result.file
            mutable.value=mutable.value.copy(recording=false,ready=true,bytes=result.bytes,durationMillis=result.durationMillis)
        }
    }
    private fun stopComposePreview() {
        try { composePlayer?.stop() } catch (_: Exception) {}
        try { composePlayer?.release() } catch (_: Exception) {}
        composePlayer=null
        mutable.value=mutable.value.copy(voicePreviewPlaying=false)
    }
    fun selectVoiceMask(preset:VoiceMask) {
        val current=mutable.value
        if(!current.voice || current.message!=null || current.recording || current.busy || current.uploadPrepared) return
        stopComposePreview()
        maskedFile?.let(scratch::delete);maskedFile=null
        mutable.value=mutable.value.copy(voiceMask=preset,error=null)
    }
    fun toggleVoicePreview() {
        val current=mutable.value
        if(!current.voice || !current.ready || current.message!=null || current.busy) return
        if(composePlayer!=null) {stopComposePreview();return}
        mutable.value=current.copy(busy=true,error=null)
        launch { expected ->
            try {
                val source=if(current.voiceMask==VoiceMask.OFF) file ?: error("voice_missing") else {
                    maskedFile ?: transformVoice(file ?: error("voice_missing"),current.voiceMask,expected)
                        .also { maskedFile=it }
                }
                check(expected)
                val media=MediaPlayer()
                try {
                    media.setDataSource(source.absolutePath)
                    withContext(Dispatchers.IO) { media.prepare() }
                    check(expected)
                    composePlayer=media
                    media.setOnCompletionListener {stopComposePreview()}
                    media.start()
                    mutable.value=mutable.value.copy(busy=false,voicePreviewPlaying=true)
                } catch (failure:Exception) {media.release();throw failure}
            } catch (_:Exception) {
                maskedFile?.let(scratch::delete);maskedFile=null
                file?.let(scratch::delete);file=null
                if(expected==epoch) mutable.value=mutable.value.copy(busy=false,ready=false,
                    error="Voice masking failed. Choose Original or record again.")
            }
        }
    }
    fun setCaption(value:String) {
        if(value.length<=512 && mutable.value.message==null && !mutable.value.recording && !mutable.value.viewOnce)
            mutable.value=mutable.value.copy(caption=value)
    }
    private fun stopVoicePlayback() {
        playbackJob?.cancel();playbackJob=null
        playbackTicker?.cancel();playbackTicker=null
        try {player?.stop()} catch (_:Exception) {}
        try {player?.release()} catch (_:Exception) {}
        player=null
        playbackFile?.let(scratch::delete);playbackFile=null
        voiceMutable.value=VoicePlaybackUi()
    }
    fun toggleVoicePlayback(conversation:String,message:String) {
        val current=voiceMutable.value
        if(current.conversation==conversation && current.message==message && player!=null) {
            if(current.playing) {
                player?.pause();voiceMutable.value=current.copy(playing=false)
            } else {
                player?.start();voiceMutable.value=current.copy(playing=true)
            }
            return
        }
        stopVoicePlayback()
        voiceMutable.value=VoicePlaybackUi(conversation,message,busy=true)
        playbackJob=scope.launch {
            val target=temporary()
            var pending:MediaPlayer?=null
            try {
                check(allowed() && app.runtime.attachmentAvailableNow(conversation,message))
                app.runtime.downloadAttachment(conversation,message).use { verified ->
                    withContext(Dispatchers.IO) {target.outputStream().use(verified::copyTo)}
                }
                check(allowed() && app.runtime.attachmentAvailableNow(conversation,message))
                val media=MediaPlayer()
                pending=media
                media.setDataSource(target.absolutePath)
                withContext(Dispatchers.IO) {media.prepare()}
                check(allowed() && app.runtime.attachmentAvailableNow(conversation,message))
                playbackFile=target;player=media
                pending=null
                media.setOnCompletionListener {stopVoicePlayback()}
                media.start()
                voiceMutable.value=VoicePlaybackUi(conversation,message,playing=true,durationMillis=media.duration.toLong())
                playbackTicker=scope.launch {while(isActive && player===media) {
                    delay(250)
                    if(!allowed() || !app.runtime.attachmentAvailableNow(conversation,message)) {stopVoicePlayback();break}
                    voiceMutable.value=voiceMutable.value.copy(progressMillis=media.currentPosition.toLong(),playing=media.isPlaying)
                }}
            } catch (_:Exception) {
                try {pending?.release()} catch (_:Exception) {}
                scratch.delete(target)
                stopVoicePlayback()
                voiceMutable.value=VoicePlaybackUi(conversation,message,error=true)
            }
        }
    }
    fun seekVoice(conversation:String,message:String,millis:Long) {
        val current=voiceMutable.value
        if(current.conversation==conversation && current.message==message && player!=null) {
            player?.seekTo(millis.coerceIn(0,current.durationMillis).toInt())
            voiceMutable.value=current.copy(progressMillis=millis.coerceIn(0,current.durationMillis))
        }
    }
    fun toggleViewOnce() {
        val current=mutable.value
        if(current.message==null && current.photo && current.caption.isBlank() && !current.busy && !current.sending)
            mutable.value=current.copy(viewOnce=!current.viewOnce)
    }
    fun cancel() {
        val orphan = blob
        clear()
        if (orphan != null) scope.launch { try { app.runtime.discardAttachment(orphan) } catch (_: Exception) {} }
    }
    private fun launch(trace: PhotoTrace? = null, block: suspend (Long)->Unit) {
        app.localOperationGate.requireNormal()
        job?.cancel()
        val expected = epoch
        job = scope.launch {
            try { if(trace!=null) trace.step(PhotoOperation.ACCESS_CHECK) { check(expected) } else check(expected); block(expected) }
            catch (failure: CancellationException) { throw failure }
            catch (failure: OutOfMemoryError) {
                trace?.failed(failure)
                maskedFile?.let(scratch::delete);maskedFile=null
                if(mutable.value.photo) PhotoDiagnostics.failed(PhotoFailureReason.MEMORY)
                if(expected==epoch) { file?.let(scratch::delete);file=null;mutable.value=mutable.value.copy(busy=false,ready=false,preview=null,sending=mutable.value.uploadPrepared,error=PhotoFailureReason.MEMORY.userMessage) }
            }
            catch (failure: Exception) {
                trace?.failed(failure)
                if(expected==epoch && mutable.value.voice && blob==null) {
                    maskedFile?.let(scratch::delete);maskedFile=null
                }
                if (expected == epoch && mutable.value.photo && mutable.value.message==null && blob==null) {
                    if(failure.message=="attachment_size") PhotoDiagnostics.emit(PhotoEvent.SIZE_OVER)
                    PhotoDiagnostics.failed(when {
                        failure is PhotoFailure -> failure.reason
                        failure.message=="attachment_size" -> PhotoFailureReason.SOURCE_LIMIT
                        failure is java.io.IOException || failure is SecurityException -> PhotoFailureReason.READ
                        else -> PhotoFailureReason.NORMALIZE
                    })
                }
                if (expected == epoch && blob == null && mutable.value.message == null) { file?.let(scratch::delete); file=null }
                if (expected == epoch) mutable.value=mutable.value.copy(busy=false,ready=false,
                    sending=mutable.value.sending && blob!=null,
                    error=when {
                        failure is org.ghostcloak.protocol.ApiFailure && failure.status==404 -> "Attachment unavailable or expired."
                        mutable.value.message!=null -> "Download failed · Retry"
                        blob!=null -> "Upload failed · Retry"
                        mutable.value.voice -> "Voice note could not be prepared. Record it again."
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
        app.localOperationGate.requireNormal()
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
                        scratch.delete(staged)
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
                staged.takeIf { it != file }?.let(scratch::delete)
                prepared?.takeIf { it != file }?.let(scratch::delete)
                // No persistable URI grant is taken. Drop any transient read grant when practical.
                try { app.revokeUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            }
        }
    }
    fun send(refresh: ()->Unit) {
        val before=mutable.value
        if (before.conversation.isEmpty() || before.message != null || before.busy || (!before.ready && !before.uploadPrepared)) return
        val caption=try { org.ghostcloak.messaging.ConversationPayload.validateCaption(before.caption) }
            catch (_:Exception) { mutable.value=before.copy(error="Caption is too long or contains unsupported characters.");return }
        if(before.viewOnce && caption.isNotEmpty()) return
        mutable.value=before.copy(busy=true,error=null,sending=before.photo)
        stopComposePreview()
        launch { expected ->
            if(!app.runtime.supportsAttachments(before.conversation)) {
                mutable.value=mutable.value.copy(busy=false,error=supportNotConfirmed)
                return@launch
            }
            if((before.voice || caption.isNotEmpty()) && !app.runtime.supportsMedia(before.conversation)) {
                mutable.value=mutable.value.copy(busy=false,error="This contact needs an updated Ghost Cloak app for this media message.")
                return@launch
            }
            if(blob==null) {
                val source=if(before.voice && before.voiceMask!=VoiceMask.OFF) {
                    val original=file ?: error("voice_missing")
                    try {
                        val transformed=maskedFile ?: transformVoice(original,before.voiceMask,expected)
                            .also { maskedFile=it }
                        check(expected)
                        check(VoiceMasking.validate(transformed) in 500..300_000)
                        check(scratch.delete(original))
                        file=null
                        transformed
                    } catch (failure:Exception) {
                        file?.let(scratch::delete);file=null
                        maskedFile?.let(scratch::delete);maskedFile=null
                        mutable.value=mutable.value.copy(busy=false,ready=false,
                            error="Voice masking failed. Choose Original or record again.")
                        return@launch
                    }
                } else file ?: error("attachment_missing")
                val duration=app.runtime.attachmentDuration(before.conversation)
                val descriptor=app.runtime.prepareAttachment(source.inputStream(),source.length(),
                    if(before.photo) AttachmentKind.IMAGE else if(before.voice) AttachmentKind.VOICE_NOTE else AttachmentKind.DOCUMENT,
                    duration,if(before.voice || before.photo) null else before.filename,
                    if(before.voice) if(before.voiceMask==VoiceMask.OFF) before.durationMillis else VoiceMasking.validate(source) else null)
                check(expected); blob=descriptor.id;file?.let(scratch::delete);file=null
                maskedFile?.let(scratch::delete);maskedFile=null
                mutable.value=mutable.value.copy(uploadPrepared=true)
            }
            val id=blob!!
            app.runtime.uploadAttachment(id); check(expected)
            app.runtime.sendPreparedAttachment(before.conversation,id,true,requireNegotiatedSupport=true,
                viewOnce=before.viewOnce,caption=caption)
            check(expected); clear(); refresh()
        }
    }
    fun openViewOncePhoto(conversation:String,message:String,refresh:()->Unit={}) {
        app.localOperationGate.requireNormal()
        scope.launch {
            try {
                val opened=app.runtime.beginViewOnce(conversation,message)
                check(opened.viewOnceKind==org.ghostcloak.messaging.ViewOnceKind.PHOTO)
                if(!allowed()) {app.runtime.consumeViewOnce(conversation,message);return@launch}
                download(conversation,message,true,"",refresh,viewOnce=true)
            } catch (_: Exception) { /* Remain unavailable; never downgrade to an ordinary photo. */ }
        }
    }
    fun download(conversation: String, message: String, photo: Boolean, filename: String, refresh: ()->Unit = {},
        viewOnce:Boolean=false) {
        app.localOperationGate.requireNormal()
        clear()
        mutable.value=MediaUi(conversation=conversation,message=message,filename=filename,photo=photo,busy=true,viewOnce=viewOnce)
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
                if(photo) scratch.delete(target) else file=target
                mutable.value=mutable.value.copy(busy=false,ready=true,bytes=target.length(),preview=preview)
                refresh()
            } finally { if(file!=target) scratch.delete(target) }
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
        if(app.localOperationGate.blocked) return
        val current=lease
        lease=null; leaseMessage=null
        current?.let { (uri,source) ->
            try { app.revokeUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (_: Exception) { scratch.invalidate() }
            closeOwnedViewerHandles()
            scratch.delete(source)
        }
    }
    /** Explicit external-view handoff only; it may retain a copy outside our control. */
    suspend fun documentIntent(): Intent = app.localOperationGate.operation {
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
        Intent.createChooser(view,"Open document").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    private val providerHandles = mutableListOf<android.os.ParcelFileDescriptor>()
    private fun closeOwnedViewerHandles() {
        val handles=synchronized(providerHandles) { providerHandles.toList().also { providerHandles.clear() } }
        handles.forEach { try { it.close() } catch (_: Exception) { scratch.invalidate() } }
    }
    internal fun openLease(uri: Uri): android.os.ParcelFileDescriptor = app.localOperationGate.access {
        android.os.ParcelFileDescriptor.open(leaseFile(uri),android.os.ParcelFileDescriptor.MODE_READ_ONLY).also {
            synchronized(providerHandles) { providerHandles.add(it) }
        }
    }
    /** Revokes access and joins the entire presentation scope without invoking file cleanup.
     * Existing operation rollback may clean its own incomplete scratch as before; durable data stays. */
    internal suspend fun quiesce() {
        visible=false; epoch++
        recorderTicker?.cancel();recorderTicker=null;voiceCapture.cancel();stopComposePreview()
        stopVoicePlayback()
        mutable.value=MediaUi(); photos.clear()
        val revoked=synchronized(inputGate) { sourceSignal to activeInput }
        revoked.first?.cancel()
        revoked.second?.close()
        synchronized(inputGate) {
            if(sourceSignal===revoked.first) sourceSignal=null
            if(activeInput===revoked.second) activeInput=null
        }
        val viewer=lease
        viewer?.let { app.revokeUriPermission(it.first,Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        lease=null; leaseMessage=null
        closeOwnedViewerHandles()
        scope.coroutineContext[Job]!!.cancelAndJoin()
        job=null; file=null; maskedFile=null; blob=null
    }
    internal fun leaseFile(uri: Uri): File {
        app.localOperationGate.requireNormal()
        val pair=lease ?: throw java.io.FileNotFoundException()
        val reference=leaseMessage ?: throw java.io.FileNotFoundException()
        if(pair.first!=uri || android.os.SystemClock.elapsedRealtime()>=leaseDeadline ||
            !app.appLock.state.value.canShowContent || !app.runtime.attachmentAvailableNow(reference.first,reference.second))
            throw java.io.FileNotFoundException()
        return pair.second
    }
}
