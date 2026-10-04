package org.ghostcloak.app

import android.os.Bundle
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
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
import org.ghostcloak.app.application.RecoveryStatus
import org.ghostcloak.app.access.LocalOperationState
import org.ghostcloak.app.access.SafeExitRecoveryDiagnostics
import org.ghostcloak.app.access.SafeExitRecoveryEvent

class MainActivity : FragmentActivity() {
    private var chatsRequest by mutableIntStateOf(0)
    private val app get() = application as org.ghostcloak.app.application.GhostApplication
    private val runtime get() = (application as org.ghostcloak.app.application.GhostApplication).runtime
    private val appLock get() = (application as org.ghostcloak.app.application.GhostApplication).appLock
    override fun onStart() {
        if (app.localOperationGate.blocked) { super.onStart(); return }
        app.inactivity.check()
        if (app.localOperationGate.blocked) { super.onStart(); return }
        appLock.start()
        // Startup callbacks must see current visibility; photo results additionally wait for RESUMED.
        if (app.inactivity.normalAccessAllowed) {
            app.media.start()
            runtime.notificationActivityVisible(true)
        }
        super.onStart()
    }
    override fun onStop() {
        if (!app.localOperationGate.blocked) {
            if (app.inactivity.normalAccessAllowed) { app.media.stop(); runtime.notificationActivityVisible(false) }
            appLock.stop(isChangingConfigurations)
        }
        super.onStop()
    }
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == org.ghostcloak.app.application.AndroidLocalNotifications.OPEN_CHATS) chatsRequest++
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        val restored=if(app.freshGeneration.value>0) null else savedInstanceState
        super.onCreate(restored)
        chatsRequest = restored?.getInt("chats-request", 0) ?: 0
        // Global policy: protects the first frame, PIN enrollment, grace periods and locked screens.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        lifecycleScope.launch { if (!app.localOperationGate.blocked && app.inactivity.state.value !in
            setOf(org.ghostcloak.app.access.InactivityAccess.UNAVAILABLE,org.ghostcloak.app.access.InactivityAccess.EXPIRED))
            app.localOperationGate.operation { appLock.initialize() } }
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
                    val operationState by app.localOperationGate.state.collectAsState()
                    val inactivityAccess by app.inactivity.state.collectAsState()
                    val generation by app.freshGeneration.collectAsState()
                    var lastInactivityAccess by remember { mutableStateOf<org.ghostcloak.app.access.InactivityAccess?>(null) }
                    LaunchedEffect(inactivityAccess) {
                        if(inactivityAccess==org.ghostcloak.app.access.InactivityAccess.TIME_UNCERTAIN) appLock.requireFreshUnlock()
                        if(lastInactivityAccess==org.ghostcloak.app.access.InactivityAccess.TIME_UNCERTAIN && app.inactivity.normalAccessAllowed &&
                            operationState==LocalOperationState.NONE &&
                            lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                            app.media.start(); runtime.notificationActivityVisible(true)
                        }
                        lastInactivityAccess=inactivityAccess
                    }
                    LaunchedEffect(operationState,generation) {
                        if(operationState==org.ghostcloak.app.access.LocalOperationState.NONE && generation>0) {
                            chatsRequest=0
                            if(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                                appLock.start(); app.media.start(); runtime.notificationActivityVisible(true)
                            }
                            app.localOperationGate.operation {appLock.initialize()}
                        }
                    }
                    if (operationState != org.ghostcloak.app.access.LocalOperationState.NONE) {
                        val recoveryStatus by app.recoveryStatus.collectAsState()
                        SafeExitRecoveryScreen(operationState,recoveryStatus) { app.resumeLocalOperation(retry=true) }
                    } else if(inactivityAccess==org.ghostcloak.app.access.InactivityAccess.UNAVAILABLE ||
                        inactivityAccess==org.ghostcloak.app.access.InactivityAccess.EXPIRED) {
                        InactivityUnavailableScreen()
                    } else key(generation) { AppLockGate(appLock) {
                        if(app.inactivity.normalAccessAllowed) {
                            val model = remember { ViewModelProvider(this@MainActivity)["ghost-$generation",GhostViewModel::class.java] }
                            SensitiveClipboardProvider { GhostApp(model, chatsRequest) }
                        }
                    } }
                }
            }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("chats-request", chatsRequest)
        super.onSaveInstanceState(outState)
    }
}

@Composable private fun InactivityUnavailableScreen() {
    androidx.compose.material3.Surface(androidx.compose.ui.Modifier.fillMaxSize()) {
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment=androidx.compose.ui.Alignment.Center) {
            androidx.compose.material3.Text("Protected local state is unavailable. Close and reopen Ghost Cloak. Your data has not been reset.")
        }
    }
}

@Composable
internal fun SafeExitRecoveryScreen(state: LocalOperationState, status: RecoveryStatus, onRetry: () -> Unit) {
    LaunchedEffect(state) {
        SafeExitRecoveryDiagnostics.emit(SafeExitRecoveryEvent.SAFE_EXIT_RECOVERY_SCREEN)
        SafeExitRecoveryDiagnostics.state(state)
    }
    androidx.compose.material3.Surface(androidx.compose.ui.Modifier.fillMaxSize()) {
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment=androidx.compose.ui.Alignment.Center) {
            androidx.compose.foundation.layout.Column(horizontalAlignment=androidx.compose.ui.Alignment.CenterHorizontally) {
                androidx.compose.material3.Text("Ghost Cloak is completing a secure local operation.")
                when (status) {
                    RecoveryStatus.RUNNING -> androidx.compose.material3.Text("Retrying secure local operation…")
                    RecoveryStatus.FAILED -> androidx.compose.material3.Text("Could not finish. You can retry.")
                    RecoveryStatus.IDLE -> Unit
                }
                androidx.compose.material3.TextButton(onClick=onRetry,enabled=status!=RecoveryStatus.RUNNING) {
                    androidx.compose.material3.Text("Retry")
                }
            }
        }
    }
}
