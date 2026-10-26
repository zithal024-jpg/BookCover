package com.bookcover.diagnostic.cover

import org.junit.Assert.assertEquals
import org.junit.Test

class MatchingCoverColorTest {
    @Test fun dominantEdgeColorWinsInsteadOfMixingUnrelatedArtColors() {
        val teal = 0xFF24645A.toInt()
        assertEquals(teal, matchingCoverColor(intArrayOf(teal, teal, teal, 0xFFFFFFFF.toInt(), 0xFFE02030.toInt())))
    }

    @Test fun transparentBordersDoNotOverrideVisibleColorAndHaveANeutralFallback() {
        val visible = 0xFF593523.toInt()
        assertEquals(visible, matchingCoverColor(intArrayOf(0x00FFFFFF, 0x00000000, visible)))
        assertEquals(0xFF121212.toInt(), matchingCoverColor(intArrayOf(0x00FFFFFF, 0x10000000)))
    }

    @Test fun winningColorFamilyRetainsItsMeasuredShadeRatherThanAQuantizedBlockColor() {
        assertEquals(0xFF233446.toInt(), matchingCoverColor(intArrayOf(0xFF223344.toInt(), 0xFF243648.toInt())))
    }
}
