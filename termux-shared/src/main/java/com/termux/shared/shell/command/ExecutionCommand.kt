package com.termux.shared.shell.command

import android.content.Intent
import android.net.Uri
import com.termux.shared.data.DataUtils
import com.termux.shared.data.IntentUtils
import com.termux.shared.errors.Error
import com.termux.shared.logger.Logger
import com.termux.shared.markdown.MarkdownUtils
import com.termux.shared.shell.command.result.ResultConfig
import com.termux.shared.shell.command.result.ResultData

class ExecutionCommand {

    /*
    The {@link ExecutionState#SUCCESS} and {@link ExecutionState#FAILED} is defined based on
    successful execution of command without any internal errors or exceptions being raised.
    The shell command {@link #exitCode} being non-zero **does not** mean that execution command failed.
    Only the {@link #errCode} being non-zero means that execution command failed from the Termux app
    perspective.
    */

    /** The {@link Enum} that defines {@link ExecutionCommand} state. */
    enum class ExecutionState(private val _name: String, val value: Int) {
        PRE_EXECUTION("Pre-Execution", 0),
        EXECUTING("Executing", 1),
        EXECUTED("Executed", 2),
        SUCCESS("Success", 3),
        FAILED("Failed", 4);

        fun getName(): String {
            return _name
        }
    }

    enum class Runner(private val _name: String) {
        /** Run command in {@link TerminalSession}. */
        TERMINAL_SESSION("terminal-session"),

        /** Run command in {@link AppShell}. */
        APP_SHELL("app-shell");

        ///** Run command in {@link AdbShell}. */
        //ADB_SHELL("adb-shell"),

        ///** Run command in {@link RootShell}. */
        //ROOT_SHELL("root-shell");

        fun getName(): String {
            return _name
        }

        fun equalsRunner(runner: String?): Boolean {
            return runner != null && runner == _name
        }

        companion object {
            /** Get {@link Runner} for {@code name} if found, otherwise {@code null}. */
            @JvmStatic
            fun runnerOf(name: String?): Runner? {
                if (name == null) return null
                for (v in values()) {
                    if (v._name == name) {
                        return v
                    }
                }
                return null
            }

            /** Get {@link Runner} for {@code name} if found, otherwise {@code def}. */
            @JvmStatic
            fun runnerOf(name: String?, def: Runner): Runner {
                return runnerOf(name) ?: def
            }
        }
    }

    enum class ShellCreateMode(val mode: String) {
        /** Always create {@link TerminalSession}. */
        ALWAYS("always"),

        /** Create shell only if no shell with {@link #shellName} found. */
        NO_SHELL_WITH_NAME("no-shell-with-name");

        fun equalsMode(sessionCreateMode: String?): Boolean {
            return sessionCreateMode != null && sessionCreateMode == this.mode
        }

        companion object {
            /** Get {@link ShellCreateMode} for {@code mode} if found, otherwise {@code null}. */
            @JvmStatic
            fun modeOf(mode: String?): ShellCreateMode? {
                if (mode == null) return null
                for (v in values()) {
                    if (v.mode == mode) {
                        return v
                    }
                }
                return null
            }
        }
    }

    /** The optional unique id for the {@link ExecutionCommand}. This should equal -1 if execution
     * command is not going to be managed by a shell manager. */
    @JvmField
    var id: Int? = null

    /** The process id of command. */
    @JvmField
    var mPid: Int = -1

    /** The current state of the {@link ExecutionCommand}. */
    private var currentState: ExecutionState = ExecutionState.PRE_EXECUTION

    /** The previous state of the {@link ExecutionCommand}. */
    private var previousState: ExecutionState = ExecutionState.PRE_EXECUTION

    /** The executable for the {@link ExecutionCommand}. */
    @JvmField
    var executable: String? = null

    /** The executable Uri for the {@link ExecutionCommand}. */
    @JvmField
    var executableUri: Uri? = null

    /** The executable arguments array for the {@link ExecutionCommand}. */
    @JvmField
    var arguments: Array<String>? = null

    /** The stdin string for the {@link ExecutionCommand}. */
    @JvmField
    var stdin: String? = null

    /** The current working directory for the {@link ExecutionCommand}. */
    @JvmField
    var workingDirectory: String? = null

    /** The terminal transcript rows for the {@link ExecutionCommand}. */
    @JvmField
    var terminalTranscriptRows: Int? = null

    /** The {@link Runner} for the {@link ExecutionCommand}. */
    @JvmField
    var runner: String? = null

