package fr.corsicajack.seekmind.engine.source

import android.content.ComponentName
import android.content.Intent
import fr.corsicajack.seekmind.core.model.Capture
import fr.corsicajack.seekmind.core.model.SourceLevel
import fr.corsicajack.seekmind.core.model.TextSource

/** Installed applications of a test: package → label; [noLauncher] have no launcher activity. */
internal class FakeApps(private val installed: Map<String, String>, private val noLauncher: Set<String> = emptySet()) :
    InstalledApps {
    override fun isInstalled(packageName: String) = packageName in installed

    override fun launchIntent(packageName: String): Intent? =
        if (packageName in installed && packageName !in noLauncher) {
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(ComponentName(packageName, "$packageName.MainActivity"))
        } else {
            null
        }

    override fun label(packageName: String) = installed[packageName]
}

/** A rule whose URI is given by [uri], null meaning a missing variable. */
internal class FakeRule(
    override val packageName: String,
    override val level: SourceLevel,
    override val scheme: String = "https",
    private val uri: (Capture) -> String?,
) : AppRule {
    override fun uriFor(capture: Capture): String? = uri(capture)
}

internal fun capture(
    packageName: String,
    url: String? = null,
    text: String = "Recette de la tarte aux pommes",
    title: String? = null,
) = Capture(
    id = 1,
    firstSeenAt = 0,
    lastSeenAt = 0,
    seenCount = 1,
    packageName = packageName,
    activityName = null,
    windowTitle = title,
    url = url,
    text = text,
    textSource = TextSource.TREE,
    charCount = text.length,
    simhash = 0,
)

internal const val CHROME = "com.android.chrome"
internal const val NOTES = "com.example.notes"
internal const val VIDEOS = "com.example.videos"
