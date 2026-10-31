package com.bookcover.diagnostic.reader

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot

class PageCurlGeometryTest {
    private val width = 1800f
    private val height = 2000f
    private val density = 2.625f
    private val radius = PageCurlGeometry.radiusFor(width, density)
    private val lift = radius * 0.25f
    private val steps = (0..100).map { it / 100f }

    private fun mesh(progress: Float, sign: Int): PageCurlMesh {
        val m = PageCurlMesh() // Not apply {}: inside it, `radius` would be the mesh's own property.
        m.update(progress, width, height, radius, sign, lift = lift)
        return m
    }

    private fun xs(mesh: PageCurlMesh) = (0 until mesh.vertexCount).map { mesh.vertices[2 * it] }
    private fun ys(mesh: PageCurlMesh) = (0 until mesh.vertexCount).map { mesh.vertices[2 * it + 1] }

    @Test fun radiusIsASlightFoldBetween60And100Dp() {
        assertTrue(radius in 60f * density..100f * density)
        assertEquals(60f * density, PageCurlGeometry.radiusFor(300f, density), 0.001f)
        assertEquals(100f * density, PageCurlGeometry.radiusFor(10_000f, density), 0.001f)
    }

    @Test fun progressZeroIsTheFlatPage() {
        for (sign in listOf(-1, 1)) {
            val flat = mesh(0f, sign)
            for (i in 0 until flat.vertexCount * 2) assertEquals("vertex $i", flat.textures[i], flat.vertices[i], 0.001f)
            assertTrue(flat.shades.all { it == 1f })
            assertFalse(flat.backVisible)
            assertEquals(width, flat.reach, 0.001f)
        }
    }

    @Test fun progressOneIsFullyOffThePage() {
        assertTrue("Peeling left, everything ends left of the page", xs(mesh(1f, -1)).all { it <= 0.01f })
        assertTrue("Peeling right (RTL forward), everything ends right of it", xs(mesh(1f, 1)).all { it >= width - 0.01f })
        assertEquals(-radius, PageCurlGeometry.foldLine(1f, width, radius), 0.5f)
    }

    @Test fun everyVertexStaysWithinThePagePlusTheFoldRadius() {
        for (sign in listOf(-1, 1)) for (p in steps) {
            val m = mesh(p, sign)
            xs(m).forEach { assertTrue("p=$p sign=$sign x=$it", it >= -width - radius && it <= 2 * width + radius) }
            if (sign < 0) xs(m).forEach { assertTrue("p=$p x=$it never right of the page", it <= width + 0.01f) }
            else xs(m).forEach { assertTrue("p=$p x=$it never left of the page", it >= -0.01f) }
            ys(m).forEach { assertTrue("p=$p y=$it", it >= -radius && it <= height + radius) }
            for (i in 0 until m.vertexCount) {
                assertTrue(m.textures[2 * i] in -0.01f..width + 0.01f)
                assertTrue(m.textures[2 * i + 1] in -0.01f..height + 0.01f)
                assertTrue(m.shades[i] in (1f - 2 * PageCurlGeometry.SHADE_DEPTH)..1f)
            }
        }
    }

    @Test fun eachPointOfThePageOnlyMovesOneWayAsTheTurnProgresses() {
        val points = (0..60).map { width * it / 60f }
        var previousFold = Float.MAX_VALUE
        var previousReach = Float.MAX_VALUE
        val previousX = FloatArray(points.size) { Float.MAX_VALUE }
        for (p in steps) {
            val fold = PageCurlGeometry.foldLine(p, width, radius)
            assertTrue("fold line moves steadily left", fold <= previousFold + 0.01f)
            previousFold = fold
            points.forEachIndexed { i, u ->
                val x = PageCurlGeometry.pointX(u, fold, radius)
                assertTrue("u=$u p=$p", x <= previousX[i] + 0.01f)
                previousX[i] = x
            }
            val reach = mesh(p, -1).reach
            assertTrue("the revealed page only grows (p=$p)", reach <= previousReach + 0.01f)
            previousReach = reach
        }
    }

    @Test fun bothDirectionsAreMirrorImages() {
        for (p in listOf(0.05f, 0.3f, 0.5f, 0.8f)) {
            val left = mesh(p, -1)
            val right = mesh(p, 1)
            for (i in 0 until left.vertexCount) {
                assertEquals(width - left.vertices[2 * i], right.vertices[2 * i], 0.01f)
                assertEquals(left.vertices[2 * i + 1], right.vertices[2 * i + 1], 0.01f)
                assertEquals(width - left.textures[2 * i], right.textures[2 * i], 0.01f)
            }
        }
    }

