package org.ghostcloak.app.access

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import org.ghostcloak.app.ui.components.PageContent

private enum class SafeExitAction { ENABLE, CHANGE, DISABLE }

/** Internal mechanism retains its engineering name. User-facing name is Safe Exit. */
@Composable fun EmergencyWipeSettingsScreen(controller: AppLockController, back: () -> Unit) {
    if (!controller.emergencyArmingEnabled) {PageContent("Safe Exit",back=back) {Text("Unavailable in this build.")}; return}
    val state by controller.state.collectAsState()
    val scope=rememberCoroutineScope()
    var action by remember {mutableStateOf<SafeExitAction?>(null)}
    var pin by remember {mutableStateOf("")}
    var confirmation by remember {mutableStateOf("")}
    var current by remember {mutableStateOf("")}
    var acknowledged by remember {mutableStateOf(false)}
    var confirmingDisable by remember {mutableStateOf(false)}
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_STOP) {
            pin=""; confirmation=""; current=""; acknowledged=false; confirmingDisable=false
        }}
        lifecycle.addObserver(observer)
        onDispose {lifecycle.removeObserver(observer); pin=""; confirmation=""; current=""; controller.endManagement()}
    }
    fun select(next: SafeExitAction) {
        controller.endManagement(); pin=""; confirmation=""; current=""; acknowledged=false; action=next
    }
    PageContent("Safe Exit",back=back) {
        Text(if(state.emergencyEnabled) "On" else "Disabled",style=MaterialTheme.typography.titleMedium)
        Text("Set a separate PIN that securely clears Ghost Cloak from this device when entered at the app lock screen.")
        Text("This build is a non-destructive debug preview. Activation permanently blocks local access and stops before deleting keys or data. Use only a disposable test device.",color=MaterialTheme.colorScheme.error)
        Text("There is no forgotten Safe Exit PIN reset through your normal PIN or biometric.",style=MaterialTheme.typography.bodySmall)
        when {
            !state.mode.pin -> Text("Configure PIN app lock first. Safe Exit requires a normal PIN unlock method.")
            action==null -> {
                if(state.emergencyEnabled) {
                    Button(onClick={select(SafeExitAction.CHANGE)}) {Text("Change PIN")}
                    OutlinedButton(onClick={select(SafeExitAction.DISABLE)}) {Text("Disable")}
                } else Button(onClick={select(SafeExitAction.ENABLE)}) {Text("Set up Safe Exit")}
            }
            !state.manageGranted -> {
                Text("Authenticate to manage Safe Exit")
                UnlockControls(controller,UnlockPurpose.MANAGE)
            }
            state.emergencyEnabled && !state.emergencyAdministrationGranted -> {
                Text("Confirm your current Safe Exit PIN.")
                PinInput(current,"Current Safe Exit PIN",!state.busy) {current=it}
                Button(enabled=!state.busy && current.length>=6,onClick={
                    val input=current.toCharArray(); current=""
                    scope.launch {controller.verifyEmergencyAdministration(input)}
                }) {Text("Confirm Safe Exit PIN")}
                state.message?.let {Text(it,color=MaterialTheme.colorScheme.error)}
            }
            action==SafeExitAction.DISABLE -> {
                Text("Both credentials confirmed. Disabling removes only the Safe Exit verifier.")
                Button(enabled=!state.busy,onClick={confirmingDisable=true}) {Text("Confirm disable")}
            }
            else -> {
                PinInput(pin,"New Safe Exit PIN",!state.busy) {pin=it}
                PinInput(confirmation,"Confirm Safe Exit PIN",!state.busy) {confirmation=it}
                Text("Planned destructive behavior: Safe Exit permanently destroys this device's Ghost Cloak cryptographic keys and local data. This cannot be undone.")
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Checkbox(acknowledged,{acknowledged=it},enabled=!state.busy)
                    Text("I understand this cannot be undone.")
                }
                state.message?.let {Text(it,color=MaterialTheme.colorScheme.error)}
                Button(enabled=acknowledged && !state.busy,onClick={
                    val first=pin.toCharArray(); val second=confirmation.toCharArray(); val ack=acknowledged
                    pin=""; confirmation=""
                    scope.launch {if(controller.configureEmergency(first,second,charArrayOf(),ack)) {acknowledged=false; action=null; back()}}
                }) {Text(if(action==SafeExitAction.CHANGE) "Save new PIN" else "Enable Safe Exit")}
            }
        }
    }
    if(confirmingDisable) AlertDialog(onDismissRequest={confirmingDisable=false},title={Text("Disable Safe Exit?")},
        text={Text("Remove the Safe Exit verifier. This does not delete your identity, messages or attachments.")},
        dismissButton={TextButton(onClick={confirmingDisable=false}) {Text("Cancel")}},
        confirmButton={TextButton(onClick={scope.launch {
            if(controller.disableEmergency(charArrayOf(),true)) {action=null; back()}
            confirmingDisable=false
        }}) {Text("Disable Safe Exit")}})
}
