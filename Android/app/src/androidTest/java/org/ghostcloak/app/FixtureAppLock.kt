package org.ghostcloak.app

import androidx.compose.runtime.*
import org.ghostcloak.app.access.*

/** Test-only OFF controller; production GhostApp always receives its Application-owned lock. */
@Composable internal fun FixtureAppLock(content: @Composable () -> Unit) {
    val scope=rememberCoroutineScope()
    val controller=remember {AppLockController(object : LockPersistence {
        override suspend fun read(): ByteArray?=null
        override suspend fun write(bytes: ByteArray)=Unit
    },scope,android.os.SystemClock::elapsedRealtime,{1})}
    LaunchedEffect(controller) {controller.start(); controller.initialize()}
    AppLockGate(controller,content)
}
