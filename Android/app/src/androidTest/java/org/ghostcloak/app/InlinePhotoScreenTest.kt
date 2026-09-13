package org.ghostcloak.app

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.ghostcloak.app.attachments.*
import org.ghostcloak.app.ui.screens.InlinePhotoContent
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class InlinePhotoScreenTest {
    @get:Rule val compose=createComposeRule()
    @Test fun readyPhotoIsInlineAndClickableWhileLockedContentIsGeneric() {
        val enabled=mutableStateOf(true)
        val image=Bitmap.createBitmap(8,4,Bitmap.Config.ARGB_8888)
        var opened=0
        compose.setContent { MaterialTheme {
            InlinePhotoContent(InlinePhoto(PhotoStage.READY,image),enabled.value,{}, {opened++})
        } }
        compose.onNodeWithText("Download").assertDoesNotExist()
        compose.onNodeWithContentDescription("Photo").assertIsDisplayed().performClick()
        assertEquals(1,opened)
        val bounds=compose.onNodeWithContentDescription("Photo").fetchSemanticsNode().boundsInRoot
        assertEquals(2f,bounds.width/bounds.height,0.05f)
        compose.runOnIdle { enabled.value=false }
        compose.onNodeWithContentDescription("Photo").assertDoesNotExist()
        compose.onNodeWithText("Attachment").assertIsDisplayed()
    }
    @Test fun failedPhotoHasLocalRetryAndNoDownloadOrConnectAction() {
        var retried=0
        compose.setContent { MaterialTheme {
            InlinePhotoContent(InlinePhoto(PhotoStage.FAILED),true,{retried++},{})
        } }
        compose.onNodeWithText("Photo unavailable · Retry").performClick()
        assertEquals(1,retried)
        compose.onNodeWithText("Download").assertDoesNotExist()
        compose.onNodeWithText("Connect").assertDoesNotExist()
        compose.onNodeWithContentDescription("Photo").assertDoesNotExist()
    }
    @Test fun installedAppDoesNotRequestPublicStorageOrBroadMediaAccess() {
        val context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val permissions=context.packageManager.getPackageInfo(context.packageName,
            android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertFalse(permissions.any { it.contains("EXTERNAL_STORAGE") || it.contains("READ_MEDIA_") || it.contains("MANAGE_EXTERNAL") })
    }
}
