package fr.corsicajack.seekmind.fixture

import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** A long list in a scroll view, scrolled by the test (E2E-07: captures are spaced out while it moves). */
class ScrollActivity : FixtureActivity() {
    override fun addContent() {
        val lines = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        repeat(LINES) { index ->
            lines.addView(
                TextView(this).apply {
                    text = getString(R.string.fixture_scroll_line, index)
                    textSize = 18f
                    setPadding(0, 24, 0, 24)
                },
            )
        }
        addView(ScrollView(this).apply { addView(lines) })
    }

    private companion object {
        const val LINES = 300
    }
}
