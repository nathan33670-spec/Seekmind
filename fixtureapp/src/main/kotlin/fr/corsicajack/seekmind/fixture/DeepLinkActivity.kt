package fr.corsicajack.seekmind.fixture

import android.widget.TextView

/**
 * Target of the return to the source (E2E-14, docs/specs/retour-source.md §7): opened by
 * `seekmind-fixture://item/<id>`, it shows the identifier it received, which the test reads.
 */
class DeepLinkActivity : FixtureActivity() {
    override fun addContent() {
        val id = intent?.data?.lastPathSegment.orEmpty()
        addView(
            TextView(this).apply {
                text = getString(R.string.fixture_deep_link_item, id)
                textSize = 18f
            },
        )
    }
}