    /** If the {@link ExecutionCommand} is meant to start a failsafe terminal session. */
    @JvmField
    var isFailsafe: Boolean = false

    /**
     * The {@link ExecutionCommand} custom log level for background {@link AppShell}
     * commands. By default, @link com.termux.shared.shell.StreamGobbler} only logs stdout and
     * stderr if {@link Logger} `CURRENT_LOG_LEVEL` is >= {@link Logger#LOG_LEVEL_VERBOSE} and
     * {@link AppShell} only logs stdin if `CURRENT_LOG_LEVEL` is >=
     * {@link Logger#LOG_LEVEL_DEBUG}.
     */
    @JvmField
    var backgroundCustomLogLevel: Int? = null

    /** The session action of {@link Runner#TERMINAL_SESSION} commands. */
    @JvmField
    var sessionAction: String? = null

    /** The shell name of commands. */
    @JvmField
    var shellName: String? = null

    /** The {@link ShellCreateMode} of commands. */
    @JvmField
    var shellCreateMode: String? = null

    /** Whether to set {@link ExecutionCommand} shell environment. */
    @JvmField
    var setShellCommandShellEnvironment: Boolean = false

    /** The command label for the {@link ExecutionCommand}. */
    @JvmField
    var commandLabel: String? = null

    /** The markdown text for the command description for the {@link ExecutionCommand}. */
    @JvmField
    var commandDescription: String? = null

    /** The markdown text for the help of command for the {@link ExecutionCommand}. This can be used
     * to provide useful info to the user if an internal error is raised. */
    @JvmField
    var commandHelp: String? = null

    /** Defines the markdown text for the help of the Termux plugin API that was used to start the
     * {@link ExecutionCommand}. This can be used to provide useful info to the user if an internal
     * error is raised. */
    @JvmField
    var pluginAPIHelp: String? = null

    /** Defines the {@link Intent} received which started the command. */
    @JvmField
    var commandIntent: Intent? = null

    /** Defines if {@link ExecutionCommand} was started because of an external plugin request
     * like with an intent or from within Termux app itself. */
    @JvmField
    var isPluginExecutionCommand: Boolean = false

    /** Defines the {@link ResultConfig} for the {@link ExecutionCommand} containing information
     * on how to handle the result. */
    @JvmField
    val resultConfig: ResultConfig = ResultConfig()

    /** Defines the {@link ResultData} for the {@link ExecutionCommand} containing information
     * of the result. */
    @JvmField
    val resultData: ResultData = ResultData()

    /** Defines if processing results already called for this {@link ExecutionCommand}. */
    @JvmField
    var processingResultsAlreadyCalled: Boolean = false

    constructor()

    constructor(id: Int?) {
        this.id = id
    }

    constructor(
        id: Int?,
        executable: String?,
        arguments: Array<String>?,
        stdin: String?,
        workingDirectory: String?,
        runner: String?,
        isFailsafe: Boolean
    ) {
        this.id = id
        this.executable = executable
        this.arguments = arguments
        this.stdin = stdin
        this.workingDirectory = workingDirectory
        this.runner = runner
        this.isFailsafe = isFailsafe
    }

    val isPluginExecutionCommandWithPendingResult: Boolean
        get() = isPluginExecutionCommand && resultConfig.isCommandWithPendingResult()

    @Synchronized
    fun setState(newState: ExecutionState): Boolean {
        // The state transition cannot go back or change if already at {@link ExecutionState#SUCCESS}
        if (newState.value < currentState.value || currentState == ExecutionState.SUCCESS) {
            Logger.logError(
                LOG_TAG,
                "Invalid " + commandIdAndLabelLogString + " state transition from \"" + currentState.getName() + "\" to \"" + newState.getName() + "\""
            )
            return false
        }

        // The {@link ExecutionState#FAILED} can be set again, like to add more errors, but we don't update
        // {@link #previousState} with the {@link #currentState} value if its at {@link ExecutionState#FAILED} to
        // preserve the last valid state
        if (currentState != ExecutionState.FAILED) {
            previousState = currentState
        }

        currentState = newState
        return true
    }

    @get:JvmName("hasExecuted")
    val hasExecuted: Boolean
        @Synchronized get() = currentState.value >= ExecutionState.EXECUTED.value

    val isExecuting: Boolean
        @Synchronized get() = currentState == ExecutionState.EXECUTING

    val isSuccessful: Boolean
        @Synchronized get() = currentState == ExecutionState.SUCCESS

