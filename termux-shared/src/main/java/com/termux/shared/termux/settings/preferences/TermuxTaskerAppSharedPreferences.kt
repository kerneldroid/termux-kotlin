package com.termux.shared.termux.settings.preferences

import android.content.Context
import com.termux.shared.android.PackageUtils
import com.termux.shared.logger.Logger
import com.termux.shared.settings.preferences.AppSharedPreferences
import com.termux.shared.settings.preferences.SharedPreferenceUtils
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.TermuxUtils
import com.termux.shared.termux.settings.preferences.TermuxPreferenceConstants.TERMUX_TASKER_APP

class TermuxTaskerAppSharedPreferences private constructor(context: Context) : AppSharedPreferences(
    context,
    SharedPreferenceUtils.getPrivateSharedPreferences(
        context,
        TermuxConstants.TERMUX_TASKER_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION
    ),
    SharedPreferenceUtils.getPrivateAndMultiProcessSharedPreferences(
        context,
        TermuxConstants.TERMUX_TASKER_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION
    )
) {

    fun getLogLevel(readFromFile: Boolean): Int {
        return if (readFromFile) {
            SharedPreferenceUtils.getInt(
                mMultiProcessSharedPreferences,
                TERMUX_TASKER_APP.KEY_LOG_LEVEL,
                Logger.DEFAULT_LOG_LEVEL
            )
        } else {
            SharedPreferenceUtils.getInt(
                mSharedPreferences,
                TERMUX_TASKER_APP.KEY_LOG_LEVEL,
                Logger.DEFAULT_LOG_LEVEL
            )
        }
    }

    fun setLogLevel(context: Context, logLevel: Int, commitToFile: Boolean) {
        val updatedLogLevel = Logger.setLogLevel(context, logLevel)
        SharedPreferenceUtils.setInt(
            mSharedPreferences,
            TERMUX_TASKER_APP.KEY_LOG_LEVEL,
            updatedLogLevel,
            commitToFile
        )
    }

    fun getLastPendingIntentRequestCode(): Int {
        return SharedPreferenceUtils.getInt(
            mSharedPreferences,
            TERMUX_TASKER_APP.KEY_LAST_PENDING_INTENT_REQUEST_CODE,
            TERMUX_TASKER_APP.DEFAULT_VALUE_KEY_LAST_PENDING_INTENT_REQUEST_CODE
        )
    }

    fun setLastPendingIntentRequestCode(lastPendingIntentRequestCode: Int) {
        SharedPreferenceUtils.setInt(
            mSharedPreferences,
            TERMUX_TASKER_APP.KEY_LAST_PENDING_INTENT_REQUEST_CODE,
            lastPendingIntentRequestCode,
            false
        )
    }

    companion object {
        private const val LOG_TAG = "TermuxTaskerAppSharedPreferences"

        /**
         * Get {@link TermuxTaskerAppSharedPreferences}.
         *
         * @param context The {@link Context} to use to get the {@link Context} of the
         *                {@link TermuxConstants#TERMUX_TASKER_PACKAGE_NAME}.
         * @return Returns the {@link TermuxTaskerAppSharedPreferences}. This will {@code null} if an exception is raised.
         */
        @JvmStatic
        fun build(context: Context): TermuxTaskerAppSharedPreferences? {
            val termuxTaskerPackageContext = PackageUtils.getContextForPackage(
                context,
                TermuxConstants.TERMUX_TASKER_PACKAGE_NAME
            ) ?: return null
            return TermuxTaskerAppSharedPreferences(termuxTaskerPackageContext)
        }

        /**
         * Get {@link TermuxTaskerAppSharedPreferences}.
         *
         * @param context The {@link Context} to use to get the {@link Context} of the
         *                {@link TermuxConstants#TERMUX_TASKER_PACKAGE_NAME}.
         * @param exitAppOnError If {@code true} and failed to get package context, then a dialog will
         *                       be shown which when dismissed will exit the app.
         * @return Returns the {@link TermuxTaskerAppSharedPreferences}. This will {@code null} if an exception is raised.
         */
        @JvmStatic
        fun build(context: Context, exitAppOnError: Boolean): TermuxTaskerAppSharedPreferences? {
            val termuxTaskerPackageContext = TermuxUtils.getContextForPackageOrExitApp(
                context,
                TermuxConstants.TERMUX_TASKER_PACKAGE_NAME,
                exitAppOnError
            ) ?: return null
            return TermuxTaskerAppSharedPreferences(termuxTaskerPackageContext)
        }
    }
}
