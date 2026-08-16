package io.spruky.debterm

import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

val ESC = 27.toChar().toString()
val CR = 13.toChar().toString()
val LF = 10.toChar().toString()
val NL = CR + LF
private val TAB = 9.toChar().toString()
private val DEL = 127.toChar().toString()

/**
 * Phone keyboards have no Ctrl/Esc/arrows, so the volume keys are modifiers:
 *   Vol-Down + key = Ctrl+key
 *   Vol-Up   + key = e Esc | t Tab | w a s d arrows | p n PgUp/PgDn |
 *                    h f Home/End | i Ins | x Del | 1-9 0 F1-F10 | l | | u _
 *                    anything else = Alt+key
 * A volume key pressed on its own still changes the volume.
 */
object Keys {

    fun ic(v: TermView, ei: EditorInfo): InputConnection {
        ei.inputType = InputType.TYPE_NULL
        ei.imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_ENTER_ACTION
        return object : BaseInputConnection(v, true) {
            override fun commitText(t: CharSequence?, n: Int): Boolean {
                if (!t.isNullOrEmpty()) v.send(t.toString())
                return true
            }

            override fun setComposingText(t: CharSequence?, n: Int) = true
            override fun finishComposingText() = true

            override fun deleteSurroundingText(l: Int, r: Int): Boolean {
                repeat(l) { v.send(DEL) }
                return true
            }

            override fun performEditorAction(a: Int): Boolean {
                v.send(CR)
                return true
            }

            override fun sendKeyEvent(e: KeyEvent): Boolean = when (e.action) {
                KeyEvent.ACTION_DOWN -> down(v, e.keyCode, e)
                KeyEvent.ACTION_UP -> up(v, e.keyCode, e)
                else -> true
            }
        }
    }

    fun down(v: TermView, kc: Int, e: KeyEvent): Boolean {
        when (kc) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> { v.ctrl = true; v.volUsed = false; return true }
            KeyEvent.KEYCODE_VOLUME_UP -> { v.alt = true; v.volUsed = false; return true }
            KeyEvent.KEYCODE_BACK -> return false
        }
        if (v.ctrl || v.alt) v.volUsed = true
        val s = map(v, kc, e) ?: return false
        v.send(s)
        return true
    }

    fun up(v: TermView, kc: Int, e: KeyEvent): Boolean {
        if (kc == KeyEvent.KEYCODE_VOLUME_DOWN) {
            v.ctrl = false
            if (!v.volUsed) v.volume(-1)
            return true
        }
        if (kc == KeyEvent.KEYCODE_VOLUME_UP) {
            v.alt = false
            if (!v.volUsed) v.volume(1)
            return true
        }
        return false
    }

    private fun fkey(n: Int) = when (n) {
        1 -> ESC + "OP"
        2 -> ESC + "OQ"
        3 -> ESC + "OR"
        4 -> ESC + "OS"
        5 -> ESC + "[15~"
        6 -> ESC + "[17~"
        7 -> ESC + "[18~"
        8 -> ESC + "[19~"
        9 -> ESC + "[20~"
        10 -> ESC + "[21~"
        11 -> ESC + "[23~"
        else -> ESC + "[24~"
    }

    private fun map(v: TermView, kc: Int, e: KeyEvent): String? {
        val ss = if (v.vt.appCursor) ESC + "O" else ESC + "["
        val meta = v.alt || e.isAltPressed
        val ctrl = v.ctrl || e.isCtrlPressed
        when (kc) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> return CR
            KeyEvent.KEYCODE_DEL -> return if (meta) ESC + DEL else DEL
            KeyEvent.KEYCODE_FORWARD_DEL -> return ESC + "[3~"
            KeyEvent.KEYCODE_TAB -> return if (e.isShiftPressed) ESC + "[Z" else TAB
            KeyEvent.KEYCODE_ESCAPE -> return ESC
            KeyEvent.KEYCODE_DPAD_UP -> return ss + "A"
            KeyEvent.KEYCODE_DPAD_DOWN -> return ss + "B"
            KeyEvent.KEYCODE_DPAD_RIGHT -> return ss + "C"
            KeyEvent.KEYCODE_DPAD_LEFT -> return ss + "D"
            KeyEvent.KEYCODE_MOVE_HOME -> return ESC + "[1~"
            KeyEvent.KEYCODE_MOVE_END -> return ESC + "[4~"
            KeyEvent.KEYCODE_PAGE_UP -> return ESC + "[5~"
            KeyEvent.KEYCODE_PAGE_DOWN -> return ESC + "[6~"
            KeyEvent.KEYCODE_INSERT -> return ESC + "[2~"
        }
        if (kc in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12) return fkey(kc - KeyEvent.KEYCODE_F1 + 1)

        var u = e.unicodeChar
        if (u == 0) return null

        if (v.alt) {                                   // Vol-Up layer
            val c = u.toChar().lowercaseChar()
            val sp: String? = when (c) {
                'e' -> ESC
                't' -> TAB
                'w' -> ss + "A"
                's' -> ss + "B"
                'd' -> ss + "C"
                'a' -> ss + "D"
                'h' -> ESC + "[1~"
                'f' -> ESC + "[4~"
                'p' -> ESC + "[5~"
                'n' -> ESC + "[6~"
                'i' -> ESC + "[2~"
                'x' -> ESC + "[3~"
                'l' -> "|"
                'u' -> "_"
                'r' -> "~"
                'q' -> "`"
                '0' -> fkey(10)
                in '1'..'9' -> fkey(c - '0')
                else -> null
            }
            if (sp != null) return sp
        }

        if (ctrl) {
            val c = u.toChar().lowercaseChar().code
            u = when {
                c in 97..122 -> c - 96                 // ^A..^Z
                c == 32 || c == 64 || c == 50 -> 0     // ^space ^@ ^2
                c in 91..95 -> c - 64                  // ^[ ^\ ^] ^^ ^_
                c == 63 -> 127                         // ^?
                c == 54 -> 30
                c == 45 || c == 55 -> 31
                else -> u
            }
        }
        val s = String(Character.toChars(u))
        return if (meta) ESC + s else s
    }
}
