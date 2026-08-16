package io.spruky.debterm

import android.content.Context
import android.os.Build
import android.system.Os
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.util.zip.GZIPInputStream

/**
 * First launch unpacks the bundled rootfs; every launch after that just execs
 * proot. proot, its ptrace loader, libtalloc and libandroid-shmem ride in
 * jniLibs as lib*.so, because nativeLibraryDir is the one directory an app may
 * exec from. The argv/env below is proot-distro's, which is the combination
 * that is actually proven on Android.
 */
object Boot {
    private const val PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    fun rootfs(c: Context) = File(c.filesDir, "debian")
    fun tmp(c: Context) = File(c.filesDir, "tmp")
    fun ready(c: Context) = File(rootfs(c), ".installed").exists() && bash(c) != null

    private fun bash(c: Context): String? {
        val r = rootfs(c)
        for (p in arrayOf("usr/bin/bash", "bin/bash")) if (File(r, p).exists()) return "/" + p
        return null
    }

    /** One line of provenance, so a failure on a device we cannot reach is still readable. */
    fun diag(c: Context): String {
        val lib = File(c.applicationInfo.nativeLibraryDir)
        val miss = arrayOf("libproot.so", "libloader.so", "libtalloc.so", "libshmem.so")
            .filter { !File(lib, it).exists() }
        val s = StringBuilder("debterm: android ")
        s.append(Build.VERSION.RELEASE).append(" sdk ").append(Build.VERSION.SDK_INT)
        s.append(" ").append(Build.SUPPORTED_ABIS.firstOrNull() ?: "?")
        s.append(if (miss.isEmpty()) ", libs ok" else ", MISSING " + miss.joinToString(" "))
        s.append(", rootfs ").append(bash(c) ?: "MISSING")
        return s.append(NL).toString()
    }

    fun install(c: Context, log: (String) -> Unit) {
        val rfs = rootfs(c)
        if (rfs.exists()) { log("clearing a partial install" + NL); rmr(rfs) }
        rfs.mkdirs()
        tmp(c).mkdirs()
        log("unpacking debian bookworm" + NL)
        var n = 0
        c.assets.open("rootfs.tar.gz").use { a ->
            Tar(GZIPInputStream(BufferedInputStream(a, 1 shl 16), 1 shl 16)).extract(rfs) { k ->
                n = k
                if (k % 500 == 0) log("  " + k + " files" + CR)
            }
        }
        log("  " + n + " files" + NL)
        for (d in arrayOf("etc", "root", "tmp", "proc", "sys", "dev", "dev/shm", ".l2s")) File(rfs, d).mkdirs()
        File(rfs, "etc/resolv.conf").writeText("nameserver 1.1.1.1" + LF + "nameserver 8.8.8.8" + LF)
        File(rfs, "etc/hosts").writeText("127.0.0.1 localhost" + LF + "::1 localhost" + LF)
        try { Os.chmod(File(rfs, "tmp").absolutePath, 1023) } catch (e: Exception) {}
        if (bash(c) == null) throw IOException("rootfs incomplete: " + n + " files, no bash")
        File(rfs, ".installed").writeText("1")
        log("ready" + NL + NL)
    }

    private fun rmr(f: File) {
        val link = try { f.canonicalFile != f.absoluteFile } catch (e: IOException) { true }
        if (f.isDirectory && !link) f.listFiles()?.forEach { rmr(it) }
        f.delete()
    }

    /** [minimal] drops the flags a older/other proot build might not know. */
    fun shell(c: Context, rows: Int, cols: Int, minimal: Boolean): Proc {
        val lib = c.applicationInfo.nativeLibraryDir
        val r = rootfs(c).absolutePath
        File(r, ".l2s").mkdirs()
        tmp(c).mkdirs()
        val env = arrayOf(
            "PROOT_TMP_DIR=" + tmp(c).absolutePath,
            "PROOT_L2S_DIR=" + r + "/.l2s",
            "PROOT_LOADER=" + lib + "/libloader.so",
            "PROOT_LOADER_32=" + lib + "/libloader32.so",
            "LD_LIBRARY_PATH=" + lib,
            "HOME=/root",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "PATH=" + PATH
        )
        val a = ArrayList<String>(48)
        a.add(lib + "/libproot.so")
        a.add("--kill-on-exit")
        a.add("--link2symlink")
        if (!minimal) {
            a.add("--sysvipc")
            a.add("--ashmem-memfd")
            a.add("-L")
            a.add("--kernel-release=6.2.1")
        }
        a.add("--change-id=0:0")
        a.add("-r"); a.add(r)
        a.add("-w"); a.add("/root")
        for (b in arrayOf(
            "/dev", "/proc", "/sys",
            "/dev/urandom:/dev/random",
            "/proc/self/fd:/dev/fd",
            "/proc/self/fd/0:/dev/stdin",
            "/proc/self/fd/1:/dev/stdout",
            "/proc/self/fd/2:/dev/stderr",
            r + "/tmp:/dev/shm"
        )) { a.add("-b"); a.add(b) }
        a.add("/usr/bin/env"); a.add("-i")
        a.add("HOME=/root"); a.add("TERM=xterm-256color"); a.add("LANG=C.UTF-8")
        a.add("TMPDIR=/tmp"); a.add("PATH=" + PATH)
        a.add(bash(c) ?: "/bin/sh"); a.add("--login")
        val pid = IntArray(1)
        val fd = Pty.start(a[0], a.toTypedArray(), env, pid, rows, cols)
        if (fd < 0) throw IOException("pty/exec failed (" + fd + ")")
        return Proc(fd, pid[0])
    }
}
