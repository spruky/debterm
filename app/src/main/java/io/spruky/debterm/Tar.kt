package io.spruky.debterm

import android.system.Os
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Streaming ustar/GNU/pax reader. Android has no tar and the rootfs binaries
 * are useless without their modes and symlinks, so we unpack it ourselves.
 */
class Tar(private val ins: InputStream) {
    private val h = ByteArray(512)
    private val buf = ByteArray(1 shl 16)

    private fun readFully(b: ByteArray, n: Int): Boolean {
        var o = 0
        while (o < n) {
            val r = ins.read(b, o, n - o)
            if (r < 0) return false
            o += r
        }
        return true
    }

    private fun str(off: Int, len: Int): String {
        var e = off
        val end = off + len
        while (e < end && h[e] != 0.toByte()) e++
        return String(h, off, e - off, Charsets.UTF_8)
    }

    private fun oct(off: Int, len: Int): Long {
        val s = str(off, len).trim()
        return if (s.isEmpty()) 0 else try { java.lang.Long.parseLong(s, 8) } catch (e: NumberFormatException) { 0 }
    }

    private fun skip(n: Long) {
        var left = n
        while (left > 0) {
            val r = ins.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
            if (r <= 0) return
            left -= r
        }
    }

    private fun pad(n: Long) {
        val r = (n % 512).toInt()
        if (r != 0) skip((512 - r).toLong())
    }

    private fun text(n: Long): String {
        val b = ByteArray(n.toInt())
        readFully(b, b.size)
        pad(n)
        return String(b, Charsets.UTF_8)
    }

    private fun chmod(f: File, mode: Int) {
        try { Os.chmod(f.absolutePath, if (mode == 0) 384 else mode and 4095) } catch (e: Exception) {}
    }

    private fun strip(p0: String): String {
        var p = p0
        while (p.startsWith("./")) p = p.substring(2)
        p = p.trimStart('/')
        if (p.isEmpty() || p == "." || p.contains("..")) return ""
        return if (p.substringBefore('/') in ROOTS) p else p.substringAfter('/', "")
    }

    fun extract(dest: File, prog: (Int) -> Unit) {
        var lname: String? = null
        var llink: String? = null
        var count = 0
        while (true) {
            if (!readFully(h, 512)) break
            var zero = true
            for (b in h) if (b != 0.toByte()) { zero = false; break }
            if (zero) break

            val size = oct(124, 12)
            val mode = oct(100, 8).toInt()
            val type = h[156].toInt().toChar()
            val pre = str(345, 155)
            val name = lname ?: if (pre.isEmpty()) str(0, 100) else pre + "/" + str(0, 100)
            val link = llink ?: str(157, 100)
            lname = null
            llink = null

            if (type == 'L') { lname = text(size).trimEnd(0.toChar()); continue }
            if (type == 'K') { llink = text(size).trimEnd(0.toChar()); continue }
            if (type == 'x' || type == 'g') {
                for (rec in text(size).split(LF)) {
                    val eq = rec.indexOf('=')
                    if (eq < 0) continue
                    val k = rec.substring(0, eq).substringAfter(' ')
                    val v = rec.substring(eq + 1)
                    if (k == "path") lname = v else if (k == "linkpath") llink = v
                }
                continue
            }

            val file = type == '0' || type == 0.toChar() || type == '7'
            val rel = strip(name)
            if (rel.isEmpty()) {
                skip(size); pad(size); continue
            }
            val f = File(dest, rel)
            when {
                type == '5' -> { f.mkdirs(); chmod(f, mode or 448) }
                type == '2' -> {
                    f.parentFile?.mkdirs(); f.delete()
                    try { Os.symlink(link, f.absolutePath) } catch (e: Exception) {}
                }
                type == '1' -> {
                    f.parentFile?.mkdirs(); f.delete()
                    val t = File(dest, strip(link))
                    try { Os.link(t.absolutePath, f.absolutePath) } catch (e: Exception) {
                        try { t.copyTo(f, true) } catch (e2: Exception) {}
                    }
                }
                file -> {
                    f.parentFile?.mkdirs()
                    BufferedOutputStream(FileOutputStream(f), 1 shl 16).use { o ->
                        var left = size
                        while (left > 0) {
                            val r = ins.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
                            if (r <= 0) break
                            o.write(buf, 0, r)
                            left -= r
                        }
                    }
                    chmod(f, mode)
                }
            }
            if (!file) skip(size)
            pad(size)
            count++
            prog(count)
        }
    }

    companion object {
        private val ROOTS = setOf(
            "bin", "boot", "dev", "etc", "home", "lib", "lib32", "lib64", "libx32", "media",
            "mnt", "opt", "proc", "root", "run", "sbin", "srv", "sys", "tmp", "usr", "var"
        )
    }
}
