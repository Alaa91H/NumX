package com.numx.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Numbering-plan support tiers shown in the country picker. */
enum class SupportTier {
    FULL,
    FIXED_ONLY,
    UNAVAILABLE;

    val labelAr: String
        get() = when (this) {
            FULL -> "خليوي + أرضي"
            FIXED_ONLY -> "أرضي فقط"
            UNAVAILABLE -> "لا تتوفر خطة ترقيم"
        }
}

/** The kind of range the user wants to generate. */
enum class LineType(val code: String, val labelAr: String, val labelEn: String) {
    MOBILE("MOBILE", "خليوي", "Mobile"),
    FIXED("FIXED", "أرضي", "Fixed"),
    BOTH("BOTH", "الاثنان", "Both");

    companion object {
        fun fromCode(value: String?): LineType =
            entries.firstOrNull { it.code == value } ?: MOBILE
    }
}

/** The exact classification of one generated number. */
enum class ActualLineType(
    val code: String,
    val labelAr: String,
    val csvValue: String,
    val vCardType: String,
) {
    MOBILE("MOBILE", "خليوي", "Mobile", "CELL"),
    FIXED("FIXED", "أرضي", "Landline", "VOICE"),
    AMBIGUOUS("FIXED_LINE_OR_MOBILE", "أرضي أو خليوي (غير متميز)", "Other", "VOICE");

    companion object {
        fun fromCode(value: String?): ActualLineType =
            entries.firstOrNull { it.code == value } ?: AMBIGUOUS
    }
}

data class Country(
    val iso2: String,
    val nameAr: String,
    val nameEn: String,
    val callingCode: String,
    val nationalPrefix: String?,
    val flag: String,
    val flagFallback: Boolean,
    val support: SupportTier,
    val hasMobile: Boolean,
    val hasFixed: Boolean,
    val mobileLengths: List<Int>,
    val fixedLengths: List<Int>,
    val mobileCount: Long,
    val fixedCount: Long,
    val bothCount: Long,
    val overlapCount: Long,
    val exampleMobile: String?,
    val exampleFixed: String?,
    val planVersion: Int,
    val mainCountry: String?,
    val sharedWith: List<String>,
) {
    val displayName: String get() = "$nameAr $flag"
    val internationalPrefix: String get() = "+$callingCode"

    fun supports(type: LineType): Boolean = when (type) {
        LineType.MOBILE -> hasMobile
        LineType.FIXED -> hasFixed
        LineType.BOTH -> hasMobile && hasFixed
    }

    fun countFor(type: LineType): Long = when (type) {
        LineType.MOBILE -> mobileCount
        LineType.FIXED -> fixedCount
        LineType.BOTH -> bothCount
    }

    fun lengthsFor(type: LineType): List<Int> = when (type) {
        LineType.MOBILE -> mobileLengths
        LineType.FIXED -> fixedLengths
        LineType.BOTH -> (mobileLengths + fixedLengths).distinct().sorted()
    }

    fun exampleFor(type: LineType): String? = when (type) {
        LineType.MOBILE -> exampleMobile
        LineType.FIXED -> exampleFixed
        LineType.BOTH -> exampleMobile ?: exampleFixed
    }

    /**
     * National numbers are stored with their national significant number (NSN).
     * Most plans write a leading 0 on SIM cards and in local dialling, while a
     * few plans (Italy, for example) make the leading 0 part of the NSN.
     */
    fun toLocalDisplay(nsn: String): String =
        if (nationalPrefix == "0" && !nsn.startsWith("0")) "0$nsn" else nsn
}

/** Removes all separators and converts Arabic-Indic digits to ASCII digits. */
fun nationalDigits(input: String): String =
    SyrianNumbers.normalizeDigits(input).filter { it in '0'..'9' }

/**
 * Accepts `+963...`, `00963...`, `963...`, `093...` or a bare NSN and returns
 * the candidate NSN that is feasible for [country]/[type]. The plan itself is
 * the final authority: an input that is not a feasible prefix is rejected.
 */
