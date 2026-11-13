package fr.corsicajack.seekmind.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fr.corsicajack.seekmind.core.common.Clock
import fr.corsicajack.seekmind.core.common.DefaultDispatcherProvider
import fr.corsicajack.seekmind.core.database.DatabaseState
import fr.corsicajack.seekmind.core.model.config.EmbeddingConfig
import fr.corsicajack.seekmind.core.model.config.SearchConfig
import fr.corsicajack.seekmind.core.model.port.EntitlementGate
import fr.corsicajack.seekmind.engine.embedding.codec.VectorCodec
import fr.corsicajack.seekmind.engine.embedding.engine.BuiltInModels
import fr.corsicajack.seekmind.engine.embedding.engine.MemoryPressureRelease
import fr.corsicajack.seekmind.engine.embedding.engine.OrtEmbeddingEngineFactory
import fr.corsicajack.seekmind.engine.embedding.engine.SharedEmbeddingEngineProvider
import fr.corsicajack.seekmind.engine.search.catalog.AndroidAppCatalog
import fr.corsicajack.seekmind.engine.search.hybrid.SearchCache
import fr.corsicajack.seekmind.engine.search.query.AppAliases
import fr.corsicajack.seekmind.engine.search.query.QueryParser
import fr.corsicajack.seekmind.engine.search.query.StopWords
import fr.corsicajack.seekmind.engine.search.query.TemporalParser
import fr.corsicajack.seekmind.search.AllowAllEntitlementGate
import fr.corsicajack.seekmind.search.SearchStack
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope

/** The search and the embedding engine it shares with the embedding batches (recherche, embeddings §4). */
@Module
@InstallIn(SingletonComponent::class)
object SearchModule {
    @Provides
    @Singleton
    fun embeddingConfig(): EmbeddingConfig = EmbeddingConfig()

    @Provides
    @Singleton
    fun searchConfig(): SearchConfig = SearchConfig()

    @Provides
    @Singleton
    fun codec(config: EmbeddingConfig): VectorCodec = VectorCodec(config.int8Scale)

    /**
     * The bundled `fr` model until the catalogue (M4-07); two threads until the governor (M3-04).
     * Closed under memory pressure.
     */
    @Provides
    @Singleton
    fun embeddings(
        @ApplicationContext context: Context,
        config: EmbeddingConfig,
        @ApplicationScope scope: CoroutineScope,
    ): SharedEmbeddingEngineProvider {
        val dispatchers = DefaultDispatcherProvider
        val provider = SharedEmbeddingEngineProvider(
            factory = OrtEmbeddingEngineFactory(context, config, dispatchers.io, dispatchers.default),
            activeModel = { BuiltInModels.bundledFr },
            threads = { config.threadsDefault },
            config = config,
            scope = scope,
        )
        context.registerComponentCallbacks(MemoryPressureRelease(provider))
        return provider
    }

    @Provides
    @Singleton
    fun catalog(@ApplicationContext context: Context): AndroidAppCatalog =
        AndroidAppCatalog(context, AppAliases.load(context.resources), DefaultDispatcherProvider.io).also {
            it.register()
        }

    @Provides
    @Singleton
    fun parser(@ApplicationContext context: Context): QueryParser =
        QueryParser(StopWords.load(context.resources), TemporalParser())

    @Provides
    @Singleton
    fun cache(config: SearchConfig, clock: Clock): SearchCache = SearchCache(config, clock)

    /** Until the credit ledger (M8-01). */
    @Provides
    @Singleton
    fun entitlements(): EntitlementGate = AllowAllEntitlementGate()

    @Provides
    @Singleton
    fun stack(
        database: Provider<DatabaseState>,
        parser: Provider<QueryParser>,
        catalog: Provider<AndroidAppCatalog>,
        embeddings: Provider<SharedEmbeddingEngineProvider>,
        codec: VectorCodec,
        cache: SearchCache,
        config: SearchConfig,
        @ApplicationScope scope: CoroutineScope,
    ): SearchStack = SearchStack(
        database = database,
        parser = parser,
        catalog = catalog,
        engines = embeddings,
        codec = codec,
        cache = cache,
        config = config,
        compute = DefaultDispatcherProvider.default,
        scope = scope,
    )
}
