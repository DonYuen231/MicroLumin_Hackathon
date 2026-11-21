package app.recall.glasses.device

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.TypedValue
import android.view.View
import android.widget.TextView

/** High-contrast colors for the glasses display. */
const val GLASS_GREEN = Color.GREEN

/** Large text showing five lines at a time. The scroll wheel moves it one line per step. */
class CardView(context: Context) : TextView(context) {
    private var shown: String? = null

    init {
        setTextColor(GLASS_GREEN)
        setBackgroundColor(Color.TRANSPARENT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
        includeFontPadding = false
        setLineSpacing(4f, 1f)
        height = lineHeight * VISIBLE_LINES
    }

    fun show(text: String) {
        if (text == shown) return
        shown = text
        this.text = text
        scrollTo(0, 0)
    }

    fun scrollLines(delta: Int) {
        val lines = layout?.lineCount ?: return
        val max = (lines - VISIBLE_LINES).coerceAtLeast(0) * lineHeight
        scrollTo(0, (scrollY + delta * lineHeight).coerceIn(0, max))
    }

    private companion object {
        const val VISIBLE_LINES = 5
    }
}

class CrosshairView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GLASS_GREEN
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    override fun onDraw(canvas: Canvas) {
        val x = width / 2f
        val y = height / 2f
        canvas.drawCircle(x, y, RADIUS, paint)
        canvas.drawLine(x - ARM, y, x - GAP, y, paint)
        canvas.drawLine(x + GAP, y, x + ARM, y, paint)
        canvas.drawLine(x, y - ARM, x, y - GAP, paint)
        canvas.drawLine(x, y + GAP, x, y + ARM, paint)
    }

    private companion object {
        const val RADIUS = 56f
        const val ARM = 84f
        const val GAP = 16f
    }
}
