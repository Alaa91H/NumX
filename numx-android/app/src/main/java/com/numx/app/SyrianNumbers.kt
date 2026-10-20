package com.numx.app

import java.util.Locale

object SyrianNumbers {
    // Local 10-digit mobile prefixes allowed by Syrian mobile operators.
    // Syriatel: 93, 98, 99. MTN Syria: 94, 95, 96. Wafa Telecom: 91, 92.
    // 90 and 97 are deliberately excluded because they are not allocated to a Syrian mobile operator.
    val allowedPrefixes: IntArray = intArrayOf(91, 92, 93, 94, 95, 96, 98, 99)
    private val allowedPrefixSet = allowedPrefixes.toSet()

    fun normalizeDigits(input: String): String = buildString(input.length) {
        for (ch in input) {
            when (ch) {
                in '\u0660'..'\u0669' -> append('0' + (ch - '\u0660'))
                in '\u06F0'..'\u06F9' -> append('0' + (ch - '\u06F0'))
                else -> append(ch)
            }
        }
    }

    fun normalize(input: String): String? {
        var s = normalizeDigits(input).trim()
            .replace(" ", "").replace("-", "")
            .replace("(0)", "").replace("(", "").replace(")", "")
        if (s.startsWith("+")) s = s.substring(1)
        if (s.startsWith("00")) s = s.substring(2)
        s = when {
            s.startsWith("9630") -> "0" + s.substring(4)
            s.startsWith("963") -> "0" + s.substring(3)
            else -> s
        }
        if (s.length == 9 && s.all { it.isDigit() } && s.startsWith("9")) {
            s = "0$s"
        }
        if (s.length != 10 || !s.all { it.isDigit() } || !s.startsWith("0")) return null
        val prefix = s.substring(1, 3).toInt()
        if (prefix !in allowedPrefixSet) return null
        return s
    }

    fun isValid(number: String): Boolean = normalize(number) != null

    /**
     * Returns the first valid local number greater than [number], skipping
     * unallocated prefixes, or null when the allocated range is exhausted.
     */
    fun nextAfter(number: String): String? {
        val normalized = normalize(number) ?: return null
        var nsn = normalized.substring(1).toLong() + 1
        while (nsn <= 999_999_999L) {
            val candidate = "0" + nsn.toString().padStart(9, '0')
            if (isValid(candidate)) return candidate
            val prefix = candidate.substring(1, 3).toIntOrNull() ?: return null
            val nextPrefix = allowedPrefixes.firstOrNull { it > prefix } ?: return null
            nsn = nextPrefix * 10_000_000L
        }
        return null
    }

    fun formatDisplay(number: String): String {
        val n = normalize(number) ?: return number
        return "${n.substring(0, 3)} ${n.substring(3, 6)} ${n.substring(6)}"
    }

    fun operatorFor(number: String): String? {
        val n = normalize(number) ?: return null
        return when (n.substring(1, 3)) {
            "91", "92" -> "Wafa"
            "93", "98", "99" -> "Syriatel"
            "94", "95", "96" -> "MTN"
            else -> null
        }
    }

    fun formatCount(value: Long): String = String.format(Locale.US, "%,d", value)

    fun formatDuration(ms: Long): String {
        if (ms <= 0) return "0 ث"
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return when {
            h > 0 -> "${h} س ${m} د ${s} ث"
            m > 0 -> "${m} د ${s} ث"
            else -> "${s} ث"
        }
    }
}

/**
 * Generates only valid Syrian mobile numbers, in ascending order, starting from a valid local number.
 * It jumps directly over unallocated prefixes instead of testing every number.
 */
class SyrianNumberCursor(startLocal: String) {
    private var prefixIndex: Int
    private var suffix: Int
    private val chars = CharArray(10)

    init {
        require(startLocal.length == 10 && startLocal[0] == '0') { "Invalid local number" }
        val startNsn = startLocal.substring(1).toInt()
        val startPrefix = startNsn / 10_000_000
        val idx = SyrianNumbers.allowedPrefixes.indexOf(startPrefix)
        require(idx >= 0) { "Prefix is not allowed" }
        prefixIndex = idx
        suffix = startNsn % 10_000_000
    }

    /** Returns up to [max] numbers, or null when all allocated numbers have been exhausted. */
    fun nextBatch(max: Int): List<String>? {
        if (prefixIndex >= SyrianNumbers.allowedPrefixes.size) return null
        if (max <= 0) return emptyList()
        val out = ArrayList<String>(minOf(max, 4096))
        var remaining = max
        while (remaining > 0 && prefixIndex < SyrianNumbers.allowedPrefixes.size) {
            val prefix = SyrianNumbers.allowedPrefixes[prefixIndex]
            val available = 10_000_000 - suffix
            val take = minOf(remaining, available)
            val base = prefix * 10_000_000 + suffix
            for (i in 0 until take) {
                out.add(format(base + i))
            }
            suffix += take
            remaining -= take
            if (suffix >= 10_000_000) {
                prefixIndex++
                suffix = 0
            }
        }
        return if (out.isEmpty()) null else out
    }

    private fun format(nsn: Int): String {
        chars[0] = '0'
        var v = nsn
        var i = 9
        while (i >= 1) {
            chars[i] = ('0' + (v % 10))
            v /= 10
            i--
        }
        return String(chars)
    }
}