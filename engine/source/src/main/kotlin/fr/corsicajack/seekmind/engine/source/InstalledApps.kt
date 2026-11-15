package fr.corsicajack.seekmind.engine.source

import android.content.Intent
import android.content.pm.PackageManager

/** What the return to the source needs to know about the installed applications. */
interface InstalledApps {
    fun isInstalled(packageName: String): Boolean

    /** The launcher activity of [packageName], or null if it has none. */
    fun launchIntent(packageName: String): Intent?

    /** The name shown to the user, or null if the application is not installed. */
    fun label(packageName: String): String?
}

/** [InstalledApps] backed by the [PackageManager]; queries are limited by package visibility. */
class PackageManagerApps(private val packageManager: PackageManager) : InstalledApps {
    override fun isInstalled(packageName: String): Boolean = info(packageName) != null

    override fun launchIntent(packageName: String): Intent? = packageManager.getLaunchIntentForPackage(packageName)

    override fun label(packageName: String): String? =
        info(packageName)?.let { packageManager.getApplicationLabel(it).toString() }

    private fun info(packageName: String) = try {
        packageManager.getApplicationInfo(packageName, 0)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }
}
