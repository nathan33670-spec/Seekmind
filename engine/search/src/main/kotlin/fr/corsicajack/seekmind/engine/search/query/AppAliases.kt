package fr.corsicajack.seekmind.engine.search.query

import android.content.res.Resources
import fr.corsicajack.seekmind.engine.search.R
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Aliases of applications (`res/raw/search_app_aliases.json`): "insta" → Instagram's package. */
object AppAliases {
    fun load(resources: Resources): Map<String, Set<String>> =
        parse(resources.openRawResource(R.raw.search_app_aliases).bufferedReader(Charsets.UTF_8).use { it.readText() })

    /** `{"aliases": {"insta": ["com.instagram.android"], …}}`. */
    fun parse(json: String): Map<String, Set<String>> =
        Json.parseToJsonElement(json).jsonObject.getValue("aliases").jsonObject.mapValues { (_, packages) ->
            packages.jsonArray.mapTo(LinkedHashSet()) { it.jsonPrimitive.content }
        }
}
