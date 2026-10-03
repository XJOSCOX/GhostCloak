package org.ghostcloak.app

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.ghostcloak.app.access.*
import org.ghostcloak.app.application.*
import org.ghostcloak.app.ui.screens.*
import org.ghostcloak.app.ui.theme.GhostCloakTheme
import org.ghostcloak.messaging.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BlockedContactsScreenTest {
    @get:Rule val compose=createComposeRule()
    private val contact=Contact("internal-contact","internal-account","alice","internal-device",blocked=true,request=true)
    @Test fun emptyListAndCancelThenConfirmedUnblock() {
        val state=mutableStateOf(AppState(loading=false));var calls=0
        compose.setContent {GhostCloakTheme {BlockedContactsScreen(state.value,{}, {calls++;state.value=state.value.copy(blockedContacts=emptyList())})}}
        compose.onNodeWithText("No blocked contacts").assertIsDisplayed()
        compose.runOnIdle{state.value=state.value.copy(blockedContacts=listOf(contact))}
        compose.onNodeWithText("alice").assertIsDisplayed()
        compose.onNodeWithText("internal-device").assertDoesNotExist()
        compose.onNodeWithText("internal-account").assertDoesNotExist()
        compose.onNodeWithText("Unblock").performClick();compose.onNodeWithText("Unblock this contact?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick();assertEquals(0,calls)
        compose.onNodeWithText("alice").assertIsDisplayed()
        compose.onNodeWithText("Unblock").performClick()
        compose.onAllNodesWithText("Unblock")[1].performClick()
        compose.onNodeWithText("No blocked contacts").assertIsDisplayed();assertEquals(1,calls)
    }
    @Test fun blockedSearchOffersConfirmationAndDoesNotAdd() {
        val state=mutableStateOf(AppState(loading=false,networkConfigured=true,blockedContacts=listOf(contact)))
        var lookups=0;var unblocks=0
        compose.setContent{GhostCloakTheme{AddContactScreen(state.value,{}, {}, {lookups++},
            unblock={unblocks++;state.value=state.value.copy(blockedContacts=emptyList())},import={})}}
        compose.onNodeWithText("Exact username").performTextInput("alice")
        compose.onNodeWithText("This contact is blocked.").assertIsDisplayed()
        compose.onNodeWithText("Find and add contact").assertDoesNotExist()
        compose.onNodeWithText("Unblock").performClick();compose.onNodeWithText("Cancel").performClick()
        assertEquals(0,unblocks);assertEquals(0,lookups)
        compose.onNodeWithText("Unblock").performClick();compose.onAllNodesWithText("Unblock")[1].performClick()
        assertEquals(1,unblocks);assertEquals(0,lookups)
        compose.onNodeWithText("Find and add contact").performClick();assertEquals(1,lookups)
    }
    @Test fun appLockRemovesBlockedNamesAndActionsFromAccessibilityTree():Unit=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val persistence=object:LockPersistence {
            var bytes:ByteArray?=null
            override suspend fun read()=bytes?.copyOf()
            override suspend fun write(bytes:ByteArray){this.bytes=bytes.copyOf()}
        }
        val controller=AppLockController(persistence,scope,android.os.SystemClock::elapsedRealtime,{1})
        try {
            withContext(Dispatchers.Main){controller.start();controller.initialize();controller.configure(LockMode.PIN,LockTiming.IMMEDIATE,"824619".toCharArray(),"824619".toCharArray())}
            compose.setContent{GhostCloakTheme{AppLockGate(controller){BlockedContactsScreen(AppState(loading=false,blockedContacts=listOf(contact)),{}, {})}}}
            compose.onNodeWithText("alice").assertIsDisplayed()
            compose.runOnIdle{controller.stop();controller.start()}
            compose.onNodeWithText("alice",useUnmergedTree=true).assertDoesNotExist()
            compose.onNodeWithText("Unblock",useUnmergedTree=true).assertDoesNotExist()
            compose.onNodeWithText("PIN").performTextInput("824619")
            compose.onNodeWithText("Unlock",useUnmergedTree=true).performClick()
            compose.waitUntil(20000){controller.state.value.canShowContent}
            compose.onNodeWithText("alice").assertIsDisplayed()
        } finally {scope.cancel()}
    }
    @Test fun runtimeUnblockAndLocalListIssueNoNetworkRequestAndSurviveRecreation()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val wire=SyntheticNetwork();val name=org.ghostcloak.identity.RandomIdentifiers.create()
        var runtime=AppRuntime(context,"https://fixture.invalid",name,wire)
        try {
            runtime.use{runtime.create(it,"alice")}
            // Install a local request identity through the real encrypted repository.
            org.ghostcloak.storage.EncryptedEndpointStore.open(context,name).use {records->
                val repo=LocalRepository(records);repo.save(contact);repo.startRequest(contact.remoteDeviceId,null);repo.block(contact.remoteDeviceId,true)
            }
            val before=wire.requests
            runtime.use{assertEquals(1,it.blockedContacts().size);it.unblock(contact.remoteDeviceId)}
            assertEquals(before,wire.requests)
            runtime.close();runtime=AppRuntime(context,"https://fixture.invalid",name,wire)
            runtime.use{assertTrue(it.blockedContacts().isEmpty())}
            assertEquals(before,wire.requests)
        } finally {runtime.close()}
    }
}
