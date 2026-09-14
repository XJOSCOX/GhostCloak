package org.ghostcloak.app.attachments

/** Closed vocabulary: callers cannot supply source/provider strings. */
internal enum class PhotoOperation(val failure: String) {
    ACCESS_CHECK("OPEN"), ELIGIBILITY_CHECK("OPEN"), INPUT_OPEN("OPEN"), INPUT_COPY("IO"),
    BOUNDS_READ("BOUNDS"), BOUNDS_VALID("BOUNDS"), SAMPLE_CALC("SAMPLE"),
    BITMAP_DECODE("DECODE"), ORIENTATION_READ("ORIENTATION"), ORIENTATION_APPLY("ORIENTATION"),
    COLOR_CONVERT("COLOR"), BITMAP_CREATE("BITMAP"), ENCODE("ENCODE"),
    OUTPUT_OPEN("IO"), OUTPUT_FLUSH("IO"), OUTPUT_REOPEN("IO"), OUTPUT_VALIDATE("VALIDATE")
}
internal enum class PhotoException { ARGUMENT, IO, SECURITY, OOM, RUNTIME, UNKNOWN }
internal fun photoException(failure: Throwable) = when(failure) {
    is OutOfMemoryError -> PhotoException.OOM
    is SecurityException -> PhotoException.SECURITY
    is java.io.IOException -> PhotoException.IO
    is IllegalArgumentException -> PhotoException.ARGUMENT
    is RuntimeException -> PhotoException.RUNTIME
    else -> PhotoException.UNKNOWN
}
internal class PhotoTrace(private val document: Boolean = false) {
    private var reported = false
    private var current = PhotoOperation.ACCESS_CHECK
    fun begin(operation: PhotoOperation) { current=operation }
    fun ok(operation: PhotoOperation = current) {
        if(document) DocumentDiagnostics.stage(operation,true) else PhotoDiagnostics.stage(operation,true)
    }
    inline fun <T> step(operation: PhotoOperation, block: () -> T): T {
        begin(operation)
        return block().also { ok(operation) }
    }
    fun failed(failure: Throwable) {
        if(!reported && failure !is java.util.concurrent.CancellationException) {
            reported=true
            if(document) DocumentDiagnostics.stageFailure(current,photoException(failure))
            else PhotoDiagnostics.stageFailure(current,photoException(failure))
        }
    }
}
