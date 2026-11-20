package fr.corsicajack.seekmind.fixture

import android.widget.TextView

/**
 * Target of the return to the source (E2E-14, docs/specs/retour-source.md §7). Opened without a link, it
 * is the catalogue, whose text names the item `@item42`: the shipped rule of the fixture builds the link
 * back from it. Opened by `seekmind-fixture://item/<id>`, it is the item's own page, which shows the
 * identifier it received; its text differs from the catalogue's, so that it is not taken for a duplicate.
 */
class DeepLinkActivity : FixtureActivity() {
    override fun addContent() {
        val id = intent?.data?.lastPathSegment
        if (id.isNullOrEmpty()) {
            addText(R.string.fixture_deep_link_marker)
            return
        }
        addView(
            TextView(this).apply {
                text = getString(R.string.fixture_deep_link_item, id)
                textSize = 18f
            },
        )
        addText(R.string.fixture_deep_link_detail)
    }
}