    @Test fun paperNeverStretches() {
        for (p in steps) {
            val m = mesh(p, -1)
            val columns = m.columns + 1
            for (row in 0..m.rows step 5) for (c in 0 until m.columns) {
                val a = row * columns + c
                val b = a + 1
                val screen = abs(m.vertices[2 * b] - m.vertices[2 * a])
                val paper = abs(m.textures[2 * b] - m.textures[2 * a])
                assertTrue("p=$p column $c: $screen > $paper", screen <= paper + 0.01f)
            }
        }
    }

    @Test fun theLiftedEdgeStaysUnderTheFinger() {
        for (along in listOf(10f, 100f, 400f, 700f, 1200f, 1800f)) {
            val progress = PageCurlGeometry.progressForDrag(along, width, radius)
            val fold = PageCurlGeometry.foldLine(progress, width, radius)
            assertEquals("finger $along", width - along, PageCurlGeometry.pointX(width, fold, radius), 0.5f)
        }
        assertEquals(0f, PageCurlGeometry.progressForDrag(-40f, width, radius), 0f)
        assertEquals(1f, PageCurlGeometry.progressForDrag(10 * width, width, radius), 0f)
    }

    @Test fun theCrestAndBackAreWhereTheGeometrySaysAndLighterShadingReturns() {
        val m = mesh(0.4f, -1)
        assertTrue(m.backVisible)
        val crestX = m.vertices[2 * (m.frontColumns)]
        assertEquals("a column sits on the crest", m.fold + radius, crestX, 0.5f)
        assertEquals(m.fold + radius, m.reach, 0.01f)
        assertEquals(1f - PageCurlGeometry.SHADE_DEPTH, m.shades[m.frontColumns], 0.001f)
        assertEquals("the page lying back faces the reader again", 1f, m.shades[m.columns], 0.001f)
        assertEquals(2 * radius, m.edgeZ, 0.01f)
        assertEquals(m.fold - (width - m.fold - PI.toFloat() * radius), m.edgeX, 0.5f)
    }

    @Test fun indicesCoverFrontAndBackCellsWithinTheMesh() {
        val m = PageCurlMesh()
        assertEquals(m.frontColumns * m.rows * 6, m.frontIndices.size)
        assertEquals((m.columns - m.frontColumns) * m.rows * 6, m.backIndices.size)
        (m.frontIndices.asList() + m.backIndices.asList()).forEach { assertTrue(it.toInt() in 0 until m.vertexCount) }
        // Every triangle has non-zero area on the flat page.
        m.update(0f, width, height, radius, -1)
        val first = m.frontIndices
        val area = abs((m.vertices[2 * first[1]] - m.vertices[2 * first[0]]) * (m.vertices[2 * first[2] + 1] - m.vertices[2 * first[0] + 1]) -
            (m.vertices[2 * first[2]] - m.vertices[2 * first[0]]) * (m.vertices[2 * first[1] + 1] - m.vertices[2 * first[0] + 1]))
        assertTrue(area > 0f)
    }

    @Test fun colorsMultiplyByShadeAndWashTheBackLighter() {
        val m = mesh(0.4f, -1)
        m.updateColors(0xFFF0E8D8.toInt(), 0xD0)
        val flat = 0
        assertEquals(0xFFFFFFFF.toInt(), m.frontColors[flat])
        assertEquals(0xD0, (m.washColors[flat] ushr 24))
        assertEquals(0xF0, (m.washColors[flat] shr 16) and 0xFF)
        val crest = m.frontColumns
        val grey = (m.frontColors[crest] shr 16) and 0xFF
        assertTrue("the crest is darker than the flat page", grey < 255)
        assertTrue(m.frontColors.size >= m.vertexCount && m.washColors.size >= m.vertexCount)
    }

    @Test fun meshKeepsItsArraysAcrossUpdates() {
        val m = PageCurlMesh()
        val vertices = m.vertices
        val colors = m.washColors
        for (p in steps) { m.update(p, width, height, radius, if (p < 0.5f) -1 else 1); m.updateColors(0xFF222222.toInt(), 0x80) }
        assertSame(vertices, m.vertices)
        assertSame(colors, m.washColors)
        assertTrue(m.vertices.all { !it.isNaN() } && hypot(m.vertices[0], m.vertices[1]).isFinite())
    }
}
