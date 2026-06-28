package com.termux.view

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.util.AttributeSet
import android.view.ActionMode
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityManager
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.Scroller
import androidx.annotation.Nullable
import androidx.annotation.RequiresApi
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.view.textselection.TextSelectionCursorController
import kotlin.math.roundToInt

/** View displaying and interacting with a {@link TerminalSession}. */
class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** The currently displayed terminal session, whose emulator is {@link #mEmulator}. */
    @JvmField
    var mTermSession: TerminalSession? = null

    /** Our terminal emulator whose session is {@link #mTermSession}. */
    @JvmField
    var mEmulator: TerminalEmulator? = null

    @JvmField
    var mRenderer: TerminalRenderer? = null

    @JvmField
    var mClient: TerminalViewClient? = null

    interface OnContextMenuShowListener {
        fun onShowContextMenu(view: View): Boolean
    }

    private var mOnContextMenuShowListener: OnContextMenuShowListener? = null

    fun setOnContextMenuShowListener(listener: OnContextMenuShowListener?) {
        mOnContextMenuShowListener = listener
    }

    @JvmField
    internal var mTextSelectionCursorController: TextSelectionCursorController? = null

    private var mTerminalCursorBlinkerHandler: Handler? = null
    private var mTerminalCursorBlinkerRunnable: TerminalCursorBlinkerRunnable? = null
    private var mTerminalCursorBlinkerRate = 0
    private var mCursorInvisibleIgnoreOnce = false

    /** The top row of text to display. Ranges from -activeTranscriptRows to 0. */
    @JvmField
    internal var mTopRow = 0

    fun getTopRow(): Int {
        return mTopRow
    }

    fun setTopRow(topRow: Int) {
        mTopRow = topRow
    }

    @JvmField
    internal val mDefaultSelectors = intArrayOf(-1, -1, -1, -1)

    @JvmField
    internal var mScaleFactor = 1.0f

    @JvmField
    internal val mGestureRecognizer: GestureAndScaleRecognizer

    /** Keep track of where mouse touch event started which we report as mouse scroll. */
    private var mMouseScrollStartX = -1
    private var mMouseScrollStartY = -1

    /** Keep track of the time when a touch event leading to sending mouse scroll events started. */
    private var mMouseStartDownTime = -1L

    @JvmField
    internal val mScroller: Scroller

    /** What was left in from scrolling movement. */
    @JvmField
    internal var mScrollRemainder = 0.0f

    /** If non-zero, this is the last unicode code point received if that was a combining character. */
    @JvmField
    internal var mCombiningAccent = 0

    /**
     * The current AutoFill type returned for {@link View#getAutofillType()} by {@link #getAutofillType()}.
     *
     * The default is {@link #AUTOFILL_TYPE_NONE} so that AutoFill UI, like toolbar above keyboard
     * is not shown automatically, like on Activity starts/View create. This value should be updated
     * to required value, like {@link #AUTOFILL_TYPE_TEXT} before calling
     * {@link AutofillManager#requestAutofill(View)} so that AutoFill UI shows. The updated value
     * set will automatically be restored to {@link #AUTOFILL_TYPE_NONE} in
     * {@link #autofill(AutofillValue)} so that AutoFill UI isn't shown anymore by calling
     * {@link #resetAutoFill()}.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private var mAutoFillType = AUTOFILL_TYPE_NONE

    /**
     * The current AutoFill type returned for {@link View#getImportantForAutofill()} by
     * {@link #getImportantForAutofill()}.
     *
     * The default is {@link #IMPORTANT_FOR_AUTOFILL_NO} so that view is not considered important
     * for AutoFill. This value should be updated to required value, like
     * {@link #IMPORTANT_FOR_AUTOFILL_YES} before calling {@link AutofillManager#requestAutofill(View)}
     * so that Android and apps consider the view as important for AutoFill to process the request.
     * The updated value set will automatically be restored to {@link #IMPORTANT_FOR_AUTOFILL_NO} in
     * {@link #autofill(AutofillValue)} by calling {@link #resetAutoFill()}.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private var mAutoFillImportance = IMPORTANT_FOR_AUTOFILL_NO

    /**
     * The current AutoFill hints returned for {@link View#getAutofillHints()} ()} by {@link #getAutofillHints()} ()}.
     *
     * The default is an empty `string[]`. This value should be updated to required value. The
     * updated value set will automatically be restored an empty `string[]` in
     * {@link #autofill(AutofillValue)} by calling {@link #resetAutoFill()}.
     */
    private var mAutoFillHints = arrayOf<String>()

    private val mAccessibilityEnabled: Boolean

    companion object {
        /** Log terminal view key and IME events. */
        @JvmField
        var TERMINAL_VIEW_KEY_LOGGING_ENABLED = false

        const val TERMINAL_CURSOR_BLINK_RATE_MIN = 100
        const val TERMINAL_CURSOR_BLINK_RATE_MAX = 2000

        /** The {@link KeyEvent} is generated from a virtual keyboard, like manually with the {@link KeyEvent#KeyEvent(int, int)} constructor. */
        const val KEY_EVENT_SOURCE_VIRTUAL_KEYBOARD = KeyCharacterMap.VIRTUAL_KEYBOARD // -1

        /** The {@link KeyEvent} is generated from a non-physical device, like if 0 value is returned by {@link KeyEvent#getDeviceId()}. */
        const val KEY_EVENT_SOURCE_SOFT_KEYBOARD = 0

        private const val LOG_TAG = "TerminalView"
    }

