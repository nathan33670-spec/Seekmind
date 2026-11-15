package fr.corsicajack.seekmind.buildlogic.guards

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DownloadSizeTest {
    @Test
    fun parsesTheTableOfGetSizeTotal() {
        assertEquals(DownloadSize(41_000_000, 44_500_000), DownloadSize.parse("MIN,MAX\n41000000,44500000\n"))
    }

    @Test
    fun refusesAnUnexpectedOutput() {
        assertFailsWith<IllegalArgumentException> { DownloadSize.parse("SDK,MIN,MAX\n21,1,2") }
    }
}

class BundleRulesTest {
    private val pack = listOf("model.onnx", "vocab.bin", "MODEL_MANIFEST.json").associate {
        "assets/model/multi/$it" to
            true
    }
    private val good = mapOf(
        BundleRules.BASE_MASTER to mapOf("assets/model/fr/model.onnx" to true, "classes.dex" to false),
        "splits/base-arm64_v8a.apk" to mapOf("lib/arm64-v8a/libonnxruntime.so" to true),
        BundleRules.MODEL_PACK to pack,
    )

    @Test
    fun aWellFormedSetPasses() {
        assertEquals(emptyList(), BundleRules.violations(good))
    }

    @Test
    fun aCompressedBundledModelFails() {
        val set = good + (BundleRules.BASE_MASTER to mapOf("assets/model/fr/model.onnx" to false))
        assertEquals(listOf("assets/model/fr/model.onnx is compressed in the base"), BundleRules.violations(set))
    }

    @Test
    fun aMissingPackOrModelFails() {
        val set = good - BundleRules.MODEL_PACK + (BundleRules.BASE_MASTER to emptyMap())
        assertEquals(
            listOf("the base has no assets/model/fr/model.onnx", "no on-demand pack ${BundleRules.MODEL_PACK}"),
            BundleRules.violations(set),
        )
    }

    @Test
    fun theMultiModelInTheBaseFails() {
        val set = good + (BundleRules.BASE_MASTER to good.getValue(BundleRules.BASE_MASTER) + pack)
        assertEquals(listOf("${BundleRules.BASE_MASTER} holds the multi model"), BundleRules.violations(set))
    }
}
