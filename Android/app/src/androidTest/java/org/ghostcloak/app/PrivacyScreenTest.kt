package org.ghostcloak.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.ghostcloak.app.application.AppState
import org.ghostcloak.app.application.NotificationPrivacy
import org.ghostcloak.app.ui.screens.PrivacyScreen
import org.ghostcloak.app.ui.theme.GhostCloakTheme
import org.ghostcloak.messaging.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PrivacyScreenTest {
    @get:Rule val compose=createComposeRule()

    @Test fun defaultsCacheAndProfileControlsUpdateOnePrivacyPage() {
        val state=mutableStateOf(AppState(loading=false,networkConfigured=false))
        var cleared=false
        compose.setContent {GhostCloakTheme {PrivacyScreen(state.value,{},
            {state.value=state.value.copy(privacyDefaults=it)},
            {state.value=state.value.copy(requireRequestConfirmation=it)},
            {state.value=state.value.copy(ownProfile=state.value.ownProfile.copy(sharing=it))},
            {},{},{},{},
            {done -> cleared=true;done()},
            {done -> done(if(cleared) 0 else 1024)},
            {})}}
        compose.onNodeWithTag("privacy-choice-Default timer").performClick()
        compose.onNodeWithText("5 minutes").performClick()
        assertEquals(300,state.value.privacyDefaults.disappearingSeconds)
        compose.onNodeWithTag("privacy-choice-Default voice mask").performClick()
        compose.onNodeWithText("Strong").performClick()
        assertEquals(VoiceMaskPreference.STRONG,state.value.privacyDefaults.voiceMask)
        compose.onNodeWithTag("privacy-choice-Photos").performClick()
        compose.onNodeWithText("Auto-download").performClick()
        assertEquals(DownloadPreference.AUTOMATIC,state.value.privacyDefaults.photos)
        compose.onNodeWithText("Check downloaded cache size").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Downloaded cache: 1 KiB").assertExists()
        compose.onNodeWithText("Clear downloaded media cache").performScrollTo().performClick()
        compose.onNodeWithText("Clear cache").performClick()
        assertTrue(cleared)
        compose.onNodeWithText("Downloaded cache: 0 KiB").assertExists()
        compose.onNodeWithTag("profile-sharing").performScrollTo().performClick()
        assertFalse(state.value.ownProfile.sharing)
    }

    @Test fun notificationChoiceUsesExistingP8Preference() {
        var context:android.content.Context?=null
        var prior:NotificationPrivacy?=null
        try {
            compose.setContent {context=androidx.compose.ui.platform.LocalContext.current;GhostCloakTheme {PrivacyScreen(AppState(loading=false,networkConfigured=true),
                {},{},{},{},{},{},{},{},{_ ->},{_ ->},{})}}
            compose.waitForIdle()
            prior=NotificationPrivacy.read(requireNotNull(context))
            compose.onNodeWithText("Contact only").performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(NotificationPrivacy.CONTACT,NotificationPrivacy.read(requireNotNull(context)))
        } finally {context?.let {ctx -> prior?.let {NotificationPrivacy.save(ctx,it)} }}
    }
}
