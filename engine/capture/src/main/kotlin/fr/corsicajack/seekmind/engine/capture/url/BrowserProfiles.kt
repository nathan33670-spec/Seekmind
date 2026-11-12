package fr.corsicajack.seekmind.engine.capture.url

import android.content.Context
import fr.corsicajack.seekmind.engine.capture.R
import org.json.JSONObject

/**
 * How to read a browser (docs/specs/capture.md §5.2): [urlBarId] is the resource name of its address
 * bar (`<package>:id/<urlBarId>`), [pageTitleClass] the class of the node that carries the page title.
 */
data class BrowserProfile(val urlBarId: String, val pageTitleClass: String)

/** The browser table (`res/raw/browser_profiles.json`), by package. */
class BrowserProfiles(private val byPackage: Map<String, BrowserProfile>) {
    val packages: Set<String> get() = byPackage.keys

    /** Classes to give TreeExtractor, so that it reads the page title. */
    val pageTitleClasses: Set<String> get() = byPackage.values.mapTo(HashSet()) { it.pageTitleClass }

    fun profileFor(packageName: String): BrowserProfile? = byPackage[packageName]

    /** This table with [packageName] added or replaced (end-to-end tests use the fixture app). */
    fun with(packageName: String, profile: BrowserProfile) = BrowserProfiles(byPackage + (packageName to profile))

    companion object {
        private const val VERSION = 1
        private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
        private val VIEW_ID = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
        private val CLASS_NAME = Regex("^[A-Za-z_][A-Za-z0-9_$]*(\\.[A-Za-z_][A-Za-z0-9_$]*)+$")

        fun load(context: Context): BrowserProfiles = parse(
            context.resources.openRawResource(R.raw.browser_profiles).bufferedReader().use { it.readText() },
        )

        /** Parses and validates the table; a malformed table is a build error, it throws. */
        fun parse(json: String): BrowserProfiles {
            val root = JSONObject(json)
            require(root.getInt("version") == VERSION) { "unsupported browser table version" }
            val defaultTitleClass = root.getString("pageTitleClass")
            val browsers = root.getJSONArray("browsers")
            val byPackage = LinkedHashMap<String, BrowserProfile>()
            for (i in 0 until browsers.length()) {
                val entry = browsers.getJSONObject(i)
                val profile = BrowserProfile(
                    urlBarId = entry.getString("urlBarId"),
                    pageTitleClass = entry.optString("pageTitleClass", defaultTitleClass),
                )
                require(VIEW_ID.matches(profile.urlBarId)) { "invalid view id at entry $i" }
                require(CLASS_NAME.matches(profile.pageTitleClass)) { "invalid class name at entry $i" }
                val packages = entry.getJSONArray("packages")
                require(packages.length() > 0) { "no package at entry $i" }
                for (j in 0 until packages.length()) {
                    val name = packages.getString(j)
                    require(PACKAGE.matches(name)) { "invalid package at entry $i" }
                    require(byPackage.put(name, profile) == null) { "duplicate package at entry $i" }
                }
            }
            return BrowserProfiles(byPackage)
        }
    }
}
