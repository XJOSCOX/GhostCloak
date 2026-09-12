package org.ghostcloak.app

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.ghostcloak.app.ui.components.scaffoldContentInsets
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class KeyboardInsetsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun keyboardAndSystemBarInsetsAreNotAddedTwice() {
        val navigation = mutableStateOf(48.dp)
        val keyboard = mutableStateOf(300.dp)
        compose.setContent {
            Box(Modifier.requiredSize(320.dp, 600.dp).testTag("window")) {
                Box(Modifier.fillMaxSize().scaffoldContentInsets(PaddingValues(bottom = navigation.value))) {
                    // Fixed insets model the final IME geometry, independent of keyboard vendor/animation.
                    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets(bottom = keyboard.value))) {
                        Box(Modifier.fillMaxSize().testTag("composer-area"))
                    }
                }
            }
        }
        for (bar in listOf(0.dp, 24.dp, 48.dp)) {
            compose.runOnIdle { navigation.value = bar; keyboard.value = 300.dp }
            val window = compose.onNodeWithTag("window").getUnclippedBoundsInRoot()
            assertEquals(window.bottom - 300.dp, compose.onNodeWithTag("composer-area").getUnclippedBoundsInRoot().bottom)
            compose.runOnIdle { keyboard.value = 0.dp }
            assertEquals(window.bottom - bar, compose.onNodeWithTag("composer-area").getUnclippedBoundsInRoot().bottom)
        }
    }
}
