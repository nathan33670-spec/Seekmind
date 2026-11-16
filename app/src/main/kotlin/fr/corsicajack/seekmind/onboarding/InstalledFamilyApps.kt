package fr.corsicajack.seekmind.onboarding

import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import fr.corsicajack.seekmind.core.model.AppFamily
import fr.corsicajack.seekmind.engine.privacy.apps.AppFamilies
import fr.corsicajack.seekmind.feature.onboarding.FamilyApp

/**
 * A few installed applications per family, for the families screen of the welcome flow (docs/specs/ui-ux.md
 * §3.1): launchable apps, classified as the privacy gate does. Sensitive apps have no family and are not
 * shown. Call it off the main thread: the package manager is read.
 */
class InstalledFamilyApps(private val packageManager: PackageManager, private val families: AppFamilies) {
    fun load(perFamily: Int = PER_FAMILY): Map<AppFamily, List<FamilyApp>> {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val byFamily = mutableMapOf<AppFamily, MutableList<FamilyApp>>()
        for (info in packageManager.queryIntentActivities(launcher, 0).distinctBy { it.activityInfo.packageName }) {
            val family = families.familyOf(info.activityInfo.packageName) ?: continue
            val list = byFamily.getOrPut(family) { mutableListOf() }
            if (list.size >= perFamily) continue
            val icon = runCatching {
                info.loadIcon(packageManager).toBitmap(ICON_PX, ICON_PX).asImageBitmap()
            }.getOrNull()
            list += FamilyApp(info.loadLabel(packageManager).toString(), icon)
        }
        return byFamily
    }

    private companion object {
        const val PER_FAMILY = 3
        const val ICON_PX = 96
    }
}
