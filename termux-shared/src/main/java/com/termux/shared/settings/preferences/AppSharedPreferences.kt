package com.termux.shared.settings.preferences

import android.content.Context
import android.content.SharedPreferences

/** A class that holds [SharedPreferences] objects for apps. */
open class AppSharedPreferences @JvmOverloads protected constructor(
    /** The [Context] for operations. */
    @JvmField protected val mContext: Context,
    /** The [SharedPreferences] that ideally should be created with [SharedPreferenceUtils.getPrivateSharedPreferences]. */
    @JvmField protected val mSharedPreferences: SharedPreferences?,
    /** The [SharedPreferences] that ideally should be created with [SharedPreferenceUtils.getPrivateAndMultiProcessSharedPreferences]. */
    @JvmField protected val mMultiProcessSharedPreferences: SharedPreferences? = null
) {

    /** Get [mContext]. */
    open val context: Context
        get() = mContext

    /** Get [mSharedPreferences]. */
    open val sharedPreferences: SharedPreferences?
        get() = mSharedPreferences

    /** Get [mMultiProcessSharedPreferences]. */
    open val multiProcessSharedPreferences: SharedPreferences?
        get() = mMultiProcessSharedPreferences
}
