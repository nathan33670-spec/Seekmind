package fr.corsicajack.seekmind.apps

import android.content.pm.PackageManager
import fr.corsicajack.seekmind.feature.journal.AppLabels
import java.util.concurrent.ConcurrentHashMap

/**
 * Application labels from the [PackageManager], cached in memory (docs/specs/ui-ux.md §7). Call off the
 * main thread: the first lookup of a package is a Binder call. An uninstalled application shows its
 * package name.
 */
class PackageManagerAppLabels(private val packageManager: PackageManager) : AppLabels {
    private val cache = ConcurrentHashMap<String, String>()

    override fun label(packageName: String): String = cache.getOrPut(packageName) {
        try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }
    }
}
