package io.spruky.debterm

import android.content.Context
import android.system.Os
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.util.zip.GZIPInputStream

/**
 * First launch unpacks the bundled rootfs; every launch after that just execs
 * proot. proot, its ptrace loader and libtalloc ride in jniLibs as lib*.so,
 * because nativeLibraryDir is the only directory an app may exec from.
 */
object Boot {
    private const val PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    fun rootfs(c: Context) = File(c.filesDir, "debian")
    fun tmp(c: Context) = File(c.filesDir, "tmp")
    fun ready(c: Context) = File(rootfs(c), ".installed").exists()

    fun install(c: Context, log: (String) -> Unit) {
        val rfs = rootfs(c)
        if (rfs.exists()) { log("clearing a partial install" + NL); rmr(rfs) }
        rfs.mkdirs()
        tmp(c).mkdirs()
        log("unpacking debian bookworm" + NL)
        c.assets.open("rootfs.tar.gz").use { a ->
            Tar(GZIPInputStream(BufferedInputStream(a, 1 shl 16), 1 shl 16)).extract(rfs) { n ->
                if (n % 500 == 0) log("  " + n + " files" + CR)
            }
        }
        for (d in arrayOf("etc", "root", "tmp", "proc", "sys", "dev", "dev/shm")) File(rfs, d).mkdirs()
        File(rfs, "etc/resolv.conf").writeText("nameserver 1.1.1.1" + LF + "nameserver 8.8.8.8" + LF)
        File(rfs, "etc/hosts").writeText("127.0.0.1 localhost" + LF + "::1 localhost" + LF)
        try { Os.chmod(File(rfs, "tmp").absolutePath, 1023) } catch (e: Exception) {}
        File(rfs, ".installed").writeText("1")
        log(NL + "ready" + NL + NL)
    }

    private fun rmr(f: File) {
        val link = try { f.canonicalFile != f.absoluteFile } catch (e: IOException) { true }
        if (f.isDirectory && !link) f.listFiles()?.forEach { rmr(it) }
        f.delete()
    }

    fun shell(c: Context, rows: Int, cols: Int): Proc {
        val lib = c.applicationInfo.nativeLibraryDir
        val r = rootfs(c).absolutePath
        val env = arrayOf(
            "PROOT_TMP_DIR=" + tmp(c).absolutePath,
            "PROOT_LOADER=" + lib + "/libloader.so",
            "PROOT_LOADER_32=" + lib + "/libloader32.so",
            "LD_LIBRARY_PATH=" + lib,
            "HOME=/root",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "PATH=" + PATH
        )
        val argv = arrayOf(
            lib + "/libproot.so",
            "--kill-on-exit", "--link2symlink",
            "-0", "-r", r, "-w", "/root",
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "/dev/urandom:/dev/random",
            "-b", "/proc/self/fd:/dev/fd",
            "-b", "/proc/self/fd/0:/dev/stdin",
            "-b", "/proc/self/fd/1:/dev/stdout",
            "-b", "/proc/self/fd/2:/dev/stderr",
            "-b", r + "/tmp:/dev/shm",
            "/usr/bin/env", "-i",
            "HOME=/root", "TERM=xterm-256color", "LANG=C.UTF-8", "TMPDIR=/tmp", "PATH=" + PATH,
            "/bin/bash", "--login"
        )
        val pid = IntArray(1)
        val fd = Pty.start(argv[0], argv, env, pid, rows, cols)
        if (fd < 0) throw IOException("pty/exec failed (" + fd + ")")
        return Proc(fd, pid[0])
    }
}
