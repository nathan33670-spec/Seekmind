package fr.corsicajack.seekmind.fixture

import android.widget.TextView

/**
 * Target of the return to the source (E2E-14, docs/specs/retour-source.md §7): opened by
 * `seekmind-fixture://item/<id>`, it shows the identifier it received, which the test reads. Its own
 * text names the item `@item42`, from which the shipped rule of the fixture builds the link back.
 */
class DeepLinkActivity : FixtureActivity() {
    override fun addContent() {
        addText(R.string.fixture_deep_link_marker)
        val id = intent?.data?.lastPathSegment.orEmpty()
        addView(
            TextView(this).apply {
                text = getString(R.string.fixture_deep_link_item, id)
                textSize = 18f
            },
        )
    }
}