    init {
        mGestureRecognizer = GestureAndScaleRecognizer(context, object : GestureAndScaleRecognizer.Listener {
            var scrolledWithFinger = false

            override fun onUp(event: MotionEvent?): Boolean {
                mScrollRemainder = 0.0f
                if (event != null && mEmulator != null && mEmulator!!.isMouseTrackingActive() && !event.isFromSource(InputDevice.SOURCE_MOUSE) && !isSelectingText() && !scrolledWithFinger) {
                    // Quick event processing when mouse tracking is active - do not wait for check of double tapping
                    // for zooming.
                    sendMouseEventCode(event, TerminalEmulator.MOUSE_LEFT_BUTTON, true)
                    sendMouseEventCode(event, TerminalEmulator.MOUSE_LEFT_BUTTON, false)
                    return true
                }
                scrolledWithFinger = false
                return false
            }

            override fun onSingleTapUp(event: MotionEvent?): Boolean {
                if (mEmulator == null) return true

                if (isSelectingText()) {
                    stopTextSelectionMode()
                    return true
                }
                requestFocus()
                if (event != null) {
                    mClient?.onSingleTapUp(event)
                }
                return true
            }

            override fun onScroll(e: MotionEvent?, distanceX: Float, distanceY: Float): Boolean {
                var dY = distanceY
                if (mEmulator == null) return true
                if (e != null && mEmulator!!.isMouseTrackingActive() && e.isFromSource(InputDevice.SOURCE_MOUSE)) {
                    // If moving with mouse pointer while pressing button, report that instead of scroll.
                    // This means that we never report moving with button press-events for touch input,
                    // since we cannot just start sending these events without a starting press event,
                    // which we do not do for touch input, only mouse in onTouchEvent().
                    sendMouseEventCode(e, TerminalEmulator.MOUSE_LEFT_BUTTON_MOVED, true)
                } else {
                    scrolledWithFinger = true
                    dY += mScrollRemainder
                    val deltaRows = (dY / mRenderer!!.mFontLineSpacing).toInt()
                    mScrollRemainder = dY - deltaRows * mRenderer!!.mFontLineSpacing
                    if (e != null) {
                        doScroll(e, deltaRows)
                    }
                }
                return true
            }

            override fun onScale(focusX: Float, focusY: Float, scale: Float): Boolean {
                if (mEmulator == null || isSelectingText()) return true
                mScaleFactor *= scale
                mScaleFactor = mClient?.onScale(mScaleFactor) ?: mScaleFactor
                return true
            }

            override fun onFling(e2: MotionEvent?, velocityX: Float, velocityY: Float): Boolean {
                if (mEmulator == null) return true
                // Do not start scrolling until last fling has been taken care of:
                if (!mScroller.isFinished) return true

                val mouseTrackingAtStartOfFling = mEmulator!!.isMouseTrackingActive()
                val scale = 0.25f
                if (mouseTrackingAtStartOfFling) {
                    mScroller.fling(0, 0, 0, -(velocityY * scale).toInt(), 0, 0, -mEmulator!!.mRows / 2, mEmulator!!.mRows / 2)
                } else {
                    mScroller.fling(0, mTopRow, 0, -(velocityY * scale).toInt(), 0, 0, -mEmulator!!.screen.getActiveTranscriptRows(), 0)
                }

                post(object : Runnable {
                    private var mLastY = 0

                    override fun run() {
                        if (mouseTrackingAtStartOfFling != mEmulator?.isMouseTrackingActive()) {
                            mScroller.abortAnimation()
                            return
                        }
                        if (mScroller.isFinished) return
                        val more = mScroller.computeScrollOffset()
                        val newY = mScroller.currY
                        val diff = if (mouseTrackingAtStartOfFling) newY - mLastY else newY - mTopRow
                        if (e2 != null) {
                            doScroll(e2, diff)
                        }
                        mLastY = newY
                        if (more) post(this)
                    }
                })

                return true
            }

            override fun onDown(x: Float, y: Float): Boolean {
                // Why is true not returned here?
                // https://developer.android.com/training/gestures/detector.html#detect-a-subset-of-supported-gestures
                // Although setting this to true still does not solve the following errors when long pressing in terminal view text area
                // ViewDragHelper: Ignoring pointerId=0 because ACTION_DOWN was not received for this pointer before ACTION_MOVE
                // Commenting out the call to mGestureDetector.onTouchEvent(event) in GestureAndScaleRecognizer#onTouchEvent() removes
                // the error logging, so issue is related to GestureDetector
                return false
            }

            override fun onDoubleTap(event: MotionEvent?): Boolean {
                // Do not treat is as a single confirmed tap - it may be followed by zoom.
                return false
            }

            override fun onLongPress(event: MotionEvent?) {
                if (mGestureRecognizer.isInProgress()) return
                if (event != null && mClient?.onLongPress(event) == true) return
                if (!isSelectingText()) {
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    if (event != null) {
                        startTextSelectionMode(event)
                    }
                }
            }
        })
        mScroller = Scroller(context)
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        mAccessibilityEnabled = am.isEnabled
    }

    /**
     * @param client The {@link TerminalViewClient} interface implementation to allow
     *                           for communication between {@link TerminalView} and its client.
     */
    fun setTerminalViewClient(client: TerminalViewClient?) {
        mClient = client
    }

    /**
     * Sets whether terminal view key logging is enabled or not.
     *
     * @param value The boolean value that defines the state.
     */
    fun setIsTerminalViewKeyLoggingEnabled(value: Boolean) {
        TERMINAL_VIEW_KEY_LOGGING_ENABLED = value
    }

    /**
     * Attach a {@link TerminalSession} to this view.
     *
     * @param session The {@link TerminalSession} this view will be displaying.
     */
    fun attachSession(session: TerminalSession): Boolean {
        if (session == mTermSession) return false
        mTopRow = 0

        mTermSession = session
        mEmulator = null
        mCombiningAccent = 0

        updateSize()

        // Wait with enabling the scrollbar until we have a terminal to get scroll position from.
        isVerticalScrollBarEnabled = true

        return true
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        // Ensure that inputType is only set if TerminalView is selected view with the keyboard and
        // an alternate view is not selected, like an EditText. This is necessary if an activity is
        // initially started with the alternate view or if activity is returned to from another app
        // and the alternate view was the one selected the last time.
        if (mClient != null && mClient!!.isTerminalViewSelected()) {
            if (mClient!!.shouldEnforceCharBasedInput()) {
                // Some keyboards seems do not reset the internal state on TYPE_NULL.
                // Affects mostly Samsung stock keyboards.
                // https://github.com/termux/termux-app/issues/686
                // However, this is not a valid value as per AOSP since `InputType.TYPE_CLASS_*` is
                // not set and it logs a warning:
                // W/InputAttributes: Unexpected input class: inputType=0x00080090 imeOptions=0x02000000
                // https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:packages/inputmethods/LatinIME/java/src/com/android/inputmethod/latin/InputAttributes.java;l=79
                outAttrs.inputType = InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            } else {
                // Using InputType.NULL is the most correct input type and avoids issues with other hacks.
                //
                // Previous keyboard issues:
                // https://github.com/termux/termux-packages/issues/25
                // https://github.com/termux/termux-app/issues/87.
                // https://github.com/termux/termux-app/issues/126.
                // https://github.com/termux/termux-app/issues/137 (japanese chars and TYPE_NULL).
                outAttrs.inputType = InputType.TYPE_NULL
            }
        } else {
            // Corresponds to android:inputType="text"
            outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
        }

        // Note that IME_ACTION_NONE cannot be used as that makes it impossible to input newlines using the on-screen
        // keyboard on Android TV (see https://github.com/termux/termux-app/issues/221).
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN

        return object : BaseInputConnection(this, true) {

            override fun finishComposingText(): Boolean {
                if (TERMINAL_VIEW_KEY_LOGGING_ENABLED) {
                    mClient?.logInfo(LOG_TAG, "IME: finishComposingText()")
                }
                super.finishComposingText()

                val content = editable
                if (content != null) {
                    sendTextToTerminal(content)
                    content.clear()
                }
                return true
            }

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (TERMINAL_VIEW_KEY_LOGGING_ENABLED) {
                    mClient?.logInfo(LOG_TAG, "IME: commitText(\"$text\", $newCursorPosition)")
                }
                super.commitText(text, newCursorPosition)

                if (mEmulator == null) return true

                val content = editable
                if (content != null) {
                    sendTextToTerminal(content)
                    content.clear()
                }
                return true
            }

            override fun deleteSurroundingText(leftLength: Int, rightLength: Int): Boolean {
                if (TERMINAL_VIEW_KEY_LOGGING_ENABLED) {
                    mClient?.logInfo(LOG_TAG, "IME: deleteSurroundingText($leftLength, $rightLength)")
                }
                // The stock Samsung keyboard with 'Auto check spelling' enabled sends leftLength > 1.
                val deleteKey = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)
                for (i in 0 until leftLength) sendKeyEvent(deleteKey)
                return super.deleteSurroundingText(leftLength, rightLength)
            }

