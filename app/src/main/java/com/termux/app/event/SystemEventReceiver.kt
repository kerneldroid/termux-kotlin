package com.termux.app.event

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.core.content.ContextCompat
import com.termux.shared.data.IntentUtils
import com.termux.shared.logger.Logger
import com.termux.shared.termux.TermuxUtils
import com.termux.shared.termux.file.TermuxFileUtils
import com.termux.shared.termux.shell.TermuxShellManager
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment

class SystemEventReceiver : BroadcastReceiver() {

    @Synchronized
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        Logger.logDebug(LOG_TAG, "Intent Received:\n" + IntentUtils.getIntentString(intent))

        val action = intent.action ?: return

        when (action) {
            Intent.ACTION_BOOT_COMPLETED -> onActionBootCompleted(context, intent)
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_REMOVED,
            Intent.ACTION_PACKAGE_REPLACED -> onActionPackageUpdated(context, intent)
            else -> Logger.logError(LOG_TAG, "Invalid action \"$action\" passed to $LOG_TAG")
        }
    }

    @Synchronized
    fun onActionBootCompleted(context: Context, intent: Intent) {
        TermuxShellManager.onActionBootCompleted(context, intent)
    }

    @Synchronized
    fun onActionPackageUpdated(context: Context, intent: Intent) {
        val data = intent.data
        if (data != null && TermuxUtils.isUriDataForTermuxPluginPackage(data)) {
            Logger.logDebug(
                LOG_TAG,
                intent.action.orEmpty().replace(Regex("^android.intent.action."), "") +
                        " event received for \"" + data.toString().replace(Regex("^package:"), "") + "\""
            )
            if (TermuxFileUtils.isTermuxFilesDirectoryAccessible(context, false, false) == null) {
                TermuxShellEnvironment.writeEnvironmentToFile(context)
            }
        }
    }

    companion object {
        private var mInstance: SystemEventReceiver? = null

        private const val LOG_TAG = "SystemEventReceiver"

        @JvmStatic
        @Synchronized
        fun getInstance(): SystemEventReceiver {
            var instance = mInstance
            if (instance == null) {
                instance = SystemEventReceiver()
                mInstance = instance
            }
            return instance
        }

        /**
         * Register {@link SystemEventReceiver} to listen to {@link Intent#ACTION_PACKAGE_ADDED},
         * {@link Intent#ACTION_PACKAGE_REMOVED} and {@link Intent#ACTION_PACKAGE_REPLACED} broadcasts.
         * They must be registered dynamically and cannot be registered implicitly in
         * the AndroidManifest.xml due to Android 8+ restrictions.
         *
         *  https://developer.android.com/guide/components/broadcast-exceptions
         */
        @JvmStatic
        @Synchronized
        fun registerPackageUpdateEvents(context: Context) {
            val intentFilter = IntentFilter()
            intentFilter.addAction(Intent.ACTION_PACKAGE_ADDED)
            intentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED)
            intentFilter.addAction(Intent.ACTION_PACKAGE_REPLACED)
            intentFilter.addDataScheme("package")
            ContextCompat.registerReceiver(context, getInstance(), intentFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }

        @JvmStatic
        @Synchronized
        fun unregisterPackageUpdateEvents(context: Context) {
            context.unregisterReceiver(getInstance())
        }
    }
}