    @Synchronized
    fun setStateFailed(error: Error): Boolean {
        return setStateFailed(error.getType(), error.getCode(), error.getMessage(), null)
    }

    @Synchronized
    fun setStateFailed(error: Error, throwable: Throwable?): Boolean {
        return setStateFailed(error.getType(), error.getCode(), error.getMessage(), throwable?.let { listOf(it) })
    }

    @Synchronized
    fun setStateFailed(error: Error, throwablesList: List<Throwable>?): Boolean {
        return setStateFailed(error.getType(), error.getCode(), error.getMessage(), throwablesList)
    }

    @Synchronized
    fun setStateFailed(code: Int, message: String?): Boolean {
        return setStateFailed(null, code, message, null)
    }

    @Synchronized
    fun setStateFailed(code: Int, message: String?, throwable: Throwable?): Boolean {
        return setStateFailed(null, code, message, throwable?.let { listOf(it) })
    }

    @Synchronized
    fun setStateFailed(code: Int, message: String?, throwablesList: List<Throwable>?): Boolean {
        return setStateFailed(null, code, message, throwablesList)
    }

    @Synchronized
    fun setStateFailed(type: String?, code: Int, message: String?, throwablesList: List<Throwable>?): Boolean {
        if (!this.resultData.setStateFailed(type, code, message, throwablesList)) {
            Logger.logWarn(LOG_TAG, "setStateFailed for " + commandIdAndLabelLogString + " resultData encountered an error.")
        }

        return setState(ExecutionState.FAILED)
    }

    @Synchronized
    fun shouldNotProcessResults(): Boolean {
        return if (processingResultsAlreadyCalled) {
            true
        } else {
            processingResultsAlreadyCalled = true
            false
        }
    }

    val isStateFailed: Boolean
        @Synchronized get() {
            if (currentState != ExecutionState.FAILED) return false

            return if (!resultData.isStateFailed()) {
                Logger.logWarn(
                    LOG_TAG,
                    "The " + commandIdAndLabelLogString + " has an invalid errCode value set in errors list while having ExecutionState.FAILED state.\n" + resultData.errorsList
                )
                false
            } else {
                true
            }
        }

    override fun toString(): String {
        return if (!hasExecuted) {
            getExecutionInputLogString(this, true, true)
        } else {
            getExecutionOutputLogString(this, true, true, true)
        }
    }

    fun getIdLogString(): String {
        return if (id != null) "($id) " else ""
    }

    fun getPidLogString(): String {
        return "Pid: `$mPid`"
    }

    fun getCurrentStateLogString(): String {
        return "Current State: `${currentState.getName()}`"
    }

    fun getPreviousStateLogString(): String {
        return "Previous State: `${previousState.getName()}`"
    }

    fun getCommandLabelLogString(): String {
        return if (!commandLabel.isNullOrEmpty()) commandLabel!! else "Execution Command"
    }

    val commandIdAndLabelLogString: String
        get() = getIdLogString() + getCommandLabelLogString()

    fun getExecutableLogString(): String {
        return "Executable: `$executable`"
    }

    fun getArgumentsLogString(): String {
        return Companion.getArgumentsLogString("Arguments", arguments)
    }

    fun getWorkingDirectoryLogString(): String {
        return "Working Directory: `$workingDirectory`"
    }

    fun getRunnerLogString(): String {
        return Logger.getSingleLineLogStringEntry("Runner", runner, "-")
    }

    fun getIsFailsafeLogString(): String {
        return "isFailsafe: `$isFailsafe`"
    }

    fun getStdinLogString(): String {
        return if (DataUtils.isNullOrEmpty(stdin)) {
            "Stdin: -"
        } else {
            Logger.getMultiLineLogStringEntry("Stdin", stdin, "-")
        }
    }

    fun getBackgroundCustomLogLevelLogString(): String {
        return "Background Custom Log Level: `$backgroundCustomLogLevel`"
    }

    fun getSessionActionLogString(): String {
        return Logger.getSingleLineLogStringEntry("Session Action", sessionAction, "-")
    }

    fun getShellNameLogString(): String {
        return Logger.getSingleLineLogStringEntry("Shell Name", shellName, "-")
    }

    fun getShellCreateModeLogString(): String {
        return Logger.getSingleLineLogStringEntry("Shell Create Mode", shellCreateMode, "-")
    }

    fun getSetRunnerShellEnvironmentLogString(): String {
        return "Set Shell Command Shell Environment: `$setShellCommandShellEnvironment`"
    }

