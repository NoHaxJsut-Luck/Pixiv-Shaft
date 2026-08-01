package ceui.pixiv.ui.novel

import android.text.Selection
import android.text.Spannable
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.TextView
import kotlin.math.abs

class CustomLinkMovementMethod(private val onLinkClick: (String) -> Unit) : LinkMovementMethod() {

    private var pressedSpan: URLSpan? = null
    private var startX = 0f
    private var startY = 0f

    override fun onTouchEvent(widget: TextView, buffer: Spannable, event: MotionEvent): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedSpan = findClickedLink(widget, buffer, event)
                if (pressedSpan == null) {
                    false
                } else {
                    startX = event.x
                    startY = event.y
                    Selection.setSelection(
                        buffer,
                        buffer.getSpanStart(pressedSpan),
                        buffer.getSpanEnd(pressedSpan)
                    )
                    true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val touchSlop = ViewConfiguration.get(widget.context).scaledTouchSlop
                val moved = abs(event.x - startX) > touchSlop || abs(event.y - startY) > touchSlop
                val stillOnSameLink = findClickedLink(widget, buffer, event) == pressedSpan
                if (moved || !stillOnSameLink) {
                    clearPressedLink(buffer)
                    false
                } else {
                    true
                }
            }

            MotionEvent.ACTION_UP -> {
                val clickedSpan = findClickedLink(widget, buffer, event)
                val shouldOpen = clickedSpan != null && clickedSpan == pressedSpan
                clearPressedLink(buffer)
                if (shouldOpen) {
                    onLinkClick(clickedSpan.url)
                }
                shouldOpen
            }

            MotionEvent.ACTION_CANCEL -> {
                clearPressedLink(buffer)
                false
            }

            else -> false
        }
    }

    private fun findClickedLink(widget: TextView, buffer: Spannable, event: MotionEvent): URLSpan? {
        val x = (event.x - widget.totalPaddingLeft + widget.scrollX).toInt()
        val y = (event.y - widget.totalPaddingTop + widget.scrollY).toInt()
        val layout = widget.layout ?: return null
        if (x < 0 || y < 0 || x > layout.width || y > layout.height) {
            return null
        }

        val line = layout.getLineForVertical(y)
        if (x > layout.getLineRight(line)) return null
        val offset = layout.getOffsetForHorizontal(line, x.toFloat())
        return buffer.getSpans(offset, offset, URLSpan::class.java).firstOrNull()
    }

    private fun clearPressedLink(buffer: Spannable) {
        pressedSpan = null
        Selection.removeSelection(buffer)
    }
}
