package org.ghostcloak.app.access

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import org.ghostcloak.app.ui.components.*
import org.ghostcloak.app.ui.theme.GhostLayout

val LocalAppLock = staticCompositionLocalOf<AppLockController?> { null }

/** The private subtree is absent, not hidden behind an overlay. Never accepts private screen data. */
@Composable fun AppLockGate(controller: AppLockController, content: @Composable () -> Unit) {
    val state by controller.state.collectAsState()
    if (state.canShowContent) CompositionLocalProvider(LocalAppLock provides controller, content = content)
    else Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = GhostLayout.maxPageWidth).fillMaxSize()) {
                PageContent("Ghost Cloak") {
                    BrandMark(Modifier.size(GhostLayout.touchTarget))
                    Text("Unlock Ghost Cloak", style = MaterialTheme.typography.headlineSmall)
                    when {
                        !state.ready -> CircularProgressIndicator()
                        state.unavailable -> {
                            Text(state.message ?: "App lock is unavailable.")
                            val scope = rememberCoroutineScope()
                            OutlinedButton(onClick = { scope.launch { controller.initialize() } }) { Text("Retry") }
                        }
                        else -> {
                            UnlockControls(controller, UnlockPurpose.UNLOCK)
                            var help by remember { mutableStateOf(false) }
                            TextButton(onClick = { help = true }) { Text("Unlock help") }
                            if (help) AlertDialog(onDismissRequest = { help = false }, title = { Text("Unlock help") },
                                text = { Text("No remote recovery. If your PIN is forgotten, use your configured biometric. Without a working method, access cannot be recovered here. Android Clear storage/uninstall destroys local identity and history.") },
                                confirmButton = { TextButton(onClick = { help = false }) { Text("Close") } })
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun PinInput(value: String, label: String, enabled: Boolean, changed: (String) -> Unit) {
    OutlinedTextField(value, onValueChange = { if (it.length <= 64 && it.all { c -> c in '0'..'9' }) changed(it) },
        modifier = Modifier.fillMaxWidth(), label = { Text(label) }, enabled = enabled, singleLine = true,
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
}

@Composable private fun UnlockControls(controller: AppLockController, purpose: UnlockPurpose) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") } // Deliberately never rememberSaveable / ViewModel / clipboard.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) pin = "" }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); pin = "" }
    }
    if (state.mode.biometric) BiometricButton(controller, purpose)
    if (state.mode.pin) {
        PinInput(pin, "PIN", !state.busy) { pin = it }
        Button(onClick = { val input = pin.toCharArray(); pin = ""; scope.launch { controller.verifyPin(input, purpose) } },
            enabled = !state.busy && pin.length >= 6) { Text(if (purpose == UnlockPurpose.MANAGE) "Confirm access" else "Unlock") }
    }
    state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
    if (state.busy) CircularProgressIndicator()
}

private fun Context.fragmentActivity(): FragmentActivity? {
    var current = this
    while (current is ContextWrapper) { if (current is FragmentActivity) return current; current = current.baseContext }
    return current as? FragmentActivity
}

@Composable private fun BiometricButton(controller: AppLockController, purpose: UnlockPurpose) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val activity = context.fragmentActivity()
    val scope = rememberCoroutineScope()
    val state by controller.state.collectAsState()
    var prompt by remember { mutableStateOf<BiometricPrompt?>(null) }
    var ticket by remember { mutableStateOf<Long?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) revision++
            if (event == Lifecycle.Event.ON_STOP) { ticket?.let { controller.cancelBiometric(it) }; ticket = null; prompt?.cancelAuthentication() }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); ticket?.let { controller.cancelBiometric(it) }; prompt?.cancelAuthentication() }
    }
    val available = remember(revision) { BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS }
    if (available && activity != null) {
        OutlinedButton(enabled = !state.busy && ticket == null, onClick = {
            val id = controller.beginBiometric(purpose) ?: return@OutlinedButton
            ticket = id
            val next = BiometricPrompt(activity, ContextCompat.getMainExecutor(context), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    ticket = null
                    scope.launch { controller.completeBiometric(id) }
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    ticket = null
                    controller.cancelBiometric(id, errorCode !in setOf(BiometricPrompt.ERROR_CANCELED, BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON))
                }
                // Non-matches stay in the system prompt. Never log callback details.
            })
            prompt = next
            try { next.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("Unlock Ghost Cloak")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG).setNegativeButtonText("Cancel").build()) }
            catch (_: Exception) { ticket = null; controller.cancelBiometric(id, true) }
        }) { Text(if (purpose == UnlockPurpose.ENROLL) "Confirm biometric" else "Use biometric") }
    } else {
        Text("Strong biometric unavailable. Use your PIN if configured, or check Android security settings.", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }) { Text("Android security settings") }
    }
}

@Composable fun AppLockSettingsScreen(controller: AppLockController, back: () -> Unit) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(state.mode) }
    var timing by remember { mutableStateOf(state.timing) }
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    DisposableEffect(Unit) { onDispose { pin = ""; confirm = ""; controller.endManagement() } }
    PageContent("App lock", back = back) {
        Text("Local app-access lock. Background delivery continues while locked. Screenshots and recent-app previews are protected throughout Ghost Cloak.", style = MaterialTheme.typography.bodyMedium)
        if (!state.manageGranted) {
            Text("Confirm your current unlock method to change app lock.")
            UnlockControls(controller, UnlockPurpose.MANAGE)
        } else {
            LockMode.entries.forEach { option ->
                Row(Modifier.fillMaxWidth().selectable(mode == option, enabled = !state.busy, role = Role.RadioButton,
                    onClick = { mode = option; pin = ""; confirm = "" }), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(mode == option, null, enabled = !state.busy)
                    Text(option.label)
                }
            }
            if (mode != LockMode.OFF) {
                Text("Auto-lock", style = MaterialTheme.typography.titleSmall)
                LockTiming.entries.forEach { option ->
                    Row(Modifier.fillMaxWidth().selectable(timing == option, enabled = !state.busy, role = Role.RadioButton,
                        onClick = { timing = option }), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(timing == option, null, enabled = !state.busy); Text(option.label)
                    }
                }
            }
            if (mode.pin) {
                Text("Choose a new PIN with at least 6 digits. Longer PINs are stronger. There is no email or SMS recovery.")
                PinInput(pin, "New PIN", !state.busy) { pin = it }
                PinInput(confirm, "Confirm PIN", !state.busy) { confirm = it }
            }
            if (mode.biometric) {
                Text("Confirm an enrolled strong biometric. Biometric-only mode has no PIN recovery if your biometric becomes unavailable.")
                BiometricButton(controller, UnlockPurpose.ENROLL)
                if (state.biometricConfirmed) Text("Biometric confirmed")
            }
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(enabled = !state.busy, onClick = {
                val first = pin.toCharArray(); val second = confirm.toCharArray(); pin = ""; confirm = ""
                scope.launch { if (controller.configure(mode, timing, first, second)) back() }
            }) { Text("Save app lock") }
        }
    }
}