    fun getCommandDescriptionLogString(): String {
        return Logger.getSingleLineLogStringEntry("Command Description", commandDescription, "-")
    }

    fun getCommandHelpLogString(): String {
        return Logger.getSingleLineLogStringEntry("Command Help", commandHelp, "-")
    }

    fun getPluginAPIHelpLogString(): String {
        return Logger.getSingleLineLogStringEntry("Plugin API Help", pluginAPIHelp, "-")
    }

    fun getCommandIntentLogString(): String {
        return if (commandIntent == null) {
            "Command Intent: -"
        } else {
            Logger.getMultiLineLogStringEntry("Command Intent", IntentUtils.getIntentString(commandIntent), "-")
        }
    }

    fun getIsPluginExecutionCommandLogString(): String {
        return "isPluginExecutionCommand: `$isPluginExecutionCommand`"
    }

    companion object {
        private const val LOG_TAG = "ExecutionCommand"

        @JvmStatic
        fun getExecutionInputLogString(executionCommand: ExecutionCommand?, ignoreNull: Boolean, logStdin: Boolean): String {
            if (executionCommand == null) return "null"

            val logString = StringBuilder()

            logString.append(executionCommand.commandIdAndLabelLogString).append(":")

            if (executionCommand.mPid != -1) {
                logString.append("\n").append(executionCommand.getPidLogString())
            }

            if (executionCommand.previousState != ExecutionState.PRE_EXECUTION) {
                logString.append("\n").append(executionCommand.getPreviousStateLogString())
            }
            logString.append("\n").append(executionCommand.getCurrentStateLogString())

            logString.append("\n").append(executionCommand.getExecutableLogString())
            logString.append("\n").append(executionCommand.getArgumentsLogString())
            logString.append("\n").append(executionCommand.getWorkingDirectoryLogString())
            logString.append("\n").append(executionCommand.getRunnerLogString())
            logString.append("\n").append(executionCommand.getIsFailsafeLogString())

            if (Runner.APP_SHELL.equalsRunner(executionCommand.runner)) {
                if (logStdin && (!ignoreNull || !DataUtils.isNullOrEmpty(executionCommand.stdin))) {
                    logString.append("\n").append(executionCommand.getStdinLogString())
                }

                if (!ignoreNull || executionCommand.backgroundCustomLogLevel != null) {
                    logString.append("\n").append(executionCommand.getBackgroundCustomLogLevelLogString())
                }
            }

            if (!ignoreNull || executionCommand.sessionAction != null) {
                logString.append("\n").append(executionCommand.getSessionActionLogString())
            }

            if (!ignoreNull || executionCommand.shellName != null) {
                logString.append("\n").append(executionCommand.getShellNameLogString())
            }

            if (!ignoreNull || executionCommand.shellCreateMode != null) {
                logString.append("\n").append(executionCommand.getShellCreateModeLogString())
            }

            logString.append("\n").append(executionCommand.getSetRunnerShellEnvironmentLogString())

            if (!ignoreNull || executionCommand.commandIntent != null) {
                logString.append("\n").append(executionCommand.getCommandIntentLogString())
            }

            logString.append("\n").append(executionCommand.getIsPluginExecutionCommandLogString())
            if (executionCommand.isPluginExecutionCommand) {
                logString.append("\n").append(ResultConfig.getResultConfigLogString(executionCommand.resultConfig, ignoreNull))
            }

            return logString.toString()
        }

        @JvmStatic
        fun getExecutionOutputLogString(executionCommand: ExecutionCommand?, ignoreNull: Boolean, logResultData: Boolean, logStdoutAndStderr: Boolean): String {
            if (executionCommand == null) return "null"

            val logString = StringBuilder()

            logString.append(executionCommand.commandIdAndLabelLogString).append(":")

            logString.append("\n").append(executionCommand.getPreviousStateLogString())
            logString.append("\n").append(executionCommand.getCurrentStateLogString())

            if (logResultData) {
                logString.append("\n").append(ResultData.getResultDataLogString(executionCommand.resultData, logStdoutAndStderr))
            }

            return logString.toString()
        }

        @JvmStatic
        fun getDetailedLogString(executionCommand: ExecutionCommand?): String {
            if (executionCommand == null) return "null"

            val logString = StringBuilder()

            logString.append(getExecutionInputLogString(executionCommand, false, true))
            logString.append(getExecutionOutputLogString(executionCommand, false, true, true))

            logString.append("\n").append(executionCommand.getCommandDescriptionLogString())
            logString.append("\n").append(executionCommand.getCommandHelpLogString())
            logString.append("\n").append(executionCommand.getPluginAPIHelpLogString())

            return logString.toString()
        }

        @JvmStatic
        fun getExecutionCommandMarkdownString(executionCommand: ExecutionCommand?): String {
            if (executionCommand == null) return "null"

            if (executionCommand.commandLabel == null) {
                executionCommand.commandLabel = "Execution Command"
            }

            val markdownString = StringBuilder()

            markdownString.append("## ").append(executionCommand.commandLabel).append("\n")

            if (executionCommand.mPid != -1) {
                markdownString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Pid", executionCommand.mPid, "-"))
            }

            markdownString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Previous State", executionCommand.previousState.getName(), "-"))
            markdownString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Current State", executionCommand.currentState.getName(), "-"))

            markdownString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Executable", executionCommand.executable, "-"))
            markdownString.append("\n").append(getArgumentsMarkdownString("Arguments", executionCommand.arguments))
            markdownString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Working Directory", executionCommand.workingDirectory, "-"))
            markdownString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Runner", executionCommand.runner, "-"))
            markdownString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("isFailsafe", executionCommand.isFailsafe, "-"))

            if (Runner.APP_SHELL.equalsRunner(executionCommand.runner)) {
                if (!DataUtils.isNullOrEmpty(executionCommand.stdin)) {
                    markdownString.append("\n").append(MarkdownUtils.getMultiLineMarkdownStringEntry("Stdin", executionCommand.stdin, "-"))
                }
                if (executionCommand.backgroundCustomLogLevel != null) {
                    markdownString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Background Custom Log Level", executionCommand.backgroundCustomLogLevel, "-"))
                }
            }

            markdownString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Session Action", executionCommand.sessionAction, "-"))

            markdownString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Shell Name", executionCommand.shellName, "-"))
            markdownString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Shell Create Mode", executionCommand.shellCreateMode, "-"))
            markdownString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Set Shell Command Shell Environment", executionCommand.setShellCommandShellEnvironment, "-"))

            markdownString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("isPluginExecutionCommand", executionCommand.isPluginExecutionCommand, "-"))

