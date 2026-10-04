package org.ghostcloak.app.access

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import kotlinx.coroutines.launch
import org.ghostcloak.app.ui.components.PageContent

@Composable fun InactivitySettingsScreen(controller: AppLockController, back: () -> Unit) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(state.inactivityPeriod) }
    var acknowledged by remember { mutableStateOf(false) }
    var confirmDisable by remember { mutableStateOf(false) }
    var emergencyPin by remember { mutableStateOf("") }
    LaunchedEffect(controller) { controller.endManagement() }
    DisposableEffect(controller) { onDispose { emergencyPin=""; controller.endManagement() } }
    val needsEmergency = state.emergencyEnabled && state.inactivityPeriod != InactivityPeriod.OFF &&
        (selected == InactivityPeriod.OFF || selected.days > state.inactivityPeriod.days)
    PageContent("Inactive Device Protection", back=back) {
        Text("Current: ${state.inactivityPeriod.label}", style=MaterialTheme.typography.titleMedium)
        Text("Ghost Cloak automatically clears local data after the selected period when the device can securely determine that the inactivity limit has expired. If device time cannot be trusted after a restart, Ghost Cloak remains locked until you authenticate.")
        Text("Clearing local keys, messages and attachments cannot be undone. This works offline and does not tell the server your setting.",
            color=MaterialTheme.colorScheme.error, style=MaterialTheme.typography.bodySmall)
        when {
            state.mode == LockMode.OFF -> Text("Enable App Lock first.")
            !state.manageGranted -> {
                Text("Confirm your normal unlock method to change this setting.")
                UnlockControls(controller, UnlockPurpose.MANAGE)
            }
            else -> {
                InactivityPeriod.entries.forEach { option ->
                    Row(Modifier.fillMaxWidth().selectable(selected==option, role=Role.RadioButton) { selected=option },
                        verticalAlignment=Alignment.CenterVertically) {
                        RadioButton(selected==option, onClick=null)
                        Text(option.label)
                    }
                }
                if(state.inactivityPeriod==InactivityPeriod.OFF && selected!=InactivityPeriod.OFF) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Checkbox(acknowledged, onCheckedChange={acknowledged=it})
                        Text("I understand Ghost Cloak may permanently clear this device's local data.")
                    }
                }
                if(needsEmergency && !state.emergencyAdministrationGranted) {
                    Text("Confirm the current Safe Exit PIN to extend or disable protection.")
                    PinInput(emergencyPin,"Current Safe Exit PIN",!state.busy) { emergencyPin=it }
                    Button(enabled=!state.busy && emergencyPin.length>=6,onClick={
                        val input=emergencyPin.toCharArray(); emergencyPin=""
                        scope.launch { controller.verifyEmergencyAdministration(input) }
                    }) { Text("Confirm Safe Exit PIN") }
                }
                state.message?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                Button(enabled=!state.busy && selected!=state.inactivityPeriod && (!needsEmergency || state.emergencyAdministrationGranted) &&
                    (state.inactivityPeriod!=InactivityPeriod.OFF || acknowledged), onClick={
                    if(selected==InactivityPeriod.OFF) confirmDisable=true
                    else scope.launch { if(controller.configureInactivity(selected,acknowledged,false)) back() }
                }) { Text(if(selected==InactivityPeriod.OFF) "Disable protection" else "Save protection period") }
            }
        }
    }
    if(confirmDisable) AlertDialog(onDismissRequest={confirmDisable=false},title={Text("Disable Inactive Device Protection?")},
        text={Text("This stops automatic local Safe Exit for inactivity. Your existing local data remains on this device.")},
        dismissButton={TextButton(onClick={confirmDisable=false}) {Text("Cancel")}},
        confirmButton={TextButton(onClick={confirmDisable=false;scope.launch {
            if(controller.configureInactivity(InactivityPeriod.OFF,false,true)) back()
        }}) {Text("Disable")}})
}
