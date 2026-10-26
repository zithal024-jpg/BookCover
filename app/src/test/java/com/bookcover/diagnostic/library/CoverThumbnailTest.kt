package com.bookcover.diagnostic.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverThumbnailTest {
    @Test fun aFullCoverShrinksToFitAndKeepsItsShape() {
        assertEquals(400 to 593, CoverThumbnail.fit(1080, 1600))
    }

    @Test fun aWideImageIsLimitedByWidth() {
        assertEquals(400 to 200, CoverThumbnail.fit(2000, 1000))
    }

    @Test fun aSmallImageIsNotEnlarged() {
        assertEquals(40 to 60, CoverThumbnail.fit(40, 60))
    }

    @Test fun anExtremeShapeStillHasAtLeastOnePixel() {
        assertEquals(1 to 600, CoverThumbnail.fit(1, 10_000))
        assertEquals(400 to 1, CoverThumbnail.fit(10_000, 1))
    }

    @Test fun decodingSkipsPixelsButNeverGoesBelowTheThumbnailSize() {
        assertEquals(2, CoverThumbnail.sampleSize(1080, 1600))
        assertEquals(1, CoverThumbnail.sampleSize(720, 1080))
        assertEquals(1, CoverThumbnail.sampleSize(40, 60))
        for ((width, height) in listOf(1080 to 1600, 4000 to 6000, 3000 to 1000, 401 to 601, 1 to 10_000)) {
            val sample = CoverThumbnail.sampleSize(width, height)
            val (fitWidth, fitHeight) = CoverThumbnail.fit(width, height)
            assertTrue("$width×$height decoded at 1/$sample is still at least the thumbnail size",
                width / sample >= fitWidth && height / sample >= fitHeight)
            assertTrue("The step is a power of two", sample > 0 && sample and (sample - 1) == 0)
        }
    }
}
