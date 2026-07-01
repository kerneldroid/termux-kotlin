package com.termux.shared.termux.shell.command.runner.terminal

import android.content.Context
import com.google.common.base.Joiner
import com.termux.shared.R
import com.termux.shared.errors.Errno
import com.termux.shared.logger.Logger
import com.termux.shared.shell.ShellUtils
import com.termux.shared.shell.command.ExecutionCommand
import com.termux.shared.shell.command.environment.IShellEnvironment
import com.termux.shared.shell.command.environment.ShellEnvironmentUtils
import com.termux.shared.shell.command.environment.UnixShellEnvironment
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.File
import java.util.Collections
import java.util.HashMap

/**
 * A class that maintains info for foreground Termux sessions.
 * It also provides a way to link each [TerminalSession] with the [ExecutionCommand]
 * that started it.
 */
class TermuxSession private constructor(
    val terminalSession: TerminalSession,
    val executionCommand: ExecutionCommand,
    private val termuxSessionClient: TermuxSessionClient?,
    private val setStdoutOnExit: Boolean
) {

    /**
     * Signal that this [TermuxSession] has finished. This should be called when
     * [TerminalSessionClient.onSessionFinished] callback is received by the caller.
     *
     * If the processes has finished, then sets [ResultData.stdout], [ResultData.stderr]
     * and [ResultData.exitCode] for the [mExecutionCommand] of the `termuxTask`
     * and then calls [processTermuxSessionResult] to process the result.
     */
    fun finish() {
        // If process is still running, then ignore the call
        if (terminalSession.isRunning) return

        val exitCode = terminalSession.exitStatus

        if (exitCode == 0) {
            Logger.logDebug(LOG_TAG, "The \"" + executionCommand.commandIdAndLabelLogString + "\" TermuxSession exited normally")
        } else {
            Logger.logDebug(LOG_TAG, "The \"" + executionCommand.commandIdAndLabelLogString + "\" TermuxSession exited with code: $exitCode")
        }

        // If the execution command has already failed, like SIGKILL was sent, then don't continue
        if (executionCommand.isStateFailed) {
            Logger.logDebug(LOG_TAG, "Ignoring setting \"" + executionCommand.commandIdAndLabelLogString + "\" TermuxSession state to ExecutionState.EXECUTED and processing results since it has already failed")
            return
        }

        executionCommand.resultData.exitCode = exitCode

        if (this.setStdoutOnExit) {
            executionCommand.resultData.stdout.append(ShellUtils.getTerminalSessionTranscriptText(terminalSession, true, false))
        }

        if (!executionCommand.setState(ExecutionCommand.ExecutionState.EXECUTED)) {
            return
        }

        processTermuxSessionResult(this, null)
    }

    /**
     * Kill this [TermuxSession] by sending a SIGKILL to its [terminalSession]
     * if it's still executing.
     *
     * @param context The [Context] for operations.
     * @param processResult If set to `true`, then [processTermuxSessionResult]
     *                      will be called to process the failure.
     */
    fun killIfExecuting(context: Context, processResult: Boolean) {
        // If execution command has already finished executing, then no need to process results or send SIGKILL
        if (executionCommand.hasExecuted) {
            Logger.logDebug(LOG_TAG, "Ignoring sending SIGKILL to \"" + executionCommand.commandIdAndLabelLogString + "\" TermuxSession since it has already finished executing")
            return
        }

        Logger.logDebug(LOG_TAG, "Send SIGKILL to \"" + executionCommand.commandIdAndLabelLogString + "\" TermuxSession")
        if (executionCommand.setStateFailed(Errno.ERRNO_FAILED.code, context.getString(R.string.error_sending_sigkill_to_process))) {
            if (processResult) {
                executionCommand.resultData.exitCode = 137 // SIGKILL

                // Get whatever output has been set till now in case its needed
                if (this.setStdoutOnExit) {
                    executionCommand.resultData.stdout.append(ShellUtils.getTerminalSessionTranscriptText(terminalSession, true, false))
                }

                processTermuxSessionResult(this, null)
            }
        }

        // Send SIGKILL to process
        terminalSession.finishIfRunning()
    }

    interface TermuxSessionClient {
        /**
         * Callback function for when [TermuxSession] exits.
         *
         * @param termuxSession The [TermuxSession] that exited.
         */
        fun onTermuxSessionExited(termuxSession: TermuxSession)
    }

    companion object {
        private const val LOG_TAG = "TermuxSession"

        /**
         * Start execution of an [ExecutionCommand] with [Runtime.exec].
         *
         * The [ExecutionCommand.executable], must be set, [ExecutionCommand.commandLabel],
         * [ExecutionCommand.arguments] and [ExecutionCommand.workingDirectory] may optionally
         * be set.
         *
         * If [ExecutionCommand.executable] is `null`, then a default shell is automatically
         * chosen.
         *
         * @param currentPackageContext The [Context] for operations. This must be the context for
         *                              the current package and not the context of a `sharedUserId` package,
         *                              since environment setup may be dependent on current package.
         * @param executionCommand The [ExecutionCommand] containing the information for execution command.
         * @param terminalSessionClient The [TerminalSessionClient] interface implementation.
         * @param termuxSessionClient The [TermuxSessionClient] interface implementation.
         * @param shellEnvironmentClient The [IShellEnvironment] interface implementation.
         * @param additionalEnvironment The additional shell environment variables to export. Existing
         *                              variables will be overridden.
         * @param setStdoutOnExit If set to `true`, then the [ResultData.stdout]
         *                        available in the [TermuxSessionClient.onTermuxSessionExited]
         *                        callback will be set to the [TerminalSession] transcript. The session
         *                        transcript will contain both stdout and stderr combined, basically
         *                        anything sent to the the pseudo terminal /dev/pts, including PS1 prefixes.
         *                        Set this to `true` only if the session transcript is required,
         *                        since this requires extra processing to get it.
         * @return Returns the [TermuxSession]. This will be `null` if failed to start the execution command.
         */
        @JvmStatic
        fun execute(
            currentPackageContext: Context,
            executionCommand: ExecutionCommand,
            terminalSessionClient: TerminalSessionClient,
            termuxSessionClient: TermuxSessionClient?,
            shellEnvironmentClient: IShellEnvironment,
            additionalEnvironment: HashMap<String, String>?,
            setStdoutOnExit: Boolean
        ): TermuxSession? {
            var executable = executionCommand.executable
            if (executable != null && executable.isEmpty()) {
                executable = null
                executionCommand.executable = null
            }
            var workingDirectory = executionCommand.workingDirectory
            if (workingDirectory == null || workingDirectory.isEmpty()) {
                workingDirectory = shellEnvironmentClient.getDefaultWorkingDirectoryPath()
                executionCommand.workingDirectory = workingDirectory
            }
            if (workingDirectory.isEmpty()) {
                workingDirectory = "/"
                executionCommand.workingDirectory = workingDirectory
            }

            var defaultBinPath = shellEnvironmentClient.getDefaultBinPath()
            if (defaultBinPath.isEmpty()) {
                defaultBinPath = "/system/bin"
            }

            var isLoginShell = false
            if (executable == null) {
                if (!executionCommand.isFailsafe) {
                    for (shellBinary in UnixShellEnvironment.LOGIN_SHELL_BINARIES) {
                        val shellFile = File(defaultBinPath, shellBinary)
                        if (shellFile.canExecute()) {
                            executable = shellFile.absolutePath
                            executionCommand.executable = executable
                            break
                        }
                    }
                }

                if (executable == null) {
                    // Fall back to system shell as last resort:
                    // Do not start a login shell since ~/.profile may cause startup failure if its invalid.
                    // /system/bin/sh is provided by mksh (not toybox) and does load .mkshrc but for android its set
                    // to /system/etc/mkshrc even though its default is ~/.mkshrc.
                    // So /system/etc/mkshrc must still be valid for failsafe session to start properly.
                    // https://cs.android.com/android/platform/superproject/+/android-11.0.0_r3:external/mksh/src/main.c;l=663
                    // https://cs.android.com/android/platform/superproject/+/android-11.0.0_r3:external/mksh/src/main.c;l=41
                    // https://cs.android.com/android/platform/superproject/+/android-11.0.0_r3:external/mksh/Android.bp;l=114
                    executable = "/system/bin/sh"
                    executionCommand.executable = executable
                } else {
                    isLoginShell = true
                }
            }

            // Setup command args
            val commandArgs = shellEnvironmentClient.setupShellCommandArguments(executable, executionCommand.arguments)

            executable = commandArgs[0]
            executionCommand.executable = executable
            val processName = (if (isLoginShell) "-" else "") + ShellUtils.getExecutableBasename(executable)

            val arguments = Array(commandArgs.size) { "" }
            arguments[0] = processName
            if (commandArgs.size > 1) {
                System.arraycopy(commandArgs, 1, arguments, 1, commandArgs.size - 1)
            }

            executionCommand.arguments = arguments

            if (executionCommand.commandLabel == null) {
                executionCommand.commandLabel = processName
            }

            // Setup command environment
            val environment = shellEnvironmentClient.setupShellCommandEnvironment(currentPackageContext, executionCommand)
            if (additionalEnvironment != null) {
                environment.putAll(additionalEnvironment)
            }
            val environmentList = ShellEnvironmentUtils.convertEnvironmentToEnviron(environment)
            Collections.sort(environmentList)
            val environmentArray = environmentList.toTypedArray()

            if (!executionCommand.setState(ExecutionCommand.ExecutionState.EXECUTING)) {
                executionCommand.setStateFailed(
                    Errno.ERRNO_FAILED.code,
                    currentPackageContext.getString(
                        R.string.error_failed_to_execute_termux_session_command,
                        executionCommand.commandIdAndLabelLogString
                    )
                )
                processTermuxSessionResult(null, executionCommand)
                return null
            }

            Logger.logDebugExtended(LOG_TAG, executionCommand.toString())
            Logger.logVerboseExtended(LOG_TAG, "\"" + executionCommand.commandIdAndLabelLogString + "\" TermuxSession Environment:\n" +
                    Joiner.on("\n").join(environmentArray))

            Logger.logDebug(LOG_TAG, "Running \"" + executionCommand.commandIdAndLabelLogString + "\" TermuxSession")
            val terminalSession = TerminalSession(
                executionCommand.executable,
                executionCommand.workingDirectory,
                executionCommand.arguments,
                environmentArray,
                executionCommand.terminalTranscriptRows,
                terminalSessionClient
            )

            if (executionCommand.shellName != null) {
                terminalSession.mSessionName = executionCommand.shellName
            }

            return TermuxSession(terminalSession, executionCommand, termuxSessionClient, setStdoutOnExit)
        }

        @JvmStatic
        private fun processTermuxSessionResult(termuxSession: TermuxSession?, executionCommand: ExecutionCommand?) {
            var execCommand = executionCommand
            if (termuxSession != null) {
                execCommand = termuxSession.executionCommand
            }

            if (execCommand == null) return

            if (execCommand.shouldNotProcessResults()) {
                Logger.logDebug(LOG_TAG, "Ignoring duplicate call to process \"" + execCommand.commandIdAndLabelLogString + "\" TermuxSession result")
                return
            }

            Logger.logDebug(LOG_TAG, "Processing \"" + execCommand.commandIdAndLabelLogString + "\" TermuxSession result")

            if (termuxSession != null && termuxSession.termuxSessionClient != null) {
                termuxSession.termuxSessionClient.onTermuxSessionExited(termuxSession)
            } else {
                // If a callback is not set and execution command didn't fail, then we set success state now
                // Otherwise, the callback host can set it himself when its done with the termuxSession
                if (!execCommand.isStateFailed) {
                    execCommand.setState(ExecutionCommand.ExecutionState.SUCCESS)
                }
            }
        }
    }
}
