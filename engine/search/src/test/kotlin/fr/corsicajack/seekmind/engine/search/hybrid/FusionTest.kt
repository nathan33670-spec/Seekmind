package fr.corsicajack.seekmind.engine.search.hybrid

import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.database.store.FragmentSpan
import fr.corsicajack.seekmind.core.model.Capture
import fr.corsicajack.seekmind.core.model.MatchKind
import fr.corsicajack.seekmind.core.model.QueryProfile
import fr.corsicajack.seekmind.core.model.Snippet
import fr.corsicajack.seekmind.core.testing.Models
import fr.corsicajack.seekmind.engine.search.lexical.LexicalHit
import fr.corsicajack.seekmind.engine.search.vector.SemanticHit
import kotlin.math.exp
import org.junit.Test

class FusionTest {
    private val fusion = Fusion()
    private val now = 100 * DAY

    private fun capture(
        id: Long,
        lastSeen: Long = now,
        pkg: String = "com.example.notes",
        simhash: Long = id *
            0x0F0F_0F0F_0F0FL,
    ) = Models.capture("texte de la capture $id", id = id, at = lastSeen, packageName = pkg, simhash = simhash)

    private fun lex(vararg ids: Long) = ids.map {
        LexicalHit(it, rank = -1.0, snippet = Snippet("lex $it", listOf(0..2)))
    }

    private fun sem(vararg ids: Long) = ids.map { SemanticHit(it, embeddingId = 1_000 + it, similarity = 0.9f) }

    private fun fuse(
        lexical: List<LexicalHit>,
        semantic: List<SemanticHit>,
        captures: List<Capture>,
        profile: QueryProfile = QueryProfile.DEFAULT,
        limit: Int = 50,
        fragments: Map<Long, FragmentSpan> = emptyMap(),
    ) = fusion.fuse(lexical, semantic, captures.associateBy { it.id }, fragments, profile, now, limit)

    @Test
    fun theRankTableMatchesTheFormula() {
        // Every capture seen now: the recency factor is 1.15 for all.
        val captures = (1L..4L).map { capture(it) }
        val hits = fuse(lex(1, 2, 3), sem(3, 4), captures)
        // rrf(3) = 1/63 + 1/61 ; rrf(1) = 1/61 ; rrf(4) = 1/62 ; rrf(2) = 1/62.
        val expected = mapOf(
            3L to (1.0 / 63 + 1.0 / 61) * 1.15,
            1L to 1.0 / 61 * 1.15,
            4L to 1.0 / 62 * 1.15,
            2L to 1.0 / 62 * 1.15,
        )
        assertThat(hits.map { it.captureId }).containsExactly(3L, 1L, 4L, 2L).inOrder()
        for (hit in hits) assertThat(hit.score).isWithin(1e-12).of(expected.getValue(hit.captureId))
    }

    @Test
    fun aDocumentInASingleListIsRanked() {
        val hits = fuse(emptyList(), sem(7), listOf(capture(7)))
        assertThat(hits.single().captureId).isEqualTo(7)
        assertThat(hits.single().matchedBy).containsExactly(MatchKind.SEMANTIC)
        assertThat(fuse(lex(8), emptyList(), listOf(capture(8))).single().matchedBy).containsExactly(MatchKind.LEXICAL)
    }

    @Test
    fun equalScoresAreBrokenByTheMostRecent() {
        // 2 is first by keywords, 5 first by meaning, at the same rank: equal RRF. 5 was seen later.
        val captures = listOf(capture(2, lastSeen = now - 1_000), capture(5, lastSeen = now))
        val hits = fuse(lex(2), sem(5), captures)
        // The recency boost alone already orders them; make it explicit with equal ages too:
        assertThat(hits.map { it.captureId }).containsExactly(5L, 2L).inOrder()
        val sameAge = fuse(lex(2), sem(5), listOf(capture(2, lastSeen = now - DAY), capture(5, lastSeen = now - DAY)))
        assertThat(sameAge[0].score).isEqualTo(sameAge[1].score)
        assertThat(sameAge.map { it.captureId }).containsExactly(5L, 2L).inOrder() // then the newest capture id
    }

