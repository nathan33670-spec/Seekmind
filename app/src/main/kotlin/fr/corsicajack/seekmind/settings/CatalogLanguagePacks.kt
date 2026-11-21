package fr.corsicajack.seekmind.settings

import android.content.ContentResolver
import android.net.Uri
import fr.corsicajack.seekmind.core.model.ModelSpec
import fr.corsicajack.seekmind.core.model.PackState
import fr.corsicajack.seekmind.engine.embedding.catalog.DefaultModelCatalog
import fr.corsicajack.seekmind.engine.embedding.catalog.FileModelPackInstaller
import fr.corsicajack.seekmind.engine.embedding.catalog.ImportResult
import fr.corsicajack.seekmind.engine.embedding.catalog.ModelPackInstaller
import fr.corsicajack.seekmind.feature.settings.LanguagePacks
import java.io.FileNotFoundException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * [LanguagePacks] over the catalogue (docs/specs/embeddings.md §5). Packs come from a file when the
 * installer is the file one (installation outside Google Play): the chosen document is read through the
 * [resolver] and checked by [FileModelPackInstaller] before anything is copied (INV-12).
 */
class CatalogLanguagePacks(
    private val catalog: DefaultModelCatalog,
    installer: ModelPackInstaller,
    private val file: FileModelPackInstaller,
    private val resolver: ContentResolver,
) : LanguagePacks {
    override val models: List<ModelSpec> get() = catalog.models
    override val active: StateFlow<ModelSpec> get() = catalog.active
    override val importsFiles: Boolean = installer is FileModelPackInstaller

    override fun state(id: String): Flow<PackState> = catalog.state(id)

    override suspend fun install(id: String): Flow<PackState> = catalog.install(id)

    override suspend fun import(id: String, uri: String): Boolean {
        val spec = catalog.models.firstOrNull { it.id == id } ?: return false
        return try {
            val stream = resolver.openInputStream(Uri.parse(uri)) ?: return false
            stream.use { file.import(spec, it) } == ImportResult.Imported
        } catch (_: FileNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    override suspend fun activate(id: String) = catalog.activate(id)

    override suspend fun remove(id: String) = catalog.remove(id)
}
