package com.termux.shared.termux.extrakeys

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.PopupWindow
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import com.google.android.material.button.MaterialButton
import com.termux.shared.R
import com.termux.shared.theme.ThemeUtils
import java.util.ArrayList
import java.util.HashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.stream.Collectors

/**
 * A {@link View} showing extra keys (such as Escape, Ctrl, Alt) not normally available on an Android soft
 * keyboards.
 *
 * To use it, add following to a layout file and import it in your activity layout file or inflate
 * it with a {@link androidx.viewpager.widget.ViewPager}.:
 * {@code
 * <?xml version="1.0" encoding="utf-8"?>
 * <com.termux.shared.termux.extrakeys.ExtraKeysView xmlns:android="http://schemas.android.com/apk/res/android"
 *     android:id="@+id/extra_keys"
 *     style="?android:attr/buttonBarStyle"
 *     android:layout_width="match_parent"
 *     android:layout_height="match_parent"
 *     android:layout_alignParentBottom="true"
 *     android:orientation="horizontal" />
 * }
 *
 * Then in your activity, get its reference by a call to {@link android.app.Activity#findViewById(int)}
 * or {@link LayoutInflater#inflate(int, ViewGroup)} if using {@link androidx.viewpager.widget.ViewPager}.
 * Then call {@link #setExtraKeysViewClient(IExtraKeysView)} and pass it the implementation of
 * {@link IExtraKeysView} so that you can receive callbacks. You can also override other values set
 * in {@link ExtraKeysView#ExtraKeysView(Context, AttributeSet)} by calling the respective functions.
 * If you extend {@link ExtraKeysView}, you can also set them in the constructor, but do call super().
 *
 * After this you will have to make a call to {@link ExtraKeysView#reload(ExtraKeysInfo, float) and pass
 * it the {@link ExtraKeysInfo} to load and display the extra keys. Read its class javadocs for more
 * info on how to create it.
 *
 * Termux app defines the view in res/layout/view_terminal_toolbar_extra_keys and
 * inflates it in TerminalToolbarViewPager.instantiateItem() and sets the {@link ExtraKeysView} client
 * and calls {@link ExtraKeysView#reload(ExtraKeysInfo).
 * The {@link ExtraKeysInfo} is created by TermuxAppSharedProperties.setExtraKeys().
 * Then its got and the view height is adjusted in TermuxActivity.setTerminalToolbarHeight().
 * The client used is TermuxTerminalExtraKeys, which extends
 * {@link TerminalExtraKeys } to handle Termux app specific logic and
 * leave the rest to the super class.
 */
open class ExtraKeysView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GridLayout(context, attrs) {

    /** The client for the {@link ExtraKeysView}. */
    interface IExtraKeysView {

        /**
         * This is called by {@link ExtraKeysView} when a button is clicked. This is also called
         * for {@link #mRepetitiveKeys} and {@link ExtraKeyButton} that have a popup set.
         * However, this is not called for {@link #mSpecialButtons}, whose state can instead be read
         * via a call to {@link #readSpecialButton(SpecialButton, boolean)}.
         *
         * @param view The view that was clicked.
         * @param buttonInfo The {@link ExtraKeyButton} for the button that was clicked.
         *                   The button may be a {@link ExtraKeyButton#KEY_MACRO} set which can be
         *                   checked with a call to {@link ExtraKeyButton#isMacro()}.
         * @param button The {@link MaterialButton} that was clicked.
         */
        fun onExtraKeyButtonClick(view: View?, buttonInfo: ExtraKeyButton, button: MaterialButton?)

        /**
         * This is called by {@link ExtraKeysView} when a button is clicked so that the client
         * can perform any hepatic feedback. This is only called in the {@link MaterialButton.OnClickListener}
         * and not for every repeat. Its also called for {@link #mSpecialButtons}.
         *
         * @param view The view that was clicked.
         * @param buttonInfo The {@link ExtraKeyButton} for the button that was clicked.
         * @param button The {@link MaterialButton} that was clicked.
         * @return Return {@code true} if the client handled the feedback, otherwise {@code false}
         * so that {@link ExtraKeysView#performExtraKeyButtonHapticFeedback(View, ExtraKeyButton, MaterialButton)}
         * can handle it depending on system settings.
         */
        fun performExtraKeyButtonHapticFeedback(view: View?, buttonInfo: ExtraKeyButton, button: MaterialButton?): Boolean
    }

