package fr.corsicajack.seekmind.engine.capture.meta

import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build

/** Whether [className] is an activity of [packageName]. */
fun interface ActivityLookup {
    fun isActivity(packageName: String, className: String): Boolean
}

/** [ActivityLookup] through `PackageManager.getActivityInfo` (docs/specs/capture.md §5.1). */
class PackageManagerActivityLookup(private val packageManager: PackageManager) : ActivityLookup {
    override fun isActivity(packageName: String, className: String): Boolean = try {
        val component = ComponentName(packageName, className)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getActivityInfo(component, PackageManager.ComponentInfoFlags.of(0))
        } else {
            packageManager.getActivityInfo(component, 0)
        }
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}

/**
 * The activity on screen (docs/specs/capture.md §5.1). Several TYPE_WINDOW_STATE_CHANGED events follow
 * the opening of a screen and the last one may name a view container, a dialog or a menu: a class is
 * kept only when [lookup] says it is an activity of the event's package. Answers are cached, the
 * [cacheSize] most recent ones. Main thread only.
 */
class ActivityTracker(private val lookup: ActivityLookup, private val cacheSize: Int = CACHE_SIZE) {
    private val cache = object : LinkedHashMap<Pair<String, String>, Boolean>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, String>, Boolean>) =
            size > cacheSize
    }
    private var current: Pair<String, String>? = null

    fun onWindowStateChanged(packageName: String, className: String?) {
        if (className.isNullOrEmpty()) return
        val key = packageName to className
        val isActivity = cache.getOrPut(key) { lookup.isActivity(packageName, className) }
        if (isActivity) current = key
    }

    /** The last activity seen for [packageName]; null when the last activity was another app's. */
    fun activityOf(packageName: String): String? = current?.takeIf { it.first == packageName }?.second

    /** After a package is installed, updated or removed. */
    fun clearCache() = cache.clear()

    private companion object {
        const val CACHE_SIZE = 200
    }
}
