package com.termux.app.terminal.io

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import com.termux.app.TermuxActivity

/**
 * Work around for fullscreen mode in Termux to fix ExtraKeysView not being visible.
 * This class is derived from:
 * https://stackoverflow.com/questions/7417123/android-how-to-adjust-layout-in-full-screen-mode-when-softkeyboard-is-visible
 * and has some additional tweaks
 * ---
 * For more information, see https://issuetracker.google.com/issues/36911528
 */
class FullScreenWorkAround private constructor(activity: TermuxActivity) {
    private val mChildOfContent: View
    private var mUsableHeightPrevious = 0
    private val mViewGroupLayoutParams: ViewGroup.LayoutParams
    private val mNavBarHeight: Int

    init {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
            ?: throw IllegalStateException("Activity content view not found")
        mChildOfContent = content.getChildAt(0)
            ?: throw IllegalStateException("Activity child of content view not found")
        mViewGroupLayoutParams = mChildOfContent.layoutParams
            ?: throw IllegalStateException("Layout params not found for child of content")
        mNavBarHeight = activity.navBarHeight
        mChildOfContent.viewTreeObserver.addOnGlobalLayoutListener {
            possiblyResizeChildOfContent()
        }
    }

    private fun possiblyResizeChildOfContent() {
        val usableHeightNow = computeUsableHeight()
        if (usableHeightNow != mUsableHeightPrevious) {
            val usableHeightSansKeyboard = mChildOfContent.rootView.height
            val heightDifference = usableHeightSansKeyboard - usableHeightNow
            if (heightDifference > usableHeightSansKeyboard / 4) {
                // keyboard probably just became visible

                // ensures that usable layout space does not extend behind the
                // soft keyboard, causing the extra keys to not be visible
                mViewGroupLayoutParams.height = usableHeightSansKeyboard - heightDifference + getNavBarHeight()
            } else {
                // keyboard probably just became hidden
                mViewGroupLayoutParams.height = usableHeightSansKeyboard
            }
            mChildOfContent.requestLayout()
            mUsableHeightPrevious = usableHeightNow
        }
    }

    private fun getNavBarHeight(): Int {
        return mNavBarHeight
    }

    private fun computeUsableHeight(): Int {
        val r = Rect()
        mChildOfContent.getWindowVisibleDisplayFrame(r)
        return r.bottom - r.top
    }

    companion object {
        @JvmStatic
        fun apply(activity: TermuxActivity) {
            FullScreenWorkAround(activity)
        }
    }
}
