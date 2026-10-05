package org.ghostcloak.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.ghostcloak.app.application.AppState
import org.ghostcloak.app.ui.qr.contactQrBitmap
import org.ghostcloak.app.ui.qr.ContactQrScreen
import org.ghostcloak.app.ui.screens.AddContactScreen
import org.ghostcloak.app.ui.theme.GhostCloakTheme
import org.ghostcloak.messaging.Contact
import org.ghostcloak.messaging.ContactStatus
import org.ghostcloak.protocol.GhostCloakContactQr
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ContactQrTest {
    @get:Rule val compose = createComposeRule()
    private val id = "7K4M9Q2FX8DR"

    @Test fun secureQrPageShowsOnlyPublicIdAndVerificationGuidance() {
        compose.setContent { GhostCloakTheme { ContactQrScreen(id, {}) } }
        compose.onNodeWithContentDescription("QR code for your Ghost Cloak ID").assertIsDisplayed()
        compose.onNodeWithText("7K4M-9Q2F-X8DR").assertIsDisplayed()
        compose.onNodeWithText("A scan adds an ID only. Compare safety numbers separately to verify a person.").assertIsDisplayed()
    }

    @Test fun renderedQrDecodesToOnlyPublicVersionedId() {
        val image = contactQrBitmap(id)
        val pixels = IntArray(image.width * image.height)
        image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        val payload = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(image.width, image.height, pixels)))).text
        assertEquals(id, GhostCloakContactQr.decode(payload))
        assertEquals("ghostcloak://contact/v1/$id", payload)
    }

    @Test fun manualAndQrCanonicalIdShareLookupPathAndSelfNeverLooksUp() {
        var lookups = 0
        val state = mutableStateOf(AppState(loading=false, networkConfigured=true, ghostCloakId=id))
        compose.setContent { GhostCloakTheme { AddContactScreen(state.value, {}, {}, { lookups++ }, import={}) } }
        compose.onNodeWithText("Scan QR").assertIsDisplayed()
        compose.onNodeWithText("Ghost Cloak ID").performTextInput("7K4M-9Q2F-X8DR")
        compose.onNodeWithText("This is your Ghost Cloak ID.").assertIsDisplayed()
        compose.onNodeWithText("Find and add contact").assertDoesNotExist()
        assertEquals(0, lookups)
    }

    @Test fun existingContactOpensWithoutSecondLookup() {
        val contact = Contact("c", "account", "Alex", "device", ghostCloakId=id)
        var lookups = 0
        var opened = 0
        compose.setContent { GhostCloakTheme { AddContactScreen(
            AppState(loading=false, networkConfigured=true, contacts=listOf(ContactStatus(contact,null,null))),
            {}, {}, { lookups++ }, import={}, openExisting={ opened++ }) } }
        compose.onNodeWithText("Ghost Cloak ID").performTextInput("7K4M-9Q2F-X8DR")
        compose.onNodeWithText("This contact is already on this device.").assertIsDisplayed()
        compose.onNodeWithText("Open contact").performClick()
        assertEquals(1, opened)
        assertEquals(0, lookups)
    }

    @Test fun cameraIsNotRequestedUntilScanAction() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        // No app startup or Add Contact side effect should request camera access.
        val before=context.checkSelfPermission(Manifest.permission.CAMERA)
        compose.setContent { GhostCloakTheme { AddContactScreen(AppState(loading=false,networkConfigured=true),{}, {}, {},import={}) } }
        compose.onNodeWithText("Scan QR").assertIsDisplayed()
        assertEquals(before,context.checkSelfPermission(Manifest.permission.CAMERA))
        compose.onNodeWithText("Ghost Cloak ID").assertIsDisplayed()
    }

    @Test fun deniedCameraPermissionKeepsManualAddAvailable() {
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
                options: androidx.core.app.ActivityOptionsCompat?) {
                if (contract is androidx.activity.result.contract.ActivityResultContracts.RequestPermission)
                    dispatchResult(requestCode, false)
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                GhostCloakTheme { AddContactScreen(AppState(loading=false,networkConfigured=true),{}, {}, {},import={}) }
            }
        }
        compose.onNodeWithText("Scan QR").performClick()
        compose.onNodeWithText("Camera access is needed to scan. You can still enter an ID manually.").assertIsDisplayed()
        compose.onNodeWithText("Ghost Cloak ID").performTextInput("7K4M-9Q2F-X8DR")
        compose.onNodeWithText("Find and add contact").assertIsDisplayed()
    }

    @Test fun failedLookupDraftIsDiscardedWhenLeavingAndReturning() {
        val visible=mutableStateOf(true)
        val existing=Contact("saved", "account", "Existing", "saved-device", ghostCloakId="UTSA2G8DBEVG")
        val state=AppState(loading=false,networkConfigured=true,contacts=listOf(ContactStatus(existing,null,null)))
        var attempts=0
        compose.setContent { GhostCloakTheme { if(visible.value) AddContactScreen(state,{}, {}, { attempts++ },import={}) } }
        compose.onNodeWithText("Ghost Cloak ID").performTextInput(id)
        compose.onNodeWithText("Find and add contact").performScrollTo().performClick()
        assertEquals(1,attempts)
        compose.runOnIdle {visible.value=false}
        compose.runOnIdle {visible.value=true}
        compose.onNodeWithText("Find and add contact").assertIsNotEnabled()
        compose.onNodeWithText("Ghost Cloak ID").performTextInput("UTSA-2G8D-BEVG")
        compose.onNodeWithText("This contact is already on this device.").assertExists()
        assertEquals(1,state.contacts.size)
    }

    @Test fun idDraftIsNotRestoredFromSavedInstanceState() {
        val restoration=StateRestorationTester(compose)
        restoration.setContent { GhostCloakTheme { AddContactScreen(AppState(loading=false,networkConfigured=true),{}, {}, {},import={}) } }
        compose.onNodeWithText("Ghost Cloak ID").performTextInput(id)
        compose.onNodeWithText("Find and add contact").assertIsEnabled()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Find and add contact").assertIsNotEnabled()
        compose.onNodeWithText("Ghost Cloak ID").performTextInput(id)
        compose.onNodeWithText("Find and add contact").assertIsEnabled()
    }

    @Test fun successfulLookupCanReturnAsAnExistingContactWithoutRetainingDraft() {
        val visible=mutableStateOf(true)
        val state=mutableStateOf(AppState(loading=false,networkConfigured=true))
        var additions=0
        compose.setContent { GhostCloakTheme { if(visible.value) AddContactScreen(state.value,{}, {}, { requested ->
            additions++
            val contact=Contact("accepted", "account", "Accepted", "remote-device",ghostCloakId=requested)
            state.value=state.value.copy(contacts=listOf(ContactStatus(contact,null,null)))
        },import={}) } }
        compose.onNodeWithText("Ghost Cloak ID").performTextInput(id)
        compose.onNodeWithText("Find and add contact").performScrollTo().performClick()
        assertEquals(1,additions)
        compose.runOnIdle {visible.value=false}
        compose.runOnIdle {visible.value=true}
        compose.onNodeWithText("Find and add contact").assertIsNotEnabled()
        compose.onNodeWithText("Ghost Cloak ID").performTextInput(id)
        compose.onNodeWithText("This contact is already on this device.").assertExists()
        assertEquals(1,additions)
    }

    @Test fun scannedQrUsesTheSameEphemeralDraftAndSuccessfulLookupPath() {
        val registry=object:ActivityResultRegistry() {
            override fun <I,O> onLaunch(requestCode:Int, contract:ActivityResultContract<I,O>, input:I,
                options:androidx.core.app.ActivityOptionsCompat?) {
                when(contract) {
                    is androidx.activity.result.contract.ActivityResultContracts.RequestPermission -> dispatchResult(requestCode,true)
                    is com.journeyapps.barcodescanner.ScanContract -> dispatchResult(requestCode,Activity.RESULT_OK,
                        Intent().putExtra("SCAN_RESULT",GhostCloakContactQr.encode(id)))
                }
            }
        }
        val owner=object:ActivityResultRegistryOwner {override val activityResultRegistry=registry}
        var requested:String?=null
        val visible=mutableStateOf(true)
        compose.setContent {CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
            GhostCloakTheme {if(visible.value) AddContactScreen(AppState(loading=false,networkConfigured=true),{}, {},
                {requested=it},import={})}
        }}
        compose.onNodeWithText("Scan QR").performClick()
        compose.onNodeWithText("Find and add contact").assertIsEnabled().performClick()
        assertEquals(id,requested)
        compose.runOnIdle {visible.value=false}
        compose.runOnIdle {visible.value=true}
        compose.onNodeWithText("Find and add contact").assertIsNotEnabled()
    }
}
