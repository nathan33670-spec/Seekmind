package fr.corsicajack.seekmind.fixture

import android.widget.EditText

/** Static text above an editable field: the static text is kept, what the field holds is not. */
class EditableActivity : FixtureActivity() {
    override fun addContent() {
        addText(R.string.fixture_editable_marker)
        addText(R.string.fixture_editable_static)
        addView(EditText(this).apply { setText(R.string.fixture_editable_field) })
    }
}
