package org.ghostcloak.app.attachments

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.ghostcloak.app.ui.screens.VoiceMaskSelector
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals

class VoiceMaskSelectorTest {
    @get:Rule val compose=createComposeRule()

    @Test fun selectionStartsOriginalAndOffersAllPresets() {
        var selected by mutableStateOf(VoiceMask.OFF)
        compose.setContent { VoiceMaskSelector(selected,true) {selected=it} }
        compose.onNodeWithText("Voice mask").assertExists()
        compose.onNodeWithText("Original").assertIsSelected()
        compose.onNodeWithText("Subtle").performClick()
        compose.runOnIdle {assertEquals(VoiceMask.SUBTLE,selected)}
        compose.onNodeWithText("Subtle").assertIsSelected()
        compose.onNodeWithText("Strong").performClick()
        compose.runOnIdle {assertEquals(VoiceMask.STRONG,selected)}
        compose.onNodeWithText("Strong").assertIsSelected()
        compose.onNodeWithText("Synthetic").performClick()
        compose.runOnIdle {assertEquals(VoiceMask.SYNTHETIC,selected)}
        compose.onNodeWithText("Synthetic").assertIsSelected()
    }
}
