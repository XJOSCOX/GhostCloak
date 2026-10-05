package org.ghostcloak.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.withResumed
import org.ghostcloak.app.application.GhostApplication
import org.ghostcloak.app.ui.components.*
import org.ghostcloak.app.ui.theme.GhostDimensions
import org.ghostcloak.messaging.Message
import org.ghostcloak.messaging.ConversationPayload
import org.ghostcloak.attachments.AttachmentKind

@Composable fun AttachmentComposer(conversation: String, enabled: Boolean, refresh: ()->Unit) {
    val context=LocalContext.current
    val owner=(context.applicationContext as GhostApplication).media
    val state by owner.state.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var external by remember { mutableStateOf(false) }
    var viewerError by remember { mutableStateOf(false) }
    var explainMicrophone by remember { mutableStateOf(false) }
    var microphoneDenied by remember { mutableStateOf(false) }
    val scope=rememberCoroutineScope()
    val pickerLifecycle=LocalLifecycleOwner.current.lifecycle
    val photo=rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if(uri!=null) scope.launch { awaitAttachmentHost(pickerLifecycle) { owner.select(conversation,uri,true) } }
    }
    val document=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) scope.launch { awaitAttachmentHost(pickerLifecycle) { owner.select(conversation,uri,false) } }
    }
    val microphone=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if(granted) owner.startVoiceRecording(conversation) else microphoneDenied=true
    }
    DisposableEffect(conversation) { onDispose { owner.clear() } }
    Box {
        IconButton(onClick={menu=true},enabled=enabled) { AppIcon(Glyph.ATTACHMENT,"Attach photo or document") }
        DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
            DropdownMenuItem(text={Text("Photo")},leadingIcon={AppIcon(Glyph.GALLERY)},onClick={menu=false;photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))})
            DropdownMenuItem(text={Text("Document")},leadingIcon={AppIcon(Glyph.ATTACHMENT)},onClick={menu=false;document.launch(arrayOf("*/*"))})
            DropdownMenuItem(text={Text("Voice note")},leadingIcon={AppIcon(Glyph.MICROPHONE)},onClick={menu=false;
                if(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)
                    owner.startVoiceRecording(conversation) else explainMicrophone=true})
        }
    }
    if(explainMicrophone) AlertDialog(onDismissRequest={explainMicrophone=false},title={Text("Record a voice note?")},
        text={Text("Ghost Cloak uses the microphone only while you record. The recording stays in private app storage until it is encrypted or cancelled.")},
        confirmButton={TextButton(onClick={explainMicrophone=false;microphone.launch(Manifest.permission.RECORD_AUDIO)}) {Text("Continue")}},
        dismissButton={TextButton(onClick={explainMicrophone=false}) {Text("Cancel")}})
    if(microphoneDenied) AlertDialog(onDismissRequest={microphoneDenied=false},title={Text("Microphone unavailable")},
        text={Text("Microphone permission was denied. You can still send text, photos, and documents.")},
        confirmButton={TextButton(onClick={microphoneDenied=false}) {Text("OK")}})
    if(state.conversation==conversation && state.photo && state.message!=null && state.ready) {
        androidx.compose.ui.window.Dialog(onDismissRequest=owner::cancel,
            properties=DialogProperties(usePlatformDefaultWidth=false,securePolicy=SecureFlagPolicy.SecureOn)) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    TextButton(onClick=owner::cancel) { Text(if(state.viewOnce) "Close View Once photo" else "Close photo") }
                    state.preview?.let { Image(it.asImageBitmap(),"Photo",Modifier.fillMaxSize(),
                        contentScale=androidx.compose.ui.layout.ContentScale.Fit) }
                }
            }
        }
    } else if(state.conversation==conversation && !state.sending) AlertDialog(onDismissRequest=owner::cancel,
        properties=DialogProperties(securePolicy=SecureFlagPolicy.SecureOn),
        title={Text(if(state.voice) "Voice note" else if(state.photo) "Photo" else "Document")},
        text={ Column(verticalArrangement=Arrangement.spacedBy(GhostDimensions.medium)) {
            if(state.voice) Text(if(state.recording) "Recording ${voiceTime(state.durationMillis)}"
                else if(state.ready) "Voice note · ${voiceTime(state.durationMillis)}" else "Preparing microphone…")
            state.preview?.let { Image(it.asImageBitmap(),"Photo preview",Modifier.fillMaxWidth().heightIn(max=GhostDimensions.previewWidth)) }
            if(state.filename.isNotEmpty()) Text(state.filename)
            if(state.bytes>0) Text("${(state.bytes+1023)/1024} KiB",style=MaterialTheme.typography.bodySmall)
            if(state.photo && state.message==null && state.ready && !state.sending && state.caption.isBlank())
                TextButton(onClick=owner::toggleViewOnce,enabled=enabled && !state.busy) {
                    Text(if(state.viewOnce) "① View Once on" else "① View Once")
                }
            if(state.message==null && state.ready && !state.viewOnce) {
                OutlinedTextField(state.caption,owner::setCaption,label={Text("Optional caption")},
                    maxLines=3,modifier=Modifier.fillMaxWidth(),
                    isError=runCatching {ConversationPayload.validateCaption(state.caption)}.isFailure)
                Text("Caption is encrypted with the message (maximum 512 UTF-8 bytes).",style=MaterialTheme.typography.bodySmall)
            }
            if(state.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(if(state.message==null && state.photo && !state.uploadPrepared) "Preparing photo…" else if(state.message==null) "Preparing / uploading…" else "Downloading / verifying…") }
            state.error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
            if(!state.photo && !state.voice && state.message==null) Text("Documents keep embedded metadata. The complete file is encrypted before upload.",style=MaterialTheme.typography.bodySmall)
            if(viewerError) Text("No document viewer is available, or access has expired.",color=MaterialTheme.colorScheme.error)
        } },
        confirmButton={
            if(state.voice && state.recording) TextButton(onClick=owner::stopVoiceRecording) {Text("Stop recording")}
            else if(state.voice && state.message==null && state.error!=null && !state.uploadPrepared)
                TextButton(onClick={owner.startVoiceRecording(conversation)},enabled=enabled) {Text("Record again")}
            else if(state.message==null) PreparationAction(state,enabled,
                {owner.cancel();if(state.photo) photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) else document.launch(arrayOf("*/*"))},
                {owner.send(refresh)})
            else if(state.ready && !state.photo) TextButton(onClick={external=true},enabled=enabled) { Text("Open document") }
            else if(state.error!=null && !state.viewOnce) TextButton(onClick={owner.download(conversation,state.message!!,state.photo,state.filename,refresh)},enabled=enabled) { Text("Retry download") }
        },
        dismissButton={TextButton(onClick=owner::cancel) { Text(if(state.message==null) "Cancel" else "Close") }})
    if(external) AlertDialog(onDismissRequest={external=false},title={Text("Open in another app?")},
        text={Text("The viewer may retain or share a copy. Ghost Cloak cannot delete that copy when this message expires. Access is read-only and temporary; app lock may prevent the viewer from opening it.")},
        confirmButton={TextButton(onClick={external=false;scope.launch {
            try { context.startActivity(owner.documentIntent()); viewerError=false }
            catch (_: Exception) { viewerError=true }
        }}) { Text("Choose viewer") }},dismissButton={TextButton(onClick={external=false}) { Text("Cancel") }})
}

