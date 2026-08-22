package hmx.domain.logic

sealed interface LimitStatus {
    data object Ok : LimitStatus
    data class Warning(val usedPct: Int) : LimitStatus
    data object Exceeded : LimitStatus
}

object DataLimits {

    fun evaluate(totalBytes: Long, limitBytes: Long, warningPct: Int): LimitStatus {
        if (limitBytes <= 0) return LimitStatus.Ok
        val pct = ((totalBytes * 100) / limitBytes).toInt()
        return when {
            pct >= 100 -> LimitStatus.Exceeded
            pct >= warningPct.coerceIn(1, 99) -> LimitStatus.Warning(pct)
            else -> LimitStatus.Ok
        }
    }

    fun usedPct(totalBytes: Long, limitBytes: Long): Int {
        if (limitBytes <= 0) return 0
        return (((totalBytes * 100) / limitBytes).toInt()).coerceAtMost(100)
    }

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.2f GB".format(bytes.toDouble() / (1L shl 30))
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes.toDouble() / (1L shl 20))
        bytes >= 1L shl 10 -> "%.0f KB".format(bytes.toDouble() / (1L shl 10))
        else -> "$bytes B"
    }

    fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }
}
