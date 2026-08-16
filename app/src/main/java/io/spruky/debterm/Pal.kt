package io.spruky.debterm

/** xterm 256-colour table. Cell values: -1 default, 0..255 index, or 0x1000000|rgb. */
object Pal {
    val BG = 0xFF000000.toInt()
    val FG = 0xFFD0D0D0.toInt()
    val c = IntArray(256)

    init {
        val base = intArrayOf(
            0x000000, 0xCC3333, 0x2FBF5F, 0xCFA732, 0x3B7DD8, 0xA557CE, 0x2FAFAF, 0xB8B8B8,
            0x5A5A5A, 0xFF5C5C, 0x5CE68A, 0xFFD75F, 0x5C9CFF, 0xD07BEE, 0x5FE0E0, 0xFFFFFF
        )
        for (i in 0..15) c[i] = 0xFF000000.toInt() or base[i]
        val q = intArrayOf(0, 95, 135, 175, 215, 255)
        var n = 16
        for (r in 0..5) for (g in 0..5) for (b in 0..5) {
            c[n++] = 0xFF000000.toInt() or (q[r] shl 16) or (q[g] shl 8) or q[b]
        }
        for (i in 0..23) {
            val v = 8 + i * 10
            c[n++] = 0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
        }
    }

    fun of(v: Int, def: Int): Int = when {
        v < 0 -> def
        v and 0x1000000 != 0 -> 0xFF000000.toInt() or (v and 0xFFFFFF)
        else -> c[v and 255]
    }
}
