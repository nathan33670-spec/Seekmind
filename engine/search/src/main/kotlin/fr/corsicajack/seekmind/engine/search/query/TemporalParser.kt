package fr.corsicajack.seekmind.engine.search.query

import fr.corsicajack.seekmind.core.model.TimeRange
import java.text.Normalizer
import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/**
 * The time expressions of docs/specs/recherche.md §3, in French and in English (ticket M5-02). Pure
 * Kotlin. Bounds are computed in the zone of the query; the interval is inclusive at both ends.
 *
 * @param monthFirst numeric dates read month/day (English of the United States); day/month otherwise.
 */
class TemporalParser(private val monthFirst: Boolean = false) : TemporalExtractor {
    override fun extract(text: String, now: Long, zone: ZoneId): TemporalMatch? {
        val words = words(text)
        if (words.isEmpty()) return null
        val context = Context(Instant.ofEpochMilli(now).atZone(zone), zone)
        var best: Found? = null
        for (start in words.indices) {
            val found = matchAt(words, start, context) ?: continue
            if (best == null || found.length > best.length) best = found
        }
        val match = best ?: return null
        var first = match.start
        repeat(MAX_LINKING) { if (first > 0 && words[first - 1].norm in LINKING) first-- }
        val last = words[match.start + match.length - 1]
        val from = words[first].begin
        return TemporalMatch(
            range = match.range,
            label = text.substring(words[match.start].begin, last.end),
            remaining = text.substring(0, from) + " " + text.substring(last.end),
        )
    }

    /** A word of the query: its position in the text and its comparison form. */
    private class Word(val begin: Int, val end: Int, val norm: String)

    private class Found(val start: Int, val length: Int, val range: TimeRange)

    private class Context(val now: ZonedDateTime, val zone: ZoneId) {
        val today: LocalDate = now.toLocalDate()
        val nowMs: Long = now.toInstant().toEpochMilli()

        fun start(date: LocalDate, hour: Int = 0): Long = date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

        fun days(first: LocalDate, last: LocalDate) = TimeRange(start(first), start(last.plusDays(1)) - 1)

        fun day(date: LocalDate) = days(date, date)

        fun untilNow(from: Long) = TimeRange(from, nowMs)

        fun moment(date: LocalDate, moment: Moment): TimeRange {
            val endDate = if (moment.nextDay) date.plusDays(1) else date
            return TimeRange(start(date, moment.from), start(endDate, moment.to) - 1)
        }
    }

    enum class Moment(val from: Int, val to: Int, val nextDay: Boolean) {
        MORNING(5, 12, false),
        NOON(11, 14, false),
        AFTERNOON(12, 18, false),
        EVENING(18, 2, true),
        NIGHT(22, 6, true),
    }

    /** A day expression: the day, and the number of words it spans. */
    private class DayMatch(val date: LocalDate, val length: Int)

