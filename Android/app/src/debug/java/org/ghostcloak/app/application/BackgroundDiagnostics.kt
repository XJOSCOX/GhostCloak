package org.ghostcloak.app.application

object BackgroundDiagnostics {
    const val ENABLED = true
    internal var sink: (String) -> Unit = { android.util.Log.d("GhostCloakBg", it); Unit }
    fun emit(event: BackgroundEvent) { try { sink(event.name) } catch (_: Exception) { } }
}
