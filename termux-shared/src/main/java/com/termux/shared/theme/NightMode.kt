package com.termux.shared.theme

import androidx.annotation.NonNull
import androidx.annotation.Nullable
import androidx.appcompat.app.AppCompatDelegate
import com.termux.shared.logger.Logger

/** The modes used by to decide night mode for themes. */
enum class NightMode(private val modeName: String, val mode: Int) {

    /** Night theme should be enabled. */
    TRUE("true", AppCompatDelegate.MODE_NIGHT_YES),

    /** Dark theme should be enabled. */
    FALSE("false", AppCompatDelegate.MODE_NIGHT_NO),

    /**
     * Use night or dark theme depending on system night mode.
     * https://developer.android.com/guide/topics/resources/providing-resources#NightQualifier
     */
    SYSTEM("system", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);

    fun getName(): String {
        return modeName
    }

    companion object {
        /** The current app wide night mode used by various libraries. Defaults to [SYSTEM]. */
        private var APP_NIGHT_MODE: NightMode? = null

        private const val LOG_TAG = "NightMode"

        /** Get [NightMode] for [name] if found, otherwise `null`. */
        @JvmStatic
        @Nullable
        fun modeOf(name: String?): NightMode? {
            if (name == null) return null
            for (v in values()) {
                if (v.modeName == name) {
                    return v
                }
            }
            return null
        }

        /** Get [NightMode] for [name] if found, otherwise [def]. */
        @JvmStatic
        @NonNull
        fun modeOf(@Nullable name: String?, @NonNull def: NightMode): NightMode {
            return modeOf(name) ?: def
        }

        /** Set [APP_NIGHT_MODE]. */
        @JvmStatic
        fun setAppNightMode(@Nullable name: String?) {
            if (name.isNullOrEmpty()) {
                APP_NIGHT_MODE = SYSTEM
            } else {
                val nightMode = modeOf(name)
                if (nightMode == null) {
                    Logger.logError(LOG_TAG, "Invalid APP_NIGHT_MODE \"$name\"")
                    return
                }
                APP_NIGHT_MODE = nightMode
            }
            Logger.logVerbose(LOG_TAG, "Set APP_NIGHT_MODE to \"" + APP_NIGHT_MODE?.getName() + "\"")
        }

        /** Get [APP_NIGHT_MODE]. */
        @JvmStatic
        @NonNull
        fun getAppNightMode(): NightMode {
            if (APP_NIGHT_MODE == null) {
                APP_NIGHT_MODE = SYSTEM
            }
            return APP_NIGHT_MODE!!
        }
    }
}
