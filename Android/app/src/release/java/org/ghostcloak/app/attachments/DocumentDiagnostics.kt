package org.ghostcloak.app.attachments

internal object DocumentDiagnostics {
    const val enabled = false
    fun stage(operation: PhotoOperation, success: Boolean) = Unit
    fun stageFailure(operation: PhotoOperation, exception: PhotoException) = Unit
}
