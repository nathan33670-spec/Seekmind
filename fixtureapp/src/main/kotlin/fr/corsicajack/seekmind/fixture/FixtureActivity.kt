package fr.corsicajack.seekmind.fixture

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Base of the fixture screens: a vertical column of plain Android views, no theme dependency. */
abstract class FixtureActivity : Activity() {
    protected lateinit var column: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        setContentView(column)
        addContent()
    }

    protected abstract fun addContent()

    protected fun addText(resId: Int): TextView = TextView(this).apply {
        setText(resId)
        textSize = 18f
        column.addView(this)
    }

    protected fun addView(view: View) = column.addView(view)
}
