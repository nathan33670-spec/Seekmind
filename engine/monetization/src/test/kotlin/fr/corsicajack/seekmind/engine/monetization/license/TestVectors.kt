package fr.corsicajack.seekmind.engine.monetization.license

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** testdata/tokens: the vectors and the keyring of the test key `l-test` (tools/license/gen_test_vectors.py). */
object TestVectors {
    data class Vector(val name: String, val token: String, val expected: String)

    private val dir = File(checkNotNull(System.getProperty("seekmind.tokens")) { "seekmind.tokens not set" })

    val all: List<Vector> by lazy {
        Json.parseToJsonElement(File(dir, "vectors.json").readText()).jsonObject.getValue("vectors").jsonArray.map {
            val v = it.jsonObject
            Vector(
                v.getValue("name").jsonPrimitive.content,
                v.getValue("token").jsonPrimitive.content,
                v.getValue("expected").jsonPrimitive.content,
            )
        }
    }

    fun named(name: String): Vector = all.single { it.name == name }

    val testKeyring: Keyring by lazy { Keyring(Keyring.parse(File(dir, "keys-test.json").readText())) }
}
