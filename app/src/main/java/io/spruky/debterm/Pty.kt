package io.spruky.debterm

import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

object Pty {
    init { System.loadLibrary("pty") }

    /** Returns the pty master fd, or -1. Child pid lands in pid[0]. */
    external fun start(cmd: String, argv: Array<String>, envp: Array<String>, pid: IntArray, rows: Int, cols: Int): Int
    external fun setSize(fd: Int, rows: Int, cols: Int)
    external fun waitFor(pid: Int): Int
    external fun killPg(pid: Int, sig: Int)
}

class Proc(val fd: Int, val pid: Int) {
    private val pfd: ParcelFileDescriptor = ParcelFileDescriptor.adoptFd(fd)
    val inp = FileInputStream(pfd.fileDescriptor)
    val out = FileOutputStream(pfd.fileDescriptor)

    fun close() {
        try { pfd.close() } catch (_: IOException) {}
    }
}
