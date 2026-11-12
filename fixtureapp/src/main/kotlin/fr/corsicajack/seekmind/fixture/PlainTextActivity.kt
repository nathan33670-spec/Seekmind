package fr.corsicajack.seekmind.fixture

/** Plain text with distinctive words, in French and English (capture, keyword search). */
class PlainTextActivity :FixtureActivity(){
    override fun addContent() {
        addText(R.string.fixture_plain_marker)
        addText(R.string.fixture_plain_fr)
        addText(R.string.fixture_plain_en)
    }
}
