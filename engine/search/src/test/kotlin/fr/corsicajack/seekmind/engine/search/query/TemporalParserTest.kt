package fr.corsicajack.seekmind.engine.search.query

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import fr.corsicajack.seekmind.core.model.TimeRange
import java.io.File
import java.time.OffsetDateTime
import java.time.ZoneId
import org.junit.Test

/** testdata/temporal_cases.tsv (tools/testdata/gen_temporal_cases.py), and the parser in the query. */
class TemporalParserTest {
    private class Case(
        val query: String,
        val now: Long,
        val zone: ZoneId,
        val locale: String,
        val range: TimeRange?,
        val label: String,
        val remaining: String,
    )

    private val cases: List<Case> = File(System.getProperty("seekmind.testdata"), "temporal_cases.tsv")
        .readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { line ->
            val f = line.split('\t')
            check(f.size == 8) { "malformed line: $line" }
            Case(
                query = f[0],
                now = epoch(f[1]),
                zone = ZoneId.of(f[2]),
                locale = f[3],
                range = if (f[4] == "-") null else TimeRange(epoch(f[4]), epoch(f[5])),
                label = f[6],
                remaining = f[7],
            )
        }

    private fun parser(locale: String) = TemporalParser(monthFirst = locale == "en-US")

    @Test
    fun `the reference set has at least 120 cases in French and in English`() {
        assertThat(cases.size).isAtLeast(120)
        assertThat(cases.count { it.locale.startsWith("fr") }).isAtLeast(40)
        assertThat(cases.count { it.locale.startsWith("en") }).isAtLeast(40)
    }

    @Test
    fun `every case of the reference set passes`() {
        val failures = cases.mapNotNull { case ->
            val text = QueryParser.clean(case.query)
            val match = parser(case.locale).extract(text, case.now, case.zone)
            val actual = Triple(
                match?.range,
                match?.label ?: "-",
                match?.let { QueryParser.collapseSpaces(it.remaining) } ?: text,
            )
            val expected = Triple(case.range, case.label, case.remaining)
            if (actual == expected) null else "${case.query} @ ${case.zone}: expected $expected, got $actual"
        }
        assertWithMessage(failures.joinToString("\n")).that(failures).isEmpty()
    }

    @Test
    fun `a query without time expression is not changed`() {
        val parser = QueryParser(StopWords(emptySet()), TemporalParser())
        cases.filter { it.range == null }.forEach { case ->
            val parsed = parser.parse(case.query, case.now, case.zone)
            assertThat(parsed.text).isEqualTo(case.query)
            assertThat(parsed.range).isNull()
            assertThat(parsed.rangeLabel).isNull()
        }
    }

    @Test
    fun `the parser takes the range, the label and the remaining words`() {
        val parser = QueryParser(StopWords(emptySet()), TemporalParser())
        val case = cases.first { it.query == "photo mardi soir" }
        val parsed = parser.parse(case.query, case.now, case.zone)
        assertThat(parsed.range).isEqualTo(case.range)
        assertThat(parsed.rangeLabel).isEqualTo("mardi soir")
        assertThat(parsed.text).isEqualTo("photo")
        assertThat(parsed.tokens).containsExactly("photo")
    }

    private companion object {
        fun epoch(iso: String): Long = OffsetDateTime.parse(iso).toInstant().toEpochMilli()
    }
}
