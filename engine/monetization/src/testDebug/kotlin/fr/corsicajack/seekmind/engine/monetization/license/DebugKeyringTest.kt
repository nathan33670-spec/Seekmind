package fr.corsicajack.seekmind.engine.monetization.license

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The debug build trusts the test key `l-test` (docs/specs/monetisation.md §5.2). */
class DebugKeyringTest {
    @Test
    fun `the debug build accepts a token of the test key`() {
        val keyring = Keyring.embedded()
        assertThat(keyring.kids).contains("l-test")
        assertThat(TokenVerifier(keyring).verifyLicense(TestVectors.named("valid").token))
            .isInstanceOf(Verification.Valid::class.java)
    }
}
