package com.bookcover.diagnostic.reader

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * PT-1 paper curl geometry: pure Kotlin (no Android types) so PageCurlGeometryTest checks it on the JVM.
 *
 * The outgoing page wraps around a vertical cylinder of radius R lying on the page. Work in a
 * "normalized" frame where the page peels from its right edge and moves left (a forward turn in a
 * left-to-right book); the other direction is the mirror image. For a page point u (distance from
 * the page's left edge) and the fold line xf (where the paper leaves the page), with s = u - xf:
 *
 *   s <= 0        flat on the page:          X = u,                    z = 0
 *   0 < s < pi*R  on the cylinder:           X = xf + R*sin(s/R),      z = R*(1 - cos(s/R))
 *   s >= pi*R     over the top, lying back:  X = xf - (s - pi*R),      z = 2R
 *
 * Paper never stretches (each screen segment is at most as long as its arc). Past the cylinder's
 * crest (s > pi*R/2) the viewer sees the back of the page, which the painter lightens.
 *
 * Progress p maps to how far the page edge has travelled: d = p * maxTravel. The edge stays under
 * the finger during a swipe (d = finger travel). At p = 1 the fold line is at -R, so every point
 * of the page is at X <= 0: fully off the page.
 */
internal object PageCurlGeometry {
    private const val PI_F = PI.toFloat()
    const val MIN_RADIUS_DP = 60f
    const val MAX_RADIUS_DP = 100f
    private const val RADIUS_WIDTH_SHARE = 0.11f

    /** A slight fold: about 11% of the page width, kept between 60 and 100 dp. */
    fun radiusFor(width: Float, density: Float): Float =
        (width * RADIUS_WIDTH_SHARE).coerceIn(MIN_RADIUS_DP * density, MAX_RADIUS_DP * density)

    /** Edge travel from flat (p = 0) to fully off the page (p = 1, fold line at -R). */
    fun maxTravel(width: Float, radius: Float): Float = 2f * width - (PI_F - 2f) * radius

    /** Progress for a finger that has moved [along] pixels into the turn, keeping the edge under it. */
    fun progressForDrag(along: Float, width: Float, radius: Float): Float {
        val travel = maxTravel(width, radius)
        return if (travel <= 0f) 0f else (along / travel).coerceIn(0f, 1f)
    }

    /**
     * Length of paper lifted off the page (width - fold line) when the edge has travelled [travel].
     * On the cylinder travel = s - R*sin(s/R) (solved by bisection: monotonic, no allocation); once
     * the edge is over the top, travel = 2s - pi*R.
     */
    fun liftedLength(travel: Float, radius: Float): Float {
        if (travel <= 0f || radius <= 0f) return travel.coerceAtLeast(0f)
        val half = PI_F * radius
        if (travel >= half) return (travel + half) / 2f
        var low = 0f
        var high = half
        repeat(32) {
            val mid = (low + high) / 2f
            if (mid - radius * sin(mid / radius) < travel) low = mid else high = mid
        }
        return (low + high) / 2f
    }

    /** Fold line position in the normalized frame for progress [progress]. */
    fun foldLine(progress: Float, width: Float, radius: Float): Float {
        val travel = progress.coerceIn(0f, 1f) * maxTravel(width, radius)
        return width - liftedLength(travel, radius)
    }

    /** Normalized screen X of page point [u]. */
    fun pointX(u: Float, fold: Float, radius: Float): Float {
        val s = u - fold
        return when {
            s <= 0f -> u
            s < PI_F * radius -> fold + radius * sin(s / radius)
            else -> fold - (s - PI_F * radius)
        }
    }

    /** Height of page point [u] above the page (0 flat, 2R lying back over the top). */
    fun pointZ(u: Float, fold: Float, radius: Float): Float {
        val s = u - fold
        return when {
            s <= 0f -> 0f
            s < PI_F * radius -> radius * (1f - cos(s / radius))
            else -> 2f * radius
        }
    }

    /**
     * Brightness of page point [u] (1 = as lit as the flat page). The front darkens as it turns away
     * toward the crest; the back brightens again as it comes to face the reader.
     */
    fun pointShade(u: Float, fold: Float, radius: Float): Float {
        val s = u - fold
        if (s <= 0f || s >= PI_F * radius) return 1f
        val theta = s / radius
        return if (theta <= PI_F / 2f) 1f - SHADE_DEPTH * (1f - cos(theta)) else 1f - SHADE_DEPTH * (1f + cos(theta))
    }

    const val SHADE_DEPTH = 0.22f
}

/**
 * A reusable vertex mesh for the curl: 1 flat column + [quarterColumns] on the front of the
 * cylinder (the front part), then [quarterColumns] on the back of the cylinder + 1 flat column
 * lying back (the back part), by [rows]. Columns sit exactly on the fold line and the crest, so the
 * silhouette is sharp at any progress. All arrays are allocated once; [update] allocates nothing.
 *
 * Coordinates are in the overlay's pixels with the page at [0, width] x [0, height]; texture
 * coordinates are in the snapshot bitmap's pixels.
 */
internal class PageCurlMesh(val quarterColumns: Int = 10, val rows: Int = 30) {
    val columns = 2 * quarterColumns + 2
    val frontColumns = quarterColumns + 1
    private val vertexColumns = columns + 1
    val vertexCount = vertexColumns * (rows + 1)
    /** x, y pairs. */
    val vertices = FloatArray(vertexCount * 2)
    val textures = FloatArray(vertexCount * 2)
    /** Per-vertex brightness from [PageCurlGeometry.pointShade]. */
    val shades = FloatArray(vertexCount)
    /** Per-vertex ARGB colours for Canvas.drawVertices; sized for the older vertexCount-length check. */
    val frontColors = IntArray(vertexCount * 2)
    val backColors = IntArray(vertexCount * 2)
    val washColors = IntArray(vertexCount * 2)
    val frontIndices = ShortArray(frontColumns * rows * 6)
    val backIndices = ShortArray((columns - frontColumns) * rows * 6)
    private val columnU = FloatArray(vertexColumns)

    /** Normalized-frame results of the last [update], for the shadows. */
    var fold = 0f; private set
    var radius = 0f; private set
    /** Rightmost normalized X of the page (where the revealed page starts). */
    var reach = 0f; private set
    /** Normalized X and height of the lifted edge (the left edge of the folded-back flap). */
    var edgeX = 0f; private set
    var edgeZ = 0f; private set
    /** Whether any of the back of the page is visible. */
    var backVisible = false; private set

    init {
        require(vertexCount <= Short.MAX_VALUE) { "mesh too large for short indices" }
        fillIndices(frontIndices, 0, frontColumns)
        fillIndices(backIndices, frontColumns, columns)
    }

    private fun fillIndices(target: ShortArray, fromColumn: Int, toColumn: Int) {
        var i = 0
        for (c in fromColumn until toColumn) for (r in 0 until rows) {
            val topLeft = r * vertexColumns + c
            val bottomLeft = topLeft + vertexColumns
            target[i++] = topLeft.toShort(); target[i++] = (topLeft + 1).toShort(); target[i++] = bottomLeft.toShort()
            target[i++] = (topLeft + 1).toShort(); target[i++] = (bottomLeft + 1).toShort(); target[i++] = bottomLeft.toShort()
        }
    }

    /**
     * Lays the mesh out for [progress]. [sign] is the screen direction the outgoing page moves in
     * (-1 left, +1 right; +1 peels from the left edge). [lift] is the most the page may grow in
     * height when raised (a hint of perspective), capped at the radius.
     */
    fun update(
        progress: Float, width: Float, height: Float, radius: Float, sign: Int,
        textureScaleX: Float = 1f, textureScaleY: Float = 1f, lift: Float = 0f,
    ) {
        this.radius = radius
        val fold = PageCurlGeometry.foldLine(progress, width, radius)
        this.fold = fold
        val quarter = PI.toFloat() * radius / 2f
        val start = fold.coerceIn(0f, width)
        val crest = (fold + quarter).coerceIn(0f, width)
        val end = (fold + 2f * quarter).coerceIn(0f, width)
        // Column positions along the page: flat | front of cylinder | back of cylinder | lying back.
        columnU[0] = 0f
        for (i in 0..quarterColumns) columnU[1 + i] = start + (crest - start) * i / quarterColumns
        for (i in 1..quarterColumns) columnU[1 + quarterColumns + i] = crest + (end - crest) * i / quarterColumns
        columnU[columns] = width

        val maxLift = lift.coerceIn(0f, radius)
        val centre = height / 2f
        var v = 0
        for (r in 0..rows) {
            val y = height * r / rows
            for (c in 0..columns) {
                val u = columnU[c]
                val x = PageCurlGeometry.pointX(u, fold, radius)
                val z = PageCurlGeometry.pointZ(u, fold, radius)
                val grow = if (radius > 0f && centre > 0f) maxLift / centre * (z / (2f * radius)) else 0f
                vertices[2 * v] = if (sign > 0) width - x else x
                vertices[2 * v + 1] = centre + (y - centre) * (1f + grow)
                textures[2 * v] = (if (sign > 0) width - u else u) * textureScaleX
                textures[2 * v + 1] = y * textureScaleY
                shades[v] = PageCurlGeometry.pointShade(u, fold, radius)
                v++
            }
        }
        backVisible = width - fold > quarter
        reach = if (backVisible) fold + radius else PageCurlGeometry.pointX(width, fold, radius)
        edgeX = PageCurlGeometry.pointX(width, fold, radius)
        edgeZ = PageCurlGeometry.pointZ(width, fold, radius)
    }

    /**
     * Fills the colour arrays: the bitmap is multiplied by the shade (front and back), and the back
     * is then washed with [backColor] at [washAlpha] so it reads as the lighter reverse of the paper.
     */
    fun updateColors(backColor: Int, washAlpha: Int) {
        val red = (backColor shr 16) and 0xFF
        val green = (backColor shr 8) and 0xFF
        val blue = backColor and 0xFF
        val alpha = washAlpha.coerceIn(0, 255) shl 24
        for (i in 0 until vertexCount) {
            val shade = shades[i].coerceIn(0f, 1f)
            val grey = (255 * shade).toInt()
            val multiply = (0xFF shl 24) or (grey shl 16) or (grey shl 8) or grey
            frontColors[i] = multiply
            backColors[i] = multiply
            washColors[i] = alpha or ((red * shade).toInt() shl 16) or ((green * shade).toInt() shl 8) or (blue * shade).toInt()
        }
    }
}