fun normalizeNationalInput(
    country: Country,
    type: LineType,
    input: String,
    isValidPrefix: (String) -> Boolean,
): String? {
    val raw = nationalDigits(input)
    if (raw.isEmpty()) return null
    val candidates = LinkedHashSet<String>()
    val trimmed = input.trim()
    val withoutIdd = when {
        trimmed.startsWith("+") -> raw
        trimmed.startsWith("00") -> raw.removePrefix("00")
        else -> null
    }
    if (withoutIdd != null &&
        withoutIdd.startsWith(country.callingCode) &&
        withoutIdd.length > country.callingCode.length
    ) {
        candidates.add(withoutIdd.removePrefix(country.callingCode))
    }
    candidates.add(raw)
    if (raw.startsWith(country.callingCode) &&
        raw.length > country.callingCode.length + 1
    ) {
        candidates.add(raw.removePrefix(country.callingCode))
    }
    val prefix = country.nationalPrefix
    if (!prefix.isNullOrEmpty() && raw.startsWith(prefix) && raw.length > prefix.length) {
        candidates.add(raw.removePrefix(prefix))
    }
    if (raw.startsWith("0") && raw.length > 1) {
        candidates.add(raw.substring(1))
    }
    return candidates.firstOrNull { it.isNotEmpty() && isValidPrefix(it) }
}

/**
 * A length-bounded deterministic finite automaton for one national numbering
 * plan. Every state belongs to exactly one national-number length, therefore
 * the state graph is acyclic and exact path counting is linear in the graph
 * size. Numbers are enumerated as digit strings, so leading zeros are kept.
 */
