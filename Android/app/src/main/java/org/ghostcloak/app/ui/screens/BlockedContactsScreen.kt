package org.ghostcloak.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import org.ghostcloak.app.application.AppState
import org.ghostcloak.app.ui.components.*
import org.ghostcloak.app.ui.theme.GhostDimensions

@Composable fun BlockedContactsScreen(state:AppState,back:()->Unit,unblock:(String)->Unit) {
    var target by remember {mutableStateOf<String?>(null)}
    PageContent("Blocked contacts",back=back) {
        ErrorNotice(state.error,important=state.errorImportant)
        if(state.blockedContacts.isEmpty()) Text("No blocked contacts")
        for(contact in state.blockedContacts) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,
                horizontalArrangement=Arrangement.spacedBy(GhostDimensions.regular)) {
                Text(contact.displayName.takeIf {it.isNotBlank()} ?: "Blocked contact",Modifier.weight(1f),
                    style=MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick={target=contact.remoteDeviceId},enabled=!state.loading){Text("Unblock")}
            }
        }
        Text("Blocking is private and stored only on this device. Discarded messages will not return.",
            style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
    target?.takeIf {id->state.blockedContacts.any {it.remoteDeviceId==id}}?.let {id->
        UnblockConfirmation({target=null}) {target=null;unblock(id)}
    }
}
