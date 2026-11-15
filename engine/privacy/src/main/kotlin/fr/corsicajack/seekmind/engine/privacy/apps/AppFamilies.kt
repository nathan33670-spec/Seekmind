package fr.corsicajack.seekmind.engine.privacy.apps

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Telephony
import fr.corsicajack.seekmind.core.model.AppFamily
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

/** What the classification needs to know about the installed applications. */
interface PackageFacts {
    fun defaultSmsPackage(): String?

    /** Applications answering `mailto:` or `smsto:`. */
    fun messagingHandlers(): Set<String>

    /** Applications answering a `VIEW` of any `https` address. */
    fun browserHandlers(): Set<String>

    /** `ApplicationInfo.category`, [ApplicationInfo.CATEGORY_UNDEFINED] when unknown. */
    fun category(packageName: String): Int
}

/** [PackageFacts] from the package manager; inter-process calls, off the main thread. */
class AndroidPackageFacts(private val context: Context) : PackageFacts {
    private val packageManager get() = context.packageManager

    override fun defaultSmsPackage(): String? = Telephony.Sms.getDefaultSmsPackage(context)

    override fun messagingHandlers(): Set<String> = handlers(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))) +
        handlers(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")))

    override fun browserHandlers(): Set<String> =
        handlers(Intent(Intent.ACTION_VIEW, Uri.parse("https://")).addCategory(Intent.CATEGORY_BROWSABLE))

    override fun category(packageName: String): Int = try {
        packageManager.getApplicationInfo(packageName, 0).category
    } catch (_: PackageManager.NameNotFoundException) {
        ApplicationInfo.CATEGORY_UNDEFINED
    }

    private fun handlers(intent: Intent): Set<String> =
        packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .mapNotNullTo(HashSet()) { it.activityInfo?.packageName }
}

/** The built-in lists of `app_families.json` (docs/specs/confidentialite.md §3.4). */
data class FamilyLists(val messaging: Set<String>, val social: Set<String>) {
    companion object {
        private const val VERSION = 1

        fun load(context: Context): FamilyLists =
            parse(context.assets.open("app_families.json").bufferedReader().use { it.readText() })

        fun parse(json: String): FamilyLists {
            val root = JSONObject(json)
            require(root.getInt("version") == VERSION) { "unsupported family list version" }
            fun set(name: String) =
                root.getJSONArray(name).let { a -> (0 until a.length()).mapTo(HashSet()) { a.getString(it) } }
            return FamilyLists(set("messaging"), set("social"))
        }
    }
}

/**
 * Classifies an application into one family (docs/specs/confidentialite.md §3.4): the first that applies,
 * in the order MESSAGING, BROWSERS, SOCIAL_MEDIA, OTHER. Sensitive applications belong to none: they stay
 * excluded whatever the families. [browserPackages] are those of the browser table. Answers are cached
 * until [invalidate], at every installation or removal.
 */
class AppFamilies(
    private val facts: PackageFacts,
    private val lists: FamilyLists,
    private val browserPackages: Set<String>,
    private val sensitive: SensitiveApps,
) {
    private class Snapshot(val defaultSms: String?, val messaging: Set<String>, val browsers: Set<String>)

    @Volatile
    private var snapshot: Snapshot? = null
    private val cache = ConcurrentHashMap<String, Any>()

    /** Bumped by [invalidate]: an answer computed before it is not cached. */
    @Volatile
    private var generation = 0

    fun familyOf(packageName: String): AppFamily? {
        cache[packageName]?.let { return it as? AppFamily }
        val before = generation
        val answer = compute(packageName) ?: NONE
        if (generation == before) cache[packageName] = answer
        return answer as? AppFamily
    }

    /**
     * At every installation or removal, and when the default SMS application changes
     * (`Telephony.Sms.Intents.ACTION_DEFAULT_SMS_PACKAGE_CHANGED`, wired with the pipeline in M2-06).
     */
    fun invalidate() {
        generation++
        snapshot = null
        cache.clear()
    }

    private fun compute(packageName: String): AppFamily? {
        if (sensitive.levelOf(packageName) != null) return null
        val facts = snapshot ?: Snapshot(
            this.facts.defaultSmsPackage(),
            this.facts.messagingHandlers(),
            this.facts.browserHandlers(),
        ).also { snapshot = it }
        return when {
            packageName == facts.defaultSms || packageName in facts.messaging || packageName in lists.messaging ->
                AppFamily.MESSAGING

            packageName in browserPackages || packageName in facts.browsers -> AppFamily.BROWSERS

            this.facts.category(packageName).let {
                it == ApplicationInfo.CATEGORY_SOCIAL || it == ApplicationInfo.CATEGORY_VIDEO
            } || packageName in lists.social -> AppFamily.SOCIAL_MEDIA

            else -> AppFamily.OTHER
        }
    }

    private companion object {
        val NONE = Any()
    }
}
