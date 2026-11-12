package fr.corsicajack.seekmind.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.database.schema.FtsSchema
import fr.corsicajack.seekmind.core.database.store.NewCapture
import fr.corsicajack.seekmind.core.database.store.NewChunk
import fr.corsicajack.seekmind.core.database.store.NewEmbedding
import fr.corsicajack.seekmind.core.model.SearchFilters
import fr.corsicajack.seekmind.core.model.TimeRange
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** CaptureStore, SearchStore and VectorStore (docs/specs/stockage.md §4, §5; ticket M1-04). */
@RunWith(AndroidJUnit4::class)
class StoresTest {
    private val test = TestDatabase("stores")
    private lateinit var handle: DatabaseHandle
    private val sql get() = handle.room.openHelper.writableDatabase

    @Before
    fun open() {
        test.delete()
        handle = test.handle()
    }

    @After
    fun clean() = test.delete()

    @Test
    fun insertWritesTheCaptureAndItsChunksInOneTransaction() = runBlocking {
        val id = handle.captures.insert(
            capture("un texte de capture assez long"),
            listOf(NewChunk(0, 0, 8), NewChunk(1, 9, 30)),
        )
        assertThat(sql.long("SELECT count(*) FROM chunk WHERE capture_id = ? AND state = 0", id)).isEqualTo(2)
        assertThat(handle.search.lexical("capture", SearchFilters(), 10).map { it.captureId }).containsExactly(id)
        assertThat(handle.captures.get(id)!!.charCount).isEqualTo(30)
    }

    @Test
    fun aFailedInsertLeavesNothing() {
        sql.execSQL("CREATE TRIGGER test_fail_chunk BEFORE INSERT ON chunk BEGIN SELECT RAISE(ABORT, 'test'); END")
        try {
            assertThrows(Exception::class.java) {
                runBlocking {
                    handle.captures.insert(capture("texte qui ne doit pas rester"), listOf(NewChunk(0, 0, 5)))
                }
            }
        } finally {
            sql.execSQL("DROP TRIGGER test_fail_chunk")
        }
        assertThat(sql.long("SELECT count(*) FROM capture")).isEqualTo(0)
        assertThat(sql.long("SELECT count(*) FROM chunk")).isEqualTo(0)
        assertThat(runBlocking { handle.search.lexical("rester", SearchFilters(), 10) }).isEmpty()
        sql.execSQL(FtsSchema.INTEGRITY_CHECK)
    }

    @Test
    fun insertAllWritesABatchInOneTransaction() = runBlocking {
        val ids = handle.captures.insertAll(
            listOf(
                capture("lot un") to listOf(NewChunk(0, 0, 3)),
                capture("lot deux") to emptyList(),
            ),
        )
        assertThat(ids).hasSize(2)
        assertThat(handle.search.captures(ids).map { it.text }).containsExactly("lot un", "lot deux").inOrder()
        assertThat(sql.long("SELECT count(*) FROM chunk")).isEqualTo(1)
        sql.execSQL(
            "CREATE TRIGGER test_fail_second BEFORE INSERT ON capture WHEN new.text = 'échec' BEGIN SELECT RAISE(ABORT, 'test'); END",
        )
        try {
            assertThrows(Exception::class.java) {
                runBlocking {
                    handle.captures.insertAll(
                        listOf(
                            capture("passe") to emptyList(),
                            capture("échec") to emptyList(),
                        ),
                    )
                }
            }
        } finally {
            sql.execSQL("DROP TRIGGER test_fail_second")
        }
        assertThat(sql.long("SELECT count(*) FROM capture")).isEqualTo(2) // the whole batch was rolled back
        assertThat(handle.captures.insertAll(emptyList())).isEmpty()
    }

    @Test
    fun touchUpdatesLastSeenWithoutTouchingTheIndex() = runBlocking {
        val id = handle.captures.insert(capture("écran revu plus tard", at = 1_000), emptyList())
        val segments = sql.long("SELECT count(*) FROM capture_fts_data")
        handle.captures.touch(id, seenAt = 5_000)
        assertThat(sql.long("SELECT count(*) FROM capture_fts_data")).isEqualTo(segments)
        sql.execSQL(FtsSchema.INTEGRITY_CHECK)
        val touched = handle.captures.get(id)!!
        assertThat(touched.lastSeenAt).isEqualTo(5_000)
        assertThat(touched.seenCount).isEqualTo(2)
        assertThat(touched.firstSeenAt).isEqualTo(1_000)
    }