            markdownString.append("\n\n").append(ResultConfig.getResultConfigMarkdownString(executionCommand.resultConfig))

            markdownString.append("\n\n").append(ResultData.getResultDataMarkdownString(executionCommand.resultData))

            if (executionCommand.commandDescription != null || executionCommand.commandHelp != null) {
                if (executionCommand.commandDescription != null) {
                    markdownString.append("\n\n### Command Description\n\n").append(executionCommand.commandDescription).append("\n")
                }
                if (executionCommand.commandHelp != null) {
                    markdownString.append("\n\n### Command Help\n\n").append(executionCommand.commandHelp).append("\n")
                }
                markdownString.append("\n##\n")
            }

            if (executionCommand.pluginAPIHelp != null) {
                markdownString.append("\n\n### Plugin API Help\n\n").append(executionCommand.pluginAPIHelp)
                markdownString.append("\n##\n")
            }

            return markdownString.toString()
        }

        @JvmStatic
        fun getArgumentsLogString(label: String, argumentsArray: Array<String>?): String {
            val argumentsString = StringBuilder("$label:")

            if (argumentsArray != null && argumentsArray.isNotEmpty()) {
                argumentsString.append("\n```\n")
                for (i in argumentsArray.indices) {
                    argumentsString.append(
                        Logger.getSingleLineLogStringEntry(
                            "Arg " + (i + 1),
                            DataUtils.getTruncatedCommandOutput(
                                argumentsArray[i],
                                Logger.LOGGER_ENTRY_MAX_SAFE_PAYLOAD / 5,
                                true,
                                false,
                                true
                            ),
                            "-"
                        )
                    ).append("\n")
                }
                argumentsString.append("```")
            } else {
                argumentsString.append(" -")
            }

            return argumentsString.toString()
        }

        @JvmStatic
        fun getArgumentsMarkdownString(label: String, argumentsArray: Array<String>?): String {
            val argumentsString = StringBuilder("**$label:**")

            if (argumentsArray != null && argumentsArray.isNotEmpty()) {
                argumentsString.append("\n")
                for (i in argumentsArray.indices) {
                    argumentsString.append(MarkdownUtils.getMultiLineMarkdownStringEntry("Arg " + (i + 1), argumentsArray[i], "-")).append("\n")
                }
            } else {
                argumentsString.append(" -  ")
            }

            return argumentsString.toString()
        }
    }
}
