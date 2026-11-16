package fr.corsicajack.seekmind.engine.monetization.license

import java.security.KeyFactory
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A public key the application trusts, for one [purpose] (docs/specs/monetisation.md §5.2). */
class TrustedKey(val kid: String, val purpose: String, val publicKey: PublicKey)

/**
 * The embedded keyring: `license/keys-release.json` in every build, plus `license/keys-debug.json` in the
 * debug build only (the test key `l-test`). A release build therefore refuses a token of the test key.
 */
class Keyring(keys: List<TrustedKey>) {
    private val byKid = keys.associateBy { it.kid }

    operator fun get(kid: String): TrustedKey? = byKid[kid]

    val kids: Set<String> get() = byKid.keys

    companion object {
        private const val RELEASE = "license/keys-release.json"
        private const val DEBUG = "license/keys-debug.json"
        private const val P256_FIELD_BITS = 256

        /** The keyrings of the build, read from the Java resources of this module. */
        fun embedded(): Keyring = Keyring(listOfNotNull(RELEASE, DEBUG).flatMap(::resource))

        private fun resource(name: String): List<TrustedKey> {
            val stream = Keyring::class.java.classLoader?.getResourceAsStream(name) ?: return emptyList()
            return stream.bufferedReader().use { parse(it.readText()) }
        }

        /**
         * Parses a keyring; an entry with an empty `spki` is a placeholder (the production key `l1` until
         * it is generated) and is skipped. A malformed keyring is a build error: it throws.
         */
        fun parse(json: String): List<TrustedKey> = Json.parseToJsonElement(json).jsonObject.getValue("keys").jsonArray
            .map { it.jsonObject }
            .filter { it.getValue("spki").jsonPrimitive.content.isNotEmpty() }
            .map { entry ->
                val spki = Base64.getDecoder().decode(entry.getValue("spki").jsonPrimitive.content)
                val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))
                require(key is ECPublicKey && key.params.curve.field.fieldSize == P256_FIELD_BITS) {
                    "not a P-256 key"
                }
                TrustedKey(
                    kid = entry.getValue("kid").jsonPrimitive.content,
                    purpose = entry.getValue("purpose").jsonPrimitive.content,
                    publicKey = key,
                )
            }
    }
}
