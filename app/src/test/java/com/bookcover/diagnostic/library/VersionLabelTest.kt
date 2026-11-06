package com.bookcover.diagnostic.library

import org.junit.Assert.assertEquals
import org.junit.Test

class VersionLabelTest {
    @Test fun aboutShowsTheInstalledVersionName() {
        assertEquals("BookCover 1.0.0", versionLabel("1.0.0"))
        assertEquals("BookCover 0.4.0-prototype-qa", versionLabel(" 0.4.0-prototype-qa "))
    }

    @Test fun missingVersionStillNamesTheApp() {
        assertEquals("BookCover", versionLabel(null))
        assertEquals("BookCover", versionLabel("  "))
    }
}
