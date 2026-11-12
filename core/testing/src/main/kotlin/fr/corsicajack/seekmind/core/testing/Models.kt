package fr.corsicajack.seekmind.core.testing

import fr.corsicajack.seekmind.core.common.IntRect
import fr.corsicajack.seekmind.core.database.store.NewCapture
import fr.corsicajack.seekmind.core.model.Capture
import fr.corsicajack.seekmind.core.model.RawCapture
import fr.corsicajack.seekmind.core.model.ScreenMeta
import fr.corsicajack.seekmind.core.model.TextLine
import fr.corsicajack.seekmind.core.model.TextSource

/** Factories of domain models with plausible defaults; every value is invented. */
object Models {
    const val PACKAGE = "com.example.notes"
    const val AT = 1_794_441_600_000L

    fun screenMeta(
        packageName: String = PACKAGE,
        activityName: String? = "$packageName.MainActivity",
        title: String? = null,
        at: Long = AT,
    ) = ScreenMeta(
        packageName = packageName,
        activityName = activityName,
        windowTitle = title,
        windowId = 1,
        capturedAt = at,
    )

    fun textLine(text: String, top: Int = 0) = TextLine(text, IntRect(0, top, 1_000, top + 40), fromDescription = false)

    fun rawCapture(text: String, meta: ScreenMeta = screenMeta(), url: String? = null) = RawCapture(
        meta = meta,
        url = url,
        text = text,
        textSource = TextSource.TREE,
        treeSimHash = text.hashCode().toLong(),
    )

    fun newCapture(
        text: String,
        at: Long = AT,
        packageName: String = PACKAGE,
        title: String? = null,
        url: String? = null,
    ) = NewCapture(at, at, packageName, null, title, url, text, TextSource.TREE, text.hashCode().toLong())

    fun capture(text: String, id: Long = 1, at: Long = AT, packageName: String = PACKAGE, title: String? = null) =
        Capture(
            id = id,
            firstSeenAt = at,
            lastSeenAt = at,
            seenCount = 1,
            packageName = packageName,
            activityName = null,
            windowTitle = title,
            url = null,
            text = text,
            textSource = TextSource.TREE,
            charCount = text.length,
        )
}
