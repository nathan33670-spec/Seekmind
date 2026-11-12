package fr.corsicajack.seekmind.buildlogic.model

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ModelLockTest {
    private val directory: File = Files.createTempDirectory("model").toFile().apply { deleteOnExit() }

    private fun write(name: String, content: String) = File(directory, name).apply { writeText(content) }

    private fun lockFor(vararg files: File) = buildString {
        append("""{"fr": {"modelId": 1, "files": {""")
        append(
            files.joinToString(",") {
                """"${it.name}": {"sha256": "${ModelLock.sha256(it)}", "size": ${it.length()}}"""
            },
        )
        append("}}}")
    }

    @Test
    fun `a set matching the lock has no problem`() {
        val lock = lockFor(write("model.onnx", "model"), write("vocab.bin", "vocab"))
        write(ModelLock.MANIFEST_FILE, "{}")
        assertEquals(emptyList(), ModelLock.problems(lock, "fr", directory))
    }

    @Test
    fun `missing, resized and altered files are reported`() {
        val model = write("model.onnx", "model")
        val vocab = write("vocab.bin", "vocab")
        val lock = lockFor(model, vocab)
        model.writeText("model, longer")
        vocab.writeText("VOCAB")
        assertEquals(
            listOf(
                "MODEL_MANIFEST.json: missing",
                "model.onnx: 13 bytes, expected 5",
                "vocab.bin: SHA-256 ${ModelLock.sha256(vocab)}, expected ${ModelLock.sha256(write("x", "vocab"))}",
            ),
            ModelLock.problems(lock, "fr", directory),
        )
    }

    @Test
    fun `an absent directory reports every file`() {
        val lock = lockFor(write("model.onnx", "m"), write("vocab.bin", "v"))
        assertEquals(3, ModelLock.problems(lock, "fr", File(directory, "absent")).size)
    }

    @Test
    fun `a lock without the model is an error`() {
        assertFailsWith<IllegalStateException> { ModelLock.expected("""{"multi": {"files": {}}}""", "fr") }
    }

    @Test
    fun `the repository lock records both models`() {
        val lock = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "engine/embedding/model.lock.json") }
            .first { it.isFile }
            .readText()
        for (model in listOf("fr", "multi")) {
            assertEquals(ModelLock.LOCKED_FILES, ModelLock.expected(lock, model).map { it.name })
        }
    }
}
