package org.ghostcloak.app.access

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import org.ghostcloak.app.ui.components.PageContent

/** Preview only: release has neither a settings entry nor an enabled credential trigger. */
@Composable fun EmergencyWipeSettingsScreen(controller: AppLockController, back: () -> Unit) {
    if (!controller.emergencyArmingEnabled) { PageContent("Emergency Wipe", back=back) { Text("Unavailable in this build.") }; return }
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var current by remember { mutableStateOf("") }
    var acknowledged by remember { mutableStateOf(false) }
    var disabling by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) {
            pin=""; confirmation=""; current=""; acknowledged=false; disabling=false
        } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); pin=""; confirmation=""; current=""; controller.endManagement() }
    }
    PageContent("Emergency Wipe", back=back) {
        Text(if (state.emergencyEnabled) "Enabled (debug preview)" else "Disabled", style=MaterialTheme.typography.titleMedium)
        Text("Set a separate PIN that permanently destroys Ghost Cloak data on this device when entered at the app lock screen.")
        Text("Future destructive feature. This debug preview ONLY arms a permanent local lock and stops before deleting anything. Use a disposable test device; this build cannot restore access after arming.", color=MaterialTheme.colorScheme.error)
        if (!state.mode.pin) {
            Text("Configure PIN app lock first. Emergency Wipe requires a normal PIN unlock method.")
        } else if (!state.manageGranted) {
            Text("Confirm your current unlock method to manage Emergency Wipe.")
            UnlockControls(controller, UnlockPurpose.MANAGE)
        } else {
            if (state.emergencyEnabled) PinInput(current, "Current emergency PIN", !state.busy) { current=it }
            PinInput(pin, if (state.emergencyEnabled) "New emergency PIN" else "Emergency PIN", !state.busy) { pin=it }
            PinInput(confirmation, "Confirm emergency PIN", !state.busy) { confirmation=it }
            Text("Permanent destruction in the future release has no recovery. This debug preview permanently blocks local access instead. Never use it on an account you need to keep.")
            Row(verticalAlignment=Alignment.CenterVertically) {
                Checkbox(acknowledged, { acknowledged=it }, enabled=!state.busy)
                Text("I understand this cannot be undone.")
            }
            state.message?.let { Text(it, color=MaterialTheme.colorScheme.error) }
            Button(enabled=acknowledged && !state.busy, onClick={
                val first=pin.toCharArray(); val second=confirmation.toCharArray(); val old=current.toCharArray()
                pin=""; confirmation=""; current=""
                scope.launch { if (controller.configureEmergency(first, second, old, acknowledged)) { acknowledged=false; back() } }
            }) { Text(if (state.emergencyEnabled) "Change emergency PIN" else "Enable emergency PIN") }
            if (state.emergencyEnabled) OutlinedButton(enabled=!state.busy, onClick={disabling=true}) { Text("Disable Emergency Wipe") }
        }
    }
    if (disabling) AlertDialog(onDismissRequest={disabling=false}, title={Text("Disable Emergency Wipe?")},
        text={Text("Remove the emergency PIN verifier. This does not delete your identity, messages or attachments.")},
        dismissButton={TextButton(onClick={disabling=false}) {Text("Cancel")}},
        confirmButton={TextButton(onClick={scope.launch { if(controller.disableEmergency(true)) back(); disabling=false }}) {Text("Disable")}})
}
