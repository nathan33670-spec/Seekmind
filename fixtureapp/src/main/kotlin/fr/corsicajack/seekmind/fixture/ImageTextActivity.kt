package fr.corsicajack.seekmind.fixture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.widget.ImageView

/**
 * An image whose text is only visible as pixels: no text node and no content description,
 * so only OCR can read it.
 */
class ImageTextActivity : FixtureActivity() {
    override fun addContent() {
        val bitmap = Bitmap.createBitmap(IMAGE_WIDTH, IMAGE_HEIGHT, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 72f
            }
            drawText(getString(R.string.fixture_image_line1), 40f, 160f, paint)
            drawText(getString(R.string.fixture_image_line2), 40f, 280f, paint)
        }
        addView(
            ImageView(this).apply {
                setImageBitmap(bitmap)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
        )
    }

    private companion object {
        const val IMAGE_WIDTH = 1000
        const val IMAGE_HEIGHT = 400
    }
}