    /** The implementation of the {@link IExtraKeysView} that acts as a client for the {@link ExtraKeysView}. */
    @JvmField
    protected var mExtraKeysViewClient: IExtraKeysView? = null

    /** The map for the {@link SpecialButton} and their {@link SpecialButtonState}. Defaults to
     * the one returned by {@link #getDefaultSpecialButtons(ExtraKeysView)}. */
    @JvmField
    protected var mSpecialButtons: Map<SpecialButton, SpecialButtonState>? = null

    /** The keys for the {@link SpecialButton} added to {@link #mSpecialButtons}. This is automatically
     * set when the call to {@link #setSpecialButtons(Map)} is made. */
    @JvmField
    protected var mSpecialButtonsKeys: Set<String>? = null

    /**
     * The list of keys for which auto repeat of key should be triggered if its extra keys button
     * is long pressed. This is done by calling {@link IExtraKeysView#onExtraKeyButtonClick(View, ExtraKeyButton, MaterialButton)}
     * every {@link #mLongPressRepeatDelay} seconds after {@link #mLongPressTimeout} has passed.
     * The default keys are defined by {@link ExtraKeysConstants#PRIMARY_REPETITIVE_KEYS}.
     */
    @JvmField
    protected var mRepetitiveKeys: List<String>? = null

    /** The text color for the extra keys button. Defaults to {@link #DEFAULT_BUTTON_TEXT_COLOR}. */
    @JvmField
    protected var mButtonTextColor: Int = 0

    /** The text color for the extra keys button when its active.
     * Defaults to {@link #DEFAULT_BUTTON_ACTIVE_TEXT_COLOR}. */
    @JvmField
    protected var mButtonActiveTextColor: Int = 0

    /** The background color for the extra keys button. Defaults to {@link #DEFAULT_BUTTON_BACKGROUND_COLOR}. */
    @JvmField
    protected var mButtonBackgroundColor: Int = 0

    /** The background color for the extra keys button when its active. Defaults to
     * {@link #DEFAULT_BUTTON_ACTIVE_BACKGROUND_COLOR}. */
    @JvmField
    protected var mButtonActiveBackgroundColor: Int = 0

    /** Defines whether text for the extra keys button should be all capitalized automatically. */
    @JvmField
    protected var mButtonTextAllCaps: Boolean = true

    /**
     * Defines the duration in milliseconds before a press turns into a long press. The default
     * duration used is the one returned by a call to {@link ViewConfiguration#getLongPressTimeout()}
     * which will return the system defined duration which can be changed in accessibility settings.
     * The duration must be in between {@link #MIN_LONG_PRESS_DURATION} and {@link #MAX_LONG_PRESS_DURATION},
     * otherwise {@link #FALLBACK_LONG_PRESS_DURATION} is used.
     */
    @JvmField
    protected var mLongPressTimeout: Int = 0

    /**
     * Defines the duration in milliseconds for the delay between trigger of each repeat of
     * {@link #mRepetitiveKeys}. The default value is defined by {@link #DEFAULT_LONG_PRESS_REPEAT_DELAY}.
     * The duration must be in between {@link #MIN_LONG_PRESS__REPEAT_DELAY} and
     * {@link #MAX_LONG_PRESS__REPEAT_DELAY}, otherwise {@link #DEFAULT_LONG_PRESS_REPEAT_DELAY} is used.
     */
    @JvmField
    protected var mLongPressRepeatDelay: Int = 0

    /** The popup window shown if {@link ExtraKeyButton#getPopup()} returns a {@code non-null} value
     * and a swipe up action is done on an extra key. */
    @JvmField
    protected var mPopupWindow: PopupWindow? = null

