package org.ghostcloak.testing

import org.ghostcloak.protocol.GhostCloakContactQr
import org.ghostcloak.protocol.GhostCloakIds
import org.junit.Assert.*
import org.junit.Test

class ContactQrPayloadTest {
    @Test fun canonicalRoundTripAndManualEntryUseSameNormalizer() {
        val manual = GhostCloakIds.normalize("7k4m-9q2f-x8dr")
        val payload = GhostCloakContactQr.encode(manual)
        assertEquals("ghostcloak://contact/v1/7K4M9Q2FX8DR", payload)
        assertEquals(manual, GhostCloakContactQr.decode(payload))
        assertFalse(payload.contains("Alice"))
    }

    @Test fun strictParserRejectsUntrustedContent() {
        val good = GhostCloakContactQr.encode("7K4M9Q2FX8DR")
        val bad = listOf("https://example.com/$good", "GHOSTCLOAK://contact/v1/7K4M9Q2FX8DR",
            good.replace("/v1/", "/v2/"), good.replace("7K4M", "0K4M"), good.lowercase(),
            good + "?x=1", good + "#x", good + "/../", good + "\n" + good,
            good.replace("contact", "person@contact"), good.replace("7K4M", "７K4M"),
            "x".repeat(10_000), " $good", good + "/")
        bad.forEach { assertNull(it.take(80), GhostCloakContactQr.decode(it)) }
    }

    @Test fun sameDisplayNameDoesNotAffectDistinctQrIdentities() {
        val a = GhostCloakContactQr.encode("7K4M9Q2FX8DR")
        val b = GhostCloakContactQr.encode("2AB3-4CD5-6EF7")
        assertNotEquals(a, b)
        assertEquals("2AB34CD56EF7", GhostCloakContactQr.decode(b))
    }
}