class DfaPlan(
    val iso2: String,
    val lineType: LineType,
    val planVersion: Int,
    val start: Int,
    private val accept: BooleanArray,
    private val transitions: Array<IntArray>,
    private val declaredLengthCounts: LongArray,
) {
    val stateCount: Int get() = transitions.size
    val minLength: Int get() = declaredLengthCounts.indexOfFirst { it > 0 }.let {
        if (it <= 0) 1 else it
    }
    val maxLength: Int get() = declaredLengthCounts.indexOfLast { it > 0 }.let {
        if (it <= 0) 0 else it
    }
    val lengthCounts: LongArray get() = declaredLengthCounts
    val total: Long get() = declaredLengthCounts.sum()

    private val exactMemo = Array(transitions.size) { LongArray(MAX_LENGTH + 1) { UNSET } }
    private val pathMemo = LongArray(transitions.size) { UNSET }

    fun stateAfter(digits: String): Int {
        var state = start
        for (ch in digits) {
            if (ch !in '0'..'9') return -1
            val next = transitions[state][ch - '0']
            if (next < 0) return -1
            state = next
        }
        return state
    }

    fun isFeasiblePrefix(digits: String): Boolean {
        val state = stateAfter(digits)
        return state >= 0 && countPaths(state) > 0
    }

    /** Number of accepted strings of exactly [remaining] digits from [state]. */
    fun countExact(state: Int, remaining: Int): Long {
        if (state < 0 || remaining < 0 || state >= transitions.size) return 0
        if (remaining == 0) return if (accept[state]) 1 else 0
        val cached = exactMemo[state][remaining]
        if (cached != UNSET) return cached
        var total = 0L
        val row = transitions[state]
        for (digit in 0..9) {
            val target = row[digit]
            if (target >= 0) total += countExact(target, remaining - 1)
        }
        exactMemo[state][remaining] = total
        return total
    }

    private fun countPaths(state: Int): Long {
        if (state < 0) return 0
        val cached = pathMemo[state]
        if (cached != UNSET) return cached
        var total = if (accept[state]) 1L else 0L
        val row = transitions[state]
        for (digit in 0..9) {
            val target = row[digit]
            if (target >= 0) total += countPaths(target)
        }
        pathMemo[state] = total
        return total
    }

    /** Counts accepted numbers at or after [prefix]. */
    fun countFrom(prefix: String): Long {
        val digits = prefix.filter { it in '0'..'9' }
        val state = stateAfter(digits)
        if (state < 0 || countPaths(state) == 0L) return 0L
        if (digits.length < minLength) return countPaths(state)
        if (digits.length > maxLength) return 0L
        var total = countAtLeastSameLength(start, digits, 0)
        for (length in digits.length + 1..maxLength) {
            total += declaredLengthCounts.getOrElse(length) { 0 }
        }
        return total
    }

    private fun countAtLeastSameLength(state: Int, bound: String, position: Int): Long {
        val remaining = bound.length - position
        if (remaining == 0) return if (accept[state]) 1 else 0
        var total = 0L
        val limit = bound[position] - '0'
        val row = transitions[state]
        // Numbers at or after the bound: only digits above the bound digit are
        // free, the bound digit itself stays tight and is followed recursively.
        for (digit in limit..9) {
            val target = row[digit]
            if (target < 0) continue
            if (digit > limit) {
                total += countExact(target, remaining - 1)
            } else {
                total += countAtLeastSameLength(target, bound, position + 1)
            }
        }
        return total
    }

    /**
     * Appends up to [limit] accepted numbers at or after [lowerBound], in
     * deterministic order: shorter national numbers first, then digit order.
     */
    fun collect(
        lowerBound: String,
        limit: Int,
        output: MutableList<String>,
    ) {
        if (limit <= 0) return
        val bound = lowerBound.filter { it in '0'..'9' }
        if (bound.isEmpty()) {
            for (length in minLength..maxLength) {
                collectRange(start, 0, length, null, false, StringBuilder(), output, limit)
                if (output.size >= limit) return
            }
            return
        }
        if (bound.length < minLength) {
            val state = stateAfter(bound)
            if (state < 0) return
            val prefix = StringBuilder(bound)
            for (length in minLength..maxLength) {
                if (length < bound.length) continue
                collectRange(state, bound.length, length, null, false, prefix, output, limit)
                if (output.size >= limit) return
            }
            return
        }
        for (length in bound.length..maxLength) {
            val tight = length == bound.length
            collectRange(
                start,
                0,
                length,
                if (tight) bound else null,
                tight,
                StringBuilder(),
                output,
                limit,
            )
            if (output.size >= limit) return
        }
    }

    private fun collectRange(
        state: Int,
        position: Int,
        targetLength: Int,
        bound: String?,
        tight: Boolean,
        prefix: StringBuilder,
        output: MutableList<String>,
        limit: Int,
    ) {
        if (output.size >= limit) return
        val remaining = targetLength - position
        if (remaining < 0) return
        if (remaining == 0) {
            if (accept[state]) output.add(prefix.toString())
            return
        }
        val first = if (tight && bound != null) bound[position] - '0' else 0
        val row = transitions[state]
        for (digit in first..9) {
            val target = row[digit]
            if (target < 0) continue
            if (countExact(target, remaining - 1) == 0L) continue
            prefix.append(('0'.code + digit).toChar())
            collectRange(
                target,
                position + 1,
                targetLength,
                bound,
                tight && digit == first,
                prefix,
                output,
                limit,
            )
            prefix.setLength(prefix.length - 1)
            if (output.size >= limit) return
        }
    }

    companion object {
        private const val UNSET = -1L
        private const val MAX_LENGTH = 15

        fun fromJson(value: JSONObject): DfaPlan {
            val iso = value.getString("iso")
            val type = LineType.fromCode(value.optString("type"))
            val transitionsJson = value.getJSONArray("transitions")
            val transitions = Array(transitionsJson.length()) { index ->
                val row = transitionsJson.getJSONArray(index)
                IntArray(10) { digit -> row.optInt(digit, -1) }
            }
            val acceptJson = value.getJSONArray("accept")
            val accept = BooleanArray(transitions.size)
            for (i in 0 until acceptJson.length()) {
                val state = acceptJson.getInt(i)
                if (state in accept.indices) accept[state] = true
            }
            val countsJson = value.getJSONArray("lengthCounts")
            val counts = LongArray(maxOf(16, countsJson.length() + 1))
            for (i in 0 until countsJson.length()) {
                counts[i + 1] = countsJson.getLong(i)
            }
            return DfaPlan(
                iso2 = iso,
                lineType = type,
                planVersion = value.optInt("planVersion", 1),
                start = value.optInt("start", 0),
                accept = accept,
                transitions = transitions,
                declaredLengthCounts = counts,
            )
        }

        /** Product automaton accepting only numbers accepted by both plans. */
        fun intersect(first: DfaPlan, second: DfaPlan, iso2: String): DfaPlan {
            val keys = HashMap<Long, Int>()
            val pairs = ArrayList<Pair<Int, Int>>()
            fun indexOf(a: Int, b: Int): Int {
                val key = (a.toLong() shl 32) or (b.toLong() and 0xffffffffL)
                return keys.getOrPut(key) {
                    pairs.add(a to b)
                    pairs.size - 1
                }
            }
            val startIndex = indexOf(first.start, second.start)
            val transitions = ArrayList<IntArray>()
            val accept = ArrayList<Boolean>()
            var cursor = 0
            while (cursor < pairs.size) {
                val (a, b) = pairs[cursor]
                // indexOf() may append new pair states while this state is
                // being expanded, so grow the output arrays lazily.
                if (cursor >= transitions.size) {
                    transitions.add(IntArray(10) { -1 })
                    accept.add(false)
                }
                accept[cursor] = first.acceptAt(a) && second.acceptAt(b)
                for (digit in 0..9) {
                    val ta = first.transition(a, digit)
                    val tb = second.transition(b, digit)
                    if (ta >= 0 && tb >= 0) {
                        transitions[cursor][digit] = indexOf(ta, tb)
                    }
                }
                cursor++
            }
            val counts = LongArray(16)
            val plan = DfaPlan(
                iso2 = iso2,
                lineType = LineType.BOTH,
                planVersion = maxOf(first.planVersion, second.planVersion),
                start = startIndex,
                accept = accept.toBooleanArray(),
                transitions = transitions.toTypedArray(),
                declaredLengthCounts = counts,
            )
            for (length in 1..15) {
                counts[length] = plan.countExact(plan.start, length)
            }
            return plan
        }
    }

    internal fun acceptAt(state: Int): Boolean =
        state in accept.indices && accept[state]

    internal fun transition(state: Int, digit: Int): Int =
        if (state in transitions.indices && digit in 0..9) transitions[state][digit] else -1
}

