package com.almog.radioclip.core

/** Rules for what the clipboard sync is allowed to send. */
object ClipPolicy {
    const val MAX_TEXT_BYTES = 1 shl 20                 // 1 MiB, the same limit the Wi-Fi transport enforces

    enum class SendDecision { SEND, SKIP_DISABLED, SKIP_EMPTY, SKIP_DUPLICATE, SKIP_SENSITIVE, SKIP_TOO_LARGE }

    /**
     * Decides whether a clipboard change is sent automatically.
     * [lastSynced] is the text we last sent or received, so a copy we just received is never echoed back.
     */
    fun decide(text: String?, lastSynced: String?, isSensitive: Boolean, settings: Settings): SendDecision {
        if (!settings.clipboardAutoSend) return SendDecision.SKIP_DISABLED
        if (text.isNullOrBlank()) return SendDecision.SKIP_EMPTY
        if (text == lastSynced) return SendDecision.SKIP_DUPLICATE
        if (isSensitive && settings.clipboardSkipSensitive) return SendDecision.SKIP_SENSITIVE
        if (text.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES) return SendDecision.SKIP_TOO_LARGE
        return SendDecision.SEND
    }

    /** One-line, length-limited version of [text] for notifications and lists. */
    fun preview(text: String, maxChars: Int = 60): String {
        val oneLine = text.trim().replace(Regex("\\s+"), " ")
        return if (oneLine.length <= maxChars) oneLine else oneLine.take(maxChars - 1).trimEnd() + "…"
    }
}

/** Remembers the most recent message ids so the same message arriving over two transports is handled once. */
class RecentIds(private val capacity: Int = 128) {
    private val ids = object : LinkedHashMap<String, Unit>(capacity, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > capacity
    }

    /** Returns true the first time [id] is seen, false for repeats. */
    @Synchronized
    fun firstSeen(id: String): Boolean = ids.put(id, Unit) == null
}
