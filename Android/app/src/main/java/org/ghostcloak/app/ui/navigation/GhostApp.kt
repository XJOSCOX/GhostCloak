package org.ghostcloak.app.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import org.ghostcloak.app.application.GhostViewModel
import org.ghostcloak.app.ui.screens.*
import org.ghostcloak.app.ui.components.*

@Composable fun GhostApp(model: GhostViewModel) {
    val state by model.state.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { model.foregroundSync() }
    }
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = {
        if (state.identity != null && route in listOf("contacts", "people", "profiles", "settings")) {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                listOf(Triple("contacts", "Chat", Glyph.CHAT), Triple("people", "Contact", Glyph.CONTACTS), Triple("profiles", "Profiles", Glyph.PERSON), Triple("settings", "Settings", Glyph.SETTINGS)).forEach { (destination, label, glyph) ->
                    NavigationBarItem(selected = route == destination,
                        onClick = { nav.navigate(destination) { popUpTo("contacts"); launchSingleTop = true } },
                        icon = { AppIcon(glyph) }, label = { Text(label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant))
                }
            }
        }
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = 680.dp).fillMaxSize()) {
                if (state.identity == null) FirstLaunchScreen(state, model::create)
                else NavHost(nav, startDestination = "contacts") {
                    composable("contacts") { ContactsScreen(state, { nav.navigate("add") }, { id -> nav.navigate("conversation/$id") }, model::connectNetwork, model::syncNetwork) }
                    composable("people") { ContactsScreen(state, { nav.navigate("add") }, { id -> nav.navigate("conversation/$id") }, model::connectNetwork, model::syncNetwork, directory=true) }
                    composable("add") { AddContactScreen(state, { nav.popBackStack() }, model::exportCard, {name->model.addNetwork(name) {nav.popBackStack()}}) { text -> model.importCard(text) { nav.popBackStack() } } }
                    composable("profiles") { ProfilesScreen(state, model::rename) }
                    composable("settings") { SettingsScreen(state, model.developerAvailable, model::startDemo, model::leaveDemo,model::connectNetwork,model::syncNetwork,model::publishNetwork,model::logoutNetwork) }
                    composable("conversation/{id}") { backStack ->
                        val id = backStack.arguments?.getString("id") ?: return@composable
                        val contact = state.contacts.firstOrNull { it.contact.remoteDeviceId == id } ?: return@composable
                        LaunchedEffect(id) { model.select(id) }
                        ConversationScreen(state, contact, { nav.popBackStack() }, { nav.navigate("security/$id") },
                            { text, success -> model.send(id, text, success) }, { model.delete(id, it) }, model::connectNetwork, model::syncNetwork,
                            { model.acceptRequest(id) }, { model.deleteRequest(id); nav.popBackStack() })
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
