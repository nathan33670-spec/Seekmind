package fr.corsicajack.seekmind.engine.source

import android.content.res.AssetManager
import fr.corsicajack.seekmind.core.common.SmLog
import fr.corsicajack.seekmind.core.model.Capture
import fr.corsicajack.seekmind.core.model.SourceLevel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A rule of `source_rules.json` (docs/specs/retour-source.md §4): [template] with `{variable}`
 * placeholders. The rule applies only when every variable it uses or [requires] is available.
 */
data class TemplateRule(
    override val packageName: String,
    override val level: SourceLevel,
    val template: String,
    val requires: Set<String>,
) : AppRule {
    override val scheme: String = SourceSchemes.schemeOf(template).orEmpty()

    /** The variables of the template, in order of appearance. */
    val placeholders: List<String> = PLACEHOLDER.findAll(template).map { it.groupValues[1] }.distinct().toList()

    override fun uriFor(capture: Capture): String? {
        val values = (placeholders + requires).distinct().associateWith { RuleVariables.encoded(it, capture) }
        if (values.values.any { it == null }) return null
        return PLACEHOLDER.replace(template) { values.getValue(it.groupValues[1]).orEmpty() }
    }

    companion object {
        internal val PLACEHOLDER = Regex("""\{([A-Za-z]+)\}""")
    }
}

/**
 * Loads the application rules: `assets/source_rules.json` of this module. Adding an application only
 * changes that file. A malformed rule is skipped (and counted in the log), never the whole file.
 */
object SourceRules {
    const val ASSET = "source_rules.json"
    const val VERSION = 1
    private const val TAG = "SourceRules"

    fun load(assets: AssetManager): List<TemplateRule> = try {
        parse(assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() })
    } catch (error: java.io.IOException) {
        SmLog.w(TAG, "source_rules_unreadable", error)
        emptyList()
    }

    /** `{"version": 1, "rules": [{"package", "level", "uri", "requires"}, …]}`. */
    fun parse(json: String): List<TemplateRule> {
        val root = try {
            Json.parseToJsonElement(json).jsonObject
        } catch (error: IllegalArgumentException) {
            SmLog.w(TAG, "source_rules_malformed", error)
            return emptyList()
        }
        val version = runCatching { root.getValue("version").jsonPrimitive.int }.getOrNull()
        if (version != VERSION) {
            SmLog.w(TAG, "source_rules_unknown_version", null, "version" to (version ?: -1))
            return emptyList()
        }
        val entries = runCatching { root.getValue("rules").jsonArray }.getOrElse { return emptyList() }
        val rules = entries.mapNotNull { entry -> runCatching { rule(entry.jsonObject) }.getOrNull() }
        if (rules.size != entries.size) {
            SmLog.w(TAG, "source_rules_skipped", null, "skipped" to entries.size - rules.size)
        }
        return rules
    }

    private fun rule(entry: JsonObject): TemplateRule? {
        val level = SourceLevel.valueOf(entry.getValue("level").jsonPrimitive.content)
        val rule = TemplateRule(
            packageName = entry.getValue("package").jsonPrimitive.content.trim(),
            level = level,
            template = entry.getValue("uri").jsonPrimitive.content.trim(),
            requires = entry["requires"]?.jsonArray?.mapTo(LinkedHashSet()) { it.jsonPrimitive.content }.orEmpty(),
        )
        val valid = rule.packageName.isNotEmpty() &&
            (level == SourceLevel.APP_LINK || level == SourceLevel.APP_SEARCH) &&
            rule.scheme.isNotEmpty() &&
            SourceSchemes(listOf(rule.scheme)).isAllowed(rule.scheme) &&
            (rule.placeholders + rule.requires).all { it in RuleVariables.NAMES } &&
            fixedTarget(rule)
        return rule.takeIf { valid }
    }

    /**
     * The content of a capture never chooses where the link goes: the scheme, and the host of a
     * hierarchical URI, come before the first variable.
     */
    private fun fixedTarget(rule: TemplateRule): Boolean {
        val prefix = rule.template.substringBefore('{')
        return if (prefix.startsWith("${rule.scheme}://", ignoreCase = true)) {
            val authority = prefix.substringAfter("://")
            authority.contains('/') && authority.substringBefore('/').isNotEmpty()
        } else {
            prefix.length > rule.scheme.length + 1
        }
    }
}
