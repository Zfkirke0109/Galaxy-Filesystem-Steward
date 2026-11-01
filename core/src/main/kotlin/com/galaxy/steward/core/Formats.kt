package com.galaxy.steward.core

import java.util.Locale

/** Human-readable byte counts using binary units, matching the Termux steward's `human()` helper. */
fun Long.humanBytes(): String {
    if (this < 1024) return "$this B"
    val units = arrayOf("KiB", "MiB", "GiB", "TiB")
    var value = this.toDouble() / 1024
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return String.format(Locale.US, if (value >= 100) "%.0f %s" else "%.1f %s", value, units[index])
}

fun Int.plural(one: String, many: String = one + "s"): String = "$this ${if (this == 1) one else many}"

fun Long.plural(one: String, many: String = one + "s"): String = "$this ${if (this == 1L) one else many}"

const val KIB = 1024L
const val MIB = 1024L * 1024L
const val GIB = 1024L * 1024L * 1024L
const val DAY_MS = 24L * 60L * 60L * 1000L

/** How long ago [then] was, for "Used 4 months ago": days, then weeks, months and years. */
fun ageText(then: Long, now: Long): String {
    val days = (now - then).coerceAtLeast(0) / DAY_MS
    return when {
        days < 1 -> "in the last day"
        days < 2 -> "yesterday"
        days < 14 -> "$days days ago"
        days < 61 -> "${days / 7} weeks ago"
        days < 730 -> "${days / 30} months ago"
        else -> "${days / 365} years ago"
    }
}
