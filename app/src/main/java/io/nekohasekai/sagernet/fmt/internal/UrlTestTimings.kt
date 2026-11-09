package io.nekohasekai.sagernet.fmt.internal

// Go duration ("10m0s", "1h30m", "90s", "500ms") to milliseconds; null when unparsable.
internal fun goDurationMillis(raw: String): Long? {
    val s = raw.trim()
    if (s.isEmpty()) return null
    if (s.all { it.isDigit() }) return s.toLongOrNull()?.times(1000) // bare number = seconds
    val re = Regex("(\\d+(?:\\.\\d+)?)(ms|s|m|h)")
    var pos = 0
    var total = 0.0
    for (m in re.findAll(s)) {
        if (m.range.first != pos) return null
        pos = m.range.last + 1
        val v = m.groupValues[1].toDouble()
        total += when (m.groupValues[2]) {
            "ms" -> v
            "s" -> v * 1000
            "m" -> v * 60_000
            else -> v * 3_600_000
        }
    }
    return if (pos == s.length) total.toLong() else null
}

// The core rejects a url-test group whose interval exceeds idle_timeout
// ("interval must be less or equal than idle_timeout"). Subscriptions set
// interval; the bean's idle default is 3m. Stretch idle to the interval.
internal fun urlTestIdleTimeoutFor(interval: String, idleTimeout: String): String {
    val i = goDurationMillis(interval) ?: return idleTimeout
    val idle = goDurationMillis(idleTimeout) ?: return interval
    return if (idle < i) interval else idleTimeout
}
