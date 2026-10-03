package org.ghostcloak.app.access

import androidx.activity.compose.BackHandler
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import org.ghostcloak.app.ui.components.PageContent

/** Sensitive subtree is not composed until separately authorized, including restored routes. */
@Composable fun SettingsAccessGate(controller: AppLockController, cancel: () -> Unit, content: @Composable () -> Unit) {
    val state by controller.state.collectAsState()
    if(state.settingsGranted) content()
    else {
        val leave={controller.leaveSettings(); cancel()}
        BackHandler(onBack=leave)
        PageContent("Authenticate to open Settings", back=leave) {
            Text("Confirm your app unlock method to continue.")
            if(state.ready && !state.unavailable && state.canShowContent) UnlockControls(controller,UnlockPurpose.SETTINGS)
        }
    }
}