    @JvmField
    protected var mScheduledExecutor: ScheduledExecutorService? = null

    @JvmField
    protected var mHandler: Handler? = null

    @JvmField
    protected var mSpecialButtonsLongHoldRunnable: SpecialButtonsLongHoldRunnable? = null

    @JvmField
    protected var mLongPressCount: Int = 0

    /** Get/Set {@link #mExtraKeysViewClient}. */
    open var extraKeysViewClient: IExtraKeysView?
        get() = mExtraKeysViewClient
        set(value) {
            mExtraKeysViewClient = value
        }

    /** Get/Set {@link #mRepetitiveKeys}. */
    open var repetitiveKeys: List<String>?
        get() {
            val keys = mRepetitiveKeys ?: return null
            return keys.stream().map { java.lang.String(it) as String }.collect(Collectors.toList())
        }
        set(value) {
            mRepetitiveKeys = value
        }

    /** Get/Set {@link #mSpecialButtons}. */
    open var specialButtons: Map<SpecialButton, SpecialButtonState>?
        get() = mSpecialButtons?.toMap()
        set(value) {
            mSpecialButtons = value
            mSpecialButtonsKeys = value?.keys?.stream()?.map { it.key }?.collect(Collectors.toSet())
        }

    /** Get {@link #mSpecialButtonsKeys}. */
    open val specialButtonsKeys: Set<String>?
        get() {
            val keys = mSpecialButtonsKeys ?: return null
            return keys.stream().map { java.lang.String(it) as String }.collect(Collectors.toSet())
        }

    /** Get/Set {@link #mButtonTextColor}. */
    open var buttonTextColor: Int
        get() = mButtonTextColor
        set(value) {
            mButtonTextColor = value
        }

    /** Get/Set {@link #mButtonActiveTextColor}. */
    open var buttonActiveTextColor: Int
        get() = mButtonActiveTextColor
        set(value) {
            mButtonActiveTextColor = value
        }

    /** Get/Set {@link #mButtonBackgroundColor}. */
    open var buttonBackgroundColor: Int
        get() = mButtonBackgroundColor
        set(value) {
            mButtonBackgroundColor = value
        }

    /** Get/Set {@link #mButtonActiveBackgroundColor}. */
    open var buttonActiveBackgroundColor: Int
        get() = mButtonActiveBackgroundColor
        set(value) {
            mButtonActiveBackgroundColor = value
        }

    /** Get/Set {@link #mButtonTextAllCaps}. */
    open var buttonTextAllCaps: Boolean
        get() = mButtonTextAllCaps
        set(value) {
            mButtonTextAllCaps = value
        }

    /** Get/Set {@link #mLongPressTimeout}. */
    open var longPressTimeout: Int
        get() = mLongPressTimeout
        set(value) {
            if (value in MIN_LONG_PRESS_DURATION..MAX_LONG_PRESS_DURATION) {
                mLongPressTimeout = value
            } else {
                mLongPressTimeout = FALLBACK_LONG_PRESS_DURATION
            }
        }

    /** Get/Set {@link #mLongPressRepeatDelay}. */
    open var longPressRepeatDelay: Int
        get() = mLongPressRepeatDelay
        set(value) {
            if (value in MIN_LONG_PRESS__REPEAT_DELAY..MAX_LONG_PRESS__REPEAT_DELAY) {
                mLongPressRepeatDelay = value
            } else {
                mLongPressRepeatDelay = DEFAULT_LONG_PRESS_REPEAT_DELAY
            }
        }

