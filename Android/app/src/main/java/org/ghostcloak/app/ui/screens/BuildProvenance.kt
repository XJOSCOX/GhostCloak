package org.ghostcloak.app.ui.screens

/** Only public, checkout-derived values are exposed in Settings. */
internal object BuildProvenance {
    fun shortSha(fullSha: String): String {
        require(fullSha.matches(Regex("[0-9a-fA-F]{40}")))
        return fullSha.take(12)
    }

    fun status(dirty: Boolean): String =
        if (dirty) "Modified development build" else "Clean source build"
}