@Composable internal fun PreparationAction(state: org.ghostcloak.app.attachments.MediaUi, enabled: Boolean,
    chooseFile: () -> Unit, send: () -> Unit) {
    if(state.error!=null && !state.uploadPrepared)
        TextButton(onClick=chooseFile,enabled=enabled && !state.busy) { Text(if(state.photo) "Choose another photo" else "Choose another document") }
    else TextButton(onClick=send,enabled=enabled && !state.busy && (state.ready || state.uploadPrepared) &&
        runCatching {ConversationPayload.validateCaption(state.caption)}.isSuccess) {
        Text(if(state.uploadPrepared) "Retry upload" else "Send")
    }
}

@Composable fun AttachmentMessage(message: Message, enabled: Boolean, cached: Boolean, refresh: ()->Unit) {
    val owner=(LocalContext.current.applicationContext as GhostApplication).media
    val summary=message.attachment ?: return
    if(summary.photo && summary.supported) { InlinePhotoMessage(message,enabled); return }
    if(summary.kind==AttachmentKind.VOICE_NOTE && summary.supported) {
        val playback by owner.voicePlayback.collectAsState()
        val current=playback.conversation==message.conversationId && playback.message==message.localId
        val duration=if(current && playback.durationMillis>0) playback.durationMillis else summary.durationMillis ?: 0
        Column {
            Text("Voice note",style=MaterialTheme.typography.bodyLarge)
            if(enabled) {
                TextButton(onClick={owner.toggleVoicePlayback(message.conversationId,message.localId)},enabled=!current || !playback.busy) {
                    Text(if(current && playback.playing) "Pause voice note" else "Play voice note")
                }
                if(current && playback.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if(current && playback.error) Text("Could not play voice note.",color=MaterialTheme.colorScheme.error)
                Text("${voiceTime(if(current) playback.progressMillis else 0)} / ${voiceTime(duration)}",
                    style=MaterialTheme.typography.labelSmall)
                if(current && duration>0) Slider(value=playback.progressMillis.coerceIn(0,duration).toFloat(),
                    onValueChange={owner.seekVoice(message.conversationId,message.localId,it.toLong())},
                    valueRange=0f..duration.toFloat(),modifier=Modifier.fillMaxWidth())
            }
            if(enabled) summary.caption?.let {Text(it,style=MaterialTheme.typography.bodyMedium)}
        }
        return
    }
    Column {
        Text(if(enabled) summary.filename else "Attachment",style=MaterialTheme.typography.bodyLarge)
        if(enabled && summary.bytes>0) Text("${(summary.bytes+1023)/1024} KiB",style=MaterialTheme.typography.labelSmall)
        if(enabled) summary.caption?.let {Text(it,style=MaterialTheme.typography.bodyMedium)}
        if(enabled && summary.supported) TextButton(onClick={owner.download(message.conversationId,message.localId,summary.photo,summary.filename,refresh)}) {
            Text(if(cached) { if(summary.photo) "View photo" else "Open document" } else "Download",color=LocalContentColor.current)
        } else Text(if(summary.supported) "Attachment unavailable until this conversation is accepted and secure." else "Unsupported attachment type.",style=MaterialTheme.typography.bodySmall)
    }
}

private fun voiceTime(milliseconds:Long):String {
    val seconds=(milliseconds.coerceAtLeast(0)/1000).toInt()
    return "%d:%02d".format(seconds/60,seconds%60)
}


internal suspend fun awaitAttachmentHost(lifecycle: androidx.lifecycle.Lifecycle, select: () -> Unit) {
    lifecycle.withResumed { select() }
}