            fun sendTextToTerminal(text: CharSequence) {
                stopTextSelectionMode()
                val textLengthInChars = text.length
                var i = 0
                while (i < textLengthInChars) {
                    val firstChar = text[i]
                    var codePoint: Int
                    if (Character.isHighSurrogate(firstChar)) {
                        i++
                        if (i < textLengthInChars) {
                            codePoint = Character.toCodePoint(firstChar, text[i])
                        } else {
                            // At end of string, with no low surrogate following the high:
                            codePoint = TerminalEmulator.UNICODE_REPLACEMENT_CHAR
                        }
                    } else {
                        codePoint = firstChar.code
                    }

                    // Check onKeyDown() for details.
                    if (mClient?.readShiftKey() == true) {
                        codePoint = Character.toUpperCase(codePoint)
                    }

                    var ctrlHeld = false
                    if (codePoint <= 31 && codePoint != 27) {
                        if (codePoint == '\n'.code) {
                            // The AOSP keyboard and descendants seems to send \n as text when the enter key is pressed,
                            // instead of a key event like most other keyboard apps. A terminal expects \r for the enter
                            // key (although when icrnl is enabled this doesn't make a difference - run 'stty -icrnl' to
                            // check the behaviour).
                            codePoint = '\r'.code
                        }

                        // E.g. penti keyboard for ctrl input.
                        ctrlHeld = true
                        when (codePoint) {
                            31 -> codePoint = '_'.code
                            30 -> codePoint = '^'.code
                            29 -> codePoint = ']'.code
                            28 -> codePoint = '\\'.code
                            else -> codePoint += 96
                        }
                    }

                    inputCodePoint(KEY_EVENT_SOURCE_SOFT_KEYBOARD, codePoint, ctrlHeld, false)
                    i++
                }
            }
        }
    }

    override fun computeVerticalScrollRange(): Int {
        return mEmulator?.screen?.getActiveRows() ?: 1
    }

    override fun computeVerticalScrollExtent(): Int {
        return mEmulator?.mRows ?: 1
    }

    override fun computeVerticalScrollOffset(): Int {
        return if (mEmulator == null) 1 else mEmulator!!.screen.getActiveRows() + mTopRow - mEmulator!!.mRows
    }

    @JvmOverloads
    fun onScreenUpdated(skipScrolling: Boolean = false) {
        var skip = skipScrolling
        if (mEmulator == null) return

        val rowsInHistory = mEmulator!!.screen.getActiveTranscriptRows()
        if (mTopRow < -rowsInHistory) mTopRow = -rowsInHistory

        if (isSelectingText() || mEmulator!!.isAutoScrollDisabled()) {
            // Do not scroll when selecting text.
            val rowShift = mEmulator!!.getScrollCounter()
            if (-mTopRow + rowShift > rowsInHistory) {
                // .. unless we're hitting the end of history transcript, in which
                // case we abort text selection and scroll to end.
                if (isSelectingText()) {
                    stopTextSelectionMode()
                }

                if (mEmulator!!.isAutoScrollDisabled()) {
                    mTopRow = -rowsInHistory
                    skip = true
                }
            } else {
                skip = true
                mTopRow -= rowShift
                decrementYTextSelectionCursors(rowShift)
            }
        }

        if (!skip && mTopRow != 0) {
            // Scroll down if not already there.
            if (mTopRow < -3) {
                // Awaken scroll bars only if scrolling a noticeable amount
                // - we do not want visible scroll bars during normal typing
                // of one row at a time.
                awakenScrollBars()
            }
            mTopRow = 0
        }

        mEmulator!!.clearScrollCounter()

        invalidate()
        if (mAccessibilityEnabled) {
            contentDescription = getText()
        }
    }

    /** This must be called by the hosting activity in {@link Activity#onContextMenuClosed(Menu)}
     * when context menu for the {@link TerminalView} is started by
     * {@link TextSelectionCursorController#ACTION_MORE} is closed. */
    fun onContextMenuClosed(menu: Menu?) {
        // Unset the stored text since it shouldn't be used anymore and should be cleared from memory
        unsetStoredSelectedText()
    }

    /**
     * Sets the text size, which in turn sets the number of rows and columns.
     *
     * @param textSize the new font size, in density-independent pixels.
     */
    fun setTextSize(textSize: Int) {
        mRenderer = TerminalRenderer(textSize, if (mRenderer == null) Typeface.MONOSPACE else mRenderer!!.mTypeface)
        updateSize()
    }

    fun setTypeface(newTypeface: Typeface?) {
        mRenderer = TerminalRenderer(mRenderer!!.mTextSize, newTypeface ?: Typeface.MONOSPACE)
        updateSize()
        invalidate()
    }

    override fun onCheckIsTextEditor(): Boolean {
        return true
    }

    override fun isOpaque(): Boolean {
        return true
    }

    /**
     * Get the zero indexed column and row of the terminal view for the
     * position of the event.
     *
     * @param event The event with the position to get the column and row for.
     * @param relativeToScroll If true the column number will take the scroll
     * position into account. E.g. if scrolled 3 lines up and the event
     * position is in the top left, column will be -3 if relativeToScroll is
     * true and 0 if relativeToScroll is false.
     * @return Array with the column and row.
     */
    fun getColumnAndRow(event: MotionEvent, relativeToScroll: Boolean): IntArray {
        val column = (event.x / mRenderer!!.mFontWidth).toInt()
        var row = ((event.y - mRenderer!!.mFontLineSpacingAndAscent) / mRenderer!!.mFontLineSpacing).toInt()
        if (relativeToScroll) {
            row += mTopRow
        }
        return intArrayOf(column, row)
    }

    /** Send a single mouse event code to the terminal. */
    fun sendMouseEventCode(e: MotionEvent, button: Int, pressed: Boolean) {
        val columnAndRow = getColumnAndRow(e, false)
        var x = columnAndRow[0] + 1
        var y = columnAndRow[1] + 1
        if (pressed && (button == TerminalEmulator.MOUSE_WHEELDOWN_BUTTON || button == TerminalEmulator.MOUSE_WHEELUP_BUTTON)) {
            if (mMouseStartDownTime == e.downTime) {
                x = mMouseScrollStartX
                y = mMouseScrollStartY
            } else {
                mMouseStartDownTime = e.downTime
                mMouseScrollStartX = x
                mMouseScrollStartY = y
            }
        }
        mEmulator?.sendMouseEvent(button, x, y, pressed)
    }

    /** Perform a scroll, either from dragging the screen or by scrolling a mouse wheel. */
    fun doScroll(event: MotionEvent, rowsDown: Int) {
        val up = rowsDown < 0
        val amount = Math.abs(rowsDown)
        for (i in 0 until amount) {
            if (mEmulator != null && mEmulator!!.isMouseTrackingActive()) {
                sendMouseEventCode(event, if (up) TerminalEmulator.MOUSE_WHEELUP_BUTTON else TerminalEmulator.MOUSE_WHEELDOWN_BUTTON, true)
            } else if (mEmulator != null && mEmulator!!.isAlternateBufferActive()) {
                // Send up and down key events for scrolling, which is what some terminals do to make scroll work in
                // e.g. less, which shifts to the alt screen without mouse handling.
                handleKeyCode(if (up) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN, 0)
            } else {
                mTopRow = Math.min(0, Math.max(-(mEmulator?.screen?.getActiveTranscriptRows() ?: 0), mTopRow + if (up) -1 else 1))
                if (!awakenScrollBars()) invalidate()
            }
        }
    }

    /** Overriding {@link View#onGenericMotionEvent(MotionEvent)}. */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (mEmulator != null && event.isFromSource(InputDevice.SOURCE_MOUSE) && event.action == MotionEvent.ACTION_SCROLL) {
            // Handle mouse wheel scrolling.
            val up = event.getAxisValue(MotionEvent.AXIS_VSCROLL) > 0.0f
            doScroll(event, if (up) -3 else 3)
            return true
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (mEmulator == null) return true
        val action = event.action

        if (isSelectingText()) {
            updateFloatingToolbarVisibility(event)
            mGestureRecognizer.onTouchEvent(event)
            return true
        } else if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            if (event.isButtonPressed(MotionEvent.BUTTON_SECONDARY)) {
                if (action == MotionEvent.ACTION_DOWN) showContextMenu()
                return true
            } else if (event.isButtonPressed(MotionEvent.BUTTON_TERTIARY)) {
                val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clipData = clipboardManager.primaryClip
                if (clipData != null) {
                    val clipItem = clipData.getItemAt(0)
                    if (clipItem != null) {
                        val text = clipItem.coerceToText(context)
                        if (!TextUtils.isEmpty(text)) mEmulator!!.paste(text.toString())
                    }
                }
            } else if (mEmulator!!.isMouseTrackingActive()) { // BUTTON_PRIMARY.
                when (event.action) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP -> sendMouseEventCode(event, TerminalEmulator.MOUSE_LEFT_BUTTON, event.action == MotionEvent.ACTION_DOWN)
                    MotionEvent.ACTION_MOVE -> sendMouseEventCode(event, TerminalEmulator.MOUSE_LEFT_BUTTON_MOVED, true)
                }
            }
        }

        mGestureRecognizer.onTouchEvent(event)
        return true
    }

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (TERMINAL_VIEW_KEY_LOGGING_ENABLED) {
            mClient?.logInfo(LOG_TAG, "onKeyPreIme(keyCode=$keyCode, event=$event)")
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            cancelRequestAutoFill()
            if (isSelectingText()) {
                stopTextSelectionMode()
                return true
            } else if (mClient?.shouldBackButtonBeMappedToEscape() == true) {
                // Intercept back button to treat it as escape:
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> return onKeyDown(keyCode, event)
                    KeyEvent.ACTION_UP -> return onKeyUp(keyCode, event)
                }
            }
        } else if (mClient?.shouldUseCtrlSpaceWorkaround() == true &&
            keyCode == KeyEvent.KEYCODE_SPACE && event.isCtrlPressed) {
            /* ctrl+space does not work on some ROMs without this workaround.
               However, this breaks it on devices where it works out of the box. */
            return onKeyDown(keyCode, event)
        }
        return super.onKeyPreIme(keyCode, event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (TERMINAL_VIEW_KEY_LOGGING_ENABLED) {
            mClient?.logInfo(LOG_TAG, "onKeyDown(keyCode=$keyCode, isSystem()=${event.isSystem}, event=$event)")
        }
        if (mEmulator == null) return true
        if (isSelectingText()) {
            stopTextSelectionMode()
        }

        if (mClient?.onKeyDown(keyCode, event, mTermSession) == true) {
            invalidate()
            return true
        } else if (event.isSystem && (mClient?.shouldBackButtonBeMappedToEscape() != true || keyCode != KeyEvent.KEYCODE_BACK)) {
            return super.onKeyDown(keyCode, event)
        } else if (event.action == KeyEvent.ACTION_MULTIPLE && keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            mTermSession?.write(event.characters)
            return true
        } else if (keyCode == KeyEvent.KEYCODE_LANGUAGE_SWITCH) {
            return super.onKeyDown(keyCode, event)
        }

        val metaState = event.metaState
        val controlDown = event.isCtrlPressed || mClient?.readControlKey() == true
        val leftAltDown = (metaState and KeyEvent.META_ALT_LEFT_ON) != 0 || mClient?.readAltKey() == true
        val shiftDown = event.isShiftPressed || mClient?.readShiftKey() == true
        val rightAltDownFromEvent = (metaState and KeyEvent.META_ALT_RIGHT_ON) != 0

        var keyMod = 0
        if (controlDown) keyMod = keyMod or KeyHandler.KEYMOD_CTRL
        if (event.isAltPressed || leftAltDown) keyMod = keyMod or KeyHandler.KEYMOD_ALT
        if (shiftDown) keyMod = keyMod or KeyHandler.KEYMOD_SHIFT
        if (event.isNumLockOn) keyMod = keyMod or KeyHandler.KEYMOD_NUM_LOCK
        // https://github.com/termux/termux-app/issues/731
        if (!event.isFunctionPressed && handleKeyCode(keyCode, keyMod)) {
            if (TERMINAL_VIEW_KEY_LOGGING_ENABLED) mClient?.logInfo(LOG_TAG, "handleKeyCode() took key event")
            return true
        }

        // Clear Ctrl since we handle that ourselves:
        var bitsToClear = KeyEvent.META_CTRL_MASK
        if (rightAltDownFromEvent) {
            // Let right Alt/Alt Gr be used to compose characters.
        } else {
            // Use left alt to send to terminal (e.g. Left Alt+B to jump back a word), so remove:
            bitsToClear = bitsToClear or (KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON)
        }
        var effectiveMetaState = event.metaState and bitsToClear.inv()

        if (shiftDown) effectiveMetaState = effectiveMetaState or (KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON)
        if (mClient?.readFnKey() == true) effectiveMetaState = effectiveMetaState or KeyEvent.META_FUNCTION_ON

        val result = event.getUnicodeChar(effectiveMetaState)
        if (TERMINAL_VIEW_KEY_LOGGING_ENABLED) {
            mClient?.logInfo(LOG_TAG, "KeyEvent#getUnicodeChar($effectiveMetaState) returned: $result")
        }
        if (result == 0) {
            return false
        }

        val oldCombiningAccent = mCombiningAccent
        if ((result and KeyCharacterMap.COMBINING_ACCENT) != 0) {
            // If entered combining accent previously, write it out:
            if (mCombiningAccent != 0) {
                inputCodePoint(event.deviceId, mCombiningAccent, controlDown, leftAltDown)
            }
            mCombiningAccent = result and KeyCharacterMap.COMBINING_ACCENT_MASK
        } else {
            var finalResult = result
            if (mCombiningAccent != 0) {
                val combinedChar = KeyCharacterMap.getDeadChar(mCombiningAccent, result)
                if (combinedChar > 0) finalResult = combinedChar
                mCombiningAccent = 0
            }
            inputCodePoint(event.deviceId, finalResult, controlDown, leftAltDown)
        }

        if (mCombiningAccent != oldCombiningAccent) invalidate()

        return true
    }

    fun inputCodePoint(eventSource: Int, codePoint: Int, controlDownFromEvent: Boolean, leftAltDownFromEvent: Boolean) {
        var cp = codePoint
        if (TERMINAL_VIEW_KEY_LOGGING_ENABLED) {
            mClient?.logInfo(LOG_TAG, "inputCodePoint(eventSource=$eventSource, codePoint=$cp, controlDownFromEvent=$controlDownFromEvent, leftAltDownFromEvent=$leftAltDownFromEvent)")
        }

        if (mTermSession == null) return

        // Ensure cursor is shown when a key is pressed down like long hold on (arrow) keys
        mEmulator?.setCursorBlinkState(true)

        val controlDown = controlDownFromEvent || mClient?.readControlKey() == true
        val altDown = leftAltDownFromEvent || mClient?.readAltKey() == true

        if (mClient?.onCodePoint(cp, controlDown, mTermSession) == true) return

        if (controlDown) {
            if (cp in 'a'.code..'z'.code) {
                cp = cp - 'a'.code + 1
            } else if (cp in 'A'.code..'Z'.code) {
                cp = cp - 'A'.code + 1
            } else if (cp == ' '.code || cp == '2'.code) {
                cp = 0
            } else if (cp == '['.code || cp == '3'.code) {
                cp = 27 // ^[ (Esc)
            } else if (cp == '\\'.code || cp == '4'.code) {
                cp = 28
            } else if (cp == ']'.code || cp == '5'.code) {
                cp = 29
            } else if (cp == '^'.code || cp == '6'.code) {
                cp = 30 // control-^
            } else if (cp == '_'.code || cp == '7'.code || cp == '/'.code) {
                // "Ctrl-/ sends 0x1f which is equivalent of Ctrl-_ since the days of VT102"
                // - http://apple.stackexchange.com/questions/24261/how-do-i-send-c-that-is-control-slash-to-the-terminal
                cp = 31
            } else if (cp == '8'.code) {
                cp = 127 // DEL
            }
        }

        if (cp > -1) {
            // If not virtual or soft keyboard.
            if (eventSource > KEY_EVENT_SOURCE_SOFT_KEYBOARD) {
                // Work around bluetooth keyboards sending funny unicode characters instead
                // of the more normal ones from ASCII that terminal programs expect - the
                // desire to input the original characters should be low.
                when (cp) {
                    0x02DC -> cp = 0x007E // TILDE (~).
                    0x02CB -> cp = 0x0060 // GRAVE ACCENT (`).
                    0x02C6 -> cp = 0x005E // CIRCUMFLEX ACCENT (^).
                }
            }

            // If left alt, send escape before the code point to make e.g. Alt+B and Alt+F work in readline:
            mTermSession?.writeCodePoint(altDown, cp)
        }
    }

    /** Input the specified keyCode if applicable and return if the input was consumed. */
    fun handleKeyCode(keyCode: Int, keyMod: Int): Boolean {
        // Ensure cursor is shown when a key is pressed down like long hold on (arrow) keys
        mEmulator?.setCursorBlinkState(true)

        if (handleKeyCodeAction(keyCode, keyMod)) return true

        val term = mTermSession?.emulator ?: return false
        val code = KeyHandler.getCode(keyCode, keyMod, term.isCursorKeysApplicationMode(), term.isKeypadApplicationMode()) ?: return false
        mTermSession?.write(code)
        return true
    }

    fun handleKeyCodeAction(keyCode: Int, keyMod: Int): Boolean {
        val shiftDown = (keyMod and KeyHandler.KEYMOD_SHIFT) != 0

        when (keyCode) {
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN -> {
                // shift+page_up and shift+page_down should scroll scrollback history instead of
                // scrolling command history or changing pages
                if (shiftDown) {
                    val time = SystemClock.uptimeMillis()
                    val motionEvent = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, 0.0f, 0.0f, 0)
                    doScroll(motionEvent, if (keyCode == KeyEvent.KEYCODE_PAGE_UP) -mEmulator!!.mRows else mEmulator!!.mRows)
                    motionEvent.recycle()
                    return true
                }
            }
        }

        return false
    }

    /**
     * Called when a key is released in the view.
     *
     * @param keyCode The keycode of the key which was released.
     * @param event   A {@link KeyEvent} describing the event.
     * @return Whether the event was handled.
     */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (TERMINAL_VIEW_KEY_LOGGING_ENABLED) {
            mClient?.logInfo(LOG_TAG, "onKeyUp(keyCode=$keyCode, event=$event)")
        }

        // Do not return for KEYCODE_BACK and send it to the client since user may be trying
        // to exit the activity.
        if (mEmulator == null && keyCode != KeyEvent.KEYCODE_BACK) return true

        if (mClient?.onKeyUp(keyCode, event) == true) {
            invalidate()
            return true
        } else if (event.isSystem) {
            // Let system key events through.
            return super.onKeyUp(keyCode, event)
        }

        return true
    }

    /**
     * This is called during layout when the size of this view has changed. If you were just added to the view
     * hierarchy, you're called with the old values of 0.
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        updateSize()
    }

    /** Check if the terminal size in rows and columns should be updated. */
    fun updateSize() {
        val viewWidth = width
        val viewHeight = height
        val renderer = mRenderer
        if (viewWidth == 0 || viewHeight == 0 || mTermSession == null || renderer == null) return

        // Set to 80 and 24 if you want to enable vttest.
        val newColumns = Math.max(4, (viewWidth / renderer.mFontWidth).toInt())
        val newRows = Math.max(4, (viewHeight - renderer.mFontLineSpacingAndAscent) / renderer.mFontLineSpacing)

        if (mEmulator == null || newColumns != mEmulator!!.mColumns || newRows != mEmulator!!.mRows) {
            mTermSession!!.updateSize(newColumns, newRows, renderer.getFontWidth().toInt(), renderer.getFontLineSpacing())
            mEmulator = mTermSession!!.emulator
            mClient?.onEmulatorSet()

            // Update mTerminalCursorBlinkerRunnable inner class mEmulator on session change
            mTerminalCursorBlinkerRunnable?.setEmulator(mEmulator)

            mTopRow = 0
            scrollTo(0, 0)
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val emulator = mEmulator
        val renderer = mRenderer
        if (emulator == null || renderer == null) {
            canvas.drawColor(-0x1000000) // 0XFF000000
        } else {
            // render the terminal view and highlight any selected text
            val sel = mDefaultSelectors
            mTextSelectionCursorController?.getSelectors(sel)

            renderer.render(emulator, canvas, mTopRow, sel[0], sel[1], sel[2], sel[3])

            // render the text selection handles
            renderTextSelection()
        }
    }

    val currentSession: TerminalSession?
        get() = mTermSession

    private fun getText(): CharSequence {
        return mEmulator?.screen?.getSelectedText(0, mTopRow, mEmulator!!.mColumns, mTopRow + mEmulator!!.mRows) ?: ""
    }

    fun getCursorX(x: Float): Int {
        val renderer = mRenderer ?: return 0
        return (x / renderer.mFontWidth).toInt()
    }

    fun getCursorY(y: Float): Int {
        val renderer = mRenderer ?: return mTopRow
        return ((y - 40) / renderer.mFontLineSpacing).toInt() + mTopRow
    }

    fun getPointX(cx: Int): Int {
        var cursorX = cx
        val emulator = mEmulator
        val renderer = mRenderer ?: return 0
        if (emulator != null && cursorX > emulator.mColumns) {
            cursorX = emulator.mColumns
        }
        return (cursorX * renderer.mFontWidth).roundToInt()
    }

    fun getPointY(cy: Int): Int {
        val renderer = mRenderer ?: return 0
        return (cy - mTopRow) * renderer.mFontLineSpacing
    }

    /**
     * Define functions required for AutoFill API
     */
    @RequiresApi(Build.VERSION_CODES.O)
    override fun autofill(value: AutofillValue) {
        if (value.isText) {
            mTermSession?.write(value.textValue.toString())
        }

        resetAutoFill()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun getAutofillType(): Int {
        return mAutoFillType
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun getAutofillHints(): Array<String> {
        return mAutoFillHints
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun getAutofillValue(): AutofillValue {
        return AutofillValue.forText("")
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun getImportantForAutofill(): Int {
        return mAutoFillImportance
    }

    @RequiresApi(Build.VERSION_CODES.O)
    @Synchronized
    private fun resetAutoFill() {
        // Restore none type so that AutoFill UI isn't shown anymore.
        mAutoFillType = AUTOFILL_TYPE_NONE
        mAutoFillImportance = IMPORTANT_FOR_AUTOFILL_NO
        mAutoFillHints = arrayOf()
    }

    fun getAutoFillManagerService(): AutofillManager? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null

        return try {
            val context = context ?: return null
            context.getSystemService(AutofillManager::class.java)
        } catch (e: Exception) {
            mClient?.logStackTraceWithMessage(LOG_TAG, "Failed to get AutofillManager service", e)
            null
        }
    }

    fun isAutoFillEnabled(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false

        return try {
            val autofillManager = getAutoFillManagerService()
            autofillManager != null && autofillManager.isEnabled
        } catch (e: Exception) {
            mClient?.logStackTraceWithMessage(LOG_TAG, "Failed to check if Autofill is enabled", e)
            false
        }
    }

    @Synchronized
    fun requestAutoFillUsername() {
        requestAutoFill(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) arrayOf(View.AUTOFILL_HINT_USERNAME) else null
        )
    }

    @Synchronized
    fun requestAutoFillPassword() {
        requestAutoFill(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) arrayOf(View.AUTOFILL_HINT_PASSWORD) else null
        )
    }

    @Synchronized
    fun requestAutoFill(autoFillHints: Array<String>?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (autoFillHints == null || autoFillHints.isEmpty()) return

        try {
            val autofillManager = getAutoFillManagerService()
            if (autofillManager != null && autofillManager.isEnabled) {
                // Update type that will be returned by `getAutofillType()` so that AutoFill UI is shown.
                mAutoFillType = AUTOFILL_TYPE_TEXT
                // Update importance that will be returned by `getImportantForAutofill()` so that
                // AutoFill considers the view as important.
                mAutoFillImportance = IMPORTANT_FOR_AUTOFILL_YES
                // Update hints that will be returned by `getAutofillHints()` for which to show AutoFill UI.
                mAutoFillHints = autoFillHints
                autofillManager.requestAutofill(this)
            }
        } catch (e: Exception) {
            mClient?.logStackTraceWithMessage(LOG_TAG, "Failed to request Autofill", e)
        }
    }

    @Synchronized
    fun cancelRequestAutoFill() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (mAutoFillType == AUTOFILL_TYPE_NONE) return

        try {
            val autofillManager = getAutoFillManagerService()
            if (autofillManager != null && autofillManager.isEnabled) {
                resetAutoFill()
                autofillManager.cancel()
            }
        } catch (e: Exception) {
            mClient?.logStackTraceWithMessage(LOG_TAG, "Failed to cancel Autofill request", e)
        }
    }

    /**
     * Set terminal cursor blinker rate. It must be between {@link #TERMINAL_CURSOR_BLINK_RATE_MIN}
     * and {@link #TERMINAL_CURSOR_BLINK_RATE_MAX}, otherwise it will be disabled.
     *
     * The {@link #setTerminalCursorBlinkerState(boolean, boolean)} must be called after this
     * for changes to take effect if not disabling.
     *
     * @param blinkRate The value to set.
     * @return Returns {@code true} if setting blinker rate was successfully set, otherwise [@code false].
     */
    @Synchronized
    fun setTerminalCursorBlinkerRate(blinkRate: Int): Boolean {
        val result: Boolean

        // If cursor blinking rate is not valid
        if (blinkRate != 0 && (blinkRate < TERMINAL_CURSOR_BLINK_RATE_MIN || blinkRate > TERMINAL_CURSOR_BLINK_RATE_MAX)) {
            mClient?.logError(LOG_TAG, "The cursor blink rate must be in between $TERMINAL_CURSOR_BLINK_RATE_MIN-$TERMINAL_CURSOR_BLINK_RATE_MAX: $blinkRate")
            mTerminalCursorBlinkerRate = 0
            result = false
        } else {
            mClient?.logVerbose(LOG_TAG, "Setting cursor blinker rate to $blinkRate")
            mTerminalCursorBlinkerRate = blinkRate
            result = true
        }

        if (mTerminalCursorBlinkerRate == 0) {
            mClient?.logVerbose(LOG_TAG, "Cursor blinker disabled")
            stopTerminalCursorBlinker()
        }

        return result
    }

    /**
     * Sets whether cursor blinker should be started or stopped. Cursor blinker will only be
     * started if {@link #mTerminalCursorBlinkerRate} does not equal 0 and is between
     * {@link #TERMINAL_CURSOR_BLINK_RATE_MIN} and {@link #TERMINAL_CURSOR_BLINK_RATE_MAX}.
     *
     * This should be called when the view holding this activity is resumed or stopped so that
     * cursor blinker does not run when activity is not visible. If you call this on onResume()
     * to start cursor blinking, then ensure that {@link #mEmulator} is set, otherwise wait for the
     * {@link TerminalViewClient#onEmulatorSet()} event after calling {@link #attachSession(TerminalSession)}
     * for the first session added in the activity since blinking will not start if {@link #mEmulator}
     * is not set, like if activity is started again after exiting it with double back press. Do not
     * call this directly after {@link #attachSession(TerminalSession)} since {@link #updateSize()}
     * may return without setting {@link #mEmulator} since width/height may be 0. Its called again in
     * {@link #onSizeChanged(int, int, int, int)}. Calling on onResume() if emulator is already set
     * is necessary, since onEmulatorSet() may not be called after activity is started after device
     * display timeout with double tap and not power button.
     *
     * It should also be called on the
     * {@link com.termux.terminal.TerminalSessionClient#onTerminalCursorStateChange(boolean)}
     * callback when cursor is enabled or disabled so that blinker is disabled if cursor is not
     * to be shown. It should also be checked if activity is visible if blinker is to be started
     * before calling this.
     *
     * It should also be called after terminal is reset with {@link TerminalSession#reset()} in case
     * cursor blinker was disabled before reset due to call to
     * {@link com.termux.terminal.TerminalSessionClient#onTerminalCursorStateChange(boolean)}.
     *
     * How cursor blinker starting works is by registering a {@link Runnable} with the looper of
     * the main thread of the app which when run, toggles the cursor blinking state and re-registers
     * itself to be called with the delay set by {@link #mTerminalCursorBlinkerRate}. When cursor
     * blinking needs to be disabled, we just cancel any callbacks registered. We don't run our own
     * "thread" and let the thread for the main looper do the work for us, whose usage is also
     * required to update the UI, since it also handles other calls to update the UI as well based
     * on a queue.
     *
     * Note that when moving cursor in text editors like nano, the cursor state is quickly
     * toggled `-> off -> on`, which would call this very quickly sequentially. So that if cursor
     * is moved 2 or more times quickly, like long hold on arrow keys, it would trigger
     * `-> off -> on -> off -> on -> ...`, and the "on" callback at index 2 is automatically
     * cancelled by next "off" callback at index 3 before getting a chance to be run. For this case
     * we log only if {@link #TERMINAL_VIEW_KEY_LOGGING_ENABLED} is enabled, otherwise would clutter
     * the log. We don't start the blinking with a delay to immediately show cursor in case it was
     * previously not visible.
     *
     * @param start If cursor blinker should be started or stopped.
     * @param startOnlyIfCursorEnabled If set to {@code true}, then it will also be checked if the
     *                                 cursor is even enabled by {@link TerminalEmulator} before
     *                                 starting the cursor blinker.
     */
    @Synchronized
    fun setTerminalCursorBlinkerState(start: Boolean, startOnlyIfCursorEnabled: Boolean) {
        // Stop any existing cursor blinker callbacks
        stopTerminalCursorBlinker()

        if (mEmulator == null) return

        mEmulator!!.setCursorBlinkingEnabled(false)

        if (start) {
            // If cursor blinker is not enabled or is not valid
            if (mTerminalCursorBlinkerRate < TERMINAL_CURSOR_BLINK_RATE_MIN || mTerminalCursorBlinkerRate > TERMINAL_CURSOR_BLINK_RATE_MAX) {
                return
            } else if (startOnlyIfCursorEnabled && !mEmulator!!.isCursorEnabled()) {
                if (TERMINAL_VIEW_KEY_LOGGING_ENABLED) {
                    mClient?.logVerbose(LOG_TAG, "Ignoring call to start cursor blinker since cursor is not enabled")
                }
                return
            }

            // Start cursor blinker runnable
            if (TERMINAL_VIEW_KEY_LOGGING_ENABLED) {
                mClient?.logVerbose(LOG_TAG, "Starting cursor blinker with the blink rate $mTerminalCursorBlinkerRate")
            }
            if (mTerminalCursorBlinkerHandler == null) {
                mTerminalCursorBlinkerHandler = Handler(Looper.getMainLooper())
            }
            mTerminalCursorBlinkerRunnable = TerminalCursorBlinkerRunnable(mEmulator, mTerminalCursorBlinkerRate)
            mEmulator!!.setCursorBlinkingEnabled(true)
            mTerminalCursorBlinkerRunnable!!.run()
        }
    }

    /**
     * Cancel the terminal cursor blinker callbacks
     */
    private fun stopTerminalCursorBlinker() {
        if (mTerminalCursorBlinkerHandler != null && mTerminalCursorBlinkerRunnable != null) {
            if (TERMINAL_VIEW_KEY_LOGGING_ENABLED) {
                mClient?.logVerbose(LOG_TAG, "Stopping cursor blinker")
            }
            mTerminalCursorBlinkerHandler!!.removeCallbacks(mTerminalCursorBlinkerRunnable!!)
        }
    }

    private inner class TerminalCursorBlinkerRunnable(
        private var mEmulator: TerminalEmulator?,
        private val mBlinkRate: Int
    ) : Runnable {

        // Initialize with false so that initial blink state is visible after toggling
        var mCursorVisible = false

        fun setEmulator(emulator: TerminalEmulator?) {
            mEmulator = emulator
        }

        override fun run() {
            try {
                if (mEmulator != null) {
                    // Toggle the blink state and then invalidate() the view so
                    // that onDraw() is called, which then calls TerminalRenderer.render()
                    // which checks with TerminalEmulator.shouldCursorBeVisible() to decide whether
                    // to draw the cursor or not
                    mCursorVisible = !mCursorVisible
                    mEmulator!!.setCursorBlinkState(mCursorVisible)
                    invalidate()
                }
            } finally {
                // Recall the Runnable after mBlinkRate milliseconds to toggle the blink state
                mTerminalCursorBlinkerHandler?.postDelayed(this, mBlinkRate.toLong())
            }
        }
    }

    /**
     * Define functions required for text selection and its handles.
     */
    fun getTextSelectionCursorController(): TextSelectionCursorController? {
        if (mTextSelectionCursorController == null) {
            mTextSelectionCursorController = TextSelectionCursorController(this)

            val observer = viewTreeObserver
            observer?.addOnTouchModeChangeListener(mTextSelectionCursorController)
        }

        return mTextSelectionCursorController
    }

    private fun showTextSelectionCursors(event: MotionEvent) {
        getTextSelectionCursorController()?.show(event)
    }

    private fun hideTextSelectionCursors(): Boolean {
        return getTextSelectionCursorController()?.hide() == true
    }

    private fun renderTextSelection() {
        mTextSelectionCursorController?.render()
    }

    fun isSelectingText(): Boolean {
        return mTextSelectionCursorController != null && mTextSelectionCursorController!!.isActive()
    }

    /** Get the currently selected text if selecting. */
    fun getSelectedText(): String? {
        return if (isSelectingText() && mTextSelectionCursorController != null) {
            mTextSelectionCursorController!!.selectedText
        } else {
            null
        }
    }

    /** Get the selected text stored before "MORE" button was pressed on the context menu. */
    fun getStoredSelectedText(): String? {
        return mTextSelectionCursorController?.storedSelectedText
    }

    /** Unset the selected text stored before "MORE" button was pressed on the context menu. */
    fun unsetStoredSelectedText() {
        mTextSelectionCursorController?.unsetStoredSelectedText()
    }

    private fun getTextSelectionActionMode(): ActionMode? {
        return mTextSelectionCursorController?.actionMode
    }

    fun startTextSelectionMode(event: MotionEvent) {
        if (!requestFocus()) {
            return
        }

        showTextSelectionCursors(event)
        mClient?.copyModeChanged(isSelectingText())

        invalidate()
    }

    fun stopTextSelectionMode() {
        if (hideTextSelectionCursors()) {
            mClient?.copyModeChanged(isSelectingText())
            invalidate()
        }
    }

    fun decrementYTextSelectionCursors(decrement: Int) {
        mTextSelectionCursorController?.decrementYTextSelectionCursors(decrement)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        if (mTextSelectionCursorController != null) {
            viewTreeObserver.addOnTouchModeChangeListener(mTextSelectionCursorController)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()

        if (mTextSelectionCursorController != null) {
            // Might solve the following exception
            // android.view.WindowLeaked: Activity com.termux.app.TermuxActivity has leaked window android.widget.PopupWindow
            stopTextSelectionMode()

            viewTreeObserver.removeOnTouchModeChangeListener(mTextSelectionCursorController)
            mTextSelectionCursorController!!.onDetached()
        }
    }

    /**
     * Define functions required for long hold toolbar.
     */
    private val mShowFloatingToolbar = Runnable {
        if (getTextSelectionActionMode() != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                getTextSelectionActionMode()!!.hide(0L) // hide off.
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun showFloatingToolbar() {
        if (getTextSelectionActionMode() != null) {
            val delay = ViewConfiguration.getDoubleTapTimeout()
            postDelayed(mShowFloatingToolbar, delay.toLong())
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    fun hideFloatingToolbar() {
        if (getTextSelectionActionMode() != null) {
            removeCallbacks(mShowFloatingToolbar)
            getTextSelectionActionMode()!!.hide(-1)
        }
    }

    fun updateFloatingToolbarVisibility(event: MotionEvent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && getTextSelectionActionMode() != null) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> hideFloatingToolbar()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> showFloatingToolbar()
            }
        }
    }

    override fun showContextMenu(): Boolean {
        if (mOnContextMenuShowListener != null) {
            return mOnContextMenuShowListener!!.onShowContextMenu(this)
        }
        return super.showContextMenu()
    }

    override fun showContextMenu(x: Float, y: Float): Boolean {
        if (mOnContextMenuShowListener != null) {
            return mOnContextMenuShowListener!!.onShowContextMenu(this)
        }
        return super.showContextMenu(x, y)
    }
}
