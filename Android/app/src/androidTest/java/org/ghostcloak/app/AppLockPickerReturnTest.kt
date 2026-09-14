package org.ghostcloak.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.*
import kotlinx.coroutines.*
import org.ghostcloak.app.access.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AppLockPickerReturnTest {
    @get:Rule val compose = createComposeRule()

    private fun exercise(pin: Boolean)=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val persistence=object:LockPersistence {
            var bytes:ByteArray?=null
            override suspend fun read()=bytes?.copyOf()
            override suspend fun write(bytes:ByteArray) {this.bytes=bytes.copyOf()}
        }
        val controller=AppLockController(persistence,scope,android.os.SystemClock::elapsedRealtime,{1})
        var requestCode=0
        var received=0
        val registry=object:ActivityResultRegistry() {
            override fun <I,O> onLaunch(code:Int,contract:ActivityResultContract<I,O>,input:I,options:ActivityOptionsCompat?) {
                requestCode=code
            }
        }
        val owner=object:ActivityResultRegistryOwner { override val activityResultRegistry=registry }
        withContext(Dispatchers.Main) {
            controller.start();controller.initialize()
            if(pin) assertTrue(controller.configure(LockMode.PIN,LockTiming.IMMEDIATE,"824619".toCharArray(),"824619".toCharArray()))
        }
        try {
            compose.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    AppLockGate(controller) {
                        val nav=rememberNavController()
                        NavHost(nav,startDestination="chats") {
                            composable("chats") { TextButton(onClick={nav.navigate("conversation")}) {Text("Open conversation")} }
                            composable("conversation") {
                                val launcher=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                                    assertTrue(controller.state.value.canShowContent)
                                    assertEquals(Uri.parse("content://synthetic/document"),uri)
                                    received++
                                }
                                TextButton(onClick={launcher.launch(arrayOf("*/*"))}) {Text("Pick document")}
                            }
                        }
                    }
                }
            }
            compose.onNodeWithText("Open conversation").performClick()
            repeat(2) { cycle ->
                compose.onNodeWithText("Pick document").performClick()
                compose.runOnIdle { controller.stop() }
                compose.onNodeWithText("Pick document").assertDoesNotExist()
                // Result arrives while the private subtree is absent: it must remain pending.
                compose.runOnIdle { registry.dispatchResult(requestCode,Activity.RESULT_OK,
                    Intent().setData(Uri.parse("content://synthetic/document"))) }
                assertEquals(cycle,received)
                compose.runOnIdle {controller.start()}
                if(pin) {
                    compose.onNodeWithText("Pick document").assertDoesNotExist()
                    withContext(Dispatchers.Main) {assertTrue(controller.verifyPin("824619".toCharArray()))}
                }
                compose.onNodeWithText("Pick document").assertIsDisplayed()
                compose.onNodeWithText("Open conversation").assertDoesNotExist()
                compose.runOnIdle {assertEquals(cycle+1,received)}
            }
        } finally {scope.cancel()}
    }
    @Test fun documentResultAndConversationSurviveBackgroundWithoutLock()=exercise(false)
    @Test fun documentResultWaitsForUnlockAndRestoresConversation()=exercise(true)
}
