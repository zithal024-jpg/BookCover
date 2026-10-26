package com.bookcover.diagnostic.cover

/** Dominant edge-color family, averaged at original precision; transparent pixels do not vote. */
internal fun matchingCoverColor(samples: IntArray): Int {
    val counts = IntArray(4096)
    val red = LongArray(4096)
    val green = LongArray(4096)
    val blue = LongArray(4096)
    for (pixel in samples) {
        if ((pixel ushr 24) < 192) continue
        val r = pixel ushr 16 and 255
        val g = pixel ushr 8 and 255
        val b = pixel and 255
        val bucket = ((r ushr 4) shl 8) or ((g ushr 4) shl 4) or (b ushr 4)
        counts[bucket]++
        red[bucket] += r.toLong()
        green[bucket] += g.toLong()
        blue[bucket] += b.toLong()
    }
    val winner = counts.indices.maxByOrNull { counts[it] } ?: return 0xFF121212.toInt()
    val count = counts[winner]
    if (count == 0) return 0xFF121212.toInt()
    return (255 shl 24) or ((red[winner] / count).toInt() shl 16) or
        ((green[winner] / count).toInt() shl 8) or (blue[winner] / count).toInt()
}
