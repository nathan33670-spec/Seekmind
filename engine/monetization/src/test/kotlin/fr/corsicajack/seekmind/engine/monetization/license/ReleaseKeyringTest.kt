package fr.corsicajack.seekmind.engine.monetization.license

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * The release build refuses a token signed by the test key (docs/specs/monetisation.md §5.2, M8-02):
 * its keyring is `keys-release.json` alone, the test key `l-test` being in the debug sources only.
 */
class ReleaseKeyringTest {
    private val release = File(checkNotNull(System.getProperty("seekmind.releaseKeyring")))

    @Test
    fun `the release keyring refuses a token of the test key`() {
        val keyring = Keyring(Keyring.parse(release.readText()))
        assertThat(keyring.kids).doesNotContain("l-test")
        assertThat(TokenVerifier(keyring).verifyLicense(TestVectors.named("valid").token))
            .isEqualTo(Verification.Refused(TokenRefusal.UNKNOWN_KEY))
    }

    @Test
    fun `the test key is in the debug sources only`() {
        val sources = release.parentFile.parentFile.parentFile.parentFile // src/
        val debugKeyring = File(sources, "debug/resources/license/keys-debug.json")
        assertThat(debugKeyring.isFile).isTrue()
        assertThat(release.readText()).doesNotContain("l-test")
        val elsewhere = sources.listFiles().orEmpty().filter {
            it.name != "debug" && it.name != "test" &&
                it.name != "testDebug"
        }
            .flatMap { it.walkTopDown().filter(File::isFile).toList() }
            .filter { it.readText().contains("\"l-test\"") }
        assertThat(elsewhere).isEmpty()
    }
}