    private fun matchAt(words: List<Word>, at: Int, c: Context): Found? {
        fun w(offset: Int): String? = words.getOrNull(at + offset)?.norm
        val candidates = ArrayList<Found>(4)
        fun add(length: Int, range: TimeRange?) {
            if (range != null && length > 0) candidates += Found(at, length, range)
        }

        // A day, possibly followed by a moment of the day.
        dayAt(words, at, c)?.let { day ->
            add(day.length, c.day(day.date))
            momentAfter(words, at + day.length)?.let { (moment, length) ->
                add(day.length + length, c.moment(day.date, moment))
            }
        }

        // "ce matin", "cette nuit", "this morning", "tonight", "last night".
        when (w(0)) {
            "ce", "cet", "cette", "this" -> MOMENTS[w(1)]?.let { moment ->
                val date = if (moment == Moment.NIGHT && c.now.hour < 12) c.today.minusDays(1) else c.today
                add(2, c.moment(date, moment))
            }

            "tonight" -> add(1, c.moment(c.today, Moment.EVENING))

            "last" -> if (w(1) == "night") add(2, c.moment(c.today.minusDays(1), Moment.NIGHT))
        }

        // Short windows.
        if (w(0) == "tout" && w(1) == "a" && w(2) == "l'heure") add(3, c.hoursBack(6))
        if (w(0) == "earlier") add(1, c.hoursBack(6))
        if (w(0) == "a" && w(1) == "moment" && w(2) == "ago") add(3, c.hoursBack(6))
        if (w(0) in RECENTLY) add(1, c.hoursBack(14 * 24))

        // Weeks.
        val monday = c.today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val lastWeek = c.days(monday.minusDays(7), monday.minusDays(1))
        val thisWeek = (w(0) == "cette" && w(1) == "semaine") || (w(0) == "this" && w(1) == "week")
        if (thisWeek) add(2, c.untilNow(c.start(monday)))
        if (w(0) == "last" && w(1) == "week") add(2, lastWeek)
        run {
            val skip = if (w(0) == "la") 1 else 0
            if (w(skip) == "semaine" && w(skip + 1) in LAST_FR) add(skip + 2, lastWeek)
        }

        // Weekends: the last one started (Saturday and Sunday), and the one before when it is under way.
        val saturday = c.today.with(TemporalAdjusters.previousOrSame(DayOfWeek.SATURDAY))
        val thisWeekend = c.days(saturday, saturday.plusDays(1))
        val inWeekend = c.today.dayOfWeek == DayOfWeek.SATURDAY || c.today.dayOfWeek == DayOfWeek.SUNDAY
        val lastWeekend = if (inWeekend) c.days(saturday.minusDays(7), saturday.minusDays(6)) else thisWeekend
        if (w(0) == "ce" || w(0) == "this") weekendAt(words, at + 1)?.let { add(1 + it, thisWeekend) }
        if (w(0) == "last") weekendAt(words, at + 1)?.let { add(1 + it, lastWeekend) }
        run {
            val skip = if (w(0) == "le") 1 else 0
            weekendAt(words, at + skip)?.let { length ->
                if (w(skip + length) in LAST_FR) add(skip + length + 1, lastWeekend)
            }
        }

        // Months.
        val firstOfMonth = c.today.withDayOfMonth(1)
        val lastMonth = c.days(firstOfMonth.minusMonths(1), firstOfMonth.minusDays(1))
        if (w(0) == "ce" && w(1) == "mois-ci") add(2, c.untilNow(c.start(firstOfMonth)))
        if (w(0) == "ce" && w(1) == "mois") add(2, c.untilNow(c.start(firstOfMonth)))
        if (w(0) == "this" && w(1) == "month") add(2, c.untilNow(c.start(firstOfMonth)))
        if (w(0) == "last" && w(1) == "month") add(2, lastMonth)
        run {
            val skip = if (w(0) == "le") 1 else 0
            if (w(skip) == "mois" && w(skip + 1) in LAST_FR) add(skip + 2, lastMonth)
        }

        // N weeks, N months ago: a window centred on that date.
        agoAt(words, at)?.let { (n, unit, length) ->
            when (unit) {
                Span.WEEK -> c.today.minusWeeks(n.toLong()).let { add(length, c.days(it.minusDays(3), it.plusDays(3))) }

                Span.MONTH -> c.today.minusMonths(n.toLong()).let {
                    add(length, c.days(it.minusDays(15), it.plusDays(14)))
                }

                Span.DAY -> Unit // a day, possibly with a moment: dayAt above
            }
        }

        // "en mars", "in march", optionally with the year.
        if (w(0) == "en" || w(0) == "in") {
            val month = MONTHS[w(1)]
            if (month != null) {
                val year = w(2)?.let(::year)
                val chosen = year ?: if (month <= c.today.monthValue) c.today.year else c.today.year - 1
                val first = LocalDate.of(chosen, month, 1)
                add(if (year != null) 3 else 2, c.days(first, first.plusMonths(1).minusDays(1)))
            }
        }

        return candidates.maxByOrNull { it.length }
    }

    private fun Context.hoursBack(hours: Long) = TimeRange(nowMs - hours * 3_600_000L, nowMs)

    /** "week-end", "weekend" or "week end": the number of words, or null. */
    private fun weekendAt(words: List<Word>, at: Int): Int? = when (words.getOrNull(at)?.norm) {
        "week-end", "weekend" -> 1
        "week" -> if (words.getOrNull(at + 1)?.norm == "end") 2 else null
        else -> null
    }

    private fun momentAfter(words: List<Word>, at: Int): Pair<Moment, Int>? {
        fun w(offset: Int): String? = words.getOrNull(at + offset)?.norm
        val skip = when {
            w(0) == "dans" && (w(1) == "la" || w(1) == "l'") -> 2
            w(0) == "in" && w(1) == "the" -> 2
            w(0) in setOf("au", "le", "en", "at") -> 1
            else -> 0
        }
        return MOMENTS[w(skip)]?.let { it to skip + 1 }
    }

    private enum class Span { DAY, WEEK, MONTH }

