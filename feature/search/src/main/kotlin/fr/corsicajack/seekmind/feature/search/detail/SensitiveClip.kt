package fr.corsicajack.seekmind.feature.search.detail

import android.content.ClipData
import android.content.ClipDescription
import android.os.Build
import android.os.PersistableBundle

/**
 * Text copied by the user from a capture (INV-06: only on an explicit action), marked as sensitive so
 * that the system hides its preview and keyboards do not suggest it (EXTRA_IS_SENSITIVE, API 33; the
 * same key is honoured by earlier versions that know it).
 */
fun sensitiveClip(label: String, text: String): ClipData = ClipData.newPlainText(label, text).apply {
    description.extras = PersistableBundle().apply { putBoolean(IS_SENSITIVE, true) }
}

/** ClipDescription.EXTRA_IS_SENSITIVE, a constant from API 33 that the string reaches on earlier versions too. */
internal val IS_SENSITIVE: String =
    if (Build.VERSION.SDK_INT >=
        Build.VERSION_CODES.TIRAMISU
    ) {
        ClipDescription.EXTRA_IS_SENSITIVE
    } else {
        "android.content.extra.IS_SENSITIVE"
    }

/**
 * The clipboard of the detail screen: every copy, the selection of the text included, is marked sensitive
 * before it reaches the system clipboard.
 */
class SensitiveClipboard(private val delegate: androidx.compose.ui.platform.Clipboard) :
    androidx.compose.ui.platform.Clipboard {
    override suspend fun getClipEntry(): androidx.compose.ui.platform.ClipEntry? = delegate.getClipEntry()

    override suspend fun setClipEntry(clipEntry: androidx.compose.ui.platform.ClipEntry?) {
        clipEntry?.clipData?.description?.let { description ->
            description.extras = (description.extras ?: PersistableBundle()).apply { putBoolean(IS_SENSITIVE, true) }
        }
        delegate.setClipEntry(clipEntry)
    }
}