    @Test
    fun lexicalRanksFiltersAndIgnoresAccents() = runBlocking {
        val long = handle.captures.insert(capture("truffe " + List(60) { "mot$it" }.joinToString(" ")), emptyList())
        val short = handle.captures.insert(capture("truffe noire et truffe blanche"), emptyList())
        handle.captures.insert(capture("aucun rapport"), emptyList())
        val rows = handle.search.lexical("truffe", SearchFilters(), 10)
        assertThat(rows.map { it.captureId }).containsExactly(short, long).inOrder()
        assertThat(rows.first().snippet).contains("\u0002truffe\u0003")
        assertThat(rows.first().rank).isLessThan(rows.last().rank)

        val pates = handle.captures.insert(capture("Recette des pâtes fraîches"), emptyList())
        assertThat(
            handle.search.lexical("pates fraiches", SearchFilters(), 10).map {
                it.captureId
            },
        ).containsExactly(pates)

        val old = handle.captures.insert(
            capture("facture électricité", at = 1_000, pkg = "com.example.mail"),
            emptyList(),
        )
        val recent = handle.captures.insert(
            capture("facture électricité", at = 5_000, pkg = "com.example.browser"),
            emptyList(),
        )
        assertThat(lexicalIds("facture", SearchFilters(range = TimeRange(2_000, 9_000)))).containsExactly(recent)
        assertThat(lexicalIds("facture", SearchFilters(range = TimeRange(0, 2_000)))).containsExactly(old)
        assertThat(lexicalIds("facture", SearchFilters(packages = setOf("com.example.mail")))).containsExactly(old)
        assertThat(lexicalIds("facture", SearchFilters(packages = setOf("com.example.mail", "com.example.browser"))))
            .containsExactly(old, recent)
        assertThat(handle.search.lexical("facture", SearchFilters(), 1)).hasSize(1)
    }

    @Test
    fun capturesKeepsTheOrderOfTheIds() = runBlocking {
        val a = handle.captures.insert(capture("premier"), emptyList())
        val b = handle.captures.insert(capture("second"), emptyList())
        assertThat(handle.search.captures(listOf(b, 999, a)).map { it.id }).containsExactly(b, a).inOrder()
        assertThat(handle.search.captures(emptyList())).isEmpty()
    }

    @Test
    fun deletionCascadesAndBumpsTheGeneration() = runBlocking {
        assertThat(handle.vectors.indexGeneration()).isEqualTo(0)
        val id = handle.captures.insert(capture("capture avec vecteur"), listOf(NewChunk(0, 0, 7)))
        val chunk = handle.vectors.pendingChunks(10).single()
        handle.vectors.saveEmbeddings(listOf(embedding(chunk.chunkId, id)))
        assertThat(sql.long("SELECT count(*) FROM embedding")).isEqualTo(1)

        handle.captures.delete(id)
        assertThat(sql.long("SELECT count(*) FROM chunk")).isEqualTo(0)
        assertThat(sql.long("SELECT count(*) FROM embedding")).isEqualTo(0)
        assertThat(handle.search.lexical("vecteur", SearchFilters(), 10)).isEmpty()
        assertThat(handle.vectors.indexGeneration()).isEqualTo(1)

        handle.captures.delete(id) // nothing deleted: the generation does not move
        assertThat(handle.vectors.indexGeneration()).isEqualTo(1)
    }

    @Test
    fun deleteByPackageAndByRange() = runBlocking {
        val mail = handle.captures.insert(capture("courriel", at = 1_000, pkg = "com.example.mail"), emptyList())
        val early = handle.captures.insert(capture("matin", at = 1_000), emptyList())
        val late = handle.captures.insert(capture("soir", at = 9_000), emptyList())
        handle.captures.deleteByPackage("com.example.mail")
        assertThat(handle.captures.get(mail)).isNull()
        handle.captures.deleteByRange(0, 5_000)
        assertThat(handle.captures.get(early)).isNull()
        assertThat(handle.captures.get(late)).isNotNull()
        assertThat(handle.vectors.indexGeneration()).isEqualTo(2)
        sql.execSQL(FtsSchema.INTEGRITY_CHECK)
    }

    @Test
    fun pendingChunksNewestFirstAndSaveMarksThemEmbedded() = runBlocking {
        val first = handle.captures.insert(capture("ancien texte", title = "Titre"), listOf(NewChunk(0, 0, 6)))
        val second = handle.captures.insert(capture("récent 🙂 texte"), listOf(NewChunk(0, 0, 9), NewChunk(1, 10, 15)))
        val pending = handle.vectors.pendingChunks(10)
        assertThat(pending.map { it.captureId }).containsExactly(second, second, first).inOrder()
        assertThat(pending.map { it.text }).containsExactly("texte", "récent 🙂", "ancien").inOrder()
        assertThat(pending.last().windowTitle).isEqualTo("Titre")
        assertThat(handle.vectors.pendingCount()).isEqualTo(3)

        handle.vectors.saveEmbeddings(pending.take(2).map { embedding(it.chunkId, it.captureId) })
        assertThat(handle.vectors.pendingChunks(10).map { it.captureId }).containsExactly(first)
        assertThat(handle.vectors.pendingCount()).isEqualTo(1)
    }

