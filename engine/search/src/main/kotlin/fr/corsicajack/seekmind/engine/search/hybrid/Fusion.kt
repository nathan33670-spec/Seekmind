package fr.corsicajack.seekmind.engine.search.hybrid

import fr.corsicajack.seekmind.core.database.store.FragmentSpan
import fr.corsicajack.seekmind.core.model.Capture
import fr.corsicajack.seekmind.core.model.MatchKind
import fr.corsicajack.seekmind.core.model.QueryProfile
import fr.corsicajack.seekmind.core.model.SearchHit
import fr.corsicajack.seekmind.core.model.Snippet
import fr.corsicajack.seekmind.core.model.config.SearchConfig
import fr.corsicajack.seekmind.engine.search.lexical.LexicalHit
import fr.corsicajack.seekmind.engine.search.vector.SemanticHit
import kotlin.math.exp

/**
 * Reciprocal Rank Fusion of the keyword and semantic lists (docs/specs/recherche.md §7), recency boost,
 * folding of near-duplicates, truncation and extracts. Pure: the captures and fragments are given.
 */
class Fusion(private val config: SearchConfig = SearchConfig()) {
    /** `w_lex`, `w_sem` of a profile. */
    data class Weights(val lexical: Double, val semantic: Double)

    /**
     * [lexical] and [semantic] are ranked best first. [captures] by id; a capture missing from it (deleted
     * meanwhile) is dropped. [fragments] by embedding id, for the extracts of semantic-only results.
     */
    fun fuse(
        lexical: List<LexicalHit>,
        semantic: List<SemanticHit>,
        captures: Map<Long, Capture>,
        fragments: Map<Long, FragmentSpan>,
        profile: QueryProfile,
        now: Long,
        limit: Int,
    ): List<SearchHit> {
        val weights = weights(profile, config)
        val lexicalRank = HashMap<Long, Int>()
        lexical.forEachIndexed { i, hit -> lexicalRank.putIfAbsent(hit.captureId, i + 1) }
        val semanticRank = HashMap<Long, Int>()
        semantic.forEachIndexed { i, hit -> semanticRank.putIfAbsent(hit.captureId, i + 1) }
        val lexicalHits = lexical.associateBy { it.captureId }
        val semanticHits = semantic.associateBy { it.captureId }

        val scored = (lexicalRank.keys + semanticRank.keys).mapNotNull { id ->
            val capture = captures[id] ?: return@mapNotNull null
            val rrf = (lexicalRank[id]?.let { weights.lexical / (config.rrfK + it) } ?: 0.0) +
                (semanticRank[id]?.let { weights.semantic / (config.rrfK + it) } ?: 0.0)
            val ageDays = (now - capture.lastSeenAt).coerceAtLeast(0) / DAY_MS
            val score = rrf * (1 + config.recencyBoost * exp(-ageDays / config.recencyHalfLifeDays))
            val matchedBy = buildSet {
                if (id in lexicalRank) add(MatchKind.LEXICAL)
                if (id in semanticRank) add(MatchKind.SEMANTIC)
            }
            val snippet =
                lexicalHits[id]?.snippet
                    ?: semanticSnippet(capture, semanticHits[id]?.let { fragments[it.embeddingId] })
            Ranked(capture, score, snippet, matchedBy, similarCount = 0)
        }.sortedWith(ORDER)

        return collapse(scored).take(limit).map { it.toHit() }
    }

    /** Same package and SimHash within [SearchConfig.collapseMaxHamming] bits: the most recent is kept, in the better place. */
    private fun collapse(ranked: List<Ranked>): List<Ranked> {
        val kept = ArrayList<Ranked>(ranked.size)
        for (hit in ranked) {
            val i = kept.indexOfFirst {
                it.capture.packageName == hit.capture.packageName &&
                    java.lang.Long.bitCount(it.capture.simhash xor hit.capture.simhash) <= config.collapseMaxHamming
            }
            if (i < 0) {
                kept += hit
                continue
            }
            val other = kept[i]
            val newer = if (hit.capture.lastSeenAt > other.capture.lastSeenAt) hit else other
            // An extract with highlighted terms is more useful than a semantic one, whichever capture it comes from.
            val snippet =
                listOf(newer, other, hit).firstOrNull { it.snippet.highlights.isNotEmpty() }?.snippet ?: newer.snippet
            kept[i] = newer.copy(
                snippet = snippet,
                score = maxOf(other.score, hit.score),
                matchedBy = other.matchedBy + hit.matchedBy,
                similarCount = other.similarCount + hit.similarCount + 1,
            )
        }
        return kept
    }

    private fun semanticSnippet(capture: Capture, fragment: FragmentSpan?): Snippet {
        val text = capture.text
        val start = fragment?.startOffset?.coerceIn(0, text.length) ?: 0
        val end = fragment?.endOffset?.coerceIn(start, text.length) ?: text.length
        return Snippet(text.substring(start, end).take(SEMANTIC_EXTRACT_CHARS).trim(), emptyList())
    }

    private data class Ranked(
        val capture: Capture,
        val score: Double,
        val snippet: Snippet,
        val matchedBy: Set<MatchKind>,
        val similarCount: Int,
    ) {
        fun toHit() = SearchHit(
            captureId = capture.id,
            score = score,
            packageName = capture.packageName,
            windowTitle = capture.windowTitle,
            url = capture.url,
            firstSeenAt = capture.firstSeenAt,
            lastSeenAt = capture.lastSeenAt,
            snippet = snippet,
            textSource = capture.textSource,
            matchedBy = matchedBy,
            similarCount = similarCount,
        )
    }

    companion object {
        const val SEMANTIC_EXTRACT_CHARS = 200
        private const val DAY_MS = 86_400_000.0

        /** docs/specs/recherche.md §7, from [SearchConfig]. */
        fun weights(profile: QueryProfile, config: SearchConfig): Weights = when (profile) {
            QueryProfile.DEFAULT -> Weights(config.lexicalWeightDefault, config.semanticWeightDefault)
            QueryProfile.CODE -> Weights(config.lexicalWeightCode, config.semanticWeightCode)
            QueryProfile.NATURAL -> Weights(config.lexicalWeightNatural, config.semanticWeightNatural)
        }

        /** Score, then the most recently seen, then the newest capture. */
        private val ORDER = compareByDescending<Ranked> { it.score }
            .thenByDescending { it.capture.lastSeenAt }
            .thenByDescending { it.capture.id }
    }
}
