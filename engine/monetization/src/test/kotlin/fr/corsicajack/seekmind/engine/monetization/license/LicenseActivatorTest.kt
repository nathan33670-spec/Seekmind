package fr.corsicajack.seekmind.engine.monetization.license

import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.database.store.CreditMovement
import fr.corsicajack.seekmind.core.database.store.CreditReason
import fr.corsicajack.seekmind.core.testing.FakeClock
import fr.corsicajack.seekmind.core.testing.FakeMonetizationStore
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** docs/specs/monetisation.md §5.4, on the vectors of testdata/tokens (ticket M8-05). */
class LicenseActivatorTest {
    private val store = FakeMonetizationStore()

    // 2026-11-14T00:00:00Z, the day after the `iat` of the vectors.
    private val clock = FakeClock(now = 1_794_528_000_000L)
    private val activator = LicenseActivator(
        TokenVerifier(TestVectors.testKeyring),
        revoked = setOf("TEST-REVOKED"),
        store = store,
        clock = clock,
    )

    private fun activate(name: String) = runBlocking { activator.activate(TestVectors.named(name).token) }

    @Test
    fun `a code of the test key activates, and the license row is written once`() = runBlocking<Unit> {
        assertThat(activate("valid")).isEqualTo(ActivationResult.Activated("TEST-0001", "lifetime"))
        val license = checkNotNull(store.license())
        assertThat(license.licenseId).isEqualTo("TEST-0001")
        assertThat(license.token).isEqualTo(TestVectors.named("valid").token)
        assertThat(license.activatedAt).isEqualTo(clock.nowMillis())
    }

    @Test
    fun `spaces and line breaks around a pasted code are ignored`() = runBlocking<Unit> {
        val pasted = "  \n" + TestVectors.named("valid").token + "\n "
        assertThat(activator.activate(pasted)).isInstanceOf(ActivationResult.Activated::class.java)
    }

    @Test
    fun `a code issued by seekmind_license with the test key activates`() {
        assertThat(activate("issued_by_tool")).isEqualTo(ActivationResult.Activated("SM-TEST000001", "lifetime"))
    }

    @Test
    fun `a future expiry activates`() {
        assertThat(activate("valid_with_future_exp")).isInstanceOf(ActivationResult.Activated::class.java)
    }

    @Test
    fun `each refusal has its own reason and changes nothing`() = runBlocking<Unit> {
        val cases = mapOf(
            "two_segments" to ActivationRefusal.UNREADABLE,
            "too_long" to ActivationRefusal.UNREADABLE,
            "payload_not_json" to ActivationRefusal.UNREADABLE,
            "altered_signature" to ActivationRefusal.INVALID_SIGNATURE,
            "unknown_kid" to ActivationRefusal.INVALID_SIGNATURE,
            "alg_none_signed" to ActivationRefusal.INVALID_SIGNATURE,
            "unexpected_purpose" to ActivationRefusal.INVALID_SIGNATURE,
            "valid_with_past_exp" to ActivationRefusal.EXPIRED,
            "valid_revoked" to ActivationRefusal.REVOKED,
        )
        for ((name, reason) in cases) {
            assertThat(activate(name)).isEqualTo(ActivationResult.Refused(reason))
        }
        assertThat(store.license()).isNull()
    }

    @Test
    fun `a refusal leaves an earlier activation in place`() = runBlocking<Unit> {
        activate("valid")
        activate("valid_revoked")
        assertThat(store.license()?.licenseId).isEqualTo("TEST-0001")
    }

    @Test
    fun `the expiry is compared with the clock, and an expiry equal to now is expired`() = runBlocking<Unit> {
        val token = TestVectors.named("valid_with_past_exp").token
        val expiry = (1_794_441_600L - 86_400L) * 1_000L
        clock.now = expiry - 1_000L
        assertThat(activator.activate(token)).isInstanceOf(ActivationResult.Activated::class.java)
        clock.now = expiry
        assertThat(activator.activate(token)).isEqualTo(ActivationResult.Refused(ActivationRefusal.EXPIRED))
    }

    @Test
    fun `removing the activation keeps the balance as it was`() = runBlocking<Unit> {
        store.append(CreditMovement(at = 1, delta = 7, reason = CreditReason.WELCOME, ref = null))
        activate("valid")
        activator.deactivate()
        assertThat(store.license()).isNull()
        assertThat(store.balance()).isEqualTo(7)
    }

    @Test
    fun `the embedded revocation list is read`() {
        assertThat(LicenseActivator.embeddedRevocations()).isEmpty()
        assertThat(LicenseActivator.parseRevocations("""{"revoked":["A","B"]}""")).containsExactly("A", "B")
    }
}
