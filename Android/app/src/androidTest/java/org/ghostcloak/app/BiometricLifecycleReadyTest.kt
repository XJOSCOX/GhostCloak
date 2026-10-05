package org.ghostcloak.app

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.ghostcloak.app.access.biometricLifecycleReady
import org.junit.Rule
import org.junit.Test

class BiometricLifecycleReadyTest {
    @get:Rule val compose = createComposeRule()

    @Test fun readinessTracksResumeAndStopWithoutAnotherUiStateChange() {
        lateinit var registry: LifecycleRegistry
        val owner = object : LifecycleOwner {
            override val lifecycle: Lifecycle get() = registry
        }
        registry = LifecycleRegistry(owner)
        compose.setContent { Text(if (biometricLifecycleReady(registry)) "Host ready" else "Host not ready") }
        compose.onNodeWithText("Host not ready").assertIsDisplayed()

        compose.runOnIdle {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        compose.onNodeWithText("Host ready").assertIsDisplayed()

        compose.runOnIdle {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
        compose.onNodeWithText("Host not ready").assertIsDisplayed()
    }
}
