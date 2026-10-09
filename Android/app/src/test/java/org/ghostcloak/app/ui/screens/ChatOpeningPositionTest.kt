package org.ghostcloak.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatOpeningPositionTest {
    @Test fun firstUnreadOpensWithLastSeenRowAboveIt() {
        val ids=(1..50).map {"message-$it"}
        assertEquals(0,openingMessageIndex(ids,"message-1"))
        assertEquals(9,openingMessageIndex(ids,"message-11"))
        assertEquals(48,openingMessageIndex(ids,"message-50"))
    }

    @Test fun allReadOrMissingUnreadOpensAtNewestRow() {
        val ids=listOf("one","two","three")
        assertEquals(2,openingMessageIndex(ids,null))
        assertEquals(2,openingMessageIndex(ids,"expired"))
        assertEquals(0,openingMessageIndex(emptyList(),null))
    }
}
