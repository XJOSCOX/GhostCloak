package org.ghostcloak.app

import android.content.ClipboardManager
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.ghostcloak.app.access.PinInput
import org.ghostcloak.app.ui.privacy.SensitiveClipboardProvider
import org.ghostcloak.app.ui.privacy.copySensitive
import org.ghostcloak.app.ui.privacy.noSensitiveCopyCut
import org.ghostcloak.app.ui.theme.GhostCloakTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SensitiveTextSelectionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun modernSelectionCannotCopyOrCutAndRepeatedToolbarOpensDoNotCrash() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        copySensitive(context,"clipboard sentinel")
        var value by mutableStateOf("private draft")
        compose.setContent { GhostCloakTheme { SensitiveClipboardProvider {
            OutlinedTextField(value,{ value=it },modifier=Modifier.testTag("draft").noSensitiveCopyCut())
        } } }
        repeat(6) {
            compose.onNodeWithTag("draft").performClick()
            compose.onNodeWithTag("draft").performTouchInput { longClick() }
            compose.onNodeWithText("Copy").assertDoesNotExist()
            compose.onNodeWithText("Cut").assertDoesNotExist()
        }
        compose.onNodeWithTag("draft").performSemanticsAction(SemanticsActions.CopyText) { assertFalse(it()) }
        compose.onNodeWithTag("draft").performSemanticsAction(SemanticsActions.CutText) { assertFalse(it()) }
        compose.runOnIdle {
            assertEquals("private draft", value)
            assertEquals("clipboard sentinel", clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
    }

    @Test fun pinCopyAndCutAreUnavailable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        copySensitive(context,"clipboard sentinel")
        var pin by mutableStateOf("123456")
        compose.setContent { GhostCloakTheme { PinInput(pin,"PIN",true,Modifier.testTag("pin")) { pin=it } } }
        compose.onNodeWithTag("pin").performTouchInput { longClick() }
        compose.onNodeWithText("Copy").assertDoesNotExist()
        compose.onNodeWithText("Cut").assertDoesNotExist()
        compose.onNodeWithTag("pin").performSemanticsAction(SemanticsActions.CopyText) { assertFalse(it()) }
        compose.onNodeWithTag("pin").performSemanticsAction(SemanticsActions.CutText) { assertFalse(it()) }
        compose.runOnIdle {
            assertEquals("123456",pin)
            assertEquals("clipboard sentinel",clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
    }
}
