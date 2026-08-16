package io.spruky.debterm

import kotlin.math.max
import kotlin.math.min

/**
 * xterm-subset VT parser. Bytes in, Screen mutations out; `reply` sends
 * responses (cursor reports, device attributes) back up the pty.
 */
class Vt(c: Int, r: Int, private val reply: (ByteArray) -> Unit) {
    var cols = c
    var rows = r
    var pri = Screen(c, r)
    var alt = Screen(c, r).also { it.keepBack = false }
    var s = pri
    var appCursor = false
    var showCursor = true
    var bracketed = false
    private var fg = -1
    private var bg = -1
    private var at = 0
    private var st = 0                     // 0 ground 1 esc 2 csi 3 osc/dcs 4 st-tail 5 charset
    private val par = IntArray(24)
    private var np = 0
    private var priv = 0
    private var u = 0
    private var un = 0                     // pending utf-8 continuation bytes

    fun resize(c2: Int, r2: Int) {
        cols = c2; rows = r2
        pri.resize(c2, r2); alt.resize(c2, r2)
    }

    fun feed(b: ByteArray, n: Int) { for (i in 0 until n) byte(b[i].toInt() and 255) }

    private fun byte(c2: Int) {
        when (st) {
            1 -> esc(c2)
            2 -> csi(c2)
            3 -> if (c2 == 7) st = 0 else if (c2 == 27) st = 4
            4, 5 -> st = 0
            else -> ground(c2)
        }
    }

    private fun ground(c2: Int) {
        if (un > 0) {
            if (c2 and 0xC0 == 0x80) {
                u = (u shl 6) or (c2 and 0x3F)
                if (--un == 0) s.put(u, fg, bg, at)
                return
            }
            un = 0
        }
        when {
            c2 == 27 -> st = 1
            c2 == 8 -> s.bs()
            c2 == 9 -> s.tab()
            c2 == 10 || c2 == 11 || c2 == 12 -> s.lf()
            c2 == 13 -> s.cr()
            c2 < 32 -> {}
            c2 < 0x80 -> s.put(c2, fg, bg, at)
            c2 and 0xE0 == 0xC0 -> { u = c2 and 0x1F; un = 1 }
            c2 and 0xF0 == 0xE0 -> { u = c2 and 0x0F; un = 2 }
            c2 and 0xF8 == 0xF0 -> { u = c2 and 0x07; un = 3 }
            else -> s.put(0xFFFD, fg, bg, at)
        }
    }

    private fun esc(c2: Int) {
        st = 0
        when (c2.toChar()) {
            '[' -> { par.fill(0); np = 0; priv = 0; st = 2 }
            ']', 'P', '^', '_' -> st = 3
            '(', ')', '*', '+', '-', '.', '/' -> st = 5
            '7' -> s.save()
            '8' -> s.restore()
            'D' -> s.lf()
            'M' -> s.ri()
            'E' -> { s.cr(); s.lf() }
            'H' -> s.setTab()
            'c' -> reset()
            else -> {}
        }
    }

    private fun csi(c2: Int) {
        if (c2 in 0x30..0x39) { par[np] = par[np] * 10 + (c2 - 48); return }
        if (c2 == 0x3B || c2 == 0x3A) { if (np < par.size - 1) np++; return }
        if (c2 in 0x3C..0x3F) { priv = c2; return }
        if (c2 < 0x40) return                       // intermediates
        st = 0; np++
        act(c2.toChar())
    }

    private fun d(v: Int) = if (v == 0) 1 else v

