package org.ghostcloak.app.attachments

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class VoiceMaskDspTest {
    private val fixture=ShortArray(16_000 * 3) { i ->
        // Harmonic, amplitude-modulated synthetic speech-like signal; no human recording.
        val envelope=0.5 + 0.5 * sin(2 * PI * 3 * i / 16_000)
        (envelope * (7000 * sin(2 * PI * 130 * i / 16_000) +
            3500 * sin(2 * PI * 260 * i / 16_000))).toInt().toShort()
    }

    @Test fun presetChangesRemainBoundedAndOriginalIsUnchanged() {
        val original=fixture.copyOf()
        assertArrayEquals(original,VoiceMasking.process(fixture,VoiceMask.OFF))
        val subtle=VoiceMasking.process(fixture,VoiceMask.SUBTLE)
        val strong=VoiceMasking.process(fixture,VoiceMask.STRONG)
        val synthetic=VoiceMasking.process(fixture,VoiceMask.SYNTHETIC)
        assertArrayEquals(original,fixture)
        listOf(subtle,strong,synthetic).forEach { assertEquals(fixture.size,it.size) }
        fun difference(other:ShortArray)=fixture.indices.sumOf { abs(fixture[it].toInt()-other[it].toInt()).toLong() }
        assertTrue(difference(subtle)>0)
        assertTrue(difference(strong)>0)
        assertFalse(strong.contentEquals(subtle))
        assertTrue(difference(synthetic)>0)
    }
}
