package fr.corsicajack.seekmind

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.common.SystemClockJvm
import fr.corsicajack.seekmind.core.database.store.NewChunk
import fr.corsicajack.seekmind.core.model.SearchFilters
import fr.corsicajack.seekmind.core.model.config.EmbeddingConfig
import fr.corsicajack.seekmind.core.model.config.SearchConfig
import fr.corsicajack.seekmind.core.model.port.GovernorState
import fr.corsicajack.seekmind.core.model.port.ThermalGovernor
import fr.corsicajack.seekmind.core.testing.Models
import fr.corsicajack.seekmind.core.testing.TemporaryDatabase
import fr.corsicajack.seekmind.engine.embedding.codec.VectorCodec
import fr.corsicajack.seekmind.engine.embedding.engine.BuiltInModels
import fr.corsicajack.seekmind.engine.embedding.engine.OrtEmbeddingEngineFactory
import fr.corsicajack.seekmind.engine.embedding.engine.SharedEmbeddingEngineProvider
import fr.corsicajack.seekmind.engine.index.embedding.EmbeddingBatchRunner
import fr.corsicajack.seekmind.engine.index.embedding.StopReason
import fr.corsicajack.seekmind.engine.search.vector.VectorIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2E-17 (docs/06-qualite-tests-ci.md §4), without the capture pipeline (M2-06, M3-05): the text of the
 * fixture `PlainTextActivity` stored as a capture, vectorised by the embedding batches with the bundled
 * `fr` model, then found by a paraphrase among other captures.
 */
@RunWith(AndroidJUnit4::class)
class EmbeddingBatchesE2ETest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val database = TemporaryDatabase(context, "e2e_17")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val config = EmbeddingConfig()
    private val codec = VectorCodec(config.int8Scale)
    private val governor = object : ThermalGovernor {
        override val state = MutableStateFlow(GovernorState(true, true, 2, null))
    }

    @After
    fun tearDown() {
        scope.cancel()
        database.delete()
    }

    @Test
    fun aParaphraseFindsTheFixtureScreenOnceTheBatchesRan() = runBlocking<Unit> {
        val handle = database.open()
        suspend fun capture(text: String, title: String?, at: Long) = handle.captures.insert(
            Models.newCapture(text, at = at, title = title),
            listOf(NewChunk(0, 0, text.length)),
        )
        // The French text of PlainTextActivity (fixtureapp, fixtures.xml), and other screens.
        val fixture = capture(
            "Le restaurant italien de la rue Paradis sert des pâtes à la truffe le mardi soir.",
            title = "Fixture",
            at = Models.AT,
        )
        capture("Horaires d'ouverture de la piscine municipale le dimanche matin.", "Piscine", Models.AT - 1_000)
        capture("Votre colis a été remis au point relais, retrait sous dix jours.", "Livraison", Models.AT - 2_000)
        capture("Réunion budgétaire de l'équipe projet reportée à jeudi.", "Agenda", Models.AT - 3_000)

        val engines = SharedEmbeddingEngineProvider(
            factory = OrtEmbeddingEngineFactory(context, config, Dispatchers.IO, Dispatchers.Default),
            activeModel = { BuiltInModels.bundledFr },
            threads = { config.threadsDefault },
            config = config,
            scope = scope,
        )
        val report = EmbeddingBatchRunner(handle.vectors, engines, codec, governor, SystemClockJvm).run()
        assertThat(report.reason).isEqualTo(StopReason.QUEUE_EMPTY)
        assertThat(report.embedded).isEqualTo(4)
        assertThat(handle.vectors.pendingCount()).isEqualTo(0)
        assertThat(handle.vectors.modelId()).isEqualTo(BuiltInModels.bundledFr.spec.modelId)

        val index = VectorIndex(handle.vectors, SearchConfig(), codec, Dispatchers.Default)
        val query = engines.use { it.embedQuery("où manger des spaghettis à la truffe en semaine") }
        val hits = index.search(query, SearchFilters())
        assertThat(hits.first().captureId).isEqualTo(fixture)
    }
}
