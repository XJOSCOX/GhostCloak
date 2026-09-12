package org.ghostcloak.app.ui.components

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.ghostcloak.app.application.AndroidLocalNotifications

/** User-initiated only. After one request, further changes use Android's settings. */
@Composable fun NotificationSettings() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val preferences = remember { context.getSharedPreferences("notification-permission", android.content.Context.MODE_PRIVATE) }
    val publisher = remember { AndroidLocalNotifications(context) }
    var revision by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { revision++ }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val enabled = remember(revision) { publisher.allowed() }
    SettingsGroup("Notifications") {
        Text("Optional alerts show only Ghost Cloak and New message. Messaging still works without notification permission.",
            style = MaterialTheme.typography.bodyMedium)
        Text(if (enabled) "Notifications are on" else "Notifications are off", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        val canRequest = !enabled && Build.VERSION.SDK_INT >= 33 && !preferences.getBoolean("requested", false)
        OutlinedButton(onClick = {
            if (canRequest) {
                preferences.edit().putBoolean("requested", true).apply()
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }) { Text(if (canRequest) "Enable notifications" else "Notification settings") }
    }
}
