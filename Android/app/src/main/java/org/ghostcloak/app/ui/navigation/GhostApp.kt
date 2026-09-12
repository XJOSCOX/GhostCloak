package org.ghostcloak.app.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.compose.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import org.ghostcloak.app.application.GhostViewModel
import org.ghostcloak.app.application.withoutExpired
import org.ghostcloak.app.application.nextExpiryUiDelay
import org.ghostcloak.app.ui.screens.*
import org.ghostcloak.app.ui.components.*

@Composable fun GhostApp(model: GhostViewModel, chatsRequest: Int = 0) {
    val snapshot by model.state.collectAsState()
    var expiryTick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(snapshot) {
        while (true) {
            kotlinx.coroutines.delay(snapshot.nextExpiryUiDelay(model.expiryNow()))
            expiryTick++
        }
    }
    // Read tick to invalidate even if storage/network work is delayed. No private stale frame.
    val state = expiryTick.let { snapshot.withoutExpired(model.expiryNow()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { model.foregroundSync() }
    }
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    var handledChatsRequest by remember { mutableIntStateOf(0) }
    LaunchedEffect(chatsRequest, state.identity != null, entry != null) {
        // MainActivity's app-lock gate must allow this subtree before its graph is installed.
        if (chatsRequest > handledChatsRequest && state.identity != null && entry != null) {
            nav.navigate("contacts") {
                popUpTo("contacts") { inclusive = true }
                launchSingleTop = true
            }
            handledChatsRequest = chatsRequest
        }
    }
    val route = entry?.destination?.route
    Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = {
        if (state.identity != null && route in listOf("contacts", "people", "profiles", "settings")) {
            GhostBottomBar(route) { destination ->
                nav.navigate(destination) { popUpTo("contacts"); launchSingleTop = true }
            }
        }
    }) { padding ->
        Box(Modifier.fillMaxSize().scaffoldContentInsets(padding), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = org.ghostcloak.app.ui.theme.GhostLayout.maxPageWidth).fillMaxSize()) {
                if (state.identity == null) FirstLaunchScreen(state, model::create)
                else NavHost(nav, startDestination = "contacts") {
                    composable("contacts") { ContactsScreen(state, { nav.navigate("add") }, { id -> nav.navigate("conversation/$id") }, model::connectNetwork, model::syncNetwork) }
                    composable("people") { ContactsScreen(state, { nav.navigate("add") }, { id -> nav.navigate("conversation/$id") }, model::connectNetwork, model::syncNetwork, directory=true) }
                    composable("add") { AddContactScreen(state, { nav.popBackStack() }, model::exportCard, {name->model.addNetwork(name) {nav.popBackStack()}}) { text -> model.importCard(text) { nav.popBackStack() } } }
                    composable("profiles") { ProfilesScreen(state, model::rename) }
                    composable("settings") { SettingsScreen(state, model.developerAvailable, model::startDemo, model::leaveDemo,model::connectNetwork,model::syncNetwork,model::publishNetwork,model::logoutNetwork, { nav.navigate("app-lock") }) }
                    composable("app-lock") {
                        org.ghostcloak.app.access.LocalAppLock.current?.let { controller ->
                            org.ghostcloak.app.access.AppLockSettingsScreen(controller) { nav.popBackStack() }
                        }
                    }
                    composable("conversation/{id}") { backStack ->
                        val id = backStack.arguments?.getString("id") ?: return@composable
                        val contact = state.contacts.firstOrNull { it.contact.remoteDeviceId == id } ?: return@composable
                        DisposableEffect(id, lifecycle) {
                            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                                if (event == Lifecycle.Event.ON_START) model.select(id)
                                if (event == Lifecycle.Event.ON_STOP) model.leaveConversation(id)
                            }
                            lifecycle.addObserver(observer)
                            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) model.select(id)
                            onDispose { lifecycle.removeObserver(observer); model.leaveConversation(id) }
                        }
                        ConversationScreen(state, contact, { nav.popBackStack() }, { nav.navigate("security/$id") },
                            { text, success -> model.send(id, text, success) }, { model.delete(id, it) }, model::connectNetwork, model::syncNetwork,
                            { model.acceptRequest(id) }, { model.deleteRequest(id); nav.popBackStack() }, { model.clearConversation(id) },
                            { model.setDisappearing(id, it) })
                    }
                    composable("security/{id}") { backStack ->
                        val id = backStack.arguments?.getString("id") ?: return@composable
                        val contact = state.contacts.firstOrNull { it.contact.remoteDeviceId == id } ?: return@composable
                        ContactSecurityScreen(state, contact, { nav.popBackStack() }, { model.loadFingerprint(id, it) },
                            { model.verify(id, it) }, { model.trust(id, it) }, { model.block(id, it) }, { nav.navigate("add") })
                    }
                }
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }
    }
}
