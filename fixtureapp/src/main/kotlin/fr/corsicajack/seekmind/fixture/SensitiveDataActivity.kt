package fr.corsicajack.seekmind.fixture

/**
 * A test card number, a test IBAN and a parcel tracking number (E2E-12): the first two are masked, the
 * third is kept. Public test values, no real data.
 */
class SensitiveDataActivity : FixtureActivity() {
    override fun addContent() {
        addText(R.string.fixture_sensitive_marker)
        addText(R.string.fixture_sensitive_card)
        addText(R.string.fixture_sensitive_iban)
        addText(R.string.fixture_sensitive_tracking)
    }
}
