package com.example.flowdl.ui

import java.util.Locale
import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.roundToLong

const val MAX_LIMIT_BPS: Long = 32L * 1024L * 1024L
private const val MIN_LIMIT_BPS: Long = 64L * 1024L

fun formatBytes(bytes: Long): String {
    if (bytes < 0L) return "—"
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}

fun formatSpeed(bps: Long): String = if (bps <= 0L) "0 B/s" else formatBytes(bps) + "/s"

fun formatEta(seconds: Long): String {
    if (seconds < 0L) return "—"
    if (seconds < 60L) return "${seconds}s"
    val minutes = seconds / 60L
    if (minutes < 60L) return "${minutes}m ${seconds % 60L}s"
    return "${minutes / 60L}h ${minutes % 60L}m"
}

fun limitToFraction(bps: Long): Float {
    if (bps <= 0L) return 0f
    return cbrt(bps.toDouble() / MAX_LIMIT_BPS.toDouble()).toFloat().coerceIn(0f, 1f)
}

fun fractionToBps(fraction: Float): Long {
    if (fraction <= 0.015f) return 0L
    val raw = MAX_LIMIT_BPS.toDouble() * fraction.toDouble().pow(3.0)
    val rounded = (raw / MIN_LIMIT_BPS).roundToLong() * MIN_LIMIT_BPS
    return rounded.coerceIn(MIN_LIMIT_BPS, MAX_LIMIT_BPS)
}