data class GeneratedNumber(
    val nsn: String,
    val e164: String,
    val lineType: ActualLineType,
)

/** Batched ordered cursor over one or two plans. */
interface NumberCursor {
    fun nextBatch(max: Int): List<GeneratedNumber>?
}

private class SingleCursor(
    private val plan: DfaPlan,
    start: String,
    private val lineType: ActualLineType,
    private val country: Country,
) : NumberCursor {
    private var bound = start.filter { it in '0'..'9' }
    private var done = false

    override fun nextBatch(max: Int): List<GeneratedNumber>? {
        if (done || max <= 0) return null
        val values = ArrayList<String>(minOf(max, 4096))
        plan.collect(bound, max, values)
        if (values.isEmpty()) {
            done = true
            return null
        }
        bound = incrementDecimal(values.last())
        if (values.last().length >= plan.maxLength && values.last().all { it == '9' }) {
            done = true
        }
        return values.map { nsn ->
            GeneratedNumber(
                nsn = nsn,
                e164 = "+${country.callingCode}$nsn",
                lineType = lineType,
            )
        }
    }
}

private class BufferedCursor(private val cursor: NumberCursor) {
    private val buffer = ArrayDeque<GeneratedNumber>()

    fun peek(): GeneratedNumber? {
        if (buffer.isEmpty()) {
            val batch = cursor.nextBatch(128) ?: return null
            buffer.addAll(batch)
        }
        return buffer.firstOrNull()
    }

    fun pop(): GeneratedNumber? {
        val value = peek() ?: return null
        buffer.removeFirst()
        return value
    }
}

private class MergedCursor(
    mobile: NumberCursor,
    fixed: NumberCursor,
) : NumberCursor {
    private val left = BufferedCursor(mobile)
    private val right = BufferedCursor(fixed)

    override fun nextBatch(max: Int): List<GeneratedNumber>? {
        if (max <= 0) return null
        val output = ArrayList<GeneratedNumber>(minOf(max, 4096))
        while (output.size < max) {
            val a = left.peek()
            val b = right.peek()
            if (a == null && b == null) break
            if (a == null) {
                output.add(right.pop()!!)
                continue
            }
            if (b == null) {
                output.add(left.pop()!!)
                continue
            }
            val comparison = compare(a.nsn, b.nsn)
            when {
                comparison < 0 -> output.add(left.pop()!!)
                comparison > 0 -> output.add(right.pop()!!)
                else -> {
                    left.pop()
                    right.pop()
                    output.add(
                        GeneratedNumber(
                            nsn = a.nsn,
                            e164 = a.e164,
                            lineType = ActualLineType.AMBIGUOUS,
                        )
                    )
                }
            }
        }
        return if (output.isEmpty()) null else output
    }

    private fun compare(a: String, b: String): Int {
        if (a.length != b.length) return a.length.compareTo(b.length)
        return a.compareTo(b)
    }
}

class NumberingEngine(private val context: Context) {
    private val plans = ConcurrentHashMap<String, DfaPlan>()
    private val intersections = ConcurrentHashMap<String, DfaPlan?>()

