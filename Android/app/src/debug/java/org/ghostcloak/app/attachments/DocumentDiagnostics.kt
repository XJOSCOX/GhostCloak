package org.ghostcloak.app.attachments

/** Fixed categories only: never include a URI, filename, identity or exception message. */
internal object DocumentDiagnostics {
    const val enabled = true
    fun stage(operation: PhotoOperation, success: Boolean) {
        try { android.util.Log.d("GhostCloakDoc", "DOCUMENT_${operation.name}="+if(success) "OK" else "FAILED") } catch(_:Exception) {}
    }
    fun stageFailure(operation: PhotoOperation, exception: PhotoException) {
        stage(operation,false)
        try { android.util.Log.d("GhostCloakDoc", "DOCUMENT_EXCEPTION="+exception.name) } catch(_:Exception) {}
    }
}