    init {
        repetitiveKeys = ExtraKeysConstants.PRIMARY_REPETITIVE_KEYS
        specialButtons = getDefaultSpecialButtons(this)

        setButtonColors(
            ThemeUtils.getSystemAttrColor(context, ATTR_BUTTON_TEXT_COLOR, DEFAULT_BUTTON_TEXT_COLOR),
            ThemeUtils.getSystemAttrColor(context, ATTR_BUTTON_ACTIVE_TEXT_COLOR, DEFAULT_BUTTON_ACTIVE_TEXT_COLOR),
            ThemeUtils.getSystemAttrColor(context, ATTR_BUTTON_BACKGROUND_COLOR, DEFAULT_BUTTON_BACKGROUND_COLOR),
            ThemeUtils.getSystemAttrColor(context, ATTR_BUTTON_ACTIVE_BACKGROUND_COLOR, DEFAULT_BUTTON_ACTIVE_BACKGROUND_COLOR)
        )

        longPressTimeout = ViewConfiguration.getLongPressTimeout()
        longPressRepeatDelay = DEFAULT_LONG_PRESS_REPEAT_DELAY
    }

    /**
     * Set the {@link ExtraKeysView} button colors.
     *
     * @param buttonTextColor The value for {@link #mButtonTextColor}.
     * @param buttonActiveTextColor The value for {@link #mButtonActiveTextColor}.
     * @param buttonBackgroundColor The value for {@link #mButtonBackgroundColor}.
     * @param buttonActiveBackgroundColor The value for {@link #mButtonActiveBackgroundColor}.
     */
    open fun setButtonColors(buttonTextColor: Int, buttonActiveTextColor: Int, buttonBackgroundColor: Int, buttonActiveBackgroundColor: Int) {
        mButtonTextColor = buttonTextColor
        mButtonActiveTextColor = buttonActiveTextColor
        mButtonBackgroundColor = buttonBackgroundColor
        mButtonActiveBackgroundColor = buttonActiveBackgroundColor
    }

    /** Get the default map that can be used for {@link #mSpecialButtons}. */
    open fun getDefaultSpecialButtons(extraKeysView: ExtraKeysView): Map<SpecialButton, SpecialButtonState> {
        val map = HashMap<SpecialButton, SpecialButtonState>()
        map[SpecialButton.CTRL] = SpecialButtonState(extraKeysView)
        map[SpecialButton.ALT] = SpecialButtonState(extraKeysView)
        map[SpecialButton.SHIFT] = SpecialButtonState(extraKeysView)
        map[SpecialButton.FN] = SpecialButtonState(extraKeysView)
        return map
    }