    fun plan(iso2: String, type: LineType): DfaPlan? {
        if (type == LineType.BOTH) return null
        val key = "${iso2}_${type.code}"
        plans[key]?.let { return it }
        val path = "metadata/plans/$key.json"
        val loaded = runCatching {
            context.assets.open(path).bufferedReader(Charsets.UTF_8).use { reader ->
                DfaPlan.fromJson(JSONObject(reader.readText()))
            }
        }.getOrNull() ?: return null
        plans[key] = loaded
        return loaded
    }

    fun intersection(iso2: String): DfaPlan? {
        if (intersections.containsKey(iso2)) return intersections[iso2]
        val mobile = plan(iso2, LineType.MOBILE)
        val fixed = plan(iso2, LineType.FIXED)
        val result = if (mobile != null && fixed != null) {
            DfaPlan.intersect(mobile, fixed, iso2)
        } else {
            null
        }
        intersections[iso2] = result
        return result
    }

    fun cursor(country: Country, type: LineType, start: String): NumberCursor? {
        return when (type) {
            LineType.MOBILE -> plan(country.iso2, LineType.MOBILE)?.let {
                SingleCursor(it, start, ActualLineType.MOBILE, country)
            }
            LineType.FIXED -> plan(country.iso2, LineType.FIXED)?.let {
                SingleCursor(it, start, ActualLineType.FIXED, country)
            }
            LineType.BOTH -> {
                val mobile = plan(country.iso2, LineType.MOBILE)
                val fixed = plan(country.iso2, LineType.FIXED)
                if (mobile == null || fixed == null) null
                else MergedCursor(
                    SingleCursor(mobile, start, ActualLineType.MOBILE, country),
                    SingleCursor(fixed, start, ActualLineType.FIXED, country),
                )
            }
        }
    }

    /** Number of numbers at or after [start] for the requested plan. */
    fun countFrom(country: Country, type: LineType, start: String): Long {
        val normalized = start.filter { it in '0'..'9' }
        return when (type) {
            LineType.MOBILE -> plan(country.iso2, LineType.MOBILE)?.countFrom(normalized) ?: 0
            LineType.FIXED -> plan(country.iso2, LineType.FIXED)?.countFrom(normalized) ?: 0
            LineType.BOTH -> {
                val mobile = plan(country.iso2, LineType.MOBILE) ?: return 0
                val fixed = plan(country.iso2, LineType.FIXED) ?: return 0
                val overlap = intersection(country.iso2)?.countFrom(normalized) ?: 0
                mobile.countFrom(normalized) + fixed.countFrom(normalized) - overlap
            }
        }
    }

    fun isValidStart(country: Country, type: LineType, start: String): Boolean {
        val digits = start.filter { it in '0'..'9' }
        if (digits.isEmpty()) return false
        return when (type) {
            LineType.MOBILE -> plan(country.iso2, LineType.MOBILE)?.isFeasiblePrefix(digits) == true
            LineType.FIXED -> plan(country.iso2, LineType.FIXED)?.isFeasiblePrefix(digits) == true
            LineType.BOTH ->
                plan(country.iso2, LineType.MOBILE)?.isFeasiblePrefix(digits) == true ||
                    plan(country.iso2, LineType.FIXED)?.isFeasiblePrefix(digits) == true
        }
    }

    fun firstNumber(country: Country, type: LineType): String? {
        fun firstFor(planType: LineType): String? {
            val plan = plan(country.iso2, planType) ?: return null
            val values = ArrayList<String>(1)
            plan.collect("", 1, values)
            return values.firstOrNull()
        }
        return when (type) {
            LineType.MOBILE -> firstFor(LineType.MOBILE)
            LineType.FIXED -> firstFor(LineType.FIXED)
            LineType.BOTH -> listOfNotNull(firstFor(LineType.MOBILE), firstFor(LineType.FIXED))
                .minWithOrNull(compareBy<String> { it.length }.thenBy { it })
        }
    }
}

fun incrementDecimal(value: String): String {
    if (value.isEmpty()) return "1"
    val chars = value.toCharArray()
    var index = chars.lastIndex
    while (index >= 0) {
        if (chars[index] != '9') {
            chars[index] = chars[index] + 1
            return String(chars)
        }
        chars[index] = '0'
        index--
    }
    return "1" + String(chars)
}
