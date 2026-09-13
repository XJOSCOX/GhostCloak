package org.ghostcloak.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.ghostcloak.app.attachments.MediaUi
import org.ghostcloak.app.ui.screens.PreparationAction
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PhotoPreparationActionTest {
    @get:Rule val compose=createComposeRule()
    @Test fun failedPreparationOnlyOffersReselectionAndNeverSends() {
        var choices=0;var sends=0
        compose.setContent { MaterialTheme { PreparationAction(MediaUi(photo=true,error="Couldn't safely decode this photo."),true,{choices++},{sends++}) } }
        compose.onNodeWithText("Retry upload").assertDoesNotExist()
        compose.onNodeWithText("Send").assertDoesNotExist()
        compose.onNodeWithText("Choose another photo").performClick()
        assertEquals(1,choices);assertEquals(0,sends)
    }
    @Test fun encryptedUploadFailureHasWorkingRetry() {
        var sends=0
        compose.setContent { MaterialTheme { PreparationAction(MediaUi(photo=true,error="Upload failed",uploadPrepared=true),true,{fail()},{sends++}) } }
        compose.onNodeWithText("Retry upload").performClick()
        assertEquals(1,sends)
    }
    @Test fun preparingCannotSendBeforeNormalizationCompletes() {
        compose.setContent { MaterialTheme { PreparationAction(MediaUi(photo=true,busy=true),true,{fail()},{fail()}) } }
        compose.onNodeWithText("Send").assertIsNotEnabled()
    }
}
