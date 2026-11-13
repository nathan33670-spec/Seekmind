package fr.corsicajack.seekmind.engine.privacy.redaction

import fr.corsicajack.seekmind.core.model.Redaction

/**
 * Replaces card numbers, IBANs and French national insurance numbers by [mark] (docs/specs/confidentialite.md
 * §5, PG-11 to PG-13). Each candidate is replaced only when its checksum holds, so that tracking and order
 * numbers stay readable (§5.4). Idempotent: the mark has no digit, nothing in it matches again.
 */
class Redactor(private val mark: String) {
    init {
        require(mark.none(Char::isLetterOrDigit)) { "the mark must not match a pattern" }
    }

    fun redact(text: String): Redaction {
        val spans = (cards(text) + ibans(text) + nirs(text)).sortedBy { it.first }
        if (spans.isEmpty()) return Redaction(text, 0)
        val merged = mutableListOf<IntRange>()
        for (span in spans) {
            val last = merged.lastOrNull()
            if (last != null && span.first <= last.last + 1) {
                merged[merged.lastIndex] = last.first..maxOf(last.last, span.last)
            } else {
                merged += span
            }
        }
        val out = StringBuilder(text)
        for (span in merged.asReversed()) out.replace(span.first, span.last + 1, mark)
        return Redaction(out.toString(), merged.size)
    }

    /**
     * §5.1: plausible (prefix, length), then Luhn. The candidate pattern is greedy and takes in a number
     * that touches the card ("4111 1111 1111 1111 12/27"): when the whole candidate fails, every run of
     * contiguous digit groups inside it is tried, longest first, from left to right.
     */
    private fun cards(text: String): List<IntRange> = CARD.findAll(text).flatMap { match ->
        if (isCard(match.value)) return@flatMap sequenceOf(match.range)
        val groups = GROUP.findAll(match.value).map { it.range }.toList()
        val found = mutableListOf<IntRange>()
        var i = 0
        while (i < groups.size) {
            val j = (groups.lastIndex downTo i).firstOrNull { j ->
                isCard(match.value.substring(groups[i].first, groups[j].last + 1))
            }
            if (j == null) {
                i++
            } else {
                found += (match.range.first + groups[i].first)..(match.range.first + groups[j].last)
                i = j + 1
            }
        }
        found.asSequence()
    }.toList()

    private fun isCard(candidate: String): Boolean {
        val digits = candidate.filter(Char::isDigit)
        return digits.length in 13..19 && plausibleCard(digits) && luhn(digits)
    }

    /** §5.2: exact length for known countries, then modulo 97. */
    private fun ibans(text: String): List<IntRange> {
        // Upper-casing char by char keeps the indices of the original text.
        val upper = String(CharArray(text.length) { text[it].uppercaseChar() })
        return IBAN.findAll(upper).mapNotNull { match ->
            val country = match.value.substring(0, 2)
            val expected = IBAN_LENGTHS[country]
            // A known length cuts what the pattern took beyond it ("FR76 … 189 EUR").
            val end = if (expected == null) match.range.last else endAfter(upper, match.range.first, expected)
            if (end < 0) return@mapNotNull null
            val compact = upper.substring(match.range.first, end + 1).filterNot { it == ' ' }
            val lengthOk = if (expected == null) compact.length in 15..34 else compact.length == expected
            (match.range.first..end).takeIf { lengthOk && mod97(compact) }
        }.toList()
    }

    /** §5.3: key = 97 − (13 first characters mod 97), with 2A → 19 and 2B → 18. */
    private fun nirs(text: String): List<IntRange> {
        val upper = String(CharArray(text.length) { text[it].uppercaseChar() })
        return NIR.findAll(upper).mapNotNull { match ->
            val compact = match.value.filterNot { it == ' ' }
            val body = compact.substring(0, 13).replace("2A", "19").replace("2B", "18")
            val key = compact.substring(13).toInt()
            match.range.takeIf { body.toLong() % 97 == (97 - key).toLong() }
        }.toList()
    }