    private data class Ago(val n: Int, val unit: Span, val length: Int)

    /** "il y a N jours / semaines / mois", "N days / weeks / months ago". */
    private fun agoAt(words: List<Word>, at: Int): Ago? {
        fun w(offset: Int): String? = words.getOrNull(at + offset)?.norm
        if (w(0) == "il" && w(1) == "y" && w(2) == "a") {
            val n = w(3)?.let { number(it, FR_NUMBERS) } ?: return null
            val unit = when (w(4)) {
                "jour", "jours" -> Span.DAY
                "semaine", "semaines" -> Span.WEEK
                "mois" -> Span.MONTH
                else -> return null
            }
            return Ago(n, unit, 5)
        }
        val n = w(0)?.let { number(it, EN_NUMBERS) } ?: return null
        if (w(2) != "ago") return null
        val unit = when (w(1)) {
            "day", "days" -> Span.DAY
            "week", "weeks" -> Span.WEEK
            "month", "months" -> Span.MONTH
            else -> return null
        }
        return Ago(n, unit, 3)
    }

    /** A day expression at [at]: relative day, day of the week, N days ago, or a date. */
    private fun dayAt(words: List<Word>, at: Int, c: Context): DayMatch? {
        fun w(offset: Int): String? = words.getOrNull(at + offset)?.norm
        val today = c.today
        when (w(0)) {
            "aujourd'hui", "today" -> return DayMatch(today, 1)

            "hier", "yesterday" -> return DayMatch(today.minusDays(1), 1)

            "avant-hier" -> return DayMatch(today.minusDays(2), 1)

            "the" -> if (w(1) == "day" && w(2) == "before" && w(3) == "yesterday") {
                return DayMatch(today.minusDays(2), 4)
            }

            "day" -> if (w(1) == "before" && w(2) == "yesterday") return DayMatch(today.minusDays(2), 3)
        }
        WEEKDAYS_FR[w(0)]?.let { day ->
            return if (w(1) in LAST_FR) DayMatch(previous(today, day), 2) else DayMatch(previousOrSame(today, day), 1)
        }
        WEEKDAYS_EN[w(0)]?.let { return DayMatch(previousOrSame(today, it), 1) }
        if (w(0) == "last") WEEKDAYS_EN[w(1)]?.let { return DayMatch(previous(today, it), 2) }
        agoAt(words, at)?.let { if (it.unit == Span.DAY) return DayMatch(today.minusDays(it.n.toLong()), it.length) }
        return dateAt(words, at, today)
    }

    /** "12 mars [2026]", "1er décembre", "march 12[, 2026]", "12 march 2026", "12/03[/2026]", "2026-03-12". */
    private fun dateAt(words: List<Word>, at: Int, today: LocalDate): DayMatch? {
        fun w(offset: Int): String? = words.getOrNull(at + offset)?.norm
        val first = w(0) ?: return null
        numericDate(first, today)?.let { return DayMatch(it, 1) }
        dayOfMonth(first)?.let { dayNumber ->
            val month = MONTHS[w(1)] ?: return null
            val year = w(2)?.let(::year)
            val date = dated(year, month, dayNumber, today) ?: return null
            return DayMatch(date, if (year != null) 3 else 2)
        }
        MONTHS[first]?.let { month ->
            val dayNumber = w(1)?.let(::dayOfMonth) ?: return null
            val year = w(2)?.let(::year)
            val date = dated(year, month, dayNumber, today) ?: return null
            return DayMatch(date, if (year != null) 3 else 2)
        }
        return null
    }

    private fun numericDate(word: String, today: LocalDate): LocalDate? {
        ISO_DATE.matchEntire(word)?.let { m ->
            val (y, mo, d) = m.destructured
            return safeDate(y.toInt(), mo.toInt(), d.toInt())
        }
        val m = SLASH_DATE.matchEntire(word) ?: return null
        val (a, b, y) = m.destructured
        val (day, month) = if (monthFirst) b.toInt() to a.toInt() else a.toInt() to b.toInt()
        return dated(y.takeIf { it.isNotEmpty() }?.toInt(), month, day, today)
    }

    /** The date of [year], or the most recent one not after [today] when the year is absent. */
    private fun dated(year: Int?, month: Int, day: Int, today: LocalDate): LocalDate? {
        if (year != null) return safeDate(year, month, day)
        val thisYear = safeDate(today.year, month, day) ?: return null
        return if (thisYear.isAfter(today)) safeDate(today.year - 1, month, day) else thisYear
    }

