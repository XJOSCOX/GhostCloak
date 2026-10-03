package org.ghostcloak.app.developer

import android.content.Context
import org.ghostcloak.app.application.DemoSession

/** No synthetic identities or simulator implementation in the release source set. */
object DeveloperMode {
    const val available = false
    fun quiesce() = Unit
    suspend fun create(context: Context): DemoSession? = null
}
