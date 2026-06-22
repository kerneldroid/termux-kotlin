package com.termux.shared.termux.crash

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import com.termux.shared.activities.ReportActivity
import com.termux.shared.android.AndroidUtils
import com.termux.shared.crash.CrashHandler
import com.termux.shared.data.DataUtils
import com.termux.shared.errors.Error
import com.termux.shared.file.FileUtils
import com.termux.shared.logger.Logger
import com.termux.shared.markdown.MarkdownUtils
import com.termux.shared.models.ReportInfo
import com.termux.shared.notification.NotificationUtils
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.TermuxConstants.TERMUX_APP
import com.termux.shared.termux.TermuxUtils
import com.termux.shared.termux.models.UserAction
import com.termux.shared.termux.notification.TermuxNotificationUtils
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences
import com.termux.shared.termux.settings.preferences.TermuxPreferenceConstants
import java.nio.charset.Charset

class TermuxCrashUtils private constructor(private val mType: TYPE) : CrashHandler.CrashHandlerClient {

    enum class TYPE {
        UNCAUGHT_EXCEPTION,
        CAUGHT_EXCEPTION
    }

    override fun onPreLogCrash(context: Context?, thread: Thread?, throwable: Throwable?): Boolean {
        return false
    }

    override fun onPostLogCrash(context: Context?, thread: Thread?, throwable: Throwable?) {
        if (context == null) return
        val currentPackageName = context.packageName

        // Do not notify if is a non-termux app
        val termuxContext = TermuxUtils.getTermuxPackageContext(context)
        if (termuxContext == null) {
            Logger.logWarn(
                LOG_TAG,
                "Ignoring call to onPostLogCrash() since failed to get \"" + TermuxConstants.TERMUX_PACKAGE_NAME + "\" package context from \"" + currentPackageName + "\" context"
            )
            return
        }

        // If an uncaught exception, then do not notify since the termux app itself would be crashing
        if (TYPE.UNCAUGHT_EXCEPTION == mType && TermuxConstants.TERMUX_PACKAGE_NAME == currentPackageName) return

        val message = TERMUX_APP.TERMUX_ACTIVITY_NAME + " that \"" + currentPackageName + "\" app crashed"

        try {
            Logger.logInfo(LOG_TAG, "Sending broadcast to notify $message")
            val intent = Intent(TERMUX_APP.TERMUX_ACTIVITY.ACTION_NOTIFY_APP_CRASH)
            intent.setPackage(TermuxConstants.TERMUX_PACKAGE_NAME)
            termuxContext.sendBroadcast(intent)
        } catch (e: Exception) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to notify $message", e)
        }
    }

    override fun getCrashLogFilePath(context: Context?): String {
        return TermuxConstants.TERMUX_CRASH_LOG_FILE_PATH
    }

    override fun getAppInfoMarkdownString(context: Context?): String? {
        return TermuxUtils.getAppInfoMarkdownString(context, true)
    }

    companion object {
        private const val LOG_TAG = "TermuxCrashUtils"

        /**
         * Set default uncaught crash handler of the app to [CrashHandler] for Termux app
         * and its plugins to log crashes at [TermuxConstants.TERMUX_CRASH_LOG_FILE_PATH].
         */
        @JvmStatic
        fun setDefaultCrashHandler(context: Context) {
            CrashHandler.setDefaultCrashHandler(context, TermuxCrashUtils(TYPE.UNCAUGHT_EXCEPTION))
        }

        /**
         * Set uncaught crash handler of current non-main thread to [CrashHandler] for Termux app
         * and its plugins to log crashes at [TermuxConstants.TERMUX_CRASH_LOG_FILE_PATH].
         */
        @JvmStatic
        fun setCrashHandler(context: Context) {
            CrashHandler.setCrashHandler(context, TermuxCrashUtils(TYPE.CAUGHT_EXCEPTION))
        }

        /**
         * Get [CrashHandler] for Termux app and its plugins that can be set as the uncaught
         * crash handler of a non-main thread to log crashes at [TermuxConstants.TERMUX_CRASH_LOG_FILE_PATH].
         */
        @JvmStatic
        fun getCrashHandler(context: Context): CrashHandler {
            return CrashHandler.getCrashHandler(context, TermuxCrashUtils(TYPE.CAUGHT_EXCEPTION))
        }

        /**
         * Log a crash to [TermuxConstants.TERMUX_CRASH_LOG_FILE_PATH] and notify termux app
         * by sending it the [TERMUX_APP.TERMUX_ACTIVITY.ACTION_NOTIFY_APP_CRASH] broadcast.
         *
         * @param context The [Context] for operations.
         * @param throwable The [Throwable] thrown for the crash.
         */
        @JvmStatic
        fun logCrash(context: Context, throwable: Throwable?) {
            if (throwable == null) return
            CrashHandler.logCrash(context, TermuxCrashUtils(TYPE.CAUGHT_EXCEPTION), Thread.currentThread(), throwable)
        }

        /**
         * Notify the user of an app crash by reading the crash info from the crash log file
         * at [TermuxConstants.TERMUX_CRASH_LOG_FILE_PATH]. The crash log file would have been
         * created by [com.termux.shared.crash.CrashHandler].
         *
         * If the crash log file exists and is not empty and
         * [TermuxPreferenceConstants.TERMUX_APP.KEY_CRASH_REPORT_NOTIFICATIONS_ENABLED] is
         * enabled, then a notification will be shown for the crash on the
         * [TermuxConstants.TERMUX_CRASH_REPORTS_NOTIFICATION_CHANNEL_NAME] channel, otherwise nothing will be done.
         *
         * After reading from the crash log file, it will be moved to [TermuxConstants.TERMUX_CRASH_LOG_BACKUP_FILE_PATH].
         *
         * @param currentPackageContext The [Context] of current package.
         * @param logTagParam The log tag to use for logging.
         */
        @JvmStatic
        fun notifyAppCrashFromCrashLogFile(currentPackageContext: Context?, logTagParam: String?) {
            if (currentPackageContext == null) return
            val currentPackageName = currentPackageContext.packageName

            val context = TermuxUtils.getTermuxPackageContext(currentPackageContext)
            if (context == null) {
                Logger.logWarn(
                    LOG_TAG,
                    "Ignoring call to notifyAppCrash() since failed to get \"" + TermuxConstants.TERMUX_PACKAGE_NAME + "\" package context from \"" + currentPackageName + "\" context"
                )
                return
            }

            val preferences = TermuxAppSharedPreferences.build(context) ?: return

            // If user has disabled notifications for crashes
            if (!preferences.areCrashReportNotificationsEnabled(false)) return

            Thread {
                notifyAppCrashFromCrashLogFileInner(context, logTagParam)
            }.start()
        }

        @JvmStatic
        @Synchronized
        private fun notifyAppCrashFromCrashLogFileInner(context: Context, logTagParam: String?) {
            val logTag = DataUtils.getDefaultIfNull(logTagParam, LOG_TAG)

            if (!FileUtils.regularFileExists(TermuxConstants.TERMUX_CRASH_LOG_FILE_PATH, false)) return

            var error: Error?
            val reportStringBuilder = StringBuilder()

            // Read report string from crash log file
            error = FileUtils.readTextFromFile("crash log", TermuxConstants.TERMUX_CRASH_LOG_FILE_PATH, Charset.defaultCharset(), reportStringBuilder, false)
            if (error != null) {
                Logger.logErrorExtended(logTag, error.toString())
                return
            }

            // Move crash log file to backup location if it exists
            error = FileUtils.moveRegularFile("crash log", TermuxConstants.TERMUX_CRASH_LOG_FILE_PATH, TermuxConstants.TERMUX_CRASH_LOG_BACKUP_FILE_PATH, true)
            if (error != null) {
                Logger.logErrorExtended(logTag, error.toString())
            }

            val reportString = reportStringBuilder.toString()

            if (reportString.isEmpty()) return

            Logger.logDebug(logTag, "A crash log file found at \"" + TermuxConstants.TERMUX_CRASH_LOG_FILE_PATH + "\".")

            sendCrashReportNotification(context, logTag, null, null, reportString, false, false, null, false)
        }

        /**
         * Send a crash report notification for [TermuxConstants.TERMUX_CRASH_REPORTS_NOTIFICATION_CHANNEL_ID]
         * and [TermuxConstants.TERMUX_CRASH_REPORTS_NOTIFICATION_CHANNEL_NAME].
         *
         * @param currentPackageContext The [Context] of current package.
         * @param logTag The log tag to use for logging.
         * @param title The title for the crash report and notification.
         * @param message The message for the crash report.
         * @param throwable The [Throwable] for the crash report.
         */
        @JvmStatic
        fun sendCrashReportNotification(
            currentPackageContext: Context,
            logTag: String?,
            title: CharSequence?,
            message: String?,
            throwable: Throwable?
        ) {
            sendCrashReportNotification(
                currentPackageContext, logTag,
                title, message,
                MarkdownUtils.getMarkdownCodeForString(Logger.getMessageAndStackTraceString(message, throwable), true),
                false, false, true
            )
        }

        /**
         * Send a crash report notification for [TermuxConstants.TERMUX_CRASH_REPORTS_NOTIFICATION_CHANNEL_ID]
         * and [TermuxConstants.TERMUX_CRASH_REPORTS_NOTIFICATION_CHANNEL_NAME].
         *
         * @param currentPackageContext The [Context] of current package.
         * @param logTag The log tag to use for logging.
         * @param title The title for the crash report and notification.
         * @param notificationTextString The text of the notification.
         * @param message The message for the crash report.
         */
        @JvmStatic
        fun sendCrashReportNotification(
            currentPackageContext: Context,
            logTag: String?,
            title: CharSequence?,
            notificationTextString: String?,
            message: String?
        ) {
            sendCrashReportNotification(
                currentPackageContext, logTag,
                title, notificationTextString, message,
                false, false, true
            )
        }

        /**
         * Send a crash report notification for [TermuxConstants.TERMUX_CRASH_REPORTS_NOTIFICATION_CHANNEL_ID]
         * and [TermuxConstants.TERMUX_CRASH_REPORTS_NOTIFICATION_CHANNEL_NAME].
         *
         * @param currentPackageContext The [Context] of current package.
         * @param logTag The log tag to use for logging.
         * @param title The title for the crash report and notification.
         * @param notificationTextString The text of the notification.
         * @param message The message for the crash report.
         * @param forceNotification If set to `true`, then a notification will be shown
         *                          regardless of if pending intent is `null` or
         *                          [TermuxPreferenceConstants.TERMUX_APP.KEY_CRASH_REPORT_NOTIFICATIONS_ENABLED]
         *                          is `false`.
         * @param showToast If set to `true`, then a toast will be shown for `notificationTextString`.
         * @param addDeviceInfo If set to `true`, then device info should be appended to the message.
         */
        @JvmStatic
        fun sendCrashReportNotification(
            currentPackageContext: Context,
            logTag: String?,
            title: CharSequence?,
            notificationTextString: String?,
            message: String?,
            forceNotification: Boolean,
            showToast: Boolean,
            addDeviceInfo: Boolean
        ) {
            sendCrashReportNotification(
                currentPackageContext, logTag,
                title, notificationTextString, "## $title\n\n$message\n\n",
                forceNotification, showToast, TermuxUtils.AppInfoMode.TERMUX_AND_PLUGIN_PACKAGE, addDeviceInfo
            )
        }

        /**
         * Send a crash report notification for [TermuxConstants.TERMUX_CRASH_REPORTS_NOTIFICATION_CHANNEL_ID]
         * and [TermuxConstants.TERMUX_CRASH_REPORTS_NOTIFICATION_CHANNEL_NAME].
         *
         * @param currentPackageContext The [Context] of current package.
         * @param logTag The log tag to use for logging.
         * @param title The title for the crash report and notification.
         * @param notificationTextString The text of the notification.
         * @param message The message for the crash report.
         * @param forceNotification If set to `true`, then a notification will be shown
         *                          regardless of if pending intent is `null` or
         *                          [TermuxPreferenceConstants.TERMUX_APP.KEY_CRASH_REPORT_NOTIFICATIONS_ENABLED]
         *                          is `false`.
         * @param showToast If set to `true`, then a toast will be shown for `notificationTextString`.
         * @param appInfoMode The [TermuxUtils.AppInfoMode] to use to add app info to the message.
         *                    Set to `null` if app info should not be appended to the message.
         * @param addDeviceInfo If set to `true`, then device info should be appended to the message.
         */
        @JvmStatic
        fun sendCrashReportNotification(
            currentPackageContext: Context?,
            logTag: String?,
            title: CharSequence?,
            notificationTextString: String?,
            message: String?,
            forceNotification: Boolean,
            showToast: Boolean,
            appInfoMode: TermuxUtils.AppInfoMode?,
            addDeviceInfo: Boolean
        ) {
            // Note: Do not change currentPackageContext or termuxPackageContext passed to functions or things will break

            if (currentPackageContext == null) return
            val currentPackageName = currentPackageContext.packageName

            val termuxPackageContext = TermuxUtils.getTermuxPackageContext(currentPackageContext)
            if (termuxPackageContext == null) {
                Logger.logWarn(
                    LOG_TAG,
                    "Ignoring call to sendCrashReportNotification() since failed to get \"" + TermuxConstants.TERMUX_PACKAGE_NAME + "\" package context from \"" + currentPackageName + "\" context"
                )
                return
            }

            val preferences = TermuxAppSharedPreferences.build(termuxPackageContext) ?: return

            // If user has disabled notifications for crashes
            if (!preferences.areCrashReportNotificationsEnabled(true) && !forceNotification) return

            val finalLogTag = DataUtils.getDefaultIfNull(logTag, LOG_TAG)

            if (showToast) {
                Logger.showToast(currentPackageContext, notificationTextString, true)
            }

            // Send a notification to show the crash log which when clicked will open the {@link ReportActivity}
            // to show the details of the crash
            val finalTitle = if (title == null || title.toString().isEmpty()) {
                TermuxConstants.TERMUX_APP_NAME + " Crash Report"
            } else {
                title
            }

            Logger.logDebug(finalLogTag, "Sending \"$finalTitle\" notification.")

            val reportString = StringBuilder(message ?: "")

            if (appInfoMode != null) {
                reportString.append("\n\n").append(
                    TermuxUtils.getAppInfoMarkdownString(
                        currentPackageContext,
                        appInfoMode,
                        currentPackageName
                    )
                )
            }

            if (addDeviceInfo) {
                reportString.append("\n\n").append(
                    AndroidUtils.getDeviceInfoMarkdownString(
                        currentPackageContext,
                        true
                    )
                )
            }

            val userActionName = UserAction.CRASH_REPORT.getName()

            val reportInfo = ReportInfo(userActionName, finalLogTag, finalTitle.toString())
            reportInfo.reportString = reportString.toString()
            reportInfo.reportStringSuffix = "\n\n" + TermuxUtils.getReportIssueMarkdownString(currentPackageContext)
            reportInfo.addReportInfoHeaderToMarkdown = true
            reportInfo.setReportSaveFileLabelAndPath(
                userActionName,
                Environment.getExternalStorageDirectory().toString() + "/" +
                        FileUtils.sanitizeFileName(
                            TermuxConstants.TERMUX_APP_NAME + "-" + userActionName + ".log",
                            true,
                            true
                        )
            )

            val result = ReportActivity.newInstance(termuxPackageContext, reportInfo)
            if (result.contentIntent == null) return

            // Must ensure result code for PendingIntents and id for notification are unique otherwise will override previous
            val nextNotificationId = TermuxNotificationUtils.getNextNotificationId(termuxPackageContext)

            val contentIntent = PendingIntent.getActivity(
                termuxPackageContext,
                nextNotificationId,
                result.contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT
            )

            val resultDeleteIntent = result.deleteIntent
            var deleteIntent: PendingIntent? = null
            if (resultDeleteIntent != null) {
                deleteIntent = PendingIntent.getBroadcast(
                    termuxPackageContext,
                    nextNotificationId,
                    resultDeleteIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT
                )
            }

            // Setup the notification channel if not already set up
            setupCrashReportsNotificationChannel(termuxPackageContext)

            // Use markdown in notification
            val notificationTextCharSequence = MarkdownUtils.getSpannedMarkdownText(
                termuxPackageContext,
                notificationTextString
            )

            // Build the notification
            val builder = getCrashReportsNotificationBuilder(
                currentPackageContext, termuxPackageContext,
                finalTitle, notificationTextCharSequence, notificationTextCharSequence, contentIntent, deleteIntent,
                NotificationUtils.NOTIFICATION_MODE_VIBRATE
            ) ?: return

            // Send the notification
            val notificationManager = NotificationUtils.getNotificationManager(termuxPackageContext)
            notificationManager?.notify(nextNotificationId, builder.build())
        }

        /**
         * Get [Notification.Builder] for [TermuxConstants.TERMUX_CRASH_REPORTS_NOTIFICATION_CHANNEL_ID]
         * and [TermuxConstants.TERMUX_CRASH_REPORTS_NOTIFICATION_CHANNEL_NAME].
         *
         * @param currentPackageContext The [Context] of current package.
         * @param termuxPackageContext The [Context] of termux package.
         * @param title The title for the notification.
         * @param notificationText The second line text of the notification.
         * @param notificationBigText The full text of the notification that may optionally be styled.
         * @param contentIntent The [PendingIntent] which should be sent when notification is clicked.
         * @param deleteIntent The [PendingIntent] which should be sent when notification is deleted.
         * @param notificationMode The notification mode. It must be one of `NotificationUtils.NOTIFICATION_MODE_*`.
         * @return Returns the [Notification.Builder].
         */
        @JvmStatic
        fun getCrashReportsNotificationBuilder(
            currentPackageContext: Context?,
            termuxPackageContext: Context?,
            title: CharSequence?,
            notificationText: CharSequence?,
            notificationBigText: CharSequence?,
            contentIntent: PendingIntent?,
            deleteIntent: PendingIntent?,
            notificationMode: Int
        ): Notification.Builder? {
            return TermuxNotificationUtils.getTermuxOrPluginAppNotificationBuilder(
                currentPackageContext, termuxPackageContext,
                TermuxConstants.TERMUX_CRASH_REPORTS_NOTIFICATION_CHANNEL_ID, Notification.PRIORITY_HIGH,
                title, notificationText, notificationBigText, contentIntent, deleteIntent, notificationMode
            )
        }

        /**
         * Setup the notification channel for [TermuxConstants.TERMUX_CRASH_REPORTS_NOTIFICATION_CHANNEL_ID] and
         * [TermuxConstants.TERMUX_CRASH_REPORTS_NOTIFICATION_CHANNEL_NAME].
         *
         * @param context The [Context] for operations.
         */
        @JvmStatic
        fun setupCrashReportsNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            NotificationUtils.setupNotificationChannel(
                context,
                TermuxConstants.TERMUX_CRASH_REPORTS_NOTIFICATION_CHANNEL_ID,
                TermuxConstants.TERMUX_CRASH_REPORTS_NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            )
        }
    }
}
