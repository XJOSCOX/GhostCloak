package org.ghostcloak.app.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.*
import org.ghostcloak.app.application.GhostViewModel
import org.ghostcloak.app.ui.screens.*

@Composable fun GhostApp(model: GhostViewModel) {
    val state by model.state.collectAsState()
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = {
        if (state.identity != null && route in listOf("contacts", "settings")) {
            Surface(color = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf("contacts" to "Contacts", "settings" to "Settings").forEach { (destination, label) ->
                        val click = { nav.navigate(destination) { popUpTo("contacts"); launchSingleTop = true } }
                        if (route == destination) FilledTonalButton(onClick = click, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text(label) }
                        else TextButton(onClick = click, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text(label) }
                    }
                }
            }
        }
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = 680.dp).fillMaxSize()) {
                if (state.identity == null) FirstLaunchScreen(state, model::create)
                else NavHost(nav, startDestination = "contacts") {
                    composable("contacts") { ContactsScreen(state, { nav.navigate("add") }, { id -> nav.navigate("conversation/$id") }) }
                    composable("add") { AddContactScreen(state, { nav.popBackStack() }, model::exportCard, {name->model.addNetwork(name) {nav.popBackStack()}}) { text -> model.importCard(text) { nav.popBackStack() } } }
                    composable("settings") { SettingsScreen(state, model::rename, model.developerAvailable, model::startDemo, model::leaveDemo,model::connectNetwork,model::syncNetwork,model::publishNetwork,model::logoutNetwork) }
                    composable("conversation/{id}") { backStack ->
                        val id = backStack.arguments?.getString("id") ?: return@composable
                        val contact = state.contacts.firstOrNull { it.contact.remoteDeviceId == id } ?: return@composable
                        LaunchedEffect(id) { model.select(id) }
                        ConversationScreen(state, contact, { nav.popBackStack() }, { nav.navigate("security/$id") },
                            { text, success -> model.send(id, text, success) }, { model.delete(id, it) })
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
