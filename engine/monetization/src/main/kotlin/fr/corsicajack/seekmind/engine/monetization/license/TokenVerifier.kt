package fr.corsicajack.seekmind.engine.monetization.license

import java.security.Signature
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** Why a token is refused (docs/specs/monetisation.md §5.2), in the order of the checks. */
enum class TokenRefusal {
    /** More than [TokenVerifier.MAX_LENGTH] characters. */
    TOO_LONG,

    /** Not three non-empty segments of base64url, or a header that is not a JSON object. */
    MALFORMED,

    /** `alg` other than exactly `ES256`, `none` included. */
    BAD_ALGORITHM,

    /** `kid` absent or not in the embedded keyring. */
    UNKNOWN_KEY,

    /** The key, or the token's `typ`, is for another use. */
    WRONG_PURPOSE,

    BAD_SIGNATURE,

    /** The payload is not JSON, or a field is missing or of the wrong type. */
    BAD_PAYLOAD,
}

/** What a valid licence token says. Times in seconds since the epoch. */
data class LicenseClaims(val licenseId: String, val plan: String, val issuedAt: Long, val expiresAt: Long?)

sealed interface Verification {
    data class Valid(val claims: LicenseClaims) : Verification

    data class Refused(val reason: TokenRefusal) : Verification
}

/**
 * Verification of an activation code (docs/specs/monetisation.md §5.1, §5.2): a compact JWS ES256 whose
 * signature is the raw `r ‖ s` of 64 bytes. Pure Kotlin and JCA. Checks expiry and revocation are the
 * activator's (§5.4), not this class's.
 */
class TokenVerifier(private val keyring: Keyring) {
    fun verifyLicense(token: String): Verification = verify(token, PURPOSE_LICENSE)

    fun verify(token: String, purpose: String): Verification {
        // 1. Length and shape.
        if (token.length > MAX_LENGTH) return refused(TokenRefusal.TOO_LONG)
        val segments = token.split('.')
        if (segments.size != 3 || segments.any { it.isEmpty() || !BASE64URL.matches(it) }) {
            return refused(TokenRefusal.MALFORMED)
        }
        val (headSegment, bodySegment, signatureSegment) = segments
        val header = decodeObject(headSegment) ?: return refused(TokenRefusal.MALFORMED)

        // 2. Algorithm, exactly.
        if ((header["alg"] as? JsonPrimitive)?.takeIf { it.isString }?.content != ALGORITHM) {
            return refused(TokenRefusal.BAD_ALGORITHM)
        }

        // 3. Known key, for this use.
        val kid = (header["kid"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val key = kid?.let { keyring[it] } ?: return refused(TokenRefusal.UNKNOWN_KEY)
        if (key.purpose != purpose) return refused(TokenRefusal.WRONG_PURPOSE)

        // 4. Signature: raw r ‖ s to DER, then SHA256withECDSA.
        val raw = decode(signatureSegment) ?: return refused(TokenRefusal.MALFORMED)
        val der = toDer(raw) ?: return refused(TokenRefusal.BAD_SIGNATURE)
        val valid = try {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(key.publicKey)
                update("$headSegment.$bodySegment".toByteArray(Charsets.US_ASCII))
                verify(der)
            }
        } catch (_: java.security.GeneralSecurityException) {
            false
        }
        if (!valid) return refused(TokenRefusal.BAD_SIGNATURE)

        // 5. Payload.
        val payload = decodeObject(bodySegment) ?: return refused(TokenRefusal.BAD_PAYLOAD)
        val typ = payload.string("typ") ?: return refused(TokenRefusal.BAD_PAYLOAD)
        if (typ != key.purpose) return refused(TokenRefusal.WRONG_PURPOSE)
        val claims = parseClaims(payload) ?: return refused(TokenRefusal.BAD_PAYLOAD)
        return Verification.Valid(claims)
    }

    private fun parseClaims(payload: JsonObject): LicenseClaims? {
        val lid = payload.string("lid")?.takeIf { it.isNotEmpty() } ?: return null
        val plan = payload.string("plan")?.takeIf { it.isNotEmpty() } ?: return null
        val iat = payload.long("iat") ?: return null
        val exp = if ("exp" in payload) payload.long("exp") ?: return null else null
        return LicenseClaims(lid, plan, iat, exp)
    }

    private fun refused(reason: TokenRefusal) = Verification.Refused(reason)

    private fun decode(segment: String): ByteArray? = try {
        Base64.getUrlDecoder().decode(segment)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun decodeObject(segment: String): JsonObject? {
        val bytes = decode(segment) ?: return null
        return try {
            Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: CharacterCodingException) {
            null
        }
    }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf {
        it.isString
    }?.content

    private fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.takeIf {
        !it.isString
    }?.longOrNull

    companion object {
        const val MAX_LENGTH = 2_048
        const val PURPOSE_LICENSE = "license"
        private const val ALGORITHM = "ES256"
        private const val COORDINATE_BYTES = 32
        private val BASE64URL = Regex("^[A-Za-z0-9_-]+$")

        /** `r ‖ s` (64 bytes) to the DER sequence of two integers that JCA verifies; null otherwise. */
        fun toDer(raw: ByteArray): ByteArray? {
            if (raw.size != 2 * COORDINATE_BYTES) return null
            val r = integer(raw.copyOfRange(0, COORDINATE_BYTES))
            val s = integer(raw.copyOfRange(COORDINATE_BYTES, raw.size))
            val body = r + s
            return byteArrayOf(0x30, body.size.toByte()) + body
        }

        /** A DER INTEGER: leading zeros dropped, one zero added when the high bit is set. */
        private fun integer(value: ByteArray): ByteArray {
            var start = 0
            while (start < value.size - 1 && value[start] == 0.toByte()) start++
            var magnitude = value.copyOfRange(start, value.size)
            if (magnitude[0].toInt() and 0x80 != 0) magnitude = byteArrayOf(0) + magnitude
            return byteArrayOf(0x02, magnitude.size.toByte()) + magnitude
        }
    }
}
