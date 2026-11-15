package fr.corsicajack.seekmind.engine.embedding.catalog

import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.engine.embedding.ExpectedFile
import java.io.File
import org.junit.Test

/** PackLock is a copy of engine/embedding/model.lock.json: both must say the same. */
class PackLockTest {
    private val lock = File(System.getProperty("seekmind.modelLock")).readText()

    /** The `"files"` block of [model] in the lock, as name → expected file. */
    private fun files(model: String): Map<String, ExpectedFile> {
        val block = Regex("\"$model\"\\s*:\\s*\\{.*?\"files\"\\s*:\\s*\\{(.*?)\\}\\s*\\}", RegexOption.DOT_MATCHES_ALL)
            .find(lock)!!.groupValues[1]
        return Regex(
            "\"([^\"]+)\"\\s*:\\s*\\{\\s*\"sha256\"\\s*:\\s*\"([0-9a-f]{64})\"\\s*,\\s*\"size\"\\s*:\\s*(\\d+)",
        )
            .findAll(block).associate {
                it.groupValues[1] to ExpectedFile(it.groupValues[2], it.groupValues[3].toLong())
            }
    }

    @Test
    fun theDigestsAreThoseOfTheLock() {
        assertThat(PackLock.FR).isEqualTo(files("fr"))
        assertThat(PackLock.MULTI).isEqualTo(files("multi"))
    }
}
