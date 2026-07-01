package com.termux.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.PowerManager
import androidx.annotation.Nullable
import com.termux.R
import com.termux.app.event.SystemEventReceiver
import com.termux.app.terminal.TermuxTerminalSessionActivityClient
import com.termux.app.terminal.TermuxTerminalSessionServiceClient
import com.termux.shared.termux.plugins.TermuxPluginUtils
import com.termux.shared.data.IntentUtils
import com.termux.shared.net.uri.UriUtils
import com.termux.shared.errors.Errno
import com.termux.shared.shell.ShellUtils
import com.termux.shared.shell.command.runner.app.AppShell
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment
import com.termux.shared.termux.shell.TermuxShellUtils
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences
import com.termux.shared.termux.shell.TermuxShellManager
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase
import com.termux.shared.logger.Logger
import com.termux.shared.notification.NotificationUtils
import com.termux.shared.android.PermissionUtils
import com.termux.shared.data.DataUtils
import com.termux.shared.shell.command.ExecutionCommand
import com.termux.shared.shell.command.ExecutionCommand.Runner
import com.termux.shared.shell.command.ExecutionCommand.ShellCreateMode
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

import java.util.ArrayList

/**
 * A service holding a list of {@link TermuxSession} in {@link TermuxShellManager#mTermuxSessions} and background {@link AppShell}
 * in {@link TermuxShellManager#mTermuxTasks}, showing a foreground notification while running so that it is not terminated.
 * The user interacts with the session through {@link TermuxActivity}, but this service may outlive
 * the activity when the user or the system disposes of the activity. In that case the user may
 * restart {@link TermuxActivity} later to yet again access the sessions.
 * <p/>
 * In order to keep both terminal sessions and spawned processes (who may outlive the terminal sessions) alive as long
 * as wanted by the user this service is a foreground service, {@link Service#startForeground(int, Notification)}.
 * <p/>
 * Optionally may hold a wake and a wifi lock, in which case that is shown in the notification - see
 * {@link #buildNotification()}.
 */
class TermuxService : Service(), AppShell.AppShellClient, TermuxSession.TermuxSessionClient {

    /** This service is only bound from inside the same process and never uses IPC. */
    inner class LocalBinder : Binder() {
        val service: TermuxService
            get() = this@TermuxService
    }

    private val mBinder: IBinder = LocalBinder()

    private val mHandler = Handler()

    /** The full implementation of the {@link TerminalSessionClient} interface to be used by {@link TerminalSession}
     * that holds activity references for activity related functions.
     * Note that the service may often outlive the activity, so need to clear this reference.
     */
    private var mTermuxTerminalSessionActivityClient: TermuxTerminalSessionActivityClient? = null

    /** The basic implementation of the {@link TerminalSessionClient} interface to be used by {@link TerminalSession}
     * that does not hold activity references and only a service reference.
     */
    private val mTermuxTerminalSessionServiceClient = TermuxTerminalSessionServiceClient(this)

    /**
     * Termux app shared properties manager, loaded from termux.properties
     */
    private var mProperties: TermuxAppSharedProperties? = null

    /**
     * Termux app shell manager
     */
    private var mShellManager: TermuxShellManager? = null

    /** The wake lock and wifi lock are always acquired and released together. */
    private var mWakeLock: PowerManager.WakeLock? = null
    private var mWifiLock: WifiManager.WifiLock? = null

    /** If the user has executed the {@link TERMUX_SERVICE#ACTION_STOP_SERVICE} intent. */
    @JvmField
    var mWantsToStop = false

    override fun onCreate() {
        Logger.logVerbose(LOG_TAG, "onCreate")

        // Get Termux app SharedProperties without loading from disk since TermuxApplication handles
        // load and TermuxActivity handles reloads
        mProperties = TermuxAppSharedProperties.getProperties()

        mShellManager = TermuxShellManager.getShellManager()

        runStartForeground()

        SystemEventReceiver.registerPackageUpdateEvents(this)
    }

    @SuppressLint("Wakelock")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Logger.logDebug(LOG_TAG, "onStartCommand")

        // Run again in case service is already started and onCreate() is not called
        runStartForeground()

        var action: String? = null
        if (intent != null) {
            Logger.logVerboseExtended(LOG_TAG, "Intent Received:\n" + IntentUtils.getIntentString(intent))
            action = intent.action
        }

        if (action != null) {
            when (action) {
                TERMUX_SERVICE.ACTION_STOP_SERVICE -> {
                    Logger.logDebug(LOG_TAG, "ACTION_STOP_SERVICE intent received")
                    actionStopService()
                }
                TERMUX_SERVICE.ACTION_WAKE_LOCK -> {
                    Logger.logDebug(LOG_TAG, "ACTION_WAKE_LOCK intent received")
                    actionAcquireWakeLock()
                }
                TERMUX_SERVICE.ACTION_WAKE_UNLOCK -> {
                    Logger.logDebug(LOG_TAG, "ACTION_WAKE_UNLOCK intent received")
                    actionReleaseWakeLock(true)
                }
                TERMUX_SERVICE.ACTION_SERVICE_EXECUTE -> {
                    Logger.logDebug(LOG_TAG, "ACTION_SERVICE_EXECUTE intent received")
                    actionServiceExecute(intent)
                }
                else -> Logger.logError(LOG_TAG, "Invalid action: \"$action\"")
            }
        }