    @Test
    fun aChunkFailsAfterThreeAttempts() = runBlocking {
        handle.captures.insert(capture("texte difficile"), listOf(NewChunk(0, 0, 5)))
        val chunk = handle.vectors.pendingChunks(1).single()
        handle.vectors.markFailed(chunk.chunkId)
        handle.vectors.markFailed(chunk.chunkId)
        assertThat(handle.vectors.pendingChunks(1).single().attempts).isEqualTo(2)
        handle.vectors.markFailed(chunk.chunkId)
        assertThat(handle.vectors.pendingChunks(1)).isEmpty()
        assertThat(sql.long("SELECT state FROM chunk WHERE id = ?", chunk.chunkId)).isEqualTo(2)
    }

    @Test
    fun binaryCodesAfterPagesInInsertionOrder() = runBlocking {
        val ids = (1..5).map { i ->
            handle.captures.insert(capture("texte $i", at = i * 1_000L), listOf(NewChunk(0, 0, 5)))
        }
        handle.vectors.pendingChunks(10).sortedBy { it.chunkId }.forEach {
            handle.vectors.saveEmbeddings(listOf(embedding(it.chunkId, it.captureId)))
        }
        val firstPage = handle.vectors.binaryCodesAfter(0, 2)
        val secondPage = handle.vectors.binaryCodesAfter(firstPage.last().embeddingId, 2)
        val lastPage = handle.vectors.binaryCodesAfter(secondPage.last().embeddingId, 2)
        val all = firstPage + secondPage + lastPage
        assertThat(all.map { it.captureId }).isEqualTo(ids)
        assertThat(all.map { it.embeddingId }).isInStrictOrder()
        assertThat(handle.vectors.binaryCodesAfter(all.last().embeddingId, 2)).isEmpty()
        assertThat(all.first().vecBin).hasLength(48)
        assertThat(all.first().firstSeenAt).isEqualTo(1_000)

        val int8 = handle.vectors.int8Vectors(listOf(all[3].embeddingId, all[1].embeddingId))
        assertThat(int8.map { it.captureId }.toSet()).containsExactly(ids[3], ids[1])
        assertThat(int8.first().vecI8).hasLength(384)
    }

    @Test
    fun resetForModelEmptiesTheVectors() = runBlocking {
        val id = handle.captures.insert(capture("texte"), listOf(NewChunk(0, 0, 5)))
        handle.vectors.saveEmbeddings(listOf(embedding(handle.vectors.pendingChunks(1).single().chunkId, id)))
        val generation = handle.vectors.indexGeneration()
        handle.vectors.resetForModel(2)
        assertThat(sql.long("SELECT count(*) FROM embedding")).isEqualTo(0)
        assertThat(handle.vectors.pendingCount()).isEqualTo(1)
        assertThat(sql.strings("SELECT value FROM meta WHERE key = 'model_id'").single()).isEqualTo("2")
        assertThat(handle.vectors.indexGeneration()).isEqualTo(generation + 1)
    }

    @Test
    fun pageRecentHashesAndStats() = runBlocking {
        val a = handle.captures.insert(capture("un", at = 1_000), emptyList())
        val b = handle.captures.insert(capture("deux", at = 2_000), emptyList())
        val c = handle.captures.insert(capture("trois", at = 3_000, pkg = "com.example.other"), emptyList())
        val first = handle.captures.page(before = null, packageName = null, limit = 2)
        assertThat(first.map { it.id }).containsExactly(c, b).inOrder()
        assertThat(
            handle.captures.page(before = first.last().lastSeenAt, packageName = null, limit = 2).map {
                it.id
            },
        ).containsExactly(a)
        assertThat(handle.captures.page(null, "com.example.notes", 10).map { it.id }).containsExactly(b, a).inOrder()
        assertThat(handle.captures.page(null, null, 1).single().preview).isEqualTo("trois")

        assertThat(handle.captures.recentHashes("com.example.notes", 1).single().captureId).isEqualTo(b)
        val stats = handle.captures.stats()
        assertThat(stats.captureCount).isEqualTo(3)
        assertThat(stats.oldestCaptureAt).isEqualTo(1_000)
        assertThat(stats.sizeBytes).isGreaterThan(0)
    }

    private suspend fun lexicalIds(match: String, filters: SearchFilters) =
        handle.search.lexical(match, filters, 10).map {
            it.captureId
        }

    private fun capture(text: String, at: Long = 1_000, pkg: String = "com.example.notes", title: String? = null) =
        NewCapture(
            firstSeenAt = at,
            lastSeenAt = at,
            packageName = pkg,
            activityName = null,
            windowTitle = title,
            url = null,
            text = text,
            textSource = 1,
            simhash = text.hashCode().toLong(),
        )

    private fun embedding(chunkId: Long, captureId: Long) = NewEmbedding(
        chunkId = chunkId,
        captureId = captureId,
        modelId = 1,
        vecI8 = ByteArray(384) {
            1
        },
        vecBin = ByteArray(48) { 2 },
    )
}