    /** Index of the [count]-th alphanumeric character from [start], or -1 when the text ends before. */
    private fun endAfter(text: String, start: Int, count: Int): Int {
        var seen = 0
        for (i in start until text.length) {
            if (text[i].isLetterOrDigit()) seen++
            if (seen == count) return i
        }
        return -1
    }

    private companion object {
        val CARD = Regex("(?<![\\d])(?:\\d[ \\-]?){12,18}\\d(?![\\d])")
        val IBAN = Regex("\\b[A-Z]{2}\\d{2}(?:[ ]?[A-Z0-9]{4}){2,7}(?:[ ]?[A-Z0-9]{1,4})?\\b")
        val NIR = Regex(
            "\\b[12][ ]?\\d{2}[ ]?(?:0[1-9]|1[0-2]|[2-9]\\d)[ ]?(?:\\d{2}|2[AB])[ ]?\\d{3}[ ]?\\d{3}[ ]?\\d{2}\\b",
        )
        val GROUP = Regex("\\d+")

        /** ISO 13616 registry: IBAN length by country. */
        val IBAN_LENGTHS = mapOf(
            "AD" to 24, "AE" to 23, "AL" to 28, "AT" to 20, "AZ" to 28, "BA" to 20, "BE" to 16, "BG" to 22,
            "BH" to 22, "BR" to 29, "BY" to 28, "CH" to 21, "CR" to 22, "CY" to 28, "CZ" to 24, "DE" to 22,
            "DK" to 18, "DO" to 28, "EE" to 20, "EG" to 29, "ES" to 24, "FI" to 18, "FO" to 18, "FR" to 27,
            "GB" to 22, "GE" to 22, "GI" to 23, "GL" to 18, "GR" to 27, "GT" to 28, "HR" to 21, "HU" to 28,
            "IE" to 22, "IL" to 23, "IQ" to 23, "IS" to 26, "IT" to 27, "JO" to 30, "KW" to 30, "KZ" to 20,
            "LB" to 28, "LC" to 32, "LI" to 21, "LT" to 20, "LU" to 20, "LV" to 21, "MC" to 27, "MD" to 24,
            "ME" to 22, "MK" to 19, "MR" to 27, "MT" to 31, "MU" to 30, "NL" to 18, "NO" to 15, "PK" to 24,
            "PL" to 28, "PS" to 29, "PT" to 25, "QA" to 29, "RO" to 24, "RS" to 22, "SA" to 24, "SC" to 31,
            "SE" to 24, "SI" to 19, "SK" to 24, "SM" to 27, "ST" to 25, "SV" to 28, "TL" to 23, "TN" to 24,
            "TR" to 26, "UA" to 29, "VA" to 22, "VG" to 24, "XK" to 20,
        )

        fun plausibleCard(digits: String): Boolean {
            val length = digits.length
            fun prefix(n: Int) = digits.take(n).toInt()
            return when {
                digits.startsWith("4") -> length == 16
                prefix(2) in 51..55 || prefix(4) in 2221..2720 -> length == 16
                prefix(2) == 34 || prefix(2) == 37 -> length == 15
                digits.startsWith("6011") || digits.startsWith("65") -> length == 16
                digits.startsWith("62") -> length in 16..19
                else -> false
            }
        }

        fun luhn(digits: String): Boolean {
            var sum = 0
            for (i in digits.indices) {
                var d = digits[digits.length - 1 - i] - '0'
                if (i % 2 == 1) {
                    d *= 2
                    if (d > 9) d -= 9
                }
                sum += d
            }
            return sum % 10 == 0
        }

        /** ISO 13616: the four first characters moved to the end, letters as 10–35, modulo 97 equals 1. */
        fun mod97(iban: String): Boolean {
            var remainder = 0
            for (c in iban.substring(4) + iban.substring(0, 4)) {
                val value = when (c) {
                    in '0'..'9' -> c - '0'
                    in 'A'..'Z' -> c - 'A' + 10
                    else -> return false
                }
                remainder = if (value >= 10) (remainder * 100 + value) % 97 else (remainder * 10 + value) % 97
            }
            return remainder == 1
        }
    }
}
