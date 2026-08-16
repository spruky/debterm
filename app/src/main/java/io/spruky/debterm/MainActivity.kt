package io.spruky.debterm

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowInsets
import android.view.WindowManager
import java.io.IOException
import java.util.concurrent.Executors

/** One view, one pty, one shell. */
class MainActivity : Activity() {
    private lateinit var v: TermView
    private var proc: Proc? = null
    private var live = false
    private var booted = false
    private var minimal = false                     // retry drops the newer proot flags
    private val h = Handler(Looper.getMainLooper())
    private val wr = Executors.newSingleThreadExecutor()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        v = TermView(this)
        setContentView(v)
        v.setOnApplyWindowInsetsListener { view, ins ->        // 35 draws edge to edge
            if (Build.VERSION.SDK_INT >= 30) {
                val i = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(i.left, i.top, i.right, i.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(
                    ins.systemWindowInsetLeft, ins.systemWindowInsetTop,
                    ins.systemWindowInsetRight, ins.systemWindowInsetBottom
                )
            }
            v.refit()
            ins
        }
        v.onResize = { c, r ->
            proc?.let { Pty.setSize(it.fd, r, c) }
            if (!booted) { booted = true; boot() }             // first layout knows the real size
        }
        v.onTap = { if (!live && booted) { minimal = false; boot() } }
        v.kb()
    }

    private fun out(s: String) {
        val b = s.toByteArray()
        h.post { v.vt.feed(b, b.size); v.invalidate() }
    }

    private fun boot() {
        out(Boot.diag(this))
        if (Boot.ready(this)) { start(); return }
        out("first run: unpacking, this takes a moment" + NL)
        Thread {
            try {
                Boot.install(this) { out(it) }
                h.post { start() }
            } catch (e: Throwable) {
                out(NL + "install failed: " + e + NL + "tap to retry" + NL)
            }
        }.start()
    }

    private fun start() {
        if (live) return
        val t0 = System.currentTimeMillis()
        val p = try {
            Boot.shell(this, v.rows, v.cols, minimal)
        } catch (e: Throwable) {
            out("start failed: " + e + NL); return
        }
        proc = p
        live = true
        v.onWrite = { b -> wr.execute { try { p.out.write(b); p.out.flush() } catch (e: IOException) {} } }
        Thread {
            val buf = ByteArray(1 shl 15)
            while (true) {
                val n = try { p.inp.read(buf) } catch (e: IOException) { -1 }
                if (n <= 0) break
                val chunk = buf.copyOf(n)
                h.post { v.vt.feed(chunk, n); v.invalidate() }
            }
            val st = Pty.waitFor(p.pid)
            p.close()
            val quick = System.currentTimeMillis() - t0 < 4000
            h.post {
                live = false
                proc = null
                if (quick && st != 0 && !minimal) {           // an unknown proot flag looks like this
                    minimal = true
                    out(NL + "[proot exited " + st + ", retrying with fewer flags]" + NL)
                    start()
                } else {
                    out(NL + "[exited " + st + " - tap to restart]" + NL)
                }
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        proc?.let { Pty.killPg(it.pid, 9); it.close() }
        proc = null
        wr.shutdownNow()
    }
}
