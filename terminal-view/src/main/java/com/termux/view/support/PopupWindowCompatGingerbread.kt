/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */
package com.termux.view.support

import android.widget.PopupWindow
import java.lang.reflect.Method

/**
 * Implementation of PopupWindow compatibility that can call Gingerbread APIs.
 * https://chromium.googlesource.com/android_tools/+/HEAD/sdk/extras/android/support/v4/src/gingerbread/android/support/v4/widget/PopupWindowCompatGingerbread.java
 */
object PopupWindowCompatGingerbread {

    private var sSetWindowLayoutTypeMethod: Method? = null
    private var sSetWindowLayoutTypeMethodAttempted = false
    private var sGetWindowLayoutTypeMethod: Method? = null
    private var sGetWindowLayoutTypeMethodAttempted = false

    @JvmStatic
    fun setWindowLayoutType(popupWindow: PopupWindow, layoutType: Int) {
        if (!sSetWindowLayoutTypeMethodAttempted) {
            try {
                sSetWindowLayoutTypeMethod = PopupWindow::class.java.getDeclaredMethod(
                    "setWindowLayoutType", Int::class.javaPrimitiveType!!
                )
                sSetWindowLayoutTypeMethod?.isAccessible = true
            } catch (e: Exception) {
                // Reflection method fetch failed. Oh well.
            }
            sSetWindowLayoutTypeMethodAttempted = true
        }
        sSetWindowLayoutTypeMethod?.let { method ->
            try {
                method.invoke(popupWindow, layoutType)
            } catch (e: Exception) {
                // Reflection call failed. Oh well.
            }
        }
    }

    @JvmStatic
    fun getWindowLayoutType(popupWindow: PopupWindow): Int {
        if (!sGetWindowLayoutTypeMethodAttempted) {
            try {
                sGetWindowLayoutTypeMethod = PopupWindow::class.java.getDeclaredMethod(
                    "getWindowLayoutType"
                )
                sGetWindowLayoutTypeMethod?.isAccessible = true
            } catch (e: Exception) {
                // Reflection method fetch failed. Oh well.
            }
            sGetWindowLayoutTypeMethodAttempted = true
        }
        sGetWindowLayoutTypeMethod?.let { method ->
            try {
                return method.invoke(popupWindow) as? Int ?: 0
            } catch (e: Exception) {
                // Reflection call failed. Oh well.
            }
        }
        return 0
    }
}
