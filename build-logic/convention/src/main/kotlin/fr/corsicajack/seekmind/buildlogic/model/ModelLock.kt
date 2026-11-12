package fr.corsicajack.seekmind.buildlogic.model

import groovy.json.JsonSlurper
import java.io.File
import java.security.MessageDigest

/**
 * Conformance of an installed model set with `engine/embedding/model.lock.json`
 * (docs/specs/embeddings.md §2): every locked file present, with the recorded size and SHA-256.
 */
object ModelLock {
    /** Files of a set that the lock records; MODEL_MANIFEST.json must only be present. */
    val LOCKED_FILES = listOf("model.onnx", "vocab.bin")
    const val MANIFEST_FILE = "MODEL_MANIFEST.json"

    data class Expected(val name: String, val sha256: String, val size: Long)

    /** Expected files of [model], read from the content of the lock file. */
    fun expected(lockJson: String, model: String): List<Expected> {
        @Suppress("UNCHECKED_CAST")
        val root = JsonSlurper().parseText(lockJson) as Map<String, Any?>
        val entry = root[model] as? Map<*, *> ?: error("model.lock.json has no entry for '$model'")
        val files = entry["files"] as? Map<*, *> ?: error("model.lock.json: '$model' has no files")
        return LOCKED_FILES.map { name ->
            val file = files[name] as? Map<*, *> ?: error("model.lock.json: '$model' does not lock $name")
            Expected(name, file["sha256"] as String, (file["size"] as Number).toLong())
        }
    }

    /** Differences between [directory] and the lock; empty when the set conforms. */
    fun problems(lockJson: String, model: String, directory: File): List<String> {
        val problems = mutableListOf<String>()
        if (!File(directory, MANIFEST_FILE).isFile) problems += "$MANIFEST_FILE: missing"
        for (expected in expected(lockJson, model)) {
            val file = File(directory, expected.name)
            when {
                !file.isFile -> problems += "${expected.name}: missing"

                file.length() != expected.size ->
                    problems +=
                        "${expected.name}: ${file.length()} bytes, expected ${expected.size}"

                sha256(file) != expected.sha256 ->
                    problems +=
                        "${expected.name}: SHA-256 ${sha256(file)}, expected ${expected.sha256}"
            }
        }
        return problems
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
