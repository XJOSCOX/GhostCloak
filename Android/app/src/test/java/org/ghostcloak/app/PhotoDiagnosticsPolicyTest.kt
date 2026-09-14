package org.ghostcloak.app

import org.ghostcloak.app.attachments.*
import org.junit.Assert.*
import org.junit.Test

class PhotoDiagnosticsPolicyTest {
    @Test fun releaseCannotEmitAndDiagnosticsHaveClosedVocabulary() {
        assertEquals(BuildConfig.DEBUG,PhotoDiagnostics.enabled)
        for(event in PhotoEvent.entries) {
            assertTrue(event.text.matches(Regex("[A-Z_]+(=[A-Z_]+)?")))
            if(!BuildConfig.DEBUG) PhotoDiagnostics.emit(event)
        }
        if(!BuildConfig.DEBUG) for(operation in PhotoOperation.entries) {
            PhotoDiagnostics.stage(operation,true);PhotoDiagnostics.stage(operation,false)
            for(exception in PhotoException.entries) PhotoDiagnostics.stageFailure(operation,exception)
        }
        if(!BuildConfig.DEBUG) for(reason in PhotoFailureReason.entries) PhotoDiagnostics.failed(reason)
    }
    @Test fun exceptionsMapByTypeWithoutInspectingPrivateMessages() {
        assertEquals(PhotoException.ARGUMENT,photoException(IllegalArgumentException("private provider text")))
        assertEquals(PhotoException.IO,photoException(java.io.IOException("private path")))
        assertEquals(PhotoException.SECURITY,photoException(SecurityException("private URI")))
        assertEquals(PhotoException.OOM,photoException(OutOfMemoryError()))
        assertEquals(PhotoException.RUNTIME,photoException(IllegalStateException()))
        assertEquals(PhotoException.UNKNOWN,photoException(Exception()))
    }}
