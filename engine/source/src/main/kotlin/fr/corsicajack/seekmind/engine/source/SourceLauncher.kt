package fr.corsicajack.seekmind.engine.source

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import fr.corsicajack.seekmind.core.common.SmLog
import fr.corsicajack.seekmind.core.model.SourceLevel
import fr.corsicajack.seekmind.core.model.SourceRef
import java.net.URISyntaxException

/** Outcome of [SourceLauncher.launch]. */
sealed interface LaunchResult {
    /** Opened at the level of the [SourceRef]. */
    data object Launched : LaunchResult

    /** The level of the [SourceRef] failed; opened at [level] instead. */
    data class Fallback(val level: SourceLevel) : LaunchResult

    /** Nothing could be opened, or the Intent was refused. */
    data object Failed : LaunchResult
}

/**
 * Opens a [SourceRef] (docs/specs/retour-source.md §5). A failed level falls back to the next one:
 * [SourceLevel.EXACT_URL] in another browser, then nothing; [SourceLevel.APP_LINK] and
 * [SourceLevel.APP_SEARCH] to the application itself, also when their Intent is refused. The Intent is
 * checked before it leaves: only admitted schemes, no selector, no permission grant. It never throws.
 */
class SourceLauncher(private val apps: InstalledApps, private val schemes: SourceSchemes = SourceSchemes()) {
    fun launch(context: Context, ref: SourceRef): LaunchResult = launch(ref) { context.startActivity(it) }

    /** [launch] with the way activities are started; for tests. */
    internal fun launch(ref: SourceRef, start: (Intent) -> Unit): LaunchResult {
        if (ref.level == SourceLevel.UNAVAILABLE) return LaunchResult.Failed
        val parsed = ref.intentUri?.let(::parse) ?: return refused(ref.level)
        val target = parsed.`package` ?: parsed.component?.packageName
        val intent = checked(parsed, ref.level)
        if (intent != null && tryStart(intent, start)) return LaunchResult.Launched
        if (intent == null) refused(ref.level)
        return when (ref.level) {
            SourceLevel.EXACT_URL ->
                if (intent != null && target != null && tryStart(Intent(intent).setPackage(null), start)) {
                    LaunchResult.Fallback(SourceLevel.EXACT_URL)
                } else {
                    failed(ref.level)
                }

            SourceLevel.APP_LINK, SourceLevel.APP_SEARCH -> {
                val home = target?.let(apps::launchIntent)?.let { checked(it, SourceLevel.APP_ONLY) }
                if (home != null && tryStart(home, start)) {
                    LaunchResult.Fallback(SourceLevel.APP_ONLY)
                } else {
                    failed(ref.level)
                }
            }

            SourceLevel.APP_ONLY, SourceLevel.UNAVAILABLE -> failed(ref.level)
        }
    }

    private fun parse(uri: String): Intent? = try {
        Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)
    } catch (_: URISyntaxException) {
        null
    }

    /**
     * The Intent as it may leave SeekMind, or null if it is refused: a view of an admitted scheme, or
     * the launcher activity of an application for [SourceLevel.APP_ONLY].
     */
    private fun checked(intent: Intent, level: SourceLevel): Intent? {
        val allowed = when (level) {
            SourceLevel.APP_ONLY -> intent.action == Intent.ACTION_MAIN && intent.data == null

            SourceLevel.UNAVAILABLE -> false

            else -> intent.action == Intent.ACTION_VIEW && schemes.isAllowed(intent.data?.scheme) &&
                intent.component == null
        }
        if (!allowed) return null
        return Intent(intent).apply {
            selector = null
            clipData = null
            flags = (flags and GRANTS.inv()) or Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    private fun tryStart(intent: Intent, start: (Intent) -> Unit): Boolean = try {
        start(intent)
        true
    } catch (error: ActivityNotFoundException) {
        SmLog.w(TAG, "source_start_failed", error)
        false
    } catch (error: SecurityException) {
        SmLog.w(TAG, "source_start_failed", error)
        false
    }

    private fun refused(level: SourceLevel): LaunchResult {
        SmLog.w(TAG, "source_intent_refused", null, "level" to level.ordinal)
        return LaunchResult.Failed
    }

    private fun failed(level: SourceLevel): LaunchResult {
        SmLog.w(TAG, "source_launch_failed", null, "level" to level.ordinal)
        return LaunchResult.Failed
    }

    private companion object {
        const val TAG = "SourceLauncher"
        const val GRANTS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
    }
}
