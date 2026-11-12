package fr.corsicajack.seekmind.engine.capture.routing

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.inputmethod.InputMethodManager

/**
 * Packages whose events are dropped on the main thread, unread (docs/specs/capture.md §5.3): SeekMind
 * itself, the system UI, the launchers (activities answering MAIN + HOME) and the enabled input methods.
 * [compute] makes inter-process calls: capture thread only.
 */
class IgnoredPackages(private val context: Context) {
    fun compute(): Set<String> = buildSet {
        add(context.packageName)
        add(SYSTEM_UI)
        addAll(launchers())
        addAll(inputMethods())
    }

    private fun launchers(): List<String> {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return context.packageManager.queryIntentActivities(home, PackageManager.MATCH_ALL)
            .mapNotNull { it.activityInfo?.packageName }
    }

    private fun inputMethods(): List<String> {
        val enabled = context.getSystemService(InputMethodManager::class.java)
            ?.enabledInputMethodList.orEmpty()
            .map { it.packageName }
        // The current keyboard, even when the list above is filtered by package visibility.
        val current = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')
            ?.takeIf { it.isNotEmpty() }
        return enabled + listOfNotNull(current)
    }

    private companion object {
        const val SYSTEM_UI = "com.android.systemui"
    }
}
