package org.ghostcloak.app.ui.theme

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppearanceMode(val label: String) {
    AUTOMATIC("Automatic"), LIGHT("Light"), DARK("Dark");
    fun isDark(systemDark: Boolean) = when(this) { AUTOMATIC -> systemDark; LIGHT -> false; DARK -> true }
}

/** Non-sensitive UI preference only; never stores accounts, keys, drafts or message data. */
class AppearanceStore(context: Context, preferenceFile: String = "appearance") {
    private val preferences = context.applicationContext.getSharedPreferences(preferenceFile, Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(AppearanceMode.entries.firstOrNull { it.name == preferences.getString("mode", null) } ?: AppearanceMode.AUTOMATIC)
    val mode = mutable.asStateFlow()
    fun select(value: AppearanceMode) {
        preferences.edit().putString("mode", value.name).apply()
        mutable.value = value
    }
}

data class AppearanceControl(val mode: AppearanceMode = AppearanceMode.AUTOMATIC, val select: (AppearanceMode) -> Unit = {})
val LocalAppearance = staticCompositionLocalOf { AppearanceControl() }
