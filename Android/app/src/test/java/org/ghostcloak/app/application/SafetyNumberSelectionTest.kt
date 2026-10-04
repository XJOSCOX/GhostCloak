package org.ghostcloak.app.application

import org.junit.Assert.*
import org.junit.Test

class SafetyNumberSelectionTest {
    @Test fun rapidThreeContactNavigationNeverPublishesAnEarlierResult() {
        val selection = SafetyNumberSelection()
        val (b1, bLoading) = selection.begin("device-b", "contact-b", false)
        assertEquals("", bLoading.visibleFor("device-b", "contact-b", false))
        val (c1, cLoading) = selection.begin("device-c", "contact-c", false)
        assertEquals("", cLoading.visibleFor("device-c", "contact-c", false))
        assertEquals("", bLoading.copy(fingerprint = "B NUMBER").visibleFor("device-c", "contact-c", false))
        assertNull(selection.complete(b1, bLoading.copy(fingerprint = "B NUMBER")))
        val (b2, bAgain) = selection.begin("device-b", "contact-b", false)
        val (c2, cAgain) = selection.begin("device-c", "contact-c", false)
        assertNull(selection.complete(c1, cLoading.copy(fingerprint = "C OLD")))
        assertNull(selection.complete(b2, bAgain.copy(fingerprint = "B LATE")))
        assertEquals("C NUMBER", selection.complete(c2, cAgain.copy(fingerprint = "C NUMBER"))?.fingerprint)
        assertFalse(selection.approves("device-b", "contact-b", false, "B NUMBER"))
        assertFalse(selection.approves("device-c", "contact-c", false, "B NUMBER"))
        assertTrue(selection.approves("device-c", "contact-c", false, "C NUMBER"))
        assertTrue(selection.leave("device-c"))
        assertNull(selection.complete(c2, cAgain.copy(fingerprint = "C NUMBER")))
        assertFalse(selection.approves("device-c", "contact-c", false, "C NUMBER"))
    }

    @Test fun replacementAndContactRecordChangesInvalidateApproval() {
        val selection = SafetyNumberSelection()
        val (old, loading) = selection.begin("device-c", "contact-old", false)
        selection.begin("device-c", "contact-new", true)
        assertNull(selection.complete(old, loading.copy(fingerprint = "OLD")))
        assertFalse(selection.approves("device-c", "contact-new", true, "OLD"))
        val (pending, current) = selection.begin("device-c", "contact-new", true)
        selection.complete(pending, current.copy(fingerprint = "NEW"))
        assertFalse(selection.approves("device-c", "contact-new", false, "NEW"))
        assertFalse(selection.approves("device-c", "contact-old", true, "NEW"))
        assertTrue(selection.approves("device-c", "contact-new", true, "NEW"))
    }
}
