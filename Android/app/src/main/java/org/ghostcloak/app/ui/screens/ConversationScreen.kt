package org.ghostcloak.app.ui.screens
import org.ghostcloak.app.ui.privacy.noSensitiveCopyCut
import org.ghostcloak.app.ui.privacy.copySensitive
import androidx.compose.foundation.background

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.ghostcloak.app.ui.theme.GhostDimensions
import androidx.compose.ui.text.style.TextOverflow
import java.time.*
import java.time.format.DateTimeFormatter
import org.ghostcloak.app.application.AppState
import org.ghostcloak.app.ui.theme.GhostLayout
import org.ghostcloak.app.ui.components.*
import org.ghostcloak.identity.*
import org.ghostcloak.messaging.*
import org.ghostcloak.protocol.EnvelopeCodec

@Composable fun ConversationScreen(state: AppState, status: ContactStatus, back: () -> Unit,
    security: () -> Unit, send: (String, () -> Unit) -> Unit, delete: (String) -> Unit, connect: () -> Unit = {}, sync: () -> Unit = {}, accept: () -> Unit = {}, reject: () -> Unit = {}, clear: () -> Unit = {}, disappearing: (Int) -> Unit = {}, refresh: () -> Unit = {}, block:()->Unit={},
    sendViewOnce:(String,()->Unit)->Unit={_,_->},revealText:(String,(String)->Unit)->Unit={_,_->},consume:(String)->Unit={},
    sendReply:(String,ReplyReference,()->Unit)->Unit={_,_,_->},
    setPinned:(Boolean)->Unit={},setArchived:(Boolean)->Unit={},setMuted:(Boolean)->Unit={},
    react:(String,String?)->Unit={_,_->},retryMessage:(String)->Unit={}) {
    var draft by remember(status.contact.remoteDeviceId) { mutableStateOf("") }
    var viewOnceText by remember(status.contact.remoteDeviceId) { mutableStateOf(false) }
    var revealed by remember(status.contact.remoteDeviceId) { mutableStateOf<Pair<String,String>?>(null) }
    val currentReveal by rememberUpdatedState(revealed)
    val currentConsume by rememberUpdatedState(consume)
    val app=(androidx.compose.ui.platform.LocalContext.current.applicationContext as org.ghostcloak.app.application.GhostApplication)
    val viewLifecycle=LocalLifecycleOwner.current.lifecycle
    fun closeReveal() {
        revealed?.first?.let(consume)
        revealed=null
    }
    DisposableEffect(viewLifecycle,status.contact.remoteDeviceId) {
        val observer=androidx.lifecycle.LifecycleEventObserver { _,event ->
            if(event==androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                currentReveal?.first?.let(currentConsume)
                revealed=null
            }
        }
        viewLifecycle.addObserver(observer)
        onDispose {
            viewLifecycle.removeObserver(observer)
            currentReveal?.first?.let(currentConsume)
            revealed=null
        }
    }
    var rejectedPaste by remember { mutableStateOf(false) }
    var actions by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var clearing by remember { mutableStateOf(false) }
    var timerSelector by remember { mutableStateOf(false) }
    var replyTo by remember(status.contact.remoteDeviceId) { mutableStateOf<ReplyReference?>(null) }
    var searching by remember(status.contact.remoteDeviceId) { mutableStateOf(false) }
    var query by remember(status.contact.remoteDeviceId) { mutableStateOf("") }
    var resultPosition by remember(status.contact.remoteDeviceId) { mutableIntStateOf(0) }
    DisposableEffect(status.contact.remoteDeviceId) { onDispose { searching=false; query=""; replyTo=null } }
    val context=androidx.compose.ui.platform.LocalContext.current
    val size = remember(draft) { val bytes = draft.encodeToByteArray(); try { bytes.size } finally { bytes.fill(0) } }
    val changed = status.identity?.trustState == IdentityTrustState.CHANGED
    val active = status.session == SessionLifecycle.ACTIVE && !changed && !status.contact.blocked && !status.contact.request
    val searchMatches=remember(state.messages,query,status.contact.blocked,status.contact.request) {
        ConversationSearch.matches(state.messages,query,!status.contact.blocked && !status.contact.request)
    }
    val selectedMatch=searchMatches.getOrNull(resultPosition.coerceIn(0,(searchMatches.size-1).coerceAtLeast(0)))
    val list = rememberLazyListState()
    LaunchedEffect(state.messages.size) { if (state.messages.isNotEmpty()) list.animateScrollToItem(state.messages.lastIndex) }
    LaunchedEffect(selectedMatch,searching) { if(searching && selectedMatch!=null) list.animateScrollToItem(selectedMatch) }
    Column(Modifier.fillMaxSize().imePadding()) {
        PageHeader(status.contact.visibleName, subtitle = when {
            status.contact.blocked -> "Blocked on this device"
            changed -> "! Security identity changed"
            status.contact.request -> "Message request - Unverified"
            !active -> "Session unavailable"
            else -> null
        }, back = back, avatarName = status.contact.visibleName,
            avatarPhoto=if(status.contact.request || status.contact.blocked) null else status.sharedPhoto) {
            if(!status.contact.request && !status.contact.blocked)
                HeaderAction(Glyph.SEARCH,"Search conversation") { searching=!searching;query="";resultPosition=0 }
            val verified = status.identity?.trustState == IdentityTrustState.VERIFIED
            HeaderAction(if (verified) Glyph.SHIELD else Glyph.UNVERIFIED,
                if (verified) "Verified contact · Security" else if (changed) "Identity changed · Security" else "Unverified contact · Security",
                verificationColor(status.identity?.trustState), security)
            Box {
                HeaderAction(Glyph.MORE, "Conversation options") { actions = true }
                DropdownMenu(expanded = actions, onDismissRequest = { actions = false }) {
                    if (state.networkConfigured && !state.demo)
                        DropdownMenuItem(text = { Text("Sync") }, onClick = { actions = false; sync() })
                    DropdownMenuItem(text = { Text("Contact details") }, onClick = { actions = false; security() })
                    if(!status.contact.request)
                        DropdownMenuItem(text = { Text("Delete conversation") }, onClick = { actions = false; clearing = true })
                    if(!status.contact.request && !status.contact.blocked) {
                        DropdownMenuItem(text={Text(if(status.contact.pinned) "Unpin chat" else "Pin chat")},
                            onClick={actions=false;setPinned(!status.contact.pinned)})
                        DropdownMenuItem(text={Text(if(status.contact.archived) "Unarchive chat" else "Archive chat")},
                            onClick={actions=false;setArchived(!status.contact.archived)})
                        DropdownMenuItem(text={Text(if(status.contact.muted) "Unmute chat" else "Mute chat")},
                            onClick={actions=false;setMuted(!status.contact.muted)})
                    }
                    if (state.networkConfigured && !state.demo && !status.contact.request)
                        DropdownMenuItem(text = { Text("Disappearing messages · ${DisappearingTimer.from(state.disappearingPolicies[status.contact.remoteDeviceId] ?: 0).label}") },
                            enabled = active && !state.loading, onClick = { actions = false; timerSelector = true })
                }
            }
        }
        if(!state.networkConnected) Box(Modifier.padding(horizontal = GhostLayout.pageInset)) { NetworkActions(state, connect, sync) }
        if(searching) Column(Modifier.fillMaxWidth().padding(horizontal=GhostLayout.pageInset)) {
            OutlinedTextField(query,onValueChange={query=it;resultPosition=0},singleLine=true,
                label={Text("Search this conversation")},modifier=Modifier.fillMaxWidth())
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text(if(query.isBlank()) "Enter a search term" else "${searchMatches.size} results",
                    Modifier.weight(1f),style=MaterialTheme.typography.labelMedium)
                TextButton(onClick={resultPosition=(resultPosition-1+searchMatches.size)%searchMatches.size},enabled=searchMatches.isNotEmpty()) {Text("Previous")}
                TextButton(onClick={resultPosition=(resultPosition+1)%searchMatches.size},enabled=searchMatches.isNotEmpty()) {Text("Next")}
                TextButton(onClick={searching=false;query="";resultPosition=0}) {Text("Close")}
            }
        }
        if (state.demo) Text("LOCAL SIMULATOR · Replies are generated by the debug endpoint", Modifier.padding(GhostLayout.pageInset),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        if (changed) TextButton(onClick = security, modifier = Modifier.fillMaxWidth()) { Text("! Review changed identity before sending", color = MaterialTheme.colorScheme.error) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(horizontal = GhostLayout.pageInset, vertical = GhostLayout.dividerGap), verticalArrangement = Arrangement.spacedBy(GhostDimensions.compact)) {
            if (state.messages.isEmpty()) item { InfoPanel(if(status.contact.request) "New message request" else "A conversation starts here", if(status.contact.request) "Inspect this identity before accepting. Hidden content is revealed only after acceptance." else if (state.networkConfigured && !state.demo) "End-to-end encrypted. Messages arrive automatically while Ghost Cloak is open." else "Messages are encrypted before local transport. This prototype cannot deliver to another device over a network.") }
            itemsIndexed(state.messages, key = { _, message -> message.localId }) { index, message ->
                val date = Instant.ofEpochMilli(message.timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
                val previous = state.messages.getOrNull(index-1)?.let { Instant.ofEpochMilli(it.timestamp).atZone(ZoneId.systemDefault()).toLocalDate() }
                Column(Modifier.then(if(searching && index==selectedMatch) Modifier.background(MaterialTheme.colorScheme.primaryContainer,
                    RoundedCornerShape(GhostDimensions.medium)) else Modifier),
                    verticalArrangement=Arrangement.spacedBy(GhostDimensions.medium)) {
                    if(date!=previous) Box(Modifier.fillMaxWidth().padding(vertical=GhostDimensions.medium),contentAlignment=Alignment.Center) {
                        Surface(shape=RoundedCornerShape(GhostDimensions.medium),color=MaterialTheme.colorScheme.surface) {
                            Text(when(date) {LocalDate.now()->"Today"; LocalDate.now().minusDays(1)->"Yesterday"; else->date.format(DateTimeFormatter.ofPattern("MMM d, yyyy"))},
                                Modifier.padding(horizontal=GhostDimensions.medium,vertical=GhostDimensions.tight),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (message.policyEvent) Column(Modifier.fillMaxWidth().padding(GhostDimensions.medium), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(message.body, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        if (message.direction == Direction.OUTGOING && message.state in setOf(MessageState.PENDING, MessageState.FAILED))
                            Text(if (message.state == MessageState.PENDING) "Update pending · applies locally" else "Update not delivered · choose the timer again to retry",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else if(message.viewOnceKind!=null) MessageBubble(message,onDelete={deleting=message.localId},
                        onReply=if(active && ReplyPresentation.reference(message)!=null) {{replyTo=ReplyPresentation.reference(message);viewOnceText=false}} else null,
                        replyPreview=message.replyTo?.let {ReplyPresentation.preview(it,status.contact.remoteDeviceId,state.messages)}) {
                        Column {
                            Text(message.body,style=MaterialTheme.typography.bodyLarge)
                            if(message.direction==Direction.INCOMING && message.viewOnceState==ViewOnceState.AVAILABLE &&
                                !status.contact.request && active)
                                TextButton(onClick={
                                    if(message.viewOnceKind==ViewOnceKind.PHOTO)
                                        app.media.openViewOncePhoto(message.conversationId,message.localId,refresh)
                                    else revealText(message.localId) { body ->
                                        if(viewLifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
                                            revealed=message.localId to body
                                        else consume(message.localId)
                                    }
                                }) { Text("Tap to view") }
                        }
                    }
                    else if(message.attachment!=null) MessageBubble(message,onDelete={deleting=message.localId},
                        onReply=if(active && ReplyPresentation.reference(message)!=null) {{replyTo=ReplyPresentation.reference(message);viewOnceText=false}} else null,
                        replyPreview=message.replyTo?.let {ReplyPresentation.preview(it,status.contact.remoteDeviceId,state.messages)},
                        onReact=message.envelopeId?.takeIf {active && state.networkConfigured && !state.demo && state.reactionsAvailable && message.state!=MessageState.EXPIRED_UNDELIVERED}
                            ?.let {target -> {emoji -> react(target,emoji)}},
                        onRetry=if(message.direction==Direction.OUTGOING && message.state==MessageState.PENDING && active && state.networkConfigured && !state.demo)
                            {{retryMessage(message.localId)}} else null) {
                        AttachmentMessage(message,active,message.localId in state.cachedAttachments,refresh)
                    } else MessageBubble(message,onDelete={deleting=message.localId},
                        onReply=if(active && ReplyPresentation.reference(message)!=null) {{replyTo=ReplyPresentation.reference(message);viewOnceText=false}} else null,
                        onCopy=if(!status.contact.request && !status.contact.blocked && message.body.isNotBlank())
                            {{copySensitive(context,message.body)}} else null,
                        onReact=message.envelopeId?.takeIf {active && state.networkConfigured && !state.demo && state.reactionsAvailable && message.state!=MessageState.EXPIRED_UNDELIVERED}
                            ?.let {target -> {emoji -> react(target,emoji)}},
                        onRetry=if(message.direction==Direction.OUTGOING && message.state==MessageState.PENDING && active && state.networkConfigured && !state.demo)
                            {{retryMessage(message.localId)}} else null,
                        replyPreview=message.replyTo?.let {ReplyPresentation.preview(it,status.contact.remoteDeviceId,state.messages)})
                }
            }
        }
        SendingPhoto(status.contact.remoteDeviceId,refresh)
        if (status.contact.request && !status.contact.blocked) {
            Column(Modifier.padding(GhostLayout.pageInset), verticalArrangement = Arrangement.spacedBy(GhostDimensions.compact)) {
                Text("New message request", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick=security) {Text("View identity / Verify")}
                Text("This sender is not in your contacts. Accepting lets you reply; it does not verify their identity.", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(GhostDimensions.medium)) {
                    Button(onClick = accept, enabled = !state.loading) { Text("Accept") }
                    TextButton(onClick = reject, enabled = !state.loading) { Text("Delete") }
                    TextButton(onClick = block, enabled = !state.loading) { Text("Block") }
                }
            }
        }
        Surface(color=MaterialTheme.colorScheme.surface) { Column(Modifier.padding(horizontal = GhostLayout.pageInset, vertical=GhostDimensions.controlGap), verticalArrangement = Arrangement.spacedBy(GhostDimensions.compact)) {
            ErrorNotice(state.error, important = state.errorImportant)
            val textLimit=ConversationPayload.MAX_TEXT-(if(replyTo!=null) 37 else 0)
            if (size > textLimit || rejectedPaste) Text("Too large. Maximum $textLimit UTF-8 bytes; text was not sent.", color = MaterialTheme.colorScheme.error)
            replyTo?.let { reference -> Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text("Replying to: ${ReplyPresentation.preview(reference,status.contact.remoteDeviceId,state.messages)}",
                    Modifier.weight(1f),maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={replyTo=null}) {Text("Cancel reply")}
            } }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GhostDimensions.controlGap)) {
                if(state.networkConfigured && !state.demo) AttachmentComposer(status.contact.remoteDeviceId,active && !state.loading,refresh)
                OutlinedTextField(draft, onValueChange = { rejectedPaste = it.length > 65536; if (!rejectedPaste) draft = it },
                    enabled = active && !state.loading, modifier = Modifier.weight(1f).noSensitiveCopyCut(), placeholder = { Text("Write a message…") }, maxLines = 5,
                    colors=OutlinedTextFieldDefaults.colors(unfocusedBorderColor=androidx.compose.ui.graphics.Color.Transparent,unfocusedContainerColor=MaterialTheme.colorScheme.surface,focusedContainerColor=MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(GhostDimensions.spacious), isError = size > textLimit)
                FilledIconButton(onClick = {
                    val done={ draft="";viewOnceText=false;replyTo=null }
                    val reference=replyTo
                    if(reference!=null) sendReply(draft,reference,done)
                    else if(viewOnceText) sendViewOnce(draft,done) else send(draft,done)
                }, enabled = active && !state.loading && draft.isNotBlank() && size <= textLimit && !rejectedPaste,
                    modifier = Modifier.size(GhostDimensions.avatar)) { AppIcon(Glyph.SEND,"Send") }
            }
            if(state.networkConfigured && !state.demo && draft.isNotBlank() && replyTo==null)
                TextButton(onClick={viewOnceText=!viewOnceText},enabled=active && !state.loading) {
                    Text(if(viewOnceText) "① View Once on" else "① View Once")
                }
            Text(if (size > 14000) "$size / 16,368 bytes" else "End-to-end encrypted", Modifier.padding(start = GhostDimensions.controlGap, bottom = GhostDimensions.medium), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    revealed?.let { (_,body) -> androidx.compose.ui.window.Dialog(onDismissRequest=::closeReveal,
        properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false,
            securePolicy=androidx.compose.ui.window.SecureFlagPolicy.SecureOn)) {
        Surface(Modifier.fillMaxSize()) { Column(Modifier.fillMaxSize().safeDrawingPadding().padding(GhostLayout.pageInset)) {
            TextButton(onClick=::closeReveal) { Text("Close") }
            Text(body,style=MaterialTheme.typography.bodyLarge)
        } }
    } }
    }
    deleting?.let { id -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete message?") },
        text = { Text("This removes the message from this device only.") },
        confirmButton = { TextButton(onClick = { deleting = null; delete(id) }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }) }
    if (clearing) AlertDialog(onDismissRequest = { clearing = false }, title = { Text("Delete this conversation?") },
        text = { Text("This removes the local message history from this device.") },
        confirmButton = { TextButton(onClick = { clearing = false; clear() }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { clearing = false }) { Text("Cancel") } })
    if (timerSelector) AlertDialog(onDismissRequest = { timerSelector = false }, title = { Text("Disappearing messages") },
        text = { Column {
            Text("Both devices need disappearing-message support. Applies to future messages. Your timer starts when their delivery ACK is observed; theirs starts when received and saved, not when read. Queued messages have no countdown.", style = MaterialTheme.typography.bodySmall)
            DisappearingTimer.entries.forEach { timer ->
                TextButton(onClick = { timerSelector = false; disappearing(timer.seconds) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (timer.seconds == (state.disappearingPolicies[status.contact.remoteDeviceId] ?: 0)) "${timer.label} ✓" else timer.label)
                }
            }
        } }, confirmButton = {}, dismissButton = { TextButton(onClick = { timerSelector = false }) { Text("Cancel") } })
}
