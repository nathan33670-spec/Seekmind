package fr.corsicajack.seekmind.engine.embedding.tokenizer

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import org.junit.Assume.assumeTrue

/** Writes `vocab.bin` content (docs/specs/embeddings.md §3.2); the four special tokens come first. */
internal fun vocabBlob(pieces: List<Pair<String, Float>>, specials: Boolean = true): ByteArray {
    val all = if (specials) listOf("<s>" to 0f, "<pad>" to 0f, "</s>" to 0f, "<unk>" to 0f) + pieces else pieces
    val out = ByteArrayOutputStream()
    out.write("SMV1".toByteArray())
    out.writeInt32(all.size)
    for ((piece, score) in all) {
        val bytes = piece.toByteArray(Charsets.UTF_8)
        out.write(bytes.size and 0xFF)
        out.write(bytes.size ushr 8)
        out.write(bytes)
        out.writeInt32(score.toRawBits())
    }
    return out.toByteArray()
}

internal fun vocabulary(vararg pieces: Pair<String, Float>): Vocabulary = Vocabulary.parse(vocabBlob(pieces.toList()))

private fun ByteArrayOutputStream.writeInt32(value: Int) {
    for (shift in 0 until 32 step 8) write((value ushr shift) and 0xFF)
}

/**
 * The real vocabularies, built by `tools/model` into `tools/model/out/<id>/` (or installed into the
 * assets for `fr`) and checked against `model.lock.json`. Missing: the test is skipped, except on CI.
 */
internal object ModelVocabularies {
    private val testdata = File(System.getProperty("seekmind.testdata") ?: "../../testdata")
    private val modelOut = File(System.getProperty("seekmind.modelOut") ?: "../../tools/model/out")
    private val bundled = File(System.getProperty("seekmind.bundledModel") ?: "src/main/assets/model")
    private val lock = File(testdata.parentFile, "engine/embedding/model.lock.json")
    private val onCi = System.getenv("CI") == "true"
    private val cache = mutableMapOf<String, Vocabulary>()

    fun golden(id: String): File = File(testdata, "tokenizer_golden_$id.jsonl")

    fun file(id: String): File? =
        listOf(File(modelOut, "$id/vocab.bin"), File(bundled, "$id/vocab.bin")).firstOrNull { it.isFile }

    /** The vocabulary of model [id], or the test is skipped (fails on CI). */
    @Synchronized
    fun require(id: String): Vocabulary = cache.getOrPut(id) {
        val file = file(id)
        val hint = "vocab.bin of '$id' missing: python tools/model/export_vocab.py --model $id"
        check(file != null || !onCi) { hint }
        assumeTrue(hint, file != null)
        val bytes = file!!.readBytes()
        val expected = expectedSha256(id)
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(actual == expected) { "$file: SHA-256 $actual, expected $expected (model.lock.json)" }
        Vocabulary.parse(bytes)
    }

    private fun expectedSha256(id: String): String {
        val text = lock.readText()
        val pattern = Regex(
            "\"" + id + "\"\\s*:\\s*\\{.*?\"vocab\\.bin\"\\s*:\\s*\\{\\s*\"sha256\"\\s*:\\s*\"([0-9a-f]{64})\"",
            RegexOption.DOT_MATCHES_ALL,
        )
        return checkNotNull(pattern.find(text)) { "no vocab.bin digest for '$id' in $lock" }.groupValues[1]
    }
}
