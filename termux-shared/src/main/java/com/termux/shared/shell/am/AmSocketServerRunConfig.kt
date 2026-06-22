package com.termux.shared.shell.am

import android.Manifest
import com.termux.shared.logger.Logger
import com.termux.shared.markdown.MarkdownUtils
import com.termux.shared.net.socket.local.ILocalSocketManager
import com.termux.shared.net.socket.local.LocalSocketRunConfig
import java.io.Serializable

/**
 * Run config for {@link AmSocketServer}.
 */
open class AmSocketServerRunConfig(
    title: String,
    path: String,
    localSocketManagerClient: ILocalSocketManager
) : LocalSocketRunConfig(title, path, localSocketManagerClient), Serializable {

    /**
     * Check if {@link Manifest.permission#SYSTEM_ALERT_WINDOW} has been granted if running on Android `>= 10`
     * if starting activities. Will also check when starting services in case starting foreground
     * service is not allowed.
     *
     * https://developer.android.com/guide/components/activities/background-starts
     */
    private var mCheckDisplayOverAppsPermission: Boolean? = null

    /** Get {@link #mCheckDisplayOverAppsPermission} if set, otherwise {@link #DEFAULT_CHECK_DISPLAY_OVER_APPS_PERMISSION}. */
    open fun shouldCheckDisplayOverAppsPermission(): Boolean {
        return mCheckDisplayOverAppsPermission ?: DEFAULT_CHECK_DISPLAY_OVER_APPS_PERMISSION
    }

    /** Set {@link #mCheckDisplayOverAppsPermission}. */
    open fun setCheckDisplayOverAppsPermission(checkDisplayOverAppsPermission: Boolean?) {
        mCheckDisplayOverAppsPermission = checkDisplayOverAppsPermission
    }

    /** Get a log {@link String} for the {@link AmSocketServerRunConfig}. */
    override fun getLogString(): String {
        val logString = StringBuilder()
        logString.append(super.getLogString()).append("\n\n\n")

        logString.append("Am Command:")
        logString.append("\n").append(Logger.getSingleLineLogStringEntry("CheckDisplayOverAppsPermission", shouldCheckDisplayOverAppsPermission(), "-"))

        return logString.toString()
    }

    /** Get a markdown {@link String} for the {@link AmSocketServerRunConfig}. */
    override fun getMarkdownString(): String {
        val markdownString = StringBuilder()
        markdownString.append(super.getMarkdownString()).append("\n\n\n")

        markdownString.append("## ").append("Am Command")
        markdownString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("CheckDisplayOverAppsPermission", shouldCheckDisplayOverAppsPermission(), "-"))

        return markdownString.toString()
    }

    override fun toString(): String {
        return getLogString()
    }

    companion object {
        const val DEFAULT_CHECK_DISPLAY_OVER_APPS_PERMISSION: Boolean = true

        /**
         * Get a log {@link String} for {@link AmSocketServerRunConfig}.
         *
         * @param config The {@link AmSocketServerRunConfig} to get info of.
         * @return Returns the log {@link String}.
         */
        @JvmStatic
        fun getRunConfigLogString(config: AmSocketServerRunConfig?): String {
            if (config == null) return "null"
            return config.getLogString()
        }

        /**
         * Get a markdown {@link String} for {@link AmSocketServerRunConfig}.
         *
         * @param config The {@link AmSocketServerRunConfig} to get info of.
         * @return Returns the markdown {@link String}.
         */
        @JvmStatic
        fun getRunConfigMarkdownString(config: AmSocketServerRunConfig?): String {
            if (config == null) return "null"
            return config.getMarkdownString()
        }
    }
}
