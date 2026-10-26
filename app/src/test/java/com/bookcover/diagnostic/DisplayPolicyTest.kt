package com.bookcover.diagnostic

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayPolicyTest {
    @Test fun primaryPrivateAndInactiveDisplaysAreNeverOffered() {
        val targets = listOf(
            DisplayCandidate(0, "Default", true, false, true),
            DisplayCandidate(2, "Host", true, false, true),
            DisplayCandidate(3, "Off cover", true, false, false),
            DisplayCandidate(4, "Private", true, true, true),
            DisplayCandidate(5, "Not presentation", false, false, true),
            DisplayCandidate(6, "Eligible secondary", true, false, true),
        )
        assertEquals(listOf(6), eligiblePresentationTargets(targets, 2).map { it.id })
    }

    @Test fun aListedButInactiveOuterDisplayIsNotTreatedAsUsable() {
        assertEquals(emptyList<DisplayCandidate>(), eligiblePresentationTargets(
            listOf(DisplayCandidate(1, "Outer", true, false, false)), 0,
        ))
    }
}
