package org.ghostcloak.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import org.ghostcloak.app.application.GhostApplication
import org.ghostcloak.app.ui.components.*
import org.ghostcloak.app.ui.theme.GhostDimensions
import org.ghostcloak.messaging.Message

@Composable fun AttachmentComposer(conversation: String, enabled: Boolean, refresh: ()->Unit) {
    val context=LocalContext.current
    val owner=(context.applicationContext as GhostApplication).media
    val state by owner.state.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var external by remember { mutableStateOf(false) }
    var viewerError by remember { mutableStateOf(false) }
    val scope=rememberCoroutineScope()
    val photo=rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if(uri!=null) owner.select(conversation,uri,true)
    }
    val document=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) owner.select(conversation,uri,false)
    }
    DisposableEffect(conversation) { onDispose { owner.clear() } }
    Box {
        IconButton(onClick={menu=true},enabled=enabled) { AppIcon(Glyph.PLUS,"Attach photo or document") }
        DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
            DropdownMenuItem(text={Text("Photo")},onClick={menu=false;photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))})
            DropdownMenuItem(text={Text("Document")},onClick={menu=false;document.launch(arrayOf("*/*"))})
        }
    }
    if(state.conversation==conversation) AlertDialog(onDismissRequest=owner::cancel,
        properties=DialogProperties(securePolicy=SecureFlagPolicy.SecureOn),
        title={Text(if(state.photo) "Photo" else "Document")},
        text={ Column(verticalArrangement=Arrangement.spacedBy(GhostDimensions.medium)) {
            state.preview?.let { Image(it.asImageBitmap(),"Photo preview",Modifier.fillMaxWidth().heightIn(max=GhostDimensions.previewWidth)) }
            if(state.filename.isNotEmpty()) Text(state.filename)
            if(state.bytes>0) Text("${(state.bytes+1023)/1024} KiB",style=MaterialTheme.typography.bodySmall)
            if(state.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(if(state.message==null) "Preparing / uploading…" else "Downloading / verifying…") }
            state.error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
            if(!state.photo && state.message==null) Text("Documents keep embedded metadata. The complete file is encrypted before upload.",style=MaterialTheme.typography.bodySmall)
            if(viewerError) Text("No document viewer is available, or access has expired.",color=MaterialTheme.colorScheme.error)
        } },
        confirmButton={
            if(state.message==null) TextButton(onClick={owner.send(refresh)},enabled=!state.busy && (state.ready || state.bytes>0)) { Text(if(state.error==null) "Send" else "Retry upload") }
            else if(state.ready && !state.photo) TextButton(onClick={external=true},enabled=enabled) { Text("Open document") }
            else if(state.error!=null) TextButton(onClick={owner.download(conversation,state.message!!,state.photo,state.filename,refresh)},enabled=enabled) { Text("Retry download") }
        },
        dismissButton={TextButton(onClick=owner::cancel) { Text(if(state.message==null) "Cancel" else "Close") }})
    if(external) AlertDialog(onDismissRequest={external=false},title={Text("Open in another app?")},
        text={Text("The viewer may retain or share a copy. Ghost Cloak cannot delete that copy when this message expires. Access is read-only and temporary; app lock may prevent the viewer from opening it.")},
        confirmButton={TextButton(onClick={external=false;scope.launch {
            try { context.startActivity(owner.documentIntent()); viewerError=false }
            catch (_: Exception) { viewerError=true }
        }}) { Text("Choose viewer") }},dismissButton={TextButton(onClick={external=false}) { Text("Cancel") }})
}

@Composable fun AttachmentMessage(message: Message, enabled: Boolean, cached: Boolean, refresh: ()->Unit) {
    val owner=(LocalContext.current.applicationContext as GhostApplication).media
    val summary=message.attachment ?: return
    Column {
        Text(if(summary.photo) "Photo" else summary.filename,style=MaterialTheme.typography.bodyLarge)
        if(summary.bytes>0) Text("${(summary.bytes+1023)/1024} KiB",style=MaterialTheme.typography.labelSmall)
        if(enabled && summary.supported) TextButton(onClick={owner.download(message.conversationId,message.localId,summary.photo,summary.filename,refresh)}) {
            Text(if(cached) { if(summary.photo) "View photo" else "Open document" } else "Download",color=LocalContentColor.current)
        } else Text(if(summary.supported) "Attachment unavailable until this conversation is accepted and secure." else "Unsupported attachment type.",style=MaterialTheme.typography.bodySmall)
    }
}
