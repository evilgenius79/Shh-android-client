package io.github.evilgenius79.shh.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.ActionMode
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.OverScroller
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import io.github.evilgenius79.shh.R
import io.github.evilgenius79.shh.session.TerminalSession
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Shows a [TerminalSession] and turns touches and key presses into terminal input.
 *
 * This is a classic View rather than a composable because a terminal needs a raw
 * [InputConnection] and key events exactly as the system delivers them.
 */
class TerminalView(context: Context) : View(context) {
    private val renderer: TerminalRenderer
    private val scroller = OverScroller(context)
    private var session: TerminalSession? = null

    /** Row at the top of the view: 0 shows the live screen, negative values the history. */
    private var topRow = 0
    private var scrollRemainder = 0f
    private var lastScrollCounter = 0L

    private var selection: TerminalSelection? = null
    private var selecting = false
    private var actionMode: ActionMode? = null

    private var cursorOn = true
    private val blink = object : Runnable {
        override fun run() {
            cursorOn = !cursorOn
            invalidate()
            postDelayed(this, BLINK_INTERVAL_MS)
        }
    }

    /** Text size in sp. Changing it re-flows the terminal. */
    var fontSizeSp: Float = 13f
        set(value) {
            val clamped = value.coerceIn(MIN_FONT_SP, MAX_FONT_SP)
            if (clamped == field) return
            field = clamped
            applyFontSize()
        }

    var cursorShape: CursorShape = CursorShape.BLOCK
        set(value) {
            field = value
            invalidate()
        }

    var cursorBlink: Boolean = true
        set(value) {
            field = value
            restartBlink()
        }

    /** Ctrl is latched from the on-screen key row and applies to the next key. */
    var ctrlLatched: Boolean = false

    /** Alt is latched from the on-screen key row and applies to the next key. */
    var altLatched: Boolean = false

    /** Called after a latched modifier was used up, so the key row can un-highlight it. */
    var onModifiersConsumed: (() -> Unit)? = null

    /** Called when pinch zooming settled on a new font size, so it can be saved. */
    var onFontSizeChanged: ((Float) -> Unit)? = null