    private fun act(f: Char) {
        val p0 = par[0]
        val p1 = if (np > 1) par[1] else 0
        when (f) {
            'A' -> { s.cy = max(0, s.cy - d(p0)); s.wrapNext = false }
            'B', 'e' -> { s.cy = min(rows - 1, s.cy + d(p0)); s.wrapNext = false }
            'C', 'a' -> { s.cx = min(cols - 1, s.cx + d(p0)); s.wrapNext = false }
            'D' -> { s.cx = max(0, s.cx - d(p0)); s.wrapNext = false }
            'E' -> { s.cy = min(rows - 1, s.cy + d(p0)); s.cr() }
            'F' -> { s.cy = max(0, s.cy - d(p0)); s.cr() }
            'G', '`' -> { s.cx = min(cols - 1, d(p0) - 1); s.wrapNext = false }
            'd' -> { s.cy = min(rows - 1, d(p0) - 1); s.wrapNext = false }
            'H', 'f' -> { s.cy = min(rows - 1, d(p0) - 1); s.cx = min(cols - 1, d(p1) - 1); s.wrapNext = false }
            'I' -> repeat(d(p0)) { s.tab() }
            'Z' -> s.backTab(d(p0))
            'J' -> s.ed(p0, bg)
            'K' -> s.el(p0, bg)
            'L' -> s.il(d(p0))
            'M' -> s.dl(d(p0))
            'P' -> s.dch(d(p0), bg)
            '@' -> s.ich(d(p0), bg)
            'X' -> s.ech(d(p0), bg)
            'S' -> s.scrollUp(d(p0))
            'T' -> s.scrollDown(d(p0))
            'b' -> repeat(d(p0)) { s.put(s.lastCh, fg, bg, at) }
            'r' -> {
                s.top = max(0, d(p0) - 1)
                s.bot = min(rows - 1, if (np > 1 && p1 > 0) p1 - 1 else rows - 1)
                if (s.top >= s.bot) { s.top = 0; s.bot = rows - 1 }
                s.cx = 0; s.cy = s.top
            }
            'm' -> sgr()
            'h' -> mode(true)
            'l' -> mode(false)
            'n' -> if (p0 == 6) reply("\u001b[${s.cy + 1};${s.cx + 1}R".toByteArray())
            'c' -> reply("\u001b[?6c".toByteArray())
            's' -> s.save()
            'u' -> s.restore()
            'g' -> s.clearTab(p0)
            else -> {}
        }
    }

    private fun mode(on: Boolean) {
        for (i in 0 until np) {
            val p = par[i]
            if (priv == 0x3F) when (p) {
                1 -> appCursor = on
                7 -> s.wrap = on
                25 -> showCursor = on
                2004 -> bracketed = on
                47, 1047, 1049 -> swap(on)
                else -> {}
            } else if (p == 4) s.insert = on
        }
    }

    private fun swap(on: Boolean) {
        if (on && s !== alt) { pri.save(); alt.ed(2, bg); alt.cx = 0; alt.cy = 0; s = alt }
        else if (!on && s === alt) { s = pri; pri.restore() }
    }

    private fun sgr() {
        var i = 0
        while (i < np) {
            when (val p = par[i]) {
                0 -> { fg = -1; bg = -1; at = 0 }
                1 -> at = at or 1
                2 -> at = at or 16
                3 -> at = at or 2
                4 -> at = at or 4
                7 -> at = at or 8
                8 -> at = at or 64
                9 -> at = at or 32
                21, 22 -> at = at and 1.inv() and 16.inv()
                23 -> at = at and 2.inv()
                24 -> at = at and 4.inv()
                27 -> at = at and 8.inv()
                28 -> at = at and 64.inv()
                29 -> at = at and 32.inv()
                39 -> fg = -1
                49 -> bg = -1
                in 30..37 -> fg = p - 30
                in 40..47 -> bg = p - 40
                in 90..97 -> fg = p - 90 + 8
                in 100..107 -> bg = p - 100 + 8
                38, 48 -> {
                    var v = -1
                    if (i + 2 < np && par[i + 1] == 5) { i += 2; v = par[i] }
                    else if (i + 4 < np && par[i + 1] == 2) { i += 4; v = 0x1000000 or (par[i - 2] shl 16) or (par[i - 1] shl 8) or par[i] }
                    if (p == 38) fg = v else bg = v
                }
                else -> {}
            }
            i++
        }
    }

    private fun reset() {
        pri = Screen(cols, rows)
        alt = Screen(cols, rows).also { it.keepBack = false }
        s = pri
        fg = -1; bg = -1; at = 0
        appCursor = false; showCursor = true; bracketed = false
    }
}
