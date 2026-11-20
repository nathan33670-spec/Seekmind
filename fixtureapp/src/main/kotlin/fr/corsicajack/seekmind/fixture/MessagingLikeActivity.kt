package fr.corsicajack.seekmind.fixture

/**
 * Answers `SENDTO smsto:` (manifest), so that SeekMind files the whole fixture package under
 * « messageries » (docs/specs/confidentialite.md §3.4, E2E-19). Families are by package.
 */
class MessagingLikeActivity : FixtureActivity() {
    override fun addContent() {
        addText(R.string.fixture_messaging_marker)
        addText(R.string.fixture_messaging_text)
    }
}
