package fr.corsicajack.seekmind.fixture

import android.os.Bundle
import android.view.WindowManager

/** Text inside a FLAG_SECURE window: must never be indexed (EF-PRIV-02). */
class SecureActivity : FixtureActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        super.onCreate(savedInstanceState)
    }

    override fun addContent() {
        addText(R.string.fixture_secure_marker)
    }
}
