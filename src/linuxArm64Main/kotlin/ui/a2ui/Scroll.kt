package ui.a2ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import ui.Canvas
import ui.Constraints
import ui.Infinity
import ui.Interaction
import ui.Layout
import ui.MeasurePolicy
import ui.MeasureResult
import ui.Modifier
import ui.dp
import ui.drawBehind
import ui.material.MaterialTheme
import ui.material.roundRect
import kotlin.math.max
import kotlin.math.min

private const val KEY_DOWN = 108
private const val KEY_VOLUMEDOWN = 114

private val LocalScroll = staticCompositionLocalOf<FocusScrollState?> { null }

/**
 * Where a [FocusScroll] is scrolled, and the controls in it. On a phone
 * driven by its keys, focus only lands on controls, so [key] makes the
 * focus key read first: while text before the next control is still below
 * the screen, it scrolls a page instead of moving the focus.
 */
class FocusScrollState {
    var offset by mutableIntStateOf(0)
        private set
    internal var viewTop = 0
    internal var viewHeight = 0
    internal var contentHeight = 0
    private var placed = 0  // the offset the content was drawn at

    // The controls' tops (from the top of the content) as last drawn, in
    // focus order (draw order), and which one has the focus.
    private var tops = IntArray(32)
    private var count = 0
    private var focusedIndex = -1
    private var frameCount = 0
    private var frameFocused = -1

    internal fun place(o: Int) { placed = o }

    internal fun frameStart() {
        count = frameCount; focusedIndex = frameFocused  // the last whole frame's
        frameCount = 0; frameFocused = -1
    }

    internal fun frameEnd() {
        count = frameCount; focusedIndex = frameFocused
    }

    internal fun control(screenTop: Int, focused: Boolean) {
        if (frameCount == tops.size) tops = tops.copyOf(tops.size * 2)
        if (focused) frameFocused = frameCount
        tops[frameCount++] = screenTop - viewTop + placed
    }

    /** Scrolls so that the screen rows [top]..[bottom] show (a newly focused control). */
    internal fun reveal(top: Int, bottom: Int) {
        val margin = 24.dp.toPx()
        var o = offset
        if (bottom > viewTop + viewHeight - margin) o += bottom - (viewTop + viewHeight - margin)
        if (top < viewTop + margin) o -= viewTop + margin - top
        set(o)
    }

    private fun set(o: Int) {
        val v = o.coerceIn(0, (contentHeight - viewHeight).coerceAtLeast(0))
        if (v != offset) offset = v
    }

    /**
     * For Device.keyFilter: VOLUME DOWN (or DOWN) scrolls a page while there
     * is more to read before the next control, and after the last control
     * goes back to the top. True when it took the key.
     */
    fun key(code: Int, value: Int): Boolean {
        if ((code != KEY_VOLUMEDOWN && code != KEY_DOWN) || value == 0 || viewHeight <= 0) return false
        val max = (contentHeight - viewHeight).coerceAtLeast(0)
        val nextTop = if (focusedIndex + 1 < count) tops[focusedIndex + 1] else contentHeight
        if (nextTop > offset + viewHeight - 24.dp.toPx() && offset < max) {
            set(min(offset + viewHeight - 48.dp.toPx(), nextTop - 24.dp.toPx()).coerceAtLeast(offset + 1))  // keep two lines
            return true
        }
        if (focusedIndex < 0 && offset > 0 && (count == 0 || tops[0] < offset)) {  // past the end (focus on a bar, or none)
            set(0)
            return true
        }
        return false
    }
}

/**
 * A column taller than its box, scrolled to the focused control and, with
 * [FocusScrollState.key], a page at a time through the text in between.
 * What scrolls out is not clipped: draw headers and footers after this, opaque.
 */
@Composable
fun FocusScroll(modifier: Modifier = Modifier, state: FocusScrollState = remember { FocusScrollState() }, content: @Composable () -> Unit) {
    val policy = remember(state) {
        MeasurePolicy { children, c ->
            var h = 0
            for (child in children) h = maxOf(h, child.measure(Constraints(c.minWidth, c.maxWidth, 0, Infinity)).height)
            state.contentHeight = h
            val height = if (c.maxHeight == Infinity) h else c.maxHeight
            MeasureResult(c.maxWidth, height) {
                state.viewHeight = height
                val o = state.offset.coerceIn(0, (h - height).coerceAtLeast(0))
                state.place(o)
                for (child in children) child.placeAt(0, -o)
            }
        }
    }
    CompositionLocalProvider(LocalScroll provides state) {
        Layout(modifier.drawBehind { state.viewTop = originY; state.frameStart() }, policy, content = content)
    }
}

/** Material's scroll indicator for [state]: a thin bar at the right of this box (the scroll's area), while there is more than a screen. */
@Composable
fun ScrollIndicator(state: FocusScrollState, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colors.onSurfaceVariant.copy(alpha = 0.5f)
    Canvas(modifier) {
        state.frameEnd()
        val content = state.contentHeight
        val view = size.height.toInt()
        if (content <= state.viewHeight || view <= 0) return@Canvas
        val w = 4.dp.toPx()
        val thumb = max(view * state.viewHeight / content, 32.dp.toPx())
        val y = (view - thumb) * state.offset / (content - state.viewHeight).coerceAtLeast(1)
        roundRect(color, w / 2f, size.width.toInt() - w - 4.dp.toPx(), y, w, thumb)
    }
}

/** Keeps a control in view in [FocusScroll] when it gets the keys' focus ([interaction]: the control's). */
@Composable
internal fun Modifier.reveal(interaction: Interaction): Modifier {
    val state = LocalScroll.current ?: return this
    val shown = remember(interaction) { BooleanArray(1) }  // revealed for this focus already
    return drawBehind {
        state.control(originY, interaction.focused)
        if (!interaction.focused) shown[0] = false
        else if (!shown[0]) {
            shown[0] = true
            state.reveal(originY, originY + size.height.toInt())
        }
    }
}
