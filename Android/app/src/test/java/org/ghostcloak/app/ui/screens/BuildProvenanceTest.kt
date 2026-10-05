package org.ghostcloak.app.ui.screens

import org.ghostcloak.app.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildProvenanceTest {
    @Test fun embeddedCommitIsFullHexSha() {
        assertTrue(BuildConfig.GIT_SHA.matches(Regex("[0-9a-fA-F]{40}")))
        assertEquals(BuildConfig.GIT_SHA.take(12), BuildProvenance.shortSha(BuildConfig.GIT_SHA))
    }

    @Test fun statusDoesNotExposeMachineOrAccountDetails() {
        assertEquals("Clean reviewed build", BuildProvenance.status(false))
        assertEquals("Modified source build", BuildProvenance.status(true))
        assertFalse(BuildProvenance.status(true).contains('\\'))
        assertFalse(BuildProvenance.status(true).contains('/'))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidCommitCannotBeShownAsReviewed() {
        BuildProvenance.shortSha("unknown")
    }
}
