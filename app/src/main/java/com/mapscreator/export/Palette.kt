package com.mapscreator.export

// Copied from garmiand — must stay in sync with garmin/source/TileDecoder.mc
// Palette.VERSION and COLORS must match watch-side decoder exactly.
object Palette {
    const val SIZE = 64
    const val VERSION = 1

    val COLORS: IntArray = IntArray(SIZE).also { arr ->
        val levels = intArrayOf(0, 85, 170, 255)
        for (r in 0..3) for (g in 0..3) for (b in 0..3) {
            arr[(r shl 4) or (g shl 2) or b] =
                (levels[r] shl 16) or (levels[g] shl 8) or levels[b]
        }
    }

    fun toBytes(): ByteArray {
        val out = ByteArray(SIZE * 3)
        for (i in 0 until SIZE) {
            val c = COLORS[i]
            out[i * 3 + 0] = ((c shr 16) and 0xFF).toByte()
            out[i * 3 + 1] = ((c shr 8) and 0xFF).toByte()
            out[i * 3 + 2] = (c and 0xFF).toByte()
        }
        return out
    }

    fun nearest(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (quantizeChannel(r) shl 4) or (quantizeChannel(g) shl 2) or quantizeChannel(b)
    }

    private fun quantizeChannel(v: Int): Int = when {
        v < 42 -> 0
        v < 127 -> 1
        v < 212 -> 2
        else -> 3
    }
}
