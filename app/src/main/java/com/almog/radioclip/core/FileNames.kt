package com.almog.radioclip.core

import java.io.File
import java.io.IOException

/** Safe handling of file names that arrive from other devices (never trust them). */
object FileNames {
    const val MAX_NAME_LENGTH = 120
    private const val FALLBACK = "received_file"
    private const val FORBIDDEN = "<>:\"|?*"

    /** Strips any directory part and unsafe characters; never returns an empty or dot-only name. */
    fun sanitize(raw: String?): String {
        var name = raw.orEmpty().replace('\\', '/').substringAfterLast('/')
        name = name.filter { !it.isISOControl() && it !in FORBIDDEN }
        name = name.trim().trimStart('.').trimEnd('.', ' ')
        if (name.isEmpty()) return FALLBACK

        if (name.length > MAX_NAME_LENGTH) {
            val dot = name.lastIndexOf('.')
            val ext = if (dot > 0 && name.length - dot <= 12) name.substring(dot) else ""
            name = name.substring(0, MAX_NAME_LENGTH - ext.length) + ext
        }
        return name
    }

    /**
     * Creates a new, empty file in [dir] named after [rawName]. Existing files are never
     * overwritten: "a.txt" becomes "a (1).txt", "a (2).txt", ...
     */
    fun reserveUniqueFile(dir: File, rawName: String?): File {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create ${dir.path}")
        val clean = sanitize(rawName)
        val dot = clean.lastIndexOf('.')
        val base = if (dot > 0) clean.substring(0, dot) else clean
        val ext = if (dot > 0) clean.substring(dot) else ""
        val canonicalDir = dir.canonicalFile

        for (i in 0..9999) {
            val candidate = File(dir, if (i == 0) clean else "$base ($i)$ext")
            if (candidate.canonicalFile.parentFile != canonicalDir) {
                throw IOException("Refusing to write outside the receive folder")
            }
            if (candidate.createNewFile()) return candidate
        }
        throw IOException("Could not find a free file name for $clean")
    }
}
