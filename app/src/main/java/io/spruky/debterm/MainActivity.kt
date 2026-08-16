package io.spruky.debterm

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import java.io.IOException
import java.util.concurrent.Executors

/** One view, one pty, one shell. */
class MainActivity : Activity() {
    private lateinit var v: TermView
    private var proc: Proc? = null
    private var live = false
    private var booted = false
    private val h = Handler(Looper.getMainLooper())
    private val wr = Executors.newSingleThreadExecutor()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        v = TermView(this)
        setContentView(v)
        v.onResize = { c, r ->
            proc?.let { Pty.setSize(it.fd, r, c) }
            if (!booted) { booted = true; boot() }        // first layout knows the real size
        }
        v.onTap = { if (!live && booted) start() }
        v.kb()
    }

    private fun out(s: String) {
        val b = s.toByteArray()
        h.post { v.vt.feed(b, b.size); v.invalidate() }
    }

    private fun boot() {
        if (Boot.ready(this)) { start(); return }
        out("debterm: first run, this takes a moment" + NL + NL)
        Thread {
            try {
                Boot.install(this) { out(it) }
                h.post { start() }
            } catch (e: Throwable) {
                out(NL + "install failed: " + e + NL)
            }
        }.start()
    }

    private fun start() {
        if (live) return
        val p = try {
            Boot.shell(this, v.rows, v.cols)
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
            h.post { live = false; proc = null }
            out(NL + "[exited " + st + " - tap to restart]" + NL)
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        proc?.let { Pty.killPg(it.pid, 9); it.close() }
        proc = null
        wr.shutdownNow()
    }
}
