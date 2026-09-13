package org.ghostcloak.app

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.ghostcloak.app.access.AppLockGate
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.lifecycle.ViewModelProvider
import org.ghostcloak.app.application.GhostViewModel
import org.ghostcloak.app.ui.navigation.GhostApp
import org.ghostcloak.app.ui.theme.*
import androidx.compose.runtime.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.core.view.WindowCompat
import org.ghostcloak.app.ui.privacy.SensitiveClipboardProvider

class MainActivity : FragmentActivity() {
    private var chatsRequest by mutableIntStateOf(0)
    private val runtime get() = (application as org.ghostcloak.app.application.GhostApplication).runtime
    private val appLock get() = (application as org.ghostcloak.app.application.GhostApplication).appLock
    override fun onStart() { appLock.start(); super.onStart(); runtime.notificationActivityVisible(true); (application as org.ghostcloak.app.application.GhostApplication).media.start() }
    override fun onStop() { (application as org.ghostcloak.app.application.GhostApplication).media.stop(); appLock.stop(isChangingConfigurations); runtime.notificationActivityVisible(false); super.onStop() }
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == org.ghostcloak.app.application.AndroidLocalNotifications.OPEN_CHATS) chatsRequest++
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Global policy: protects the first frame, PIN enrollment, grace periods and locked screens.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        lifecycleScope.launch { appLock.initialize() }
        if (savedInstanceState == null && intent.action == org.ghostcloak.app.application.AndroidLocalNotifications.OPEN_CHATS) chatsRequest++
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        val appearance = AppearanceStore(applicationContext)
        setContent {
            val mode by appearance.mode.collectAsState()
            val dark = mode.isDark(isSystemInDarkTheme())
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            CompositionLocalProvider(LocalAppearance provides AppearanceControl(mode, appearance::select)) {
                GhostCloakTheme(darkTheme = dark) {
                    AppLockGate(appLock) {
                        val model = remember { ViewModelProvider(this@MainActivity)[GhostViewModel::class.java] }
                        SensitiveClipboardProvider { GhostApp(model, chatsRequest) }
                    }
                }
            }
        }
    }
}