    /** Called for a tap that neither the terminal program nor a selection consumed. */
    var onTap: (() -> Unit)? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        val regular = ResourcesCompat.getFont(context, R.font.jetbrains_mono_regular) ?: Typeface.MONOSPACE
        val bold = ResourcesCompat.getFont(context, R.font.jetbrains_mono_bold) ?: Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        renderer = TerminalRenderer(regular, bold)
        applyFontSize()
    }

    private fun applyFontSize() {
        renderer.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSizeSp, resources.displayMetrics))
        updateTerminalSize()
        invalidate()
    }

    /** Shows [newSession], or nothing when null. */
    fun attach(newSession: TerminalSession?) {
        if (session === newSession) return
        session?.onScreenChanged = null
        session = newSession
        clearSelection()
        topRow = 0
        scrollRemainder = 0f
        if (newSession != null) {
            lastScrollCounter = synchronized(newSession.emulator) { newSession.emulator.scrollCounter }
            newSession.onScreenChanged = { postInvalidateOnAnimation() }
            updateTerminalSize()
        }
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        session?.onScreenChanged = { postInvalidateOnAnimation() }
        restartBlink()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(blink)
        session?.onScreenChanged = null
        super.onDetachedFromWindow()
    }

    private fun restartBlink() {
        removeCallbacks(blink)
        cursorOn = true
        if (cursorBlink && isAttachedToWindow) postDelayed(blink, BLINK_INTERVAL_MS)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateTerminalSize()
    }

    private fun updateTerminalSize() {
        val current = session ?: return
        if (width <= 0 || height <= 0) return
        val columns = floor(width / renderer.cellWidth).toInt().coerceAtLeast(MIN_COLUMNS)
        val rows = (height / renderer.cellHeight).coerceAtLeast(MIN_ROWS)
        current.resize(columns, rows, renderer.cellWidth.roundToInt(), renderer.cellHeight)
        topRow = 0
    }

    override fun onDraw(canvas: Canvas) {
        val current = session
        if (current == null) {
            canvas.drawColor(0xFF000000.toInt())
            return
        }
        val emulator = current.emulator
        synchronized(emulator) {
            // Lines that scrolled into the history since the last frame: keep showing the same
            // text when scrolled back, and keep the selection on the text it was made on.
            val counter = emulator.scrollCounter
            val shift = (counter - lastScrollCounter).toInt()
            lastScrollCounter = counter
            if (shift != 0) {
                if (topRow < 0) topRow -= shift
                selection?.let {
                    it.startRow -= shift
                    it.endRow -= shift
                }
            }
            topRow = topRow.coerceIn(-emulator.scrollbackSize, 0)
            val shape = if (emulator.cursorShape != CursorShape.BLOCK) emulator.cursorShape else cursorShape
            renderer.draw(canvas, emulator, topRow, selection, shape, cursorOn || !cursorBlink || !emulator.isCursorBlinking, hasWindowFocus() && isFocused)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Keyboard
    // ---------------------------------------------------------------------------------------

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // "Visible password" stops keyboards from autocorrecting, predicting or composing words,
        // none of which makes sense in a shell, while keeping their normal layout.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
        return TerminalInputConnection()
    }

    private inner class TerminalInputConnection : BaseInputConnection(this@TerminalView, false) {
        private var composing = ""

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            composing = ""
            typed(text.toString())
            return true
        }

        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
            // Held back until the keyboard commits it; there is nowhere to show a preview.
            composing = text.toString()
            return true
        }

        override fun finishComposingText(): Boolean {
            if (composing.isNotEmpty()) {
                val text = composing
                composing = ""
                typed(text)
            }
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            // How keyboards without real key events express Backspace.
            repeat(beforeLength.coerceIn(0, 64)) { sendKey(TerminalKey.BACKSPACE, 0) }
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean = dispatchKeyEvent(event)
    }

    /** Text arriving from the on-screen keyboard or the key row. */
    fun typed(text: String) {
        val current = session ?: return
        if (text.isEmpty()) return
        val modifiers = takeLatchedModifiers()
        current.sendText(text.replace("\r\n", "\r").replace('\n', '\r'), modifiers)
        afterInput()
    }

    /** A non-character key from the key row or a hardware keyboard. */
    fun sendKey(key: TerminalKey, modifiers: Int) {
        val current = session ?: return
        current.sendKey(key, modifiers or takeLatchedModifiers())
        afterInput()
    }

    private fun takeLatchedModifiers(): Int {
        var modifiers = 0
        if (ctrlLatched) modifiers = modifiers or KeyModifiers.CTRL
        if (altLatched) modifiers = modifiers or KeyModifiers.ALT
        if (modifiers != 0) {
            ctrlLatched = false
            altLatched = false
            onModifiersConsumed?.invoke()
        }
        return modifiers
    }

    private fun afterInput() {
        // Typing always returns to the live screen and restarts the blink in its visible phase.
        if (topRow != 0) {
            topRow = 0
            invalidate()
        }
        clearSelection()
        restartBlink()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (session == null || event.isSystem) return super.onKeyDown(keyCode, event)
        var modifiers = 0
        if (event.isShiftPressed) modifiers = modifiers or KeyModifiers.SHIFT
        if (event.isAltPressed) modifiers = modifiers or KeyModifiers.ALT
        if (event.isCtrlPressed) modifiers = modifiers or KeyModifiers.CTRL

        // The conventional terminal shortcuts for the clipboard.
        if (modifiers == (KeyModifiers.CTRL or KeyModifiers.SHIFT)) {
            when (keyCode) {
                KeyEvent.KEYCODE_C -> {
                    copySelection()
                    return true
                }
                KeyEvent.KEYCODE_V -> {
                    pasteFromClipboard()
                    return true
                }
            }
        }

        val key = terminalKeyFor(keyCode)
        if (key != null) {
            sendKey(key, modifiers)
            return true
        }

        // A character key. Ctrl and Alt are taken out before asking for the character because
        // they are applied by the encoder, not by the key map.
        val meta = event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK).inv()
        val codePoint = event.getUnicodeChar(meta)
        if (codePoint == 0 || (codePoint and android.view.KeyCharacterMap.COMBINING_ACCENT) != 0) {
            return super.onKeyDown(keyCode, event)
        }
        val current = session ?: return true
        val latched = takeLatchedModifiers()
        current.sendText(String(Character.toChars(codePoint)), (modifiers and (KeyModifiers.CTRL or KeyModifiers.ALT)) or latched)
        afterInput()
        return true
    }

    private fun terminalKeyFor(keyCode: Int): TerminalKey? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> TerminalKey.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> TerminalKey.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> TerminalKey.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> TerminalKey.RIGHT
        KeyEvent.KEYCODE_ENTER -> TerminalKey.ENTER
        KeyEvent.KEYCODE_NUMPAD_ENTER -> TerminalKey.KEYPAD_ENTER
        KeyEvent.KEYCODE_DEL -> TerminalKey.BACKSPACE
        KeyEvent.KEYCODE_FORWARD_DEL -> TerminalKey.DELETE
        KeyEvent.KEYCODE_TAB -> TerminalKey.TAB
        KeyEvent.KEYCODE_ESCAPE -> TerminalKey.ESCAPE
        KeyEvent.KEYCODE_MOVE_HOME -> TerminalKey.HOME
        KeyEvent.KEYCODE_MOVE_END -> TerminalKey.END
        KeyEvent.KEYCODE_PAGE_UP -> TerminalKey.PAGE_UP
        KeyEvent.KEYCODE_PAGE_DOWN -> TerminalKey.PAGE_DOWN
        KeyEvent.KEYCODE_INSERT -> TerminalKey.INSERT
        KeyEvent.KEYCODE_F1 -> TerminalKey.F1
        KeyEvent.KEYCODE_F2 -> TerminalKey.F2
        KeyEvent.KEYCODE_F3 -> TerminalKey.F3
        KeyEvent.KEYCODE_F4 -> TerminalKey.F4
        KeyEvent.KEYCODE_F5 -> TerminalKey.F5
        KeyEvent.KEYCODE_F6 -> TerminalKey.F6
        KeyEvent.KEYCODE_F7 -> TerminalKey.F7
        KeyEvent.KEYCODE_F8 -> TerminalKey.F8
        KeyEvent.KEYCODE_F9 -> TerminalKey.F9
        KeyEvent.KEYCODE_F10 -> TerminalKey.F10
        KeyEvent.KEYCODE_F11 -> TerminalKey.F11
        KeyEvent.KEYCODE_F12 -> TerminalKey.F12
        else -> null
    }

    fun showKeyboard() {
        requestFocus()
        ContextCompat.getSystemService(context, InputMethodManager::class.java)?.showSoftInput(this, 0)
    }

    fun hideKeyboard() {
        ContextCompat.getSystemService(context, InputMethodManager::class.java)?.hideSoftInputFromWindow(windowToken, 0)
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        reportFocus(hasWindowFocus && isFocused)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        reportFocus(gainFocus && hasWindowFocus())
    }

    private fun reportFocus(focused: Boolean) {
        session?.let { current -> synchronized(current.emulator) { current.emulator.reportFocus(focused) } }
        invalidate()
    }

    // ---------------------------------------------------------------------------------------
    // Touch
    // ---------------------------------------------------------------------------------------

    private fun columnAt(x: Float): Int {
        val columns = session?.emulator?.columns ?: 1
        return floor(x / renderer.cellWidth).toInt().coerceIn(0, columns - 1)
    }

    private fun viewRowAt(y: Float): Int {
        val rows = session?.emulator?.rows ?: 1
        return floor(y / renderer.cellHeight).toInt().coerceIn(0, rows - 1)
    }

    private val gestures = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                scroller.forceFinished(true)
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val current = session ?: return true
                if (selection != null) {
                    clearSelection()
                    return true
                }
                val reported = synchronized(current.emulator) {
                    val emulator = current.emulator
                    if (emulator.isMouseTrackingActive && topRow == 0) {
                        val col = columnAt(e.x)
                        val row = viewRowAt(e.y)
                        emulator.sendMouseEvent(TerminalEmulator.MOUSE_BUTTON_LEFT, col, row, TerminalEmulator.MOUSE_PRESS)
                        emulator.sendMouseEvent(TerminalEmulator.MOUSE_BUTTON_LEFT, col, row, TerminalEmulator.MOUSE_RELEASE)
                        true
                    } else {
                        false
                    }
                }
                if (!reported) onTap?.invoke()
                showKeyboard()
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (selecting) return true
                scrollByPixels(distanceY, e2)
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                val current = session ?: return true
                val canScrollBack = synchronized(current.emulator) {
                    !current.emulator.isMouseTrackingActive && !current.emulator.isAlternateScreenActive
                }
                if (!canScrollBack || selecting) return true
                scroller.fling(0, 0, 0, velocityY.toInt(), 0, 0, Int.MIN_VALUE, Int.MAX_VALUE)
                lastFlingY = 0
                postInvalidateOnAnimation()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                startSelection(e)
            }
        },
    )

    private var lastFlingY = 0

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            val y = scroller.currY
            scrollByPixels((lastFlingY - y).toFloat(), null)
            lastFlingY = y
            postInvalidateOnAnimation()
        }
    }

    /** Positive [distance] means the finger moved up, i.e. towards newer output. */
    private fun scrollByPixels(distance: Float, event: MotionEvent?) {
        val current = session ?: return
        scrollRemainder += distance
        val lines = (scrollRemainder / renderer.cellHeight).toInt()
        if (lines == 0) return
        scrollRemainder -= lines * renderer.cellHeight
        synchronized(current.emulator) {
            val emulator = current.emulator
            when {
                emulator.isMouseTrackingActive -> {
                    // The program wants the wheel: one report per line.
                    val col = if (event != null) columnAt(event.x) else 0
                    val row = if (event != null) viewRowAt(event.y) else 0
                    val button = if (lines > 0) TerminalEmulator.MOUSE_WHEEL_DOWN else TerminalEmulator.MOUSE_WHEEL_UP
                    repeat(abs(lines).coerceAtMost(MAX_WHEEL_STEPS)) {
                        emulator.sendMouseEvent(button, col, row, TerminalEmulator.MOUSE_PRESS)
                    }
                }
                emulator.isAlternateScreenActive -> {
                    // Full screen programs (less, man, vim) have no history to scroll; like xterm,
                    // translate the gesture into cursor keys so that it scrolls their content.
                    if (emulator.isAlternateScrollMode) {
                        val key = if (lines > 0) TerminalKey.DOWN else TerminalKey.UP
                        val bytes = KeyEncoder.encode(key, 0, emulator)
                        repeat(abs(lines).coerceAtMost(MAX_WHEEL_STEPS)) { current.write(bytes) }
                    }
                }
                else -> topRow = (topRow + lines).coerceIn(-emulator.scrollbackSize, 0)
            }
        }
        invalidate()
    }

    private val scaleGestures = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            private var startSize = 0f

            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                startSize = fontSizeSp
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                // Whole steps only: every change re-flows the terminal on the server too.
                val wanted = (startSize * detector.scaleFactor).roundToInt().toFloat()
                if (wanted != fontSizeSp) fontSizeSp = wanted
                return false
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                onFontSizeChanged?.invoke(fontSizeSp)
            }
        },
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleGestures.onTouchEvent(event)
        if (scaleGestures.isInProgress) return true
        if (selecting) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    selection?.let {
                        it.endRow = topRow + viewRowAt(event.y)
                        it.endCol = columnAt(event.x)
                    }
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    selecting = false
                    showSelectionActions()
                }
            }
        }
        gestures.onTouchEvent(event)
        return true
    }

    // ---------------------------------------------------------------------------------------
    // Selection and clipboard
    // ---------------------------------------------------------------------------------------

    private fun startSelection(event: MotionEvent) {
        val current = session ?: return
        val row = topRow + viewRowAt(event.y)
        val col = columnAt(event.x)
        // Start with the word under the finger; dragging then extends from there.
        var start = col
        var end = col
        synchronized(current.emulator) {
            val line = current.emulator.row(row)
            val columns = current.emulator.columns
            fun isWordChar(c: Int): Boolean {
                val codePoint = line.codePoint(c)
                return codePoint > ' '.code && WORD_BREAKS.indexOf(codePoint.toChar()) < 0
            }
            if (isWordChar(col)) {
                while (start > 0 && isWordChar(start - 1)) start--
                while (end < columns - 1 && isWordChar(end + 1)) end++
            }
        }
        selection = TerminalSelection(row, start, row, end)
        selecting = true
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    private fun showSelectionActions() {
        if (selection == null || actionMode != null) return
        actionMode = startActionMode(
            object : ActionMode.Callback2() {
                override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                    menu.add(Menu.NONE, MENU_COPY, 0, android.R.string.copy)
                    menu.add(Menu.NONE, MENU_PASTE, 1, android.R.string.paste)
                    menu.add(Menu.NONE, MENU_SELECT_ALL, 2, android.R.string.selectAll)
                    return true
                }

                override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

                override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                    when (item.itemId) {
                        MENU_COPY -> copySelection()
                        MENU_PASTE -> {
                            clearSelection()
                            pasteFromClipboard()
                        }
                        MENU_SELECT_ALL -> {
                            selectAll()
                            return true
                        }
                    }
                    mode.finish()
                    return true
                }

                override fun onDestroyActionMode(mode: ActionMode) {
                    actionMode = null
                    if (!selecting) {
                        selection = null
                        invalidate()
                    }
                }

                override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
                    val ends = selection?.ordered()
                    if (ends == null) {
                        outRect.set(0, 0, width, height)
                        return
                    }
                    val top = ((ends[0] - topRow) * renderer.cellHeight).coerceIn(0, height)
                    val bottom = ((ends[2] - topRow + 1) * renderer.cellHeight).coerceIn(0, height)
                    outRect.set(0, top, width, bottom)
                }
            },
            ActionMode.TYPE_FLOATING,
        )
    }

    private fun selectAll() {
        val current = session ?: return
        synchronized(current.emulator) {
            val emulator = current.emulator
            selection = TerminalSelection(-emulator.scrollbackSize, 0, emulator.rows - 1, emulator.columns - 1)
        }
        actionMode?.invalidateContentRect()
        invalidate()
    }

    /** The currently selected text, or null when nothing is selected. */
    fun selectedText(): String? {
        val current = session ?: return null
        val ends = selection?.ordered() ?: return null
        return synchronized(current.emulator) { current.emulator.getText(ends[0], ends[1], ends[2], ends[3]) }
    }

    fun copySelection() {
        val text = selectedText()
        if (!text.isNullOrEmpty()) {
            ContextCompat.getSystemService(context, ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText("Terminal", text))
        }
        clearSelection()
    }

    fun pasteFromClipboard() {
        val clipboard = ContextCompat.getSystemService(context, ClipboardManager::class.java) ?: return
        val clip = clipboard.primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
        if (text.isNotEmpty()) session?.paste(text)
    }

    private fun clearSelection() {
        selecting = false
        if (selection != null) {
            selection = null
            invalidate()
        }
        actionMode?.finish()
    }

    /** Jumps back to the live screen after scrolling through the history. */
    fun scrollToBottom() {
        topRow = 0
        invalidate()
    }

    private companion object {
        const val BLINK_INTERVAL_MS = 600L
        const val MIN_FONT_SP = 6f
        const val MAX_FONT_SP = 40f
        const val MIN_COLUMNS = 4
        const val MIN_ROWS = 2
        const val MAX_WHEEL_STEPS = 20
        const val MENU_COPY = 1
        const val MENU_PASTE = 2
        const val MENU_SELECT_ALL = 3

        /** Characters that end a word when long-pressing to select. Paths and URLs stay whole. */
        const val WORD_BREAKS = " \t\"'`()[]{}<>|;,"
    }
}
