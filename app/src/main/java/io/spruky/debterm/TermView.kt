package io.spruky.debterm

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.media.AudioManager
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/** The whole UI: a monospace grid on a Canvas. Drag = scrollback, pinch = size. */
class TermView(ctx: Context) : View(ctx) {
    val vt = Vt(80, 24) { onWrite?.invoke(it) }
    var onWrite: ((ByteArray) -> Unit)? = null
    var onResize: ((Int, Int) -> Unit)? = null
    var onTap: (() -> Unit)? = null
    var ctrl = false
    var alt = false
    var volUsed = false

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var cw = 1f
    private var lh = 1f
    private var asc = 0f
    private var px = 0f
    private var off = 0                     // rows scrolled back
    private var acc = 0f
    private val sb = StringBuilder(4)

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(Pal.BG)
        p.typeface = Typeface.MONOSPACE
        px = 13f * resources.displayMetrics.scaledDensity
        metrics()
    }

    private fun metrics() {
        p.textSize = px
        cw = p.measureText("M")
        val fm = p.fontMetrics
        lh = ceil(fm.descent - fm.ascent)
        asc = -fm.ascent
    }

    val cols get() = vt.cols
    val rows get() = vt.rows

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) = fit(w, h)

    /** Padding follows the system bars, so re-fit when it changes. */
    fun refit() = fit(width, height)

    private fun fit(w: Int, h: Int) {
        val aw = w - paddingLeft - paddingRight
        val ah = h - paddingTop - paddingBottom
        if (aw <= 0 || ah <= 0 || cw <= 0f || lh <= 0f) return
        val c = max(16, floor(aw / cw).toInt())
        val r = max(4, floor(ah / lh).toInt())
        if (c != vt.cols || r != vt.rows) {
            vt.resize(c, r)
            off = 0
        }
        onResize?.invoke(c, r)
        invalidate()
    }

    override fun onDraw(cv: Canvas) {
        cv.save()
        cv.translate(paddingLeft.toFloat(), paddingTop.toFloat())
        grid(cv)
        cv.restore()
    }

    private fun grid(cv: Canvas) {
        val s = vt.s
        val nb = s.back.size
        for (k in 0 until s.rows) {
            val a = nb - off + k
            val row = if (a < nb) (if (a >= 0) s.back[a] else null) else s.lines.getOrNull(a - nb)
            if (row != null) drawRow(cv, row, k * lh)
        }
        if (vt.showCursor && off == 0) {
            val x = s.cx * cw
            val y = s.cy * lh
            p.color = Pal.FG
            cv.drawRect(x, y, x + cw, y + lh, p)
            val cp = s.lines[s.cy].ch[s.cx]
            if (cp > 32) {
                p.color = Pal.BG
                sb.setLength(0)
                sb.appendCodePoint(cp)
                cv.drawText(sb.toString(), x, y + asc, p)
            }
        }
    }

    private fun drawRow(cv: Canvas, r: Row, y: Float) {
        val n = r.ch.size
        var i = 0
        while (i < n) {                                    // one rect per background run
            var j = i
            while (j < n && r.bg[j] == r.bg[i] && r.at[j] and 8 == r.at[i] and 8) j++
            val rev = r.at[i] and 8 != 0
            val bc = if (rev) Pal.of(r.fg[i], Pal.FG) else Pal.of(r.bg[i], Pal.BG)
            if (bc != Pal.BG) {
                p.color = bc
                cv.drawRect(i * cw, y, j * cw, y + lh, p)
            }
            i = j
        }
        i = 0
        while (i < n) {                                    // glyphs per cell keeps columns exact
            val cp = r.ch[i]
            val a = r.at[i]
            if (cp > 32 && a and 64 == 0) {
                var fc = if (a and 8 != 0) Pal.of(r.bg[i], Pal.BG) else Pal.of(r.fg[i], Pal.FG)
                if (a and 1 != 0 && r.fg[i] in 0..7) fc = Pal.c[r.fg[i] + 8]
                if (a and 16 != 0) fc = (fc and 0x00FFFFFF) or 0x99000000.toInt()
                p.color = fc
                p.isFakeBoldText = a and 1 != 0
                p.isUnderlineText = a and 4 != 0
                p.isStrikeThruText = a and 32 != 0
                p.textSkewX = if (a and 2 != 0) -0.22f else 0f
                sb.setLength(0)
                sb.appendCodePoint(cp)
                cv.drawText(sb.toString(), i * cw, y + asc, p)
            }
            i++
        }
        p.isFakeBoldText = false
        p.isUnderlineText = false
        p.isStrikeThruText = false
        p.textSkewX = 0f
    }

    fun send(b: ByteArray) {
        if (off != 0) { off = 0; invalidate() }
        onWrite?.invoke(b)
    }

    fun send(s: String) = send(s.toByteArray())

    fun scrollRows(n: Int) {
        val v = (off + n).coerceIn(0, vt.s.back.size)
        if (v != off) { off = v; invalidate() }
    }

    fun kb() {
        requestFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(this, 0)
    }

    fun paste() {
        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val t = cb.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: return
        if (vt.bracketed) send(ESC + "[200~" + t + ESC + "[201~") else send(t)
    }

    fun volume(dir: Int) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustSuggestedStreamVolume(
            if (dir > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI
        )
    }

    private val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean { kb(); onTap?.invoke(); return true }
        override fun onLongPress(e: MotionEvent) { paste() }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            acc -= dy / lh
            val n = acc.toInt()
            if (n != 0) { acc -= n; scrollRows(n) }
            return true
        }
    })

    private val sd = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            px = (px * d.scaleFactor).coerceIn(9f, 60f)
            metrics()
            fit(width, height)
            return true
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        sd.onTouchEvent(e)
        if (!sd.isInProgress) gd.onTouchEvent(e)
        return true
    }

    override fun onCheckIsTextEditor() = true
    override fun onCreateInputConnection(ei: EditorInfo): InputConnection = Keys.ic(this, ei)
    override fun onKeyDown(kc: Int, e: KeyEvent) = Keys.down(this, kc, e)
    override fun onKeyUp(kc: Int, e: KeyEvent) = Keys.up(this, kc, e)
}
