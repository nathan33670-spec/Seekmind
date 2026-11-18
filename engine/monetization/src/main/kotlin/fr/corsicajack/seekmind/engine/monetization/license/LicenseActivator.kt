package fr.corsicajack.seekmind.engine.monetization.license

import fr.corsicajack.seekmind.core.common.Clock
import fr.corsicajack.seekmind.core.common.SmLog
import fr.corsicajack.seekmind.core.database.store.License
import fr.corsicajack.seekmind.core.database.store.MonetizationStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Why an activation code is refused, as the user is told (docs/specs/monetisation.md §5.4). */
enum class ActivationRefusal {
    /** Not a code: wrong shape, too long, unreadable content. */
    UNREADABLE,

    /** Not signed by a key of SeekMind for this use. */
    INVALID_SIGNATURE,
    EXPIRED,
    REVOKED,
}

sealed interface ActivationResult {
    data class Activated(val licenseId: String, val plan: String) : ActivationResult

    data class Refused(val reason: ActivationRefusal) : ActivationResult
}

/**
 * Activation by code (docs/specs/monetisation.md §5.4), the same for a pasted code and a link: the
 * [TokenVerifier] checks, then `exp` absent or in the future, then the licence absent from the embedded
 * revocation list. Only a success writes: the single `license` row. A refusal changes nothing.
 */
class LicenseActivator(
    private val verifier: TokenVerifier,
    private val revoked: Set<String>,
    private val store: MonetizationStore,
    private val clock: Clock,
) {
    suspend fun activate(code: String): ActivationResult {
        val token = code.trim()
        val claims = when (val verification = verifier.verifyLicense(token)) {
            is Verification.Refused -> return refused(verification.reason.toActivation())
            is Verification.Valid -> verification.claims
        }
        val nowSeconds = clock.nowMillis() / MILLIS_PER_SECOND
        val expiresAt = claims.expiresAt
        if (expiresAt != null && expiresAt <= nowSeconds) return refused(ActivationRefusal.EXPIRED)
        if (claims.licenseId in revoked) return refused(ActivationRefusal.REVOKED)
        store.setLicense(License(claims.licenseId, claims.plan, token, clock.nowMillis()))
        SmLog.i(TAG, "license_activated")
        return ActivationResult.Activated(claims.licenseId, claims.plan)
    }

    /** « Retirer l'activation »: back to the free plan, with the balance as it was. */
    suspend fun deactivate() {
        store.clearLicense()
        SmLog.i(TAG, "license_removed")
    }

    private fun refused(reason: ActivationRefusal): ActivationResult {
        SmLog.i(TAG, "license_refused", "reason" to reason.ordinal)
        return ActivationResult.Refused(reason)
    }

    companion object {
        private const val TAG = "License"
        private const val MILLIS_PER_SECOND = 1_000L
        private const val REVOCATION_LIST = "license/revoked_licenses.json"

        private fun TokenRefusal.toActivation(): ActivationRefusal = when (this) {
            TokenRefusal.TOO_LONG, TokenRefusal.MALFORMED, TokenRefusal.BAD_PAYLOAD -> ActivationRefusal.UNREADABLE

            TokenRefusal.BAD_ALGORITHM, TokenRefusal.UNKNOWN_KEY, TokenRefusal.WRONG_PURPOSE,
            TokenRefusal.BAD_SIGNATURE,
            -> ActivationRefusal.INVALID_SIGNATURE
        }

        /** The revocation list shipped with the application (`license/revoked_licenses.json`). */
        fun embeddedRevocations(): Set<String> {
            val stream = LicenseActivator::class.java.classLoader?.getResourceAsStream(REVOCATION_LIST)
                ?: error("$REVOCATION_LIST missing")
            return stream.bufferedReader().use { parseRevocations(it.readText()) }
        }

        /** `{"revoked": ["<lid>", …]}`; a malformed list is a build error: it throws. */
        fun parseRevocations(json: String): Set<String> =
            Json.parseToJsonElement(json).jsonObject.getValue("revoked").jsonArray
                .map { it.jsonPrimitive.content }
                .toSet()
    }
}
