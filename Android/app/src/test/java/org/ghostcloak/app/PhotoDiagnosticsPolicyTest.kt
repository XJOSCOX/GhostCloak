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
        if(!BuildConfig.DEBUG) for(reason in PhotoFailureReason.entries) PhotoDiagnostics.failed(reason)
    }
}