    @Test
    fun theProfilesWeighTheLists() {
        val captures = listOf(capture(1), capture(2))
        // 1 only by keywords, 2 only by meaning, both first of their list.
        assertThat(
            fuse(lex(1), sem(2), captures, QueryProfile.CODE).map {
                it.captureId
            },
        ).containsExactly(1L, 2L).inOrder()
        assertThat(
            fuse(lex(1), sem(2), captures, QueryProfile.NATURAL).map {
                it.captureId
            },
        ).containsExactly(2L, 1L).inOrder()
        val code = fuse(lex(1), sem(2), captures, QueryProfile.CODE)
        assertThat(code[0].score).isWithin(1e-12).of(2.0 / 61 * 1.15)
        assertThat(code[1].score).isWithin(1e-12).of(0.5 / 61 * 1.15)
    }

    @Test
    fun recentCapturesGetTheBoost() {
        val old = capture(1, lastSeen = now - 30 * DAY)
        val hits = fuse(lex(1), emptyList(), listOf(old))
        assertThat(hits.single().score).isWithin(1e-12).of(1.0 / 61 * (1 + 0.15 * exp(-1.0)))
    }

    @Test
    fun nearDuplicatesOfTheSameAppAreFoldedIntoTheMostRecent() {
        val base = 0x1234_5678_9ABC_DEF0L
        val captures = listOf(
            capture(1, lastSeen = now - DAY, simhash = base),
            capture(2, lastSeen = now, simhash = base xor 0b111111), // 6 bits apart: folded
            capture(3, lastSeen = now, simhash = base xor (0b1111111L shl 20)), // 7 bits from 1, 13 from 2: kept
            capture(4, lastSeen = now, pkg = "com.example.mail", simhash = base), // other app: kept
        )
        val hits = fuse(lex(1, 2, 3, 4), emptyList(), captures)
        assertThat(hits.map { it.captureId }).containsExactly(2L, 3L, 4L).inOrder()
        assertThat(hits.first { it.captureId == 2L }.similarCount).isEqualTo(1)
        assertThat(hits.first { it.captureId == 3L }.similarCount).isEqualTo(0)
    }

    @Test
    fun aFoldedPairKeepsTheHighlightedExtract() {
        val base = 0x1234_5678_9ABC_DEF0L
        val older = capture(1, lastSeen = now - DAY, simhash = base) // found by keywords
        val newer = capture(2, lastSeen = now, simhash = base xor 1) // found by meaning only
        val hit = fuse(lex(1), sem(2), listOf(older, newer)).single()
        assertThat(hit.captureId).isEqualTo(2)
        assertThat(hit.snippet).isEqualTo(Snippet("lex 1", listOf(0..2)))
        assertThat(hit.matchedBy).containsExactly(MatchKind.LEXICAL, MatchKind.SEMANTIC)
    }

    @Test
    fun resultsAreTruncatedToTheLimit() {
        val captures = (1L..10L).map { capture(it) }
        assertThat(fuse(lex(*LongArray(10) { it + 1L }), emptyList(), captures, limit = 3)).hasSize(3)
    }

    @Test
    fun extractsComeFromFts5OrFromTheBestFragment() {
        val text =
            "Ligne une sans rapport.\nLe passage qui correspond au sens de la requête, assez long. " + "x".repeat(300)
        val semanticCapture = Models.capture(text, id = 9, at = now)
        val start = text.indexOf("Le passage")
        val fragments = mapOf(1_009L to FragmentSpan(1_009, 9, start, text.length))
        val hits = fuse(lex(1), sem(9), listOf(capture(1), semanticCapture), fragments = fragments)
        assertThat(hits.first { it.captureId == 1L }.snippet).isEqualTo(Snippet("lex 1", listOf(0..2)))
        val extract = hits.first { it.captureId == 9L }.snippet
        assertThat(extract.text).startsWith("Le passage qui correspond")
        assertThat(extract.text.length).isAtMost(Fusion.SEMANTIC_EXTRACT_CHARS)
        assertThat(extract.highlights).isEmpty()
    }

    @Test
    fun aCaptureDeletedMeanwhileIsDropped() {
        assertThat(fuse(lex(1, 2), emptyList(), listOf(capture(2))).map { it.captureId }).containsExactly(2L)
    }

    private companion object {
        const val DAY = 86_400_000L
    }
}