        // If this service really do get killed, there is no point restarting it automatically - let the user do on next
        // start of {@link Term):
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Logger.logVerbose(LOG_TAG, "onDestroy")

        TermuxShellUtils.clearTermuxTMPDIR(true)

        actionReleaseWakeLock(false)
        if (!mWantsToStop) {
            killAllTermuxExecutionCommands()
        }

        TermuxShellManager.onAppExit(this)

        SystemEventReceiver.unregisterPackageUpdateEvents(this)

        runStopForeground()
    }

    override fun onBind(intent: Intent?): IBinder? {
        Logger.logVerbose(LOG_TAG, "onBind")
        return mBinder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Logger.logVerbose(LOG_TAG, "onUnbind")

        // Since we cannot rely on {@link TermuxActivity.onDestroy()} to always complete,
        // we unset clients here as well if it failed, so that we do not leave service and session
        // clients with references to the activity.
        if (mTermuxTerminalSessionActivityClient != null) {
            unsetTermuxTerminalSessionClient()
        }
        return false
    }

    /** Make service run in foreground mode. */
    private fun runStartForeground() {
        setupNotificationChannel()
        startForeground(TermuxConstants.TERMUX_APP_NOTIFICATION_ID, buildNotification())
    }

    /** Make service leave foreground mode. */
    private fun runStopForeground() {
        stopForeground(true)
    }

    /** Request to stop service. */
    private fun requestStopService() {
        Logger.logDebug(LOG_TAG, "Requesting to stop service")
        runStopForeground()
        stopSelf()
    }

    /** Process action to stop service. */
    private fun actionStopService() {
        mWantsToStop = true
        killAllTermuxExecutionCommands()
        requestStopService()
    }

    /** Kill all TermuxSessions and TermuxTasks by sending SIGKILL to their processes.
     *
     * For TermuxSessions, all sessions will be killed, whether user manually exited Termux or if
     * onDestroy() was directly called because of unintended shutdown. The processing of results
     * will only be done if user manually exited termux or if the session was started by a plugin
     * which **expects** the result back via a pending intent.
     *
     * For TermuxTasks, only tasks that were started by a plugin which **expects** the result
     * back via a pending intent will be killed, whether user manually exited Termux or if
     * onDestroy() was directly called because of unintended shutdown. The processing of results
     * will always be done for the tasks that are killed. The remaining processes will keep on
     * running until the termux app process is killed by android, like by OOM, so we let them run
     * as long as they can.
     *
     * Some plugin execution commands may not have been processed and added to mTermuxSessions and
     * mTermuxTasks lists before the service is killed, so we maintain a separate
     * mPendingPluginExecutionCommands list for those, so that we can notify the pending intent
     * creators that execution was cancelled.
     *
     * Note that if user didn't manually exit Termux and if onDestroy() was directly called because
     * of unintended shutdown, like android deciding to kill the service, then there will be no
     * guarantee that onDestroy() will be allowed to finish and termux app process may be killed before
     * it has finished. This means that in those cases some results may not be sent back to their
     * creators for plugin commands but we still try to process whatever results can be processed
     * despite the unreliable behaviour of onDestroy().
     *
     * Note that if don't kill the processes started by plugins which **expect** the result back
     * and notify their creators that they have been killed, then they may get stuck waiting for
     * the results forever like in case of commands started by Termux:Tasker or RUN_COMMAND intent,
     * since once TermuxService has been killed, no result will be sent back. They may still get
     * stuck if termux app process gets killed, so for this case reasonable timeout values should
     * be used, like in Tasker for the Termux:Tasker actions.
     *
     * We make copies of each list since items are removed inside the loop.
     */
    @Synchronized
    private fun killAllTermuxExecutionCommands() {
        var processResult: Boolean
        val shellManager = mShellManager ?: return

        Logger.logDebug(
            LOG_TAG, "Killing TermuxSessions=" + shellManager.mTermuxSessions.size +
                    ", TermuxTasks=" + shellManager.mTermuxTasks.size +
                    ", PendingPluginExecutionCommands=" + shellManager.mPendingPluginExecutionCommands.size
        )

        val termuxSessions = ArrayList(shellManager.mTermuxSessions)
        val termuxTasks = ArrayList(shellManager.mTermuxTasks)
        val pendingPluginExecutionCommands = ArrayList(shellManager.mPendingPluginExecutionCommands)

        for (i in termuxSessions.indices) {
            val executionCommand = termuxSessions[i].executionCommand
            processResult = mWantsToStop || executionCommand.isPluginExecutionCommandWithPendingResult
            termuxSessions[i].killIfExecuting(this, processResult)
            if (!processResult) {
                shellManager.mTermuxSessions.remove(termuxSessions[i])
            }
        }

        for (i in termuxTasks.indices) {
            val executionCommand = termuxTasks[i].executionCommand
            if (executionCommand.isPluginExecutionCommandWithPendingResult) {
                termuxTasks[i].killIfExecuting(this, true)
            } else {
                shellManager.mTermuxTasks.remove(termuxTasks[i])
            }
        }

        for (i in pendingPluginExecutionCommands.indices) {
            val executionCommand = pendingPluginExecutionCommands[i]
            if (!executionCommand.shouldNotProcessResults() && executionCommand.isPluginExecutionCommandWithPendingResult) {
                if (executionCommand.setStateFailed(
                        Errno.ERRNO_CANCELLED.code,
                        this.getString(com.termux.shared.R.string.error_execution_cancelled)
                    )
                ) {
                    TermuxPluginUtils.processPluginExecutionCommandResult(this, LOG_TAG, executionCommand)
                }
            }
        }
    }

    /** Process action to acquire Power and Wi-Fi WakeLocks. */
    @SuppressLint("WakelockTimeout", "BatteryLife")
    private fun actionAcquireWakeLock() {
        if (mWakeLock != null) {
            Logger.logDebug(LOG_TAG, "Ignoring acquiring WakeLocks since they are already held")
            return
        }

        Logger.logDebug(LOG_TAG, "Acquiring WakeLocks")

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TermuxConstants.TERMUX_APP_NAME.lowercase() + ":service-wakelock")
        mWakeLock?.acquire()

        // http://tools.android.com/tech-docs/lint-in-studio-2-3#TOC-WifiManager-Leak
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        mWifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, TermuxConstants.TERMUX_APP_NAME.lowercase())
        mWifiLock?.acquire()

        if (!PermissionUtils.checkIfBatteryOptimizationsDisabled(this)) {
            PermissionUtils.requestDisableBatteryOptimizations(this)
        }

        updateNotification()

        Logger.logDebug(LOG_TAG, "WakeLocks acquired successfully")
    }

    /** Process action to release Power and Wi-Fi WakeLocks. */
    private fun actionReleaseWakeLock(updateNotification: Boolean) {
        if (mWakeLock == null && mWifiLock == null) {
            Logger.logDebug(LOG_TAG, "Ignoring releasing WakeLocks since none are already held")
            return
        }

        Logger.logDebug(LOG_TAG, "Releasing WakeLocks")

        mWakeLock?.release()
        mWakeLock = null

        mWifiLock?.release()
        mWifiLock = null

        if (updateNotification) {
            updateNotification()
        }

        Logger.logDebug(LOG_TAG, "WakeLocks released successfully")
    }

    /** Process {@link TERMUX_SERVICE#ACTION_SERVICE_EXECUTE} intent to execute a shell command in
     * a foreground TermuxSession or in a background TermuxTask. */
    private fun actionServiceExecute(intent: Intent?) {
        if (intent == null) {
            Logger.logError(LOG_TAG, "Ignoring null intent to actionServiceExecute")
            return
        }

        val executionCommand = ExecutionCommand(TermuxShellManager.getNextShellId())

        executionCommand.executableUri = intent.data
        executionCommand.isPluginExecutionCommand = true

        // If EXTRA_RUNNER is passed, use that, otherwise check EXTRA_BACKGROUND and default to Runner.TERMINAL_SESSION
        executionCommand.runner = IntentUtils.getStringExtraIfSet(
            intent, TERMUX_SERVICE.EXTRA_RUNNER,
            if (intent.getBooleanExtra(TERMUX_SERVICE.EXTRA_BACKGROUND, false)) Runner.APP_SHELL.getName() else Runner.TERMINAL_SESSION.getName()
        )
        if (Runner.runnerOf(executionCommand.runner) == null) {
            val errmsg = this.getString(R.string.error_termux_service_invalid_execution_command_runner, executionCommand.runner)
            executionCommand.setStateFailed(Errno.ERRNO_FAILED.code, errmsg)
            TermuxPluginUtils.processPluginExecutionCommandError(this, LOG_TAG, executionCommand, false)
            return
        }

        val executableUri = executionCommand.executableUri
        if (executableUri != null) {
            Logger.logVerbose(
                LOG_TAG,
                "uri: \"" + executableUri + "\", path: \"" + executableUri.path + "\", fragment: \"" + executableUri.fragment + "\""
            )

            // Get full path including fragment (anything after last "#")
            executionCommand.executable = UriUtils.getUriFilePathWithFragment(executableUri)
            executionCommand.arguments = IntentUtils.getStringArrayExtraIfSet(intent, TERMUX_SERVICE.EXTRA_ARGUMENTS, null)
            if (Runner.APP_SHELL.equalsRunner(executionCommand.runner)) {
                executionCommand.stdin = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_STDIN, null)
            }
            executionCommand.backgroundCustomLogLevel = IntentUtils.getIntegerExtraIfSet(intent, TERMUX_SERVICE.EXTRA_BACKGROUND_CUSTOM_LOG_LEVEL, null)
        }

        executionCommand.workingDirectory = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_WORKDIR, null)
        executionCommand.isFailsafe = intent.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false)
        executionCommand.sessionAction = intent.getStringExtra(TERMUX_SERVICE.EXTRA_SESSION_ACTION)
        executionCommand.shellName = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_SHELL_NAME, null)
        executionCommand.shellCreateMode = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_SHELL_CREATE_MODE, null)
        executionCommand.commandLabel = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_COMMAND_LABEL, "Execution Intent Command")
        executionCommand.commandDescription = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_COMMAND_DESCRIPTION, null)
        executionCommand.commandHelp = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_COMMAND_HELP, null)
        executionCommand.pluginAPIHelp = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_PLUGIN_API_HELP, null)
        executionCommand.resultConfig.resultPendingIntent = intent.getParcelableExtra(TERMUX_SERVICE.EXTRA_PENDING_INTENT)
        executionCommand.resultConfig.resultDirectoryPath = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_RESULT_DIRECTORY, null)
        if (executionCommand.resultConfig.resultDirectoryPath != null) {
            executionCommand.resultConfig.resultSingleFile = intent.getBooleanExtra(TERMUX_SERVICE.EXTRA_RESULT_SINGLE_FILE, false)
            executionCommand.resultConfig.resultFileBasename = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_RESULT_FILE_BASENAME, null)
            executionCommand.resultConfig.resultFileOutputFormat = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_RESULT_FILE_OUTPUT_FORMAT, null)
            executionCommand.resultConfig.resultFileErrorFormat = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_RESULT_FILE_ERROR_FORMAT, null)
            executionCommand.resultConfig.resultFilesSuffix = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_RESULT_FILES_SUFFIX, null)
        }

        if (executionCommand.shellCreateMode == null) {
            executionCommand.shellCreateMode = ShellCreateMode.ALWAYS.mode
        }

        // Add the execution command to pending plugin execution commands list
        mShellManager?.mPendingPluginExecutionCommands?.add(executionCommand)

        if (Runner.APP_SHELL.equalsRunner(executionCommand.runner)) {
            executeTermuxTaskCommand(executionCommand)
        } else if (Runner.TERMINAL_SESSION.equalsRunner(executionCommand.runner)) {
            executeTermuxSessionCommand(executionCommand)
        } else {
            val errmsg = getString(R.string.error_termux_service_unsupported_execution_command_runner, executionCommand.runner)
            executionCommand.setStateFailed(Errno.ERRNO_FAILED.code, errmsg)
            TermuxPluginUtils.processPluginExecutionCommandError(this, LOG_TAG, executionCommand, false)
        }
    }

    /** Execute a shell command in background TermuxTask. */
    private fun executeTermuxTaskCommand(executionCommand: ExecutionCommand?) {
        if (executionCommand == null) return

        Logger.logDebug(LOG_TAG, "Executing background \"" + executionCommand.commandIdAndLabelLogString + "\" TermuxTask command")

        // Transform executable path to shell/session name, e.g. "/bin/do-something.sh" => "do-something.sh".
        if (executionCommand.shellName == null && executionCommand.executable != null) {
            executionCommand.shellName = ShellUtils.getExecutableBasename(executionCommand.executable)
        }

        var newTermuxTask: AppShell? = null
        val shellCreateMode = processShellCreateMode(executionCommand) ?: return
        if (ShellCreateMode.NO_SHELL_WITH_NAME == shellCreateMode) {
            newTermuxTask = getTermuxTaskForShellName(executionCommand.shellName)
            if (newTermuxTask != null) {
                Logger.logVerbose(LOG_TAG, "Existing TermuxTask with \"" + executionCommand.shellName + "\" shell name found for shell create mode \"" + shellCreateMode.mode + "\"")
            } else {
                Logger.logVerbose(LOG_TAG, "No existing TermuxTask with \"" + executionCommand.shellName + "\" shell name found for shell create mode \"" + shellCreateMode.mode + "\"")
            }
        }

        if (newTermuxTask == null) {
            newTermuxTask = createTermuxTask(executionCommand)
        }
    }

    /** Create a TermuxTask. */
    @Nullable
    fun createTermuxTask(executablePath: String?, arguments: Array<String>?, stdin: String?, workingDirectory: String?): AppShell? {
        return createTermuxTask(
            ExecutionCommand(
                TermuxShellManager.getNextShellId(), executablePath,
                arguments, stdin, workingDirectory, Runner.APP_SHELL.getName(), false
            )
        )
    }

    /** Create a TermuxTask. */
    @Nullable
    @Synchronized
    fun createTermuxTask(executionCommand: ExecutionCommand?): AppShell? {
        if (executionCommand == null) return null

        Logger.logDebug(LOG_TAG, "Creating \"" + executionCommand.commandIdAndLabelLogString + "\" TermuxTask")

        if (!Runner.APP_SHELL.equalsRunner(executionCommand.runner)) {
            Logger.logDebug(LOG_TAG, "Ignoring wrong runner \"" + executionCommand.runner + "\" command passed to createTermuxTask()")
            return null
        }

        executionCommand.setShellCommandShellEnvironment = true

        if (Logger.getLogLevel() >= Logger.LOG_LEVEL_VERBOSE) {
            Logger.logVerboseExtended(LOG_TAG, executionCommand.toString())
        }

        val newTermuxTask = AppShell.execute(
            this, executionCommand, this,
            TermuxShellEnvironment(), null, false
        )
        if (newTermuxTask == null) {
            Logger.logError(LOG_TAG, "Failed to execute new TermuxTask command for:\n" + executionCommand.commandIdAndLabelLogString)
            // If the execution command was started for a plugin, then process the error
            if (executionCommand.isPluginExecutionCommand) {
                TermuxPluginUtils.processPluginExecutionCommandError(this, LOG_TAG, executionCommand, false)
            } else {
                Logger.logError(LOG_TAG, "Set log level to debug or higher to see error in logs")
                Logger.logErrorPrivateExtended(LOG_TAG, executionCommand.toString())
            }
            return null
        }

        mShellManager?.mTermuxTasks?.add(newTermuxTask)

        // Remove the execution command from the pending plugin execution commands list since it has
        // now been processed
        if (executionCommand.isPluginExecutionCommand) {
            mShellManager?.mPendingPluginExecutionCommands?.remove(executionCommand)
        }

        updateNotification()

        return newTermuxTask
    }

    /** Callback received when a TermuxTask finishes. */
    override fun onAppShellExited(termuxTask: AppShell?) {
        mHandler.post {
            if (termuxTask != null) {
                val executionCommand = termuxTask.executionCommand

                Logger.logVerbose(LOG_TAG, "The onTermuxTaskExited() callback called for \"" + executionCommand?.commandIdAndLabelLogString + "\" TermuxTask command")

                // If the execution command was started for a plugin, then process the results
                if (executionCommand != null && executionCommand.isPluginExecutionCommand) {
                    TermuxPluginUtils.processPluginExecutionCommandResult(this, LOG_TAG, executionCommand)
                }

                mShellManager?.mTermuxTasks?.remove(termuxTask)
            }

            updateNotification()
        }
    }

    /** Execute a shell command in a foreground {@link TermuxSession}. */
    private fun executeTermuxSessionCommand(executionCommand: ExecutionCommand?) {
        if (executionCommand == null) return

        Logger.logDebug(LOG_TAG, "Executing foreground \"" + executionCommand.commandIdAndLabelLogString + "\" TermuxSession command")

        // Transform executable path to shell/session name, e.g. "/bin/do-something.sh" => "do-something.sh".
        if (executionCommand.shellName == null && executionCommand.executable != null) {
            executionCommand.shellName = ShellUtils.getExecutableBasename(executionCommand.executable)
        }

        var newTermuxSession: TermuxSession? = null
        val shellCreateMode = processShellCreateMode(executionCommand) ?: return
        if (ShellCreateMode.NO_SHELL_WITH_NAME == shellCreateMode) {
            newTermuxSession = getTermuxSessionForShellName(executionCommand.shellName)
            if (newTermuxSession != null) {
                Logger.logVerbose(LOG_TAG, "Existing TermuxSession with \"" + executionCommand.shellName + "\" shell name found for shell create mode \"" + shellCreateMode.mode + "\"")
            } else {
                Logger.logVerbose(LOG_TAG, "No existing TermuxSession with \"" + executionCommand.shellName + "\" shell name found for shell create mode \"" + shellCreateMode.mode + "\"")
            }
        }

        if (newTermuxSession == null) {
            newTermuxSession = createTermuxSession(executionCommand)
        }
        if (newTermuxSession == null) return

        handleSessionAction(
            DataUtils.getIntFromString(
                executionCommand.sessionAction,
                TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_OPEN_ACTIVITY
            ),
            newTermuxSession.terminalSession
        )
    }

    /**
     * Create a {@link TermuxSession}.
     * Currently called by {@link TermuxTerminalSessionActivityClient#addNewSession(boolean, String)} to add a new {@link TermuxSession}.
     */
    @Nullable
    fun createTermuxSession(
        executablePath: String?, arguments: Array<String>?, stdin: String?,
        workingDirectory: String?, isFailSafe: Boolean, sessionName: String?
    ): TermuxSession? {
        val executionCommand = ExecutionCommand(
            TermuxShellManager.getNextShellId(),
            executablePath, arguments, stdin, workingDirectory, Runner.TERMINAL_SESSION.getName(), isFailSafe
        )
        executionCommand.shellName = sessionName
        return createTermuxSession(executionCommand)
    }

    /** Create a {@link TermuxSession}. */
    @Nullable
    @Synchronized
    fun createTermuxSession(executionCommand: ExecutionCommand?): TermuxSession? {
        if (executionCommand == null) return null

        Logger.logDebug(LOG_TAG, "Creating \"" + executionCommand.commandIdAndLabelLogString + "\" TermuxSession")

        if (!Runner.TERMINAL_SESSION.equalsRunner(executionCommand.runner)) {
            Logger.logDebug(LOG_TAG, "Ignoring wrong runner \"" + executionCommand.runner + "\" command passed to createTermuxSession()")
            return null
        }

        executionCommand.setShellCommandShellEnvironment = true
        executionCommand.terminalTranscriptRows = mProperties?.terminalTranscriptRows ?: TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS

        if (Logger.getLogLevel() >= Logger.LOG_LEVEL_VERBOSE) {
            Logger.logVerboseExtended(LOG_TAG, executionCommand.toString())
        }

        // If the execution command was started for a plugin, only then will the stdout be set
        // Otherwise if command was manually started by the user like by adding a new terminal session,
        // then no need to set stdout
        val newTermuxSession = TermuxSession.execute(
            this, executionCommand, getTermuxTerminalSessionClient(),
            this, TermuxShellEnvironment(), null, executionCommand.isPluginExecutionCommand
        )
        if (newTermuxSession == null) {
            Logger.logError(LOG_TAG, "Failed to execute new TermuxSession command for:\n" + executionCommand.commandIdAndLabelLogString)
            // If the execution command was started for a plugin, then process the error
            if (executionCommand.isPluginExecutionCommand) {
                TermuxPluginUtils.processPluginExecutionCommandError(this, LOG_TAG, executionCommand, false)
            } else {
                Logger.logError(LOG_TAG, "Set log level to debug or higher to see error in logs")
                Logger.logErrorPrivateExtended(LOG_TAG, executionCommand.toString())
            }
            return null
        }

        mShellManager?.mTermuxSessions?.add(newTermuxSession)

        // Remove the execution command from the pending plugin execution commands list since it has
        // now been processed
        if (executionCommand.isPluginExecutionCommand) {
            mShellManager?.mPendingPluginExecutionCommands?.remove(executionCommand)
        }

        // Notify {@link TermuxSessionsListViewController} that sessions list has been updated if
        // activity in is foreground
        mTermuxTerminalSessionActivityClient?.termuxSessionListNotifyUpdated()

        updateNotification()

        // No need to recreate the activity since it likely just started and theme should already have applied
        TermuxActivity.updateTermuxActivityStyling(this, false)

        return newTermuxSession
    }

    /** Remove a TermuxSession. */
    @Synchronized
    fun removeTermuxSession(sessionToRemove: TerminalSession?): Int {
        val index = getIndexOfSession(sessionToRemove)

        if (index >= 0) {
            mShellManager?.mTermuxSessions?.get(index)?.finish()
        }

        return index
    }

    /** Callback received when a {@link TermuxSession} finishes. */
    override fun onTermuxSessionExited(termuxSession: TermuxSession) {
        val executionCommand = termuxSession.executionCommand

        Logger.logVerbose(LOG_TAG, "The onTermuxSessionExited() callback called for \"" + executionCommand?.commandIdAndLabelLogString + "\" TermuxSession command")

        // If the execution command was started for a plugin, then process the results
        if (executionCommand != null && executionCommand.isPluginExecutionCommand) {
            TermuxPluginUtils.processPluginExecutionCommandResult(this, LOG_TAG, executionCommand)
        }

        mShellManager?.mTermuxSessions?.remove(termuxSession)

        // Notify {@link TermuxSessionsListViewController} that sessions list has been updated if
        // activity in is foreground
        mTermuxTerminalSessionActivityClient?.termuxSessionListNotifyUpdated()

        updateNotification()
    }

    private fun processShellCreateMode(executionCommand: ExecutionCommand): ShellCreateMode? {
        return if (ShellCreateMode.ALWAYS.equalsMode(executionCommand.shellCreateMode)) {
            ShellCreateMode.ALWAYS // Default
        } else if (ShellCreateMode.NO_SHELL_WITH_NAME.equalsMode(executionCommand.shellCreateMode)) {
            if (DataUtils.isNullOrEmpty(executionCommand.shellName)) {
                TermuxPluginUtils.setAndProcessPluginExecutionCommandError(
                    this, LOG_TAG, executionCommand, false,
                    getString(R.string.error_termux_service_execution_command_shell_name_unset, executionCommand.shellCreateMode)
                )
                null
            } else {
                ShellCreateMode.NO_SHELL_WITH_NAME
            }
        } else {
            TermuxPluginUtils.setAndProcessPluginExecutionCommandError(
                this, LOG_TAG, executionCommand, false,
                getString(R.string.error_termux_service_unsupported_execution_command_shell_create_mode, executionCommand.shellCreateMode)
            )
            null
        }
    }

    /** Process session action for new session. */
    private fun handleSessionAction(sessionAction: Int, newTerminalSession: TerminalSession) {
        Logger.logDebug(LOG_TAG, "Processing sessionAction \"" + sessionAction + "\" for session \"" + newTerminalSession.mSessionName + "\"")

        when (sessionAction) {
            TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_OPEN_ACTIVITY -> {
                setCurrentStoredTerminalSession(newTerminalSession)
                mTermuxTerminalSessionActivityClient?.setCurrentSession(newTerminalSession)
                startTermuxActivity()
            }
            TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_KEEP_CURRENT_SESSION_AND_OPEN_ACTIVITY -> {
                if (termuxSessionsSize == 1) {
                    setCurrentStoredTerminalSession(newTerminalSession)
                }
                startTermuxActivity()
            }
            TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_DONT_OPEN_ACTIVITY -> {
                setCurrentStoredTerminalSession(newTerminalSession)
                mTermuxTerminalSessionActivityClient?.setCurrentSession(newTerminalSession)
            }
            TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_KEEP_CURRENT_SESSION_AND_DONT_OPEN_ACTIVITY -> {
                if (termuxSessionsSize == 1) {
                    setCurrentStoredTerminalSession(newTerminalSession)
                }
            }
            else -> {
                Logger.logError(LOG_TAG, "Invalid sessionAction: \"$sessionAction\". Force using default sessionAction.")
                handleSessionAction(
                    TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_OPEN_ACTIVITY,
                    newTerminalSession
                )
            }
        }
    }

    /** Launch the {@link }TermuxActivity} to bring it to foreground. */
    private fun startTermuxActivity() {
        // For android >= 10, apps require Display over other apps permission to start foreground activities
        // from background (services). If it is not granted, then TermuxSessions that are started will
        // show in Termux notification but will not run until user manually clicks the notification.
        if (PermissionUtils.validateDisplayOverOtherAppsPermissionForPostAndroid10(this, true)) {
            TermuxActivity.startTermuxActivity(this)
        } else {
            val preferences = TermuxAppSharedPreferences.build(this) ?: return
            if (preferences.arePluginErrorNotificationsEnabled(false)) {
                Logger.showToast(
                    this,
                    this.getString(R.string.error_display_over_other_apps_permission_not_granted_to_start_terminal),
                    true
                )
            }
        }
    }

    /** If {@link TermuxActivity} has not bound to the {@link TermuxService} yet or is destroyed, then
     * interface functions requiring the activity should not be available to the terminal sessions,
     * so we just return the {@link #mTermuxTerminalSessionServiceClient}. Once {@link TermuxActivity} bind
     * callback is received, it should call {@link #setTermuxTerminalSessionClient} to set the
     * {@link TermuxService#mTermuxTerminalSessionActivityClient} so that further terminal sessions are directly
     * passed the {@link TermuxTerminalSessionActivityClient} object which fully implements the
     * {@link TerminalSessionClient} interface.
     *
     * @return Returns the {@link TermuxTerminalSessionActivityClient} if {@link TermuxActivity} has bound with
     * {@link TermuxService}, otherwise {@link TermuxTerminalSessionServiceClient}.
     */
    @Synchronized
    fun getTermuxTerminalSessionClient(): TermuxTerminalSessionClientBase {
        return mTermuxTerminalSessionActivityClient ?: mTermuxTerminalSessionServiceClient
    }

    /** This should be called when {@link TermuxActivity#onServiceConnected} is called to set the
     * {@link TermuxService#mTermuxTerminalSessionActivityClient} variable and update the {@link TerminalSession}
     * and {@link TerminalEmulator} clients in case they were passed {@link TermuxTerminalSessionServiceClient}
     * earlier.
     *
     * @param termuxTerminalSessionActivityClient The {@link TermuxTerminalSessionActivityClient} object that fully
     * implements the {@link TerminalSessionClient} interface.
     */
    @Synchronized
    fun setTermuxTerminalSessionClient(termuxTerminalSessionActivityClient: TermuxTerminalSessionActivityClient?) {
        mTermuxTerminalSessionActivityClient = termuxTerminalSessionActivityClient

        val sessions = mShellManager?.mTermuxSessions ?: return
        for (i in sessions.indices) {
            sessions[i].terminalSession.updateTerminalSessionClient(mTermuxTerminalSessionActivityClient)
        }
    }

    /** This should be called when {@link TermuxActivity} has been destroyed and in {@link #onUnbind(Intent)}
     * so that the {@link TermuxService} and {@link TerminalSession} and {@link TerminalEmulator}
     * clients do not hold an activity references.
     */
    @Synchronized
    fun unsetTermuxTerminalSessionClient() {
        val sessions = mShellManager?.mTermuxSessions ?: return
        for (i in sessions.indices) {
            sessions[i].terminalSession.updateTerminalSessionClient(mTermuxTerminalSessionServiceClient)
        }

        mTermuxTerminalSessionActivityClient = null
    }

    private fun buildNotification(): Notification? {
        val res = resources

        // Set pending intent to be launched when notification is clicked
        val notificationIntent = TermuxActivity.newInstance(this)
        val contentIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0)

        // Set notification text
        val sessionCount = termuxSessionsSize
        val taskCount = mShellManager?.mTermuxTasks?.size ?: 0
        var notificationText = "$sessionCount session" + if (sessionCount == 1) "" else "s"
        if (taskCount > 0) {
            notificationText += ", $taskCount task" + if (taskCount == 1) "" else "s"
        }

        val wakeLockHeld = mWakeLock != null
        if (wakeLockHeld) {
            notificationText += " (wake lock held)"
        }

        // Set notification priority
        // If holding a wake or wifi lock consider the notification of high priority since it's using power,
        // otherwise use a low priority
        val priority = if (wakeLockHeld) Notification.PRIORITY_HIGH else Notification.PRIORITY_LOW

        // Build the notification
        val builder = NotificationUtils.geNotificationBuilder(
            this,
            TermuxConstants.TERMUX_APP_NOTIFICATION_CHANNEL_ID, priority,
            TermuxConstants.TERMUX_APP_NAME, notificationText, null,
            contentIntent, null, NotificationUtils.NOTIFICATION_MODE_SILENT
        ) ?: return null

        // No need to show a timestamp:
        builder.setShowWhen(false)

        // Set notification icon
        builder.setSmallIcon(R.drawable.ic_service_notification)

        // Set background color for small notification icon
        builder.setColor(0xFF607D8B.toInt())

        // TermuxSessions are always ongoing
        builder.setOngoing(true)

        // Set Exit button action
        val exitIntent = Intent(this, TermuxService::class.java).setAction(TERMUX_SERVICE.ACTION_STOP_SERVICE)
        builder.addAction(
            android.R.drawable.ic_delete,
            res.getString(R.string.notification_action_exit),
            PendingIntent.getService(this, 0, exitIntent, 0)
        )

        // Set Wakelock button actions
        val newWakeAction = if (wakeLockHeld) TERMUX_SERVICE.ACTION_WAKE_UNLOCK else TERMUX_SERVICE.ACTION_WAKE_LOCK
        val toggleWakeLockIntent = Intent(this, TermuxService::class.java).setAction(newWakeAction)
        val actionTitle = res.getString(if (wakeLockHeld) R.string.notification_action_wake_unlock else R.string.notification_action_wake_lock)
        val actionIcon = if (wakeLockHeld) android.R.drawable.ic_lock_idle_lock else android.R.drawable.ic_lock_lock
        builder.addAction(actionIcon, actionTitle, PendingIntent.getService(this, 0, toggleWakeLockIntent, 0))

        return builder.build()
    }

    private fun setupNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        NotificationUtils.setupNotificationChannel(
            this, TermuxConstants.TERMUX_APP_NOTIFICATION_CHANNEL_ID,
            TermuxConstants.TERMUX_APP_NOTIFICATION_CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW
        )
    }

    /** Update the shown foreground service notification after making any changes that affect it. */
    @Synchronized
    private fun updateNotification() {
        val shellManager = mShellManager
        if (mWakeLock == null && (shellManager == null || (shellManager.mTermuxSessions.isEmpty() && shellManager.mTermuxTasks.isEmpty()))) {
            // Exit if we are updating after the user disabled all locks with no sessions or tasks running.
            requestStopService()
        } else {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notification = buildNotification()
            if (notification != null) {
                notificationManager.notify(TermuxConstants.TERMUX_APP_NOTIFICATION_ID, notification)
            }
        }
    }

    private fun setCurrentStoredTerminalSession(terminalSession: TerminalSession?) {
        if (terminalSession == null) return
        // Make the newly created session the current one to be displayed
        val preferences = TermuxAppSharedPreferences.build(this) ?: return
        preferences.setCurrentSession(terminalSession.mHandle)
    }

    @Synchronized
    fun isTermuxSessionsEmpty(): Boolean {
        return mShellManager?.mTermuxSessions?.isEmpty() ?: true
    }

    val termuxSessionsSize: Int
        @Synchronized get() = mShellManager?.mTermuxSessions?.size ?: 0

    @Synchronized
    fun getTermuxSessions(): List<TermuxSession> {
        return mShellManager?.mTermuxSessions ?: emptyList<TermuxSession>()
    }

    @Nullable
    @Synchronized
    fun getTermuxSession(index: Int): TermuxSession? {
        val sessions = mShellManager?.mTermuxSessions ?: return null
        return if (index in 0 until sessions.size) {
            sessions[index]
        } else {
            null
        }
    }

    @Nullable
    @Synchronized
    fun getTermuxSessionForTerminalSession(terminalSession: TerminalSession?): TermuxSession? {
        if (terminalSession == null) return null

        val sessions = mShellManager?.mTermuxSessions ?: return null
        for (i in sessions.indices) {
            if (sessions[i].terminalSession == terminalSession) {
                return sessions[i]
            }
        }

        return null
    }

    val lastTermuxSession: TermuxSession?
        @Synchronized get() {
            val shellManager = mShellManager ?: return null
            return if (shellManager.mTermuxSessions.isEmpty()) null else shellManager.mTermuxSessions[shellManager.mTermuxSessions.size - 1]
        }

    @Synchronized
    fun getIndexOfSession(terminalSession: TerminalSession?): Int {
        if (terminalSession == null) return -1

        val sessions = mShellManager?.mTermuxSessions ?: return -1
        for (i in sessions.indices) {
            if (sessions[i].terminalSession == terminalSession) {
                return i
            }
        }
        return -1
    }

    @Synchronized
    fun getTerminalSessionForHandle(sessionHandle: String?): TerminalSession? {
        if (sessionHandle == null) return null
        val sessions = mShellManager?.mTermuxSessions ?: return null
        for (i in sessions.indices) {
            val terminalSession = sessions[i].terminalSession
            if (terminalSession.mHandle == sessionHandle) {
                return terminalSession
            }
        }
        return null
    }

    @Synchronized
    fun getTermuxTaskForShellName(name: String?): AppShell? {
        if (DataUtils.isNullOrEmpty(name)) return null
        val tasks = mShellManager?.mTermuxTasks ?: return null
        for (i in tasks.indices) {
            val appShell = tasks[i]
            val shellName = appShell.executionCommand.shellName
            if (shellName != null && shellName == name) {
                return appShell
            }
        }
        return null
    }

    @Synchronized
    fun getTermuxSessionForShellName(name: String?): TermuxSession? {
        if (DataUtils.isNullOrEmpty(name)) return null
        val sessions = mShellManager?.mTermuxSessions ?: return null
        for (i in sessions.indices) {
            val termuxSession = sessions[i]
            val shellName = termuxSession.executionCommand.shellName
            if (shellName != null && shellName == name) {
                return termuxSession
            }
        }
        return null
    }

    fun wantsToStop(): Boolean {
        return mWantsToStop
    }

    companion object {
        private const val LOG_TAG = "TermuxService"
    }
}
