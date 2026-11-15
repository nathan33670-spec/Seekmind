package fr.corsicajack.seekmind.engine.search.catalog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import fr.corsicajack.seekmind.engine.search.query.AppEntry
import fr.corsicajack.seekmind.engine.search.query.AppMatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * The launchable applications of the phone (docs/specs/recherche.md §4, ticket M5-03), read from the
 * `PackageManager` off the main thread, kept in memory, and read again after an application is
 * installed, updated or removed. The manifest declares the launcher query (`<queries>`), so no
 * `QUERY_ALL_PACKAGES` is needed.
 */
class AndroidAppCatalog(
    private val context: Context,
    private val aliases: Map<String, Set<String>>,
    private val io: CoroutineDispatcher,
) {
    @Volatile private var cached: Snapshot? = null

    private class Snapshot(val apps: List<AppEntry>, val matcher: AppMatcher)

    internal val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = invalidate()
    }

    /** Listens to installations and removals; call once, with [close] at the end. */
    fun register() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        // System broadcasts reach a non-exported receiver.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun close() = context.unregisterReceiver(receiver)

    /** The next call reads the applications again. */
    fun invalidate() {
        cached = null
    }

    suspend fun apps(): List<AppEntry> = snapshot().apps

    /** The application filter for the installed applications. */
    suspend fun matcher(): AppMatcher = snapshot().matcher

    private suspend fun snapshot(): Snapshot = cached ?: withContext(io) {
        val apps = launchable()
        Snapshot(apps, AppMatcher(apps, aliases)).also { cached = it }
    }

    private fun launchable(): List<AppEntry> {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION") // ResolveInfoFlags needs API 33; the minimum is 30.
        return pm.queryIntentActivities(launcher, 0)
            .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }
}
