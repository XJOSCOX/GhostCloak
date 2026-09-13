package org.ghostcloak.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import org.ghostcloak.app.application.GhostApplication
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PickerLaunchTest {
    @get:Rule val compose = createAndroidComposeRule<PickerRegressionActivity>()

    private fun exercise(label: String, recreate: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val actions = mutableListOf<String?>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action == Intent.ACTION_OPEN_DOCUMENT ||
                    intent.action?.contains("PICK_IMAGES") == true) {
                    actions.add(intent.action)
                    return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                }
                return null
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            if (recreate) compose.activityRule.scenario.recreate()
            repeat(3) {
                compose.onNodeWithContentDescription("Attach photo or document").performClick()
                compose.onNodeWithText(label).performClick()
                compose.waitForIdle()
                compose.activityRule.scenario.onActivity { activity ->
                    assertTrue("Registry must exercise a high-bit request code", activity.lastRequestCode!! > 65535)
                }
                if (recreate) compose.activityRule.scenario.recreate()
                else {
                    compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
                    compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
                }
            }
            assertEquals(3, actions.size)
            if (label == "Document") assertTrue(actions.all { it == Intent.ACTION_OPEN_DOCUMENT })
            if (label == "Photo" && !androidx.activity.result.contract.ActivityResultContracts
                    .PickVisualMedia.isPhotoPickerAvailable(instrumentation.targetContext)) {
                assertTrue("Photo fallback must use SAF", actions.all { it == Intent.ACTION_OPEN_DOCUMENT })
            }
            // A cancelled result must never enter attachment preparation.
            compose.onNodeWithContentDescription("Attach photo or document").assertExists()
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test fun photoOpenCancelRepeatAndBackground() = exercise("Photo", false)
    @Test fun documentOpenCancelRepeatAndBackground() = exercise("Document", false)
    @Test fun photoRegistrationSurvivesRecreation() = exercise("Photo", true)
    @Test fun documentRegistrationSurvivesRecreation() = exercise("Document", true)

    private fun pendingResult(label: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var launches = 0
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action == Intent.ACTION_OPEN_DOCUMENT || intent.action?.contains("PICK_IMAGES") == true) {
                    launches++
                    return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                }
                return null
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            compose.activityRule.scenario.onActivity { it.deferResult = true; it.launchPending(label) }
            instrumentation.waitForIdleSync()
            // Keep the result outstanding while the host is stopped and recreated.
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            compose.activityRule.scenario.recreate()
            compose.activityRule.scenario.onActivity { it.deliverDeferredResult() }
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitForIdle()
            assertEquals(1, launches)
            compose.activityRule.scenario.onActivity { assertEquals(1, it.cancellations) }
            val media = (instrumentation.targetContext.applicationContext as GhostApplication).media
            assertEquals("", media.state.value.conversation)
            compose.onNodeWithContentDescription("Attach photo or document").assertExists()
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test fun photoPendingCancellationSurvivesStoppedHostRecreation() = pendingResult("Photo")
    @Test fun documentPendingCancellationSurvivesStoppedHostRecreation() = pendingResult("Document")
}
