package org.ghostcloak.app.attachments

internal object PhotoDiagnostics {
    const val enabled = false
    fun emit(event: PhotoEvent) = Unit
    fun failed(reason: PhotoFailureReason) = Unit
    fun stage(operation: PhotoOperation, success: Boolean) = Unit
    fun stageFailure(operation: PhotoOperation, exception: PhotoException) = Unit
}
