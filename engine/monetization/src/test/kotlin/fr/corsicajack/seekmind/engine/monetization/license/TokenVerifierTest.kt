package fr.corsicajack.seekmind.engine.monetization.license

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/** docs/specs/monetisation.md §5.2, on the vectors of testdata/tokens (ticket M8-02). */
class TokenVerifierTest {
    private val verifier = TokenVerifier(TestVectors.testKeyring)

    @Test
    fun `every vector has its expected outcome`() {
        assertThat(TestVectors.all).isNotEmpty()
        for (vector in TestVectors.all) {
            val outcome = when (val result = verifier.verifyLicense(vector.token)) {
                is Verification.Valid -> "VALID"
                is Verification.Refused -> result.reason.name
            }
            assertWithMessage(vector.name).that(outcome).isEqualTo(vector.expected)
        }
    }

    @Test
    fun `every refusal reason is exercised by a vector`() {
        val expected = TestVectors.all.map { it.expected }.toSet()
        assertThat(expected).containsAtLeastElementsIn(TokenRefusal.entries.map { it.name })
    }

    @Test
    fun `a valid token gives its claims`() {
        val result = verifier.verifyLicense(TestVectors.named("valid").token)
        assertThat(result).isEqualTo(Verification.Valid(LicenseClaims("TEST-0001", "lifetime", 1_794_441_600L, null)))
        val withExp = verifier.verifyLicense(TestVectors.named("valid_with_future_exp").token) as Verification.Valid
        assertThat(withExp.claims.expiresAt).isEqualTo(1_794_441_600L + 10 * 365 * 86_400L)
    }

    @Test
    fun `a key for another use is refused`() {
        val other = Keyring(TestVectors.testKeyring.kids.map { kid -> TestVectors.testKeyring[kid]!! })
        assertThat(TokenVerifier(other).verify(TestVectors.named("valid").token, purpose = "other"))
            .isEqualTo(Verification.Refused(TokenRefusal.WRONG_PURPOSE))
    }

    @Test
    fun `an empty keyring knows no key`() {
        assertThat(TokenVerifier(Keyring(emptyList())).verifyLicense(TestVectors.named("valid").token))
            .isEqualTo(Verification.Refused(TokenRefusal.UNKNOWN_KEY))
    }

    @Test
    fun `raw signatures become DER integers`() {
        // High bit set: a leading zero is added; leading zeros are dropped.
        val raw = ByteArray(32) { 0x80.toByte() } + (ByteArray(31) + byteArrayOf(5))
        val der = TokenVerifier.toDer(raw)!!
        assertThat(der[0]).isEqualTo(0x30.toByte())
        assertThat(der[2]).isEqualTo(0x02.toByte())
        assertThat(der[3]).isEqualTo(33.toByte())
        assertThat(der[4]).isEqualTo(0.toByte())
        assertThat(der.takeLast(3)).containsExactly(0x02.toByte(), 1.toByte(), 5.toByte()).inOrder()
        assertThat(TokenVerifier.toDer(ByteArray(63))).isNull()
    }

    @Test
    fun `the placeholder of the production key is skipped`() {
        val keys = Keyring.parse("""{"keys":[{"kid":"l1","purpose":"license","spki":""}]}""")
        assertThat(keys).isEmpty()
    }
}
