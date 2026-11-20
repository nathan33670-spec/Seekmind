package fr.corsicajack.seekmind.fixture

import android.text.InputType
import android.widget.EditText

/** A login form with a password field: the whole screen is dropped (PASSWORD_FIELD). */
class PasswordActivity : FixtureActivity() {
    override fun addContent() {
        addText(R.string.fixture_password_marker)
        addView(EditText(this).apply { setText(R.string.fixture_password_user) })
        addView(
            EditText(this).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setText(R.string.fixture_password_value)
            },
        )
    }
}
