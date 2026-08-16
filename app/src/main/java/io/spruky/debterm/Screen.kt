package io.spruky.debterm

import kotlin.math.max
import kotlin.math.min

/** One line of cells. Rows are moved by reference, so scrolling is O(1) per line. */
class Row(n: Int) {
    val ch = IntArray(n) { 32 }
    val fg = IntArray(n) { -1 }
    val bg = IntArray(n) { -1 }
    val at = IntArray(n)

    fun blank(a: Int, b: Int, bgc: Int) {
        var i = max(0, a)
        val e = min(ch.size - 1, b)
        while (i <= e) { ch[i] = 32; fg[i] = -1; bg[i] = bgc; at[i] = 0; i++ }
    }
}

/** The cell grid + cursor + scroll region. No escape parsing here. */
class Screen(var cols: Int, var rows: Int) {
    var lines = ArrayList<Row>(rows)
    val back = ArrayList<Row>()
    var cx = 0
    var cy = 0
    var top = 0
    var bot = rows - 1
    var wrap = true
    var insert = false
    var wrapNext = false
    var keepBack = true
    var lastCh = 32
    private var sx = 0
    private var sy = 0
    private var tabs = BooleanArray(cols + 1)

    init {
        for (i in 0 until rows) lines.add(Row(cols))
        resetTabs()
    }

    private fun resetTabs() {
        tabs = BooleanArray(cols + 1)
        var i = 8
        while (i < cols) { tabs[i] = true; i += 8 }
    }

    fun put(cp: Int, f: Int, b: Int, a: Int) {
        if (wrapNext) { cr(); lf() }
        if (cx > cols - 1) cx = cols - 1
        val r = lines[cy]
        if (insert) {
            for (i in cols - 1 downTo cx + 1) {
                r.ch[i] = r.ch[i - 1]; r.fg[i] = r.fg[i - 1]; r.bg[i] = r.bg[i - 1]; r.at[i] = r.at[i - 1]
            }
        }
        r.ch[cx] = cp; r.fg[cx] = f; r.bg[cx] = b; r.at[cx] = a
        lastCh = cp
        if (cx == cols - 1) wrapNext = wrap else cx++
    }

    fun cr() { cx = 0; wrapNext = false }
    fun bs() { if (wrapNext) wrapNext = false else if (cx > 0) cx-- }
    fun lf() { wrapNext = false; if (cy == bot) scrollUp(1) else if (cy < rows - 1) cy++ }
    fun ri() { wrapNext = false; if (cy == top) scrollDown(1) else if (cy > 0) cy-- }
    fun tab() { var i = cx + 1; while (i < cols - 1 && !tabs[i]) i++; cx = min(cols - 1, i); wrapNext = false }
    fun backTab(n: Int) { repeat(n) { var i = cx - 1; while (i > 0 && !tabs[i]) i--; cx = max(0, i) } }
    fun setTab() { if (cx in 0 until cols) tabs[cx] = true }
    fun clearTab(m: Int) { if (m == 3) tabs.fill(false) else if (cx in 0 until cols) tabs[cx] = false }
    fun save() { sx = cx; sy = cy }
    fun restore() { cx = sx.coerceIn(0, cols - 1); cy = sy.coerceIn(0, rows - 1); wrapNext = false }

    fun scrollUp(n: Int) {
        repeat(n) {
            val r = lines.removeAt(top)
            if (top == 0 && keepBack) {
                back.add(r)
                if (back.size > 4000) back.removeAt(0)
            }
            lines.add(bot, Row(cols))
        }
    }

    fun scrollDown(n: Int) { repeat(n) { lines.removeAt(bot); lines.add(top, Row(cols)) } }
    fun il(n: Int) { if (cy in top..bot) repeat(min(n, bot - cy + 1)) { lines.removeAt(bot); lines.add(cy, Row(cols)) } }
    fun dl(n: Int) { if (cy in top..bot) repeat(min(n, bot - cy + 1)) { lines.removeAt(cy); lines.add(bot, Row(cols)) } }

    fun dch(n: Int, bgc: Int) {
        val r = lines[cy]
        for (i in cx until cols) {
            val j = i + n
            if (j < cols) { r.ch[i] = r.ch[j]; r.fg[i] = r.fg[j]; r.bg[i] = r.bg[j]; r.at[i] = r.at[j] }
            else { r.ch[i] = 32; r.fg[i] = -1; r.bg[i] = bgc; r.at[i] = 0 }
        }
    }

    fun ich(n: Int, bgc: Int) {
        val r = lines[cy]
        for (i in cols - 1 downTo cx) {
            val j = i - n
            if (j >= cx) { r.ch[i] = r.ch[j]; r.fg[i] = r.fg[j]; r.bg[i] = r.bg[j]; r.at[i] = r.at[j] }
            else { r.ch[i] = 32; r.fg[i] = -1; r.bg[i] = bgc; r.at[i] = 0 }
        }
    }

    fun ech(n: Int, bgc: Int) = lines[cy].blank(cx, cx + n - 1, bgc)

    fun el(m: Int, bgc: Int) {
        val r = lines[cy]
        when (m) {
            0 -> r.blank(cx, cols - 1, bgc)
            1 -> r.blank(0, cx, bgc)
            else -> r.blank(0, cols - 1, bgc)
        }
    }

    fun ed(m: Int, bgc: Int) {
        when (m) {
            0 -> { lines[cy].blank(cx, cols - 1, bgc); for (y in cy + 1 until rows) lines[y].blank(0, cols - 1, bgc) }
            1 -> { for (y in 0 until cy) lines[y].blank(0, cols - 1, bgc); lines[cy].blank(0, cx, bgc) }
            else -> for (y in 0 until rows) lines[y].blank(0, cols - 1, bgc)
        }
    }

    /** Keep the bottom of the buffer visible across rotations / font changes. */
    fun resize(c: Int, r: Int) {
        val keep = min(r, lines.size)
        val start = lines.size - keep
        val nl = ArrayList<Row>(r)
        for (i in 0 until r) {
            val row = Row(c)
            if (i < keep) {
                val o = lines[start + i]
                for (j in 0 until min(c, o.ch.size)) { row.ch[j] = o.ch[j]; row.fg[j] = o.fg[j]; row.bg[j] = o.bg[j]; row.at[j] = o.at[j] }
            }
            nl.add(row)
        }
        lines = nl
        cols = c; rows = r; top = 0; bot = r - 1
        cx = cx.coerceIn(0, c - 1); cy = (cy - start).coerceIn(0, r - 1)
        wrapNext = false
        resetTabs()
    }
}
