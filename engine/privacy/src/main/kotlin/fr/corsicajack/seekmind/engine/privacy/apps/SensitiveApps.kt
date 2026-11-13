package fr.corsicajack.seekmind.engine.privacy.apps

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * The lists of sensitive applications (docs/specs/confidentialite.md §3.1): the strict one (passwords,
 * authentication), which the user cannot override, and the soft one (banking, payment, health,
 * administration). A package is in a list when it is named there or when its lower-cased name matches
 * one of the list's patterns. Answers are cached.
 */
class SensitiveApps(
    private val strictPackages: Set<String>,
    private val strictPatterns: List<Regex>,
    private val softPackages: Set<String>,
    private val softPatterns: List<Regex>,
) {
    enum class Level { STRICT, SOFT }

    private val cache = ConcurrentHashMap<String, Any>()

    /** The list [packageName] belongs to, strict first; null when it is in none. */
    fun levelOf(packageName: String): Level? = cache.getOrPut(packageName) { compute(packageName) ?: NONE } as? Level

    val packageCount: Int get() = strictPackages.size + softPackages.size

    private fun compute(packageName: String): Level? {
        val lower = packageName.lowercase()
        return when {
            packageName in strictPackages || strictPatterns.any { it.containsMatchIn(lower) } -> Level.STRICT
            packageName in softPackages || softPatterns.any { it.containsMatchIn(lower) } -> Level.SOFT
            else -> null
        }
    }

    companion object {
        private const val VERSION = 1
        private const val ASSET = "sensitive_apps.json"
        private val NONE = Any()
        private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

        fun load(context: Context): SensitiveApps =
            parse(context.assets.open(ASSET).bufferedReader().use { it.readText() })

        /** Parses and validates the lists; a malformed asset is a build error, it throws. */
        fun parse(json: String): SensitiveApps {
            val root = JSONObject(json)
            require(root.getInt("version") == VERSION) { "unsupported sensitive list version" }
            val strict = root.getJSONObject("strict")
            val soft = root.getJSONObject("soft")
            val strictPackages = packages(strict.getJSONArray("packages"))
            val softPackages = packages(soft.getJSONArray("packages"))
            require(strictPackages.intersect(softPackages).isEmpty()) { "a package is in both lists" }
            return SensitiveApps(
                strictPackages,
                patterns(strict.getJSONArray("patterns")),
                softPackages,
                patterns(soft.getJSONArray("patterns")),
            )
        }

        private fun packages(array: JSONArray): Set<String> {
            val result = LinkedHashSet<String>()
            for (i in 0 until array.length()) {
                val name = array.getString(i)
                require(PACKAGE.matches(name)) { "invalid package at index $i" }
                require(result.add(name)) { "duplicate package at index $i" }
            }
            return result
        }

        private fun patterns(array: JSONArray): List<Regex> = (0 until array.length()).map {
            Regex(array.getString(it))
        }
    }
}
