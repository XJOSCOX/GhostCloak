package org.ghostcloak.app.attachments

internal object PhotoDiagnostics {
    const val enabled = true
    fun emit(event: PhotoEvent) { try { android.util.Log.d("GhostCloakPhoto",event.text) } catch(_:Exception) {} }
    fun failed(reason: PhotoFailureReason) { try { android.util.Log.d("GhostCloakPhoto","PHOTO_PREP_FAILED="+reason.name) } catch(_:Exception) {} }
}
