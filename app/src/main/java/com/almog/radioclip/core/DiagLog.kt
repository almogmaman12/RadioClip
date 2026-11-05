package com.almog.radioclip.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Fixed-size, thread-safe list of recent log lines, newest last. */
class LogBuffer(
    private val capacity: Int = 400,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val lines = ArrayDeque<String>()
    private val format = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun add(line: String) {
        if (lines.size >= capacity) lines.removeFirst()
        lines.addLast("${format.format(Date(clock()))}  $line")
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    @Synchronized
    fun clear() = lines.clear()
}

/**
 * App-wide diagnostics. The Settings screen shows these lines so connection problems can be
 * understood (and shared) without a computer. Clipboard contents are never logged.
 */
object DiagLog {
    val buffer = LogBuffer()

    /** Set by the app to forward lines to Android's logcat as well. */
    @Volatile
    var sink: ((tag: String, message: String) -> Unit)? = null

    fun d(tag: String, message: String) {
        buffer.add("$tag: $message")
        sink?.invoke(tag, message)
    }

    /** Shortens a Bluetooth/Wi-Fi address so shared logs don't expose full hardware addresses. */
    fun mask(address: String?): String = if (address.isNullOrEmpty()) "?" else "…" + address.takeLast(5)
}