    /**
     * Reload this instance of {@link ExtraKeysView} with the info passed in {@code extraKeysInfo}.
     *
     * @param extraKeysInfo The {@link ExtraKeysInfo} that defines the necessary info for the extra keys.
     * @param heightPx The height in pixels of the parent surrounding the {@link ExtraKeysView}. It must
     *                 be a single child.
     */
    @SuppressLint("ClickableViewAccessibility")
    open fun reload(extraKeysInfo: ExtraKeysInfo?, heightPx: Float) {
        if (extraKeysInfo == null) return

        mSpecialButtons?.values?.forEach { state ->
            state.buttons = ArrayList()
        }

        removeAllViews()

        val buttons = extraKeysInfo.matrix

        rowCount = buttons.size
        columnCount = maximumLength(buttons as Array<Array<*>>)

        for (row in buttons.indices) {
            for (col in buttons[row].indices) {
                val buttonInfo = buttons[row][col]

                val button: MaterialButton
                if (isSpecialButton(buttonInfo)) {
                    button = createSpecialButton(buttonInfo.key, true) ?: return
                } else {
                    button = MaterialButton(context, null, android.R.attr.buttonBarButtonStyle)
                }

                button.text = buttonInfo.display
                button.setTextColor(mButtonTextColor)
                button.isAllCaps = mButtonTextAllCaps
                button.setPadding(0, 0, 0, 0)

                button.setOnClickListener { view ->
                    performExtraKeyButtonHapticFeedback(view, buttonInfo, button)
                    onAnyExtraKeyButtonClick(view, buttonInfo, button)
                }

                button.setOnTouchListener { view, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            view.setBackgroundColor(mButtonActiveBackgroundColor)
                            // Start long press scheduled executors which will be stopped in next MotionEvent
                            startScheduledExecutors(view, buttonInfo, button)
                            true
                        }

                        MotionEvent.ACTION_MOVE -> {
                            if (buttonInfo.popup != null) {
                                // Show popup on swipe up
                                if (mPopupWindow == null && event.y < 0) {
                                    stopScheduledExecutors()
                                    view.setBackgroundColor(mButtonBackgroundColor)
                                    showPopup(view, buttonInfo.popup)
                                }
                                if (mPopupWindow != null && event.y > 0) {
                                    view.setBackgroundColor(mButtonActiveBackgroundColor)
                                    dismissPopup()
                                }
                            }
                            true
                        }

                        MotionEvent.ACTION_CANCEL -> {
                            view.setBackgroundColor(mButtonBackgroundColor)
                            stopScheduledExecutors()
                            true
                        }

                        MotionEvent.ACTION_UP -> {
                            view.setBackgroundColor(mButtonBackgroundColor)
                            stopScheduledExecutors()
                            // If ACTION_UP up was not from a repetitive key or was with a key with a popup button
                            if (mLongPressCount == 0 || mPopupWindow != null) {
                                // Trigger popup button click if swipe up complete
                                if (mPopupWindow != null) {
                                    dismissPopup()
                                    if (buttonInfo.popup != null) {
                                        onAnyExtraKeyButtonClick(view, buttonInfo.popup, button)
                                    }
                                } else {
                                    view.performClick()
                                }
                            }
                            true
                        }

                        else -> true
                    }
                }

                val param = LayoutParams()
                param.width = 0
                if (Build.VERSION.SDK_INT == Build.VERSION_CODES.LOLLIPOP) {
                    param.height = (heightPx + 0.5f).toInt()
                } else {
                    param.height = 0
                }
                param.setMargins(0, 0, 0, 0)
                param.columnSpec = GridLayout.spec(col, GridLayout.FILL, 1f)
                param.rowSpec = GridLayout.spec(row, GridLayout.FILL, 1f)
                button.layoutParams = param

                addView(button)
            }
        }
    }

    open fun onExtraKeyButtonClick(view: View, buttonInfo: ExtraKeyButton, button: MaterialButton) {
        mExtraKeysViewClient?.onExtraKeyButtonClick(view, buttonInfo, button)
    }

    open fun performExtraKeyButtonHapticFeedback(view: View, buttonInfo: ExtraKeyButton, button: MaterialButton) {
        if (mExtraKeysViewClient != null) {
            // If client handled the feedback, then just return
            if (mExtraKeysViewClient!!.performExtraKeyButtonHapticFeedback(view, buttonInfo, button)) return
        }

        if (Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 0) != 0) {
            if (Build.VERSION.SDK_INT >= 28) {
                button.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            } else {
                // Perform haptic feedback only if no total silence mode enabled.
                if (Settings.Global.getInt(context.contentResolver, "zen_mode", 0) != 2) {
                    button.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
            }
        }
    }

    open fun onAnyExtraKeyButtonClick(view: View, buttonInfo: ExtraKeyButton, button: MaterialButton) {
        if (isSpecialButton(buttonInfo)) {
            if (mLongPressCount > 0) return
            val state = mSpecialButtons?.get(SpecialButton.valueOf(buttonInfo.key)) ?: return

            // Toggle active state and disable lock state if new state is not active
            state.setIsActive(!state.isActive)
            if (!state.isActive) state.setIsLocked(false)
        } else {
            onExtraKeyButtonClick(view, buttonInfo, button)
        }
    }

    open fun startScheduledExecutors(view: View, buttonInfo: ExtraKeyButton, button: MaterialButton) {
        stopScheduledExecutors()
        mLongPressCount = 0
        if (mRepetitiveKeys != null && mRepetitiveKeys!!.contains(buttonInfo.key)) {
            // Auto repeat key if long pressed until ACTION_UP stops it by calling stopScheduledExecutors.
            // Currently, only one (last) repeat key can run at a time. Old ones are stopped.
            mScheduledExecutor = Executors.newSingleThreadScheduledExecutor()
            mScheduledExecutor!!.scheduleWithFixedDelay({
                mLongPressCount++
                onExtraKeyButtonClick(view, buttonInfo, button)
            }, mLongPressTimeout.toLong(), mLongPressRepeatDelay.toLong(), TimeUnit.MILLISECONDS)
        } else if (isSpecialButton(buttonInfo)) {
            // Lock the key if long pressed by running mSpecialButtonsLongHoldRunnable after
            // waiting for mLongPressTimeout milliseconds. If user does not long press, then the
            // ACTION_UP triggered will cancel the runnable by calling stopScheduledExecutors before
            // it has a chance to run.
            val state = mSpecialButtons?.get(SpecialButton.valueOf(buttonInfo.key)) ?: return
            if (mHandler == null) mHandler = Handler(Looper.getMainLooper())
            mSpecialButtonsLongHoldRunnable = SpecialButtonsLongHoldRunnable(state)
            mHandler!!.postDelayed(mSpecialButtonsLongHoldRunnable!!, mLongPressTimeout.toLong())
        }
    }

    open fun stopScheduledExecutors() {
        if (mScheduledExecutor != null) {
            mScheduledExecutor!!.shutdownNow()
            mScheduledExecutor = null
        }

        if (mSpecialButtonsLongHoldRunnable != null && mHandler != null) {
            mHandler!!.removeCallbacks(mSpecialButtonsLongHoldRunnable!!)
            mSpecialButtonsLongHoldRunnable = null
        }
    }

    inner class SpecialButtonsLongHoldRunnable(val mState: SpecialButtonState) : Runnable {
        override fun run() {
            // Toggle active and lock state
            mState.setIsLocked(!mState.isActive)
            mState.setIsActive(!mState.isActive)
            mLongPressCount++
        }
    }

    internal fun showPopup(view: View, extraButton: ExtraKeyButton) {
        val width = view.measuredWidth
        val height = view.measuredHeight
        val button: MaterialButton
        if (isSpecialButton(extraButton)) {
            button = createSpecialButton(extraButton.key, false) ?: return
        } else {
            button = MaterialButton(context, null, android.R.attr.buttonBarButtonStyle)
            button.setTextColor(mButtonTextColor)
        }
        button.text = extraButton.display
        button.isAllCaps = mButtonTextAllCaps
        button.setPadding(0, 0, 0, 0)
        button.minHeight = 0
        button.minWidth = 0
        button.minimumWidth = 0
        button.minimumHeight = 0
        button.width = width
        button.height = height
        button.setBackgroundColor(mButtonActiveBackgroundColor)
        val popupWindow = PopupWindow(this)
        popupWindow.width = ViewGroup.LayoutParams.WRAP_CONTENT
        popupWindow.height = ViewGroup.LayoutParams.WRAP_CONTENT
        popupWindow.contentView = button
        popupWindow.isOutsideTouchable = true
        popupWindow.isFocusable = false
        popupWindow.showAsDropDown(view, 0, -2 * height)
        mPopupWindow = popupWindow
    }

    open fun dismissPopup() {
        mPopupWindow?.contentView = null
        mPopupWindow?.dismiss()
        mPopupWindow = null
    }

    /** Check whether a {@link ExtraKeyButton} is a {@link SpecialButton}. */
    open fun isSpecialButton(button: ExtraKeyButton): Boolean {
        return mSpecialButtonsKeys?.contains(button.key) == true
    }

    /**
     * Read whether {@link SpecialButton} registered in {@link #mSpecialButtons} is active or not.
     *
     * @param specialButton The {@link SpecialButton} to read.
     * @param autoSetInActive Set to {@code true} if {@link SpecialButtonState#isActive} should be
     *                        set {@code false} if button is not locked.
     * @return Returns {@code null} if button does not exist in {@link #mSpecialButtons}. If button
     *         exists, then returns {@code true} if the button is created in {@link ExtraKeysView}
     *         and is active, otherwise {@code false}.
     */
    @Nullable
    open fun readSpecialButton(specialButton: SpecialButton?, autoSetInActive: Boolean): Boolean? {
        val state = mSpecialButtons?.get(specialButton) ?: return null

        if (!state.isCreated || !state.isActive) return false

        // Disable active state only if not locked
        if (autoSetInActive && !state.isLocked) state.setIsActive(false)

        return true
    }

    open fun createSpecialButton(buttonKey: String?, needUpdate: Boolean): MaterialButton? {
        val state = mSpecialButtons?.get(SpecialButton.valueOf(buttonKey)) ?: return null
        state.setIsCreated(true)
        val button = MaterialButton(context, null, android.R.attr.buttonBarButtonStyle)
        button.setTextColor(if (state.isActive) mButtonActiveTextColor else mButtonTextColor)
        if (needUpdate) {
            state.buttons.add(button)
        }
        return button
    }

    companion object {
        /** Defines the default value for {@link #mButtonTextColor} defined by current theme. */
        @JvmField
        val ATTR_BUTTON_TEXT_COLOR = R.attr.extraKeysButtonTextColor

        /** Defines the default value for {@link #mButtonActiveTextColor} defined by current theme. */
        @JvmField
        val ATTR_BUTTON_ACTIVE_TEXT_COLOR = R.attr.extraKeysButtonActiveTextColor

        /** Defines the default value for {@link #mButtonBackgroundColor} defined by current theme. */
        @JvmField
        val ATTR_BUTTON_BACKGROUND_COLOR = R.attr.extraKeysButtonBackgroundColor

        /** Defines the default value for {@link #mButtonActiveBackgroundColor} defined by current theme. */
        @JvmField
        val ATTR_BUTTON_ACTIVE_BACKGROUND_COLOR = R.attr.extraKeysButtonActiveBackgroundColor

        /** Defines the default fallback value for {@link #mButtonTextColor} if {@link #ATTR_BUTTON_TEXT_COLOR} is undefined. */
        const val DEFAULT_BUTTON_TEXT_COLOR = -0x1 // 0xFFFFFFFF

        /** Defines the default fallback value for {@link #mButtonActiveTextColor} if {@link #ATTR_BUTTON_ACTIVE_TEXT_COLOR} is undefined. */
        const val DEFAULT_BUTTON_ACTIVE_TEXT_COLOR = -0x7f2116 // 0xFF80DEEA

        /** Defines the default fallback value for {@link #mButtonBackgroundColor} if {@link #ATTR_BUTTON_BACKGROUND_COLOR} is undefined. */
        const val DEFAULT_BUTTON_BACKGROUND_COLOR = 0x00000000

        /** Defines the default fallback value for {@link #mButtonActiveBackgroundColor} if {@link #ATTR_BUTTON_ACTIVE_BACKGROUND_COLOR} is undefined. */
        const val DEFAULT_BUTTON_ACTIVE_BACKGROUND_COLOR = -0x808081 // 0xFF7F7F7F

        /** Defines the minimum allowed duration in milliseconds for {@link #mLongPressTimeout}. */
        const val MIN_LONG_PRESS_DURATION = 200

        /** Defines the maximum allowed duration in milliseconds for {@link #mLongPressTimeout}. */
        const val MAX_LONG_PRESS_DURATION = 3000

        /** Defines the fallback duration in milliseconds for {@link #mLongPressTimeout}. */
        const val FALLBACK_LONG_PRESS_DURATION = 400

        /** Defines the minimum allowed duration in milliseconds for {@link #mLongPressRepeatDelay}. */
        const val MIN_LONG_PRESS__REPEAT_DELAY = 5

        /** Defines the maximum allowed duration in milliseconds for {@link #mLongPressRepeatDelay}. */
        const val MAX_LONG_PRESS__REPEAT_DELAY = 2000

        /** Defines the default duration in milliseconds for {@link #mLongPressRepeatDelay}. */
        const val DEFAULT_LONG_PRESS_REPEAT_DELAY = 80

        /**
         * General util function to compute the longest column length in a matrix.
         */
        @JvmStatic
        fun maximumLength(matrix: Array<out Array<*>>): Int {
            var m = 0
            for (row in matrix) m = Math.max(m, row.size)
            return m
        }
    }
}
