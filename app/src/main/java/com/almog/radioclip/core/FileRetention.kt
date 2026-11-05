package com.almog.radioclip.core

import java.io.File

/** Deletes old received files, if the user turned on auto-delete. */
object FileRetention {
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** Files in [files] that are older than [days] days. `days <= 0` means "keep everything". */
    fun expired(files: List<File>, days: Int, now: Long): List<File> {
        if (days <= 0) return emptyList()
        val limit = days * DAY_MS
        return files.filter { it.isFile && now - it.lastModified() > limit }
    }

    /** Deletes expired files directly inside [dir] and returns how many were removed. */
    fun cleanup(dir: File, days: Int, now: Long = System.currentTimeMillis()): Int {
        val files = dir.listFiles()?.toList() ?: return 0
        return expired(files, days, now).count { it.delete() }
    }
}
