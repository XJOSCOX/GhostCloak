package org.ghostcloak.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import org.ghostcloak.app.application.GhostApplication
import org.ghostcloak.app.attachments.PhotoStage
import org.ghostcloak.app.attachments.InlinePhoto
import org.ghostcloak.app.ui.theme.GhostDimensions
import org.ghostcloak.messaging.Message

@Composable fun InlinePhotoMessage(message: Message, enabled: Boolean) {
    val owner=(LocalContext.current.applicationContext as GhostApplication).media
    val photos by owner.photos.state.collectAsState()
    val session by owner.presentationSession.collectAsState()
    val key=message.conversationId to message.localId
    val value=photos[key]
    DisposableEffect(key,enabled,session) {
        owner.photos.request(key.first,key.second,true,enabled)
        onDispose { owner.photos.release(key.first,key.second) }
    }
    InlinePhotoContent(value,enabled,
        retry={owner.photos.request(key.first,key.second,true,enabled,retry=true)},
        open={owner.download(key.first,key.second,true,"Photo")})
}

@Composable internal fun InlinePhotoContent(value: InlinePhoto?, enabled: Boolean, retry: ()->Unit, open: ()->Unit) {
    if(!enabled) { Text("Attachment",style=MaterialTheme.typography.bodyMedium); return }
    val image=value?.bitmap
    if(value?.stage==PhotoStage.READY && image!=null) {
        Image(image.asImageBitmap(),"Photo",contentScale=ContentScale.Fit,
            modifier=Modifier.widthIn(max=GhostDimensions.previewWidth).heightIn(max=GhostDimensions.previewWidth)
                .aspectRatio(image.width.toFloat()/image.height)
                .clickable(onClick=open))
    } else Column(Modifier.width(GhostDimensions.previewWidth).padding(GhostDimensions.medium)) {
        when(value?.stage) {
            PhotoStage.FAILED -> {
                if(value.storageFull) Text("Photo storage full. Delete local photos to free space.",style=MaterialTheme.typography.labelSmall)
                TextButton(onClick=retry) { Text("Photo unavailable · Retry",color=LocalContentColor.current) }
            }
            PhotoStage.EXPIRED -> Text("Photo unavailable")
            else -> {
                Text(when(value?.stage) {
                    PhotoStage.FETCHING -> "Fetching photo…"
                    PhotoStage.VERIFYING -> "Preparing verified photo…"
                    else -> "Waiting for photo…"
                },style=MaterialTheme.typography.labelMedium)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

/** Locally sanitized pixels remain visible during encryption/upload, before a descriptor is queued. */
@Composable fun SendingPhoto(conversation: String, refresh: () -> Unit) {
    val owner=(LocalContext.current.applicationContext as GhostApplication).media
    val state by owner.state.collectAsState()
    if(state.conversation!=conversation || !state.photo || !state.sending || state.message!=null) return
    Column(Modifier.fillMaxWidth(),horizontalAlignment=androidx.compose.ui.Alignment.End) {
        state.preview?.let { image ->
            Image(image.asImageBitmap(),"Sending photo",contentScale=ContentScale.Fit,
                modifier=Modifier.widthIn(max=GhostDimensions.previewWidth).heightIn(max=GhostDimensions.previewWidth)
                    .aspectRatio(image.width.toFloat()/image.height))
        }
        if(state.busy) Text("Sending photo…",style=MaterialTheme.typography.labelSmall)
        if(state.error!=null) {
            Text("Photo not sent",style=MaterialTheme.typography.labelSmall)
            TextButton(onClick={owner.send(refresh)}) { Text("Retry") }
        }
        TextButton(onClick=owner::cancel) { Text("Cancel") }
    }
}
