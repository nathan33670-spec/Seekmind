package fr.corsicajack.seekmind.engine.monetization.license

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlin.random.Random
import org.junit.Test

/**
 * Robustness of what an activation link or a pasted code goes through (ticket M10-05, sécurité §8):
 * random, oversized and altered inputs never throw, and nothing but an intact signed token is valid.
 * Seeded: a failure names its input and replays.
 */
class ActivationFuzzTest {
    private val verifier = TokenVerifier(TestVectors.testKeyring)
    private val valid = TestVectors.named("valid").token
    private val validClaims = (verifier.verifyLicense(valid) as Verification.Valid).claims
    private val random = Random(SEED)

    private fun randomText(): String {
        val alphabet = ALPHABETS[random.nextInt(ALPHABETS.size)]
        val length = LENGTHS[random.nextInt(LENGTHS.size)].let { random.nextInt(it + 1) }
        return buildString(length) { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    @Test
    fun `random links and pastes never throw and give a well-formed code or nothing`() {
        repeat(RUNS) {
            val text = randomText()
            for (input in listOf(text, ActivationCode.LINK_PREFIX + text, "https://site.example/a#$text")) {
                val fromLink = runCatching { ActivationCode.fromLink(input) }
                assertWithMessage("fromLink(%s)", input.take(80)).that(fromLink.exceptionOrNull()).isNull()
                fromLink.getOrNull()?.let { code ->
                    assertThat(code).matches("[A-Za-z0-9_.-]+")
                    assertThat(code.length).isAtMost(TokenVerifier.MAX_LENGTH)
                }
                val fromInput = runCatching { ActivationCode.fromInput(input) }
                assertWithMessage("fromInput(%s)", input.take(80)).that(fromInput.exceptionOrNull()).isNull()
            }
        }
    }

    @Test
    fun `random tokens never throw and are never valid`() {
        repeat(RUNS) {
            val token = when (random.nextInt(3)) {
                0 -> randomText()
                1 -> List(3) { randomText() }.joinToString(".")
                else -> valid.split('.').let { (h, _, s) -> "$h.${randomText()}.$s" }
            }
            val result = runCatching { verifier.verifyLicense(token) }
            assertWithMessage("verifyLicense(%s)", token.take(80)).that(result.exceptionOrNull()).isNull()
            assertWithMessage("verifyLicense(%s)", token.take(80)).that(result.getOrNull())
                .isInstanceOf(Verification.Refused::class.java)
        }
    }

    @Test
    fun `an altered valid token never yields other claims`() {
        // Every single-character change, and truncations: refused, or the same claims (a change in the
        // unused low bits of the last base64url character decodes to the same bytes).
        val variants = valid.indices.flatMap { i ->
            listOf('A', 'z', '0', '-', '_', '.').filter { it != valid[i] }
                .map { valid.substring(0, i) + it + valid.substring(i + 1) }
        } + valid.indices.map { valid.substring(0, it) } + listOf("$valid.", "$valid=", ".$valid", valid + valid)
        for (token in variants) {
            when (val result = verifier.verifyLicense(token)) {
                is Verification.Valid -> assertWithMessage(token).that(result.claims).isEqualTo(validClaims)
                is Verification.Refused -> Unit
            }
        }
    }

    @Test
    fun `deeply nested or oversized headers are refused without error`() {
        fun b64(text: String) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())
        val (_, body, signature) = valid.split('.')
        val nested = b64("[".repeat(1_400) + "]".repeat(1_400)).take(TokenVerifier.MAX_LENGTH - body.length - 90)
        for (header in listOf(nested, b64("{\"alg\":\"ES256\",\"kid\":\"" + "k".repeat(1_500) + "\"}"), b64("null"))) {
            val token = "$header.$body.$signature"
            val result = runCatching { verifier.verifyLicense(token) }
            assertThat(result.exceptionOrNull()).isNull()
            assertThat(result.getOrNull()).isInstanceOf(Verification.Refused::class.java)
        }
    }

    private companion object {
        const val SEED = 20_261_120L
        const val RUNS = 5_000
        val ALPHABETS = listOf(
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_",
            "abcXYZ019-_.=+/ ?&#%:",
            "\u0000\u0001\n\r\t é ​‮😀﻿",
            "{}[]\":,. eyJhbGciOiJFUzI1NiJ9",
        )
        val LENGTHS = listOf(0, 8, 64, 600, 2_100, 6_000)
    }
}
