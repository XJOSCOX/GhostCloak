package org.ghostcloak.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.lifecycle.ViewModelProvider
import org.ghostcloak.app.application.GhostViewModel
import org.ghostcloak.app.ui.navigation.GhostApp
import org.ghostcloak.app.ui.theme.GhostCloakTheme
import org.ghostcloak.app.ui.privacy.SensitiveClipboardProvider

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        val model = ViewModelProvider(this)[GhostViewModel::class.java]
        setContent { GhostCloakTheme { SensitiveClipboardProvider { GhostApp(model) } } }
    }
}
