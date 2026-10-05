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
import org.ghostcloak.app.application.NotificationPrivacy
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.material3.RadioButton

/** User-initiated only. After one request, further changes use Android's settings. */
@Composable fun NotificationSettings() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val preferences = remember { context.getSharedPreferences("notification-permission", android.content.Context.MODE_PRIVATE) }
    val publisher = remember { AndroidLocalNotifications(context) }
    var revision by remember { mutableIntStateOf(0) }
    var privacy by remember { mutableStateOf(NotificationPrivacy.read(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { revision++ }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val enabled = remember(revision) { publisher.allowed() }
    SettingsGroup("Notifications") {
        Text("Choose what appears in notifications. The device lock screen always hides their content. Messaging works without notification permission.",
            style = MaterialTheme.typography.bodyMedium)
        NotificationPrivacy.entries.forEach { option ->
            Row(Modifier.fillMaxWidth().clickable {
                NotificationPrivacy.save(context, option); privacy = option
            }) {
                RadioButton(selected = privacy == option, onClick = {
                    NotificationPrivacy.save(context, option); privacy = option
                })
                Column(Modifier.weight(1f)) {
                    Text(option.label)
                    Text(option.explanation, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text("App Lock protects previews while locked. View Once and message requests never show content. Android may retain prior notifications in system history.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
