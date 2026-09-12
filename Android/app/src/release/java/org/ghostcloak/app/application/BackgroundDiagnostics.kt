package org.ghostcloak.app.application

object BackgroundDiagnostics {
    const val ENABLED = false
    fun emit(@Suppress("UNUSED_PARAMETER") event: BackgroundEvent) = Unit
}
