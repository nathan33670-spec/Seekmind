package fr.corsicajack.seekmind.engine.embedding.engine

import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.model.Delivery
import java.io.File
import org.junit.Test

class BuiltInModelsTest {
    /** `model.lock.json` of the module: the working directory of the unit tests. */
    private val lock = File("model.lock.json").readText()

    @Test
    fun modelIdsAndSizesMatchTheLockFile() {
        for (spec in BuiltInModels.all) {
            val entry = Regex(""""${spec.id}"\s*:\s*\{(.*?)"vocab\.bin"\s*:\s*\{[^}]*\}""", RegexOption.DOT_MATCHES_ALL)
                .find(lock)?.value ?: error("${spec.id} missing from model.lock.json")
            val modelId = Regex(""""modelId"\s*:\s*(\d+)""").find(entry)!!.groupValues[1].toInt()
            val sizes = Regex(""""size"\s*:\s*(\d+)""").findAll(entry).sumOf { it.groupValues[1].toLong() }
            assertThat(spec.modelId).isEqualTo(modelId)
            assertThat(spec.sizeBytes).isEqualTo(sizes)
        }
    }

    @Test
    fun frIsBundledAndMultiIsAPack() {
        assertThat(BuiltInModels.FR.delivery).isEqualTo(Delivery.BUNDLED)
        assertThat(BuiltInModels.MULTI.delivery).isEqualTo(Delivery.PACK)
        assertThat(BuiltInModels.MULTI.packName).isEqualTo("modelpack_multi")
        assertThat(BuiltInModels.bundledFr.source).isEqualTo(ModelSource.Bundled("model/fr"))
    }
}
