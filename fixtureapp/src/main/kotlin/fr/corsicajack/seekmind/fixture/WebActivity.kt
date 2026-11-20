package fr.corsicajack.seekmind.fixture

import android.text.InputType
import android.webkit.WebView
import android.widget.EditText
import android.widget.LinearLayout

/**
 * A browser-like screen (URL extraction): a fake address bar identified `url_bar`, as in Chrome, above
 * a web view that shows local HTML. No network: the page has no external resource. A line of plain text
 * sits under the bar: some emulators expose nothing of the web view, and a screen without any text in the
 * tree would share the fingerprint of ImageTextActivity in the memory of the OCR policy (ocr §5).
 */
class WebActivity : FixtureActivity() {
    override fun addContent() {
        addView(
            EditText(this).apply {
                id = R.id.url_bar
                setText(R.string.fixture_web_address)
                inputType = InputType.TYPE_TEXT_VARIATION_URI
                isSingleLine = true
                // Not focusable: the bar shows the page's address, it is not being typed in.
                isFocusable = false
            },
        )
        addText(R.string.fixture_web_toolbar)
        addView(
            WebView(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 800)
                loadDataWithBaseURL(
                    getString(R.string.fixture_web_base_url),
                    getString(R.string.fixture_web_html),
                    "text/html",
                    "utf-8",
                    null,
                )
            },
        )
    }
}
