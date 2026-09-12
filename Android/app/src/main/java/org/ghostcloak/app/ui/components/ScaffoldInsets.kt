package org.ghostcloak.app.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier

/** Descendant IME padding must exclude the system-bar space already applied by Scaffold. */
fun Modifier.scaffoldContentInsets(padding: PaddingValues): Modifier =
    padding(padding).consumeWindowInsets(padding)
