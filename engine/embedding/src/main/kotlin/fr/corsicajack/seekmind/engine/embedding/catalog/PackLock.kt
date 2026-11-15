package fr.corsicajack.seekmind.engine.embedding.catalog

import fr.corsicajack.seekmind.engine.embedding.ExpectedFile

/**
 * The digests a language pack must match before it is installed or activated (docs/specs/embeddings.md
 * §5.2), copied from `engine/embedding/model.lock.json`; `PackLockTest` fails when they drift apart.
 */
object PackLock {
    const val MODEL_FILE = "model.onnx"
    const val VOCABULARY_FILE = "vocab.bin"
    const val MANIFEST_FILE = "MODEL_MANIFEST.json"

    /** `fr`: the bundled model; also the files of the test pack of the automated tests. */
    val FR: Map<String, ExpectedFile> = mapOf(
        MODEL_FILE to ExpectedFile("ded7f9f9557df388009a8b54b70b4ce006d427f44e06f161bb5a3a29afc172be", 36_595_240),
        VOCABULARY_FILE to ExpectedFile("3bb4a5d9e2d381b3774fab1201855402ac50ea8226b5c4fdfe3a92bf5ad36084", 460_929),
    )

    val MULTI: Map<String, ExpectedFile> = mapOf(
        MODEL_FILE to ExpectedFile("c9391bd927dbf1aadedde96db8ad660034be110d04de4eb4ee6ec323d5dab618", 118_330_479),
        VOCABULARY_FILE to ExpectedFile("7ac81a2015fe21c833d36b44df0c066bd8e6f56b30055ae3eb318994f8b21dce", 4_081_365),
    )

    /** By catalogue id. */
    val byModel: Map<String, Map<String, ExpectedFile>> = mapOf("fr" to FR, "multi" to MULTI)
}