    private fun safeDate(year: Int, month: Int, day: Int): LocalDate? = try {
        LocalDate.of(year, month, day)
    } catch (_: DateTimeException) {
        null
    }

    private fun dayOfMonth(word: String): Int? =
        DAY_OF_MONTH.matchEntire(word)?.groupValues?.get(1)?.toInt()?.takeIf { it in 1..31 }

    private fun year(word: String): Int? = word.takeIf { YEAR.matches(it) }?.toInt()

    private fun number(word: String, words: Map<String, Int>): Int? =
        words[word] ?: word.takeIf { DIGITS.matches(it) }?.toIntOrNull()?.takeIf { it in 1..MAX_AGO }

    private fun previousOrSame(today: LocalDate, day: DayOfWeek) = today.with(TemporalAdjusters.previousOrSame(day))

    private fun previous(today: LocalDate, day: DayOfWeek) = today.with(TemporalAdjusters.previous(day))

    private companion object {
        /** Linking words that go with the expression when they precede it. */
        val LINKING = setOf("vu", "vue", "vus", "vues", "le", "la", "du", "depuis", "on", "at", "from", "seen", "saw")
        const val MAX_LINKING = 2
        const val MAX_AGO = 999

        val LAST_FR = setOf("dernier", "derniere", "passe", "passee")
        val RECENTLY = setOf("recemment", "dernierement", "recently", "lately")
        val MOMENTS = mapOf(
            "matin" to Moment.MORNING, "matinee" to Moment.MORNING, "morning" to Moment.MORNING,
            "midi" to Moment.NOON, "noon" to Moment.NOON, "lunchtime" to Moment.NOON,
            "apres-midi" to Moment.AFTERNOON, "afternoon" to Moment.AFTERNOON,
            "soir" to Moment.EVENING, "soiree" to Moment.EVENING, "evening" to Moment.EVENING,
            "nuit" to Moment.NIGHT, "night" to Moment.NIGHT,
        )
        val WEEKDAYS_FR = listOf("lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche")
            .withIndex().associate { (i, name) -> name to DayOfWeek.of(i + 1) }
        val WEEKDAYS_EN = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
            .withIndex().associate { (i, name) -> name to DayOfWeek.of(i + 1) }
        val MONTHS: Map<String, Int> = buildMap {
            listOf(
                "janvier", "fevrier", "mars", "avril", "mai", "juin",
                "juillet", "aout", "septembre", "octobre", "novembre", "decembre",
            ).forEachIndexed { i, name -> put(name, i + 1) }
            listOf(
                "january", "february", "march", "april", "may", "june",
                "july", "august", "september", "october", "november", "december",
            ).forEachIndexed { i, name -> put(name, i + 1) }
        }
        val FR_NUMBERS =
            listOf("un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf", "dix", "onze", "douze")
                .withIndex().associate { (i, name) -> name to i + 1 } + ("une" to 1)
        val EN_NUMBERS =
            listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve")
                .withIndex().associate { (i, name) -> name to i + 1 } + mapOf("a" to 1, "an" to 1)

        val DIGITS = Regex("[0-9]{1,3}")
        val YEAR = Regex("(19|20)[0-9]{2}")
        val DAY_OF_MONTH = Regex("([0-9]{1,2})(er|st|nd|rd|th)?")
        val ISO_DATE = Regex("([0-9]{4})-([0-9]{2})-([0-9]{2})")
        val SLASH_DATE = Regex("([0-9]{1,2})/([0-9]{1,2})(?:/((?:19|20)[0-9]{2}))?")

        /** Characters trimmed around a word: punctuation, not the apostrophe or hyphen inside it. */
        val EDGE = setOf(',', '.', ';', ':', '!', '?', '(', ')', '"', '«', '»', '[', ']')

        fun words(text: String): List<Word> {
            val out = ArrayList<Word>()
            var i = 0
            while (i < text.length) {
                while (i < text.length && text[i].isWhitespace()) i++
                var begin = i
                while (i < text.length && !text[i].isWhitespace()) i++
                var end = i
                while (begin < end && text[begin] in EDGE) begin++
                while (end > begin && text[end - 1] in EDGE) end--
                if (begin < end) out += Word(begin, end, normalize(text.substring(begin, end)))
            }
            return out
        }

        fun normalize(word: String): String =
            Normalizer.normalize(word.lowercase(java.util.Locale.ROOT), Normalizer.Form.NFD)
                .filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
    }
}
