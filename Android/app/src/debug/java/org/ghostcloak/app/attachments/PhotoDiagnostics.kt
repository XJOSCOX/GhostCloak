package org.ghostcloak.app.attachments

internal object PhotoDiagnostics {
    const val enabled = true
    fun emit(event: PhotoEvent) { try { android.util.Log.d("GhostCloakPhoto",event.text) } catch(_:Exception) {} }
    fun failed(reason: PhotoFailureReason) { try { android.util.Log.d("GhostCloakPhoto","PHOTO_PREP_FAILED="+reason.name) } catch(_:Exception) {} }
    fun stage(operation: PhotoOperation, success: Boolean) {
        try {
            val value=if(operation==PhotoOperation.BOUNDS_VALID) { if(success) "YES" else "NO" } else if(success) "OK" else "FAILED"
            android.util.Log.d("GhostCloakPhoto","PHOTO_${operation.name}=$value")
        } catch(_:Exception) {}
    }
    fun stageFailure(operation: PhotoOperation, exception: PhotoException) {
        stage(operation,false)
        try {
            android.util.Log.d("GhostCloakPhoto","PHOTO_NORMALIZE_FAILED="+if(exception==PhotoException.OOM) "OOM" else operation.failure)
            android.util.Log.d("GhostCloakPhoto","PHOTO_NORMALIZE_EXCEPTION="+exception.name)
        } catch(_:Exception) {}
    }}
