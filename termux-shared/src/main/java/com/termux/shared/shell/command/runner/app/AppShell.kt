package com.termux.shared.shell.command.runner.app

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.google.common.base.Joiner
import com.termux.shared.R
import com.termux.shared.data.DataUtils
import com.termux.shared.errors.Errno
import com.termux.shared.logger.Logger
import com.termux.shared.shell.ShellUtils
import com.termux.shared.shell.StreamGobbler
import com.termux.shared.shell.command.ExecutionCommand
import com.termux.shared.shell.command.ExecutionCommand.ExecutionState
import com.termux.shared.shell.command.environment.IShellEnvironment
import com.termux.shared.shell.command.environment.ShellEnvironmentUtils
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.HashMap

/**
 * A class that maintains info for background app shells run with [Runtime.exec].
 * It also provides a way to link each [Process] with the [ExecutionCommand]
 * that started it. The shell is run in the app user context.
 */
class AppShell private constructor(
    val process: Process,
    val executionCommand: ExecutionCommand,
    private val mAppShellClient: AppShellClient?
) {

    /**
     * Sets up stdout and stderr readers for the [process] and waits for the process to end.
     *
     * If the processes finishes, then sets [com.termux.shared.shell.command.result.ResultData.stdout],
     * [com.termux.shared.shell.command.result.ResultData.stderr] and [com.termux.shared.shell.command.result.ResultData.exitCode]
     * for the [executionCommand] of the `appShell` and then calls [processAppShellResult] to process the result.
     *
     * @param context The [Context] for operations.
     */
    @Throws(IllegalThreadStateException::class, InterruptedException::class)
    private fun executeInner(context: Context) {
        executionCommand.mPid = ShellUtils.getPid(process)

        Logger.logDebug(
            LOG_TAG,
            "Running \"" + executionCommand.commandIdAndLabelLogString + "\" AppShell with pid " + executionCommand.mPid
        )

        executionCommand.resultData.exitCode = null

        // setup stdin, and stdout and stderr gobblers
        val STDIN = DataOutputStream(process.outputStream)
        val STDOUT = StreamGobbler(
            executionCommand.mPid.toString() + "-stdout",
            process.inputStream,
            executionCommand.resultData.stdout,
            executionCommand.backgroundCustomLogLevel
        )
        val STDERR = StreamGobbler(
            executionCommand.mPid.toString() + "-stderr",
            process.errorStream,
            executionCommand.resultData.stderr,
            executionCommand.backgroundCustomLogLevel
        )

        // start gobbling
        STDOUT.start()
        STDERR.start()

        if (!DataUtils.isNullOrEmpty(executionCommand.stdin)) {
            try {
                STDIN.write((executionCommand.stdin + "\n").toByteArray(StandardCharsets.UTF_8))
                STDIN.flush()
                STDIN.close()
                //STDIN.write("exit\n".getBytes(StandardCharsets.UTF_8));
                //STDIN.flush();
            } catch (e: IOException) {
                if (e.message != null && (e.message!!.contains("EPIPE") || e.message!!.contains("Stream closed"))) {
                    // Method most horrid to catch broken pipe, in which case we
                    // do nothing. The command is not a shell, the shell closed
                    // STDIN, the script already contained the exit command, etc.
                    // these cases we want the output instead of returning null.
                } else {
                    // other issues we don't know how to handle, leads to
                    // returning null
                    executionCommand.setStateFailed(
                        Errno.ERRNO_FAILED.code,
                        context.getString(
                            R.string.error_exception_received_while_executing_app_shell_command,
                            executionCommand.commandIdAndLabelLogString,
                            e.message
                        ),
                        e
                    )
                    executionCommand.resultData.exitCode = 1
                    processAppShellResult(this, null)
                    kill()
                    return
                }
            }
        }

        // wait for our process to finish, while we gobble away in the background
        val exitCode = process.waitFor()

        // make sure our threads are done gobbling
        // and the process is destroyed - while the latter shouldn't be
        // needed in theory, and may even produce warnings, in "normal" Java
        // they are required for guaranteed cleanup of resources, so lets be
        // safe and do this on Android as well
        try {
            STDIN.close()
        } catch (e: IOException) {
            // might be closed already
        }
        STDOUT.join()
        STDERR.join()
        process.destroy()

        // Process result
        if (exitCode == 0) {
            Logger.logDebug(
                LOG_TAG,
                "The \"" + executionCommand.commandIdAndLabelLogString + "\" AppShell with pid " + executionCommand.mPid + " exited normally"
            )
        } else {
            Logger.logDebug(
                LOG_TAG,
                "The \"" + executionCommand.commandIdAndLabelLogString + "\" AppShell with pid " + executionCommand.mPid + " exited with code: " + exitCode
            )
        }

        // If the execution command has already failed, like SIGKILL was sent, then don't continue
        if (executionCommand.isStateFailed) {
            Logger.logDebug(
                LOG_TAG,
                "Ignoring setting \"" + executionCommand.commandIdAndLabelLogString + "\" AppShell state to ExecutionState.EXECUTED and processing results since it has already failed"
            )
            return
        }

        executionCommand.resultData.exitCode = exitCode

        if (!executionCommand.setState(ExecutionState.EXECUTED)) return

        processAppShellResult(this, null)
    }

    /**
     * Kill this [AppShell] by sending a [OsConstants.SIGKILL] to its [process]
     * if its still executing.
     *
     * @param context The [Context] for operations.
     * @param processResult If set to `true`, then the [processAppShellResult]
     *                      will be called to process the failure.
     */
    fun killIfExecuting(context: Context, processResult: Boolean) {
        // If execution command has already finished executing, then no need to process results or send SIGKILL
        if (executionCommand.hasExecuted) {
            Logger.logDebug(
                LOG_TAG,
                "Ignoring sending SIGKILL to \"" + executionCommand.commandIdAndLabelLogString + "\" AppShell since it has already finished executing"
            )
            return
        }

        Logger.logDebug(
            LOG_TAG,
            "Send SIGKILL to \"" + executionCommand.commandIdAndLabelLogString + "\" AppShell"
        )

        if (executionCommand.setStateFailed(
                Errno.ERRNO_FAILED.code,
                context.getString(R.string.error_sending_sigkill_to_process)
            )
        ) {
            if (processResult) {
                executionCommand.resultData.exitCode = 137 // SIGKILL
                processAppShellResult(this, null)
            }
        }

        if (executionCommand.isExecuting) {
            kill()
        }
    }

    /**
     * Kill this [AppShell] by sending a [OsConstants.SIGKILL] to its [process].
     */
    fun kill() {
        val pid = ShellUtils.getPid(process)
        try {
            // Send SIGKILL to process
            Os.kill(pid, OsConstants.SIGKILL)
        } catch (e: ErrnoException) {
            Logger.logWarn(
                LOG_TAG,
                "Failed to send SIGKILL to \"" + executionCommand.commandIdAndLabelLogString + "\" AppShell with pid " + pid + ": " + e.message
            )
        }
    }

    interface AppShellClient {
        /**
         * Callback function for when [AppShell] exits.
         *
         * @param appShell The [AppShell] that exited.
         */
        fun onAppShellExited(appShell: AppShell?)
    }

    companion object {
        private const val LOG_TAG = "AppShell"

        /**
         * Start execution of an [ExecutionCommand] with [Runtime.exec].
         *
         * The [ExecutionCommand.executable], must be set.
         * The  [ExecutionCommand.commandLabel], [ExecutionCommand.arguments] and
         * [ExecutionCommand.workingDirectory] may optionally be set.
         *
         * @param currentPackageContext The [Context] for operations. This must be the context for
         *                              the current package and not the context of a `sharedUserId` package,
         *                              since environment setup may be dependent on current package.
         * @param executionCommand The [ExecutionCommand] containing the information for execution command.
         * @param appShellClient The [AppShellClient] interface implementation.
         *                           The [AppShellClient.onAppShellExited] will
         *                           be called regardless of `isSynchronous` value but not if
         *                           `null` is returned by this method. This can
         *                           optionally be `null`.
         * @param shellEnvironmentClient The [IShellEnvironment] interface implementation.
         * @param additionalEnvironment The additional shell environment variables to export. Existing
         *                              variables will be overridden.
         * @param isSynchronous If set to `true`, then the command will be executed in the
         *                      caller thread and results returned synchronously in the [ExecutionCommand]
         *                      sub object of the [AppShell] returned.
         *                      If set to `false`, then a new thread is started run the commands
         *                      asynchronously in the background and control is returned to the caller thread.
         * @return Returns the [AppShell]. This will be `null` if failed to start the execution command.
         */
        @JvmStatic
        fun execute(
            currentPackageContext: Context,
            executionCommand: ExecutionCommand,
            appShellClient: AppShellClient?,
            shellEnvironmentClient: IShellEnvironment,
            additionalEnvironment: HashMap<String, String>?,
            isSynchronous: Boolean
        ): AppShell? {
            val executable = executionCommand.executable
            if (executable == null || executable.isEmpty()) {
                executionCommand.setStateFailed(
                    Errno.ERRNO_FAILED.code,
                    currentPackageContext.getString(
                        R.string.error_executable_unset,
                        executionCommand.commandIdAndLabelLogString
                    )
                )
                processAppShellResult(null, executionCommand)
                return null
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

            // Transform executable path to shell/session name, e.g. "/bin/do-something.sh" => "do-something.sh".
            val executableBasename = ShellUtils.getExecutableBasename(executable)

            if (executionCommand.shellName == null) {
                executionCommand.shellName = executableBasename
            }

            if (executionCommand.commandLabel == null) {
                executionCommand.commandLabel = executableBasename
            }

            // Setup command args
            val commandArray = shellEnvironmentClient.setupShellCommandArguments(
                executable,
                executionCommand.arguments
            )

            // Setup command environment
            val environment = shellEnvironmentClient.setupShellCommandEnvironment(
                currentPackageContext,
                executionCommand
            )
            if (additionalEnvironment != null) {
                environment.putAll(additionalEnvironment)
            }
            val environmentList = ShellEnvironmentUtils.convertEnvironmentToEnviron(environment)
            Collections.sort(environmentList)
            val environmentArray = environmentList.toTypedArray()

            if (!executionCommand.setState(ExecutionState.EXECUTING)) {
                executionCommand.setStateFailed(
                    Errno.ERRNO_FAILED.code,
                    currentPackageContext.getString(
                        R.string.error_failed_to_execute_app_shell_command,
                        executionCommand.commandIdAndLabelLogString
                    )
                )
                processAppShellResult(null, executionCommand)
                return null
            }

            // No need to log stdin if logging is disabled, like for app internal scripts
            Logger.logDebugExtended(
                LOG_TAG, ExecutionCommand.getExecutionInputLogString(
                    executionCommand,
                    true,
                    Logger.shouldEnableLoggingForCustomLogLevel(executionCommand.backgroundCustomLogLevel)
                )
            )
            Logger.logVerboseExtended(
                LOG_TAG,
                "\"" + executionCommand.commandIdAndLabelLogString + "\" AppShell Environment:\n" +
                        Joiner.on("\n").join(environmentArray)
            )

            // Exec the process
            val process: Process = try {
                Runtime.getRuntime().exec(
                    commandArray,
                    environmentArray,
                    File(executionCommand.workingDirectory)
                )
            } catch (e: IOException) {
                executionCommand.setStateFailed(
                    Errno.ERRNO_FAILED.code,
                    currentPackageContext.getString(
                        R.string.error_failed_to_execute_app_shell_command,
                        executionCommand.commandIdAndLabelLogString
                    ),
                    e
                )
                processAppShellResult(null, executionCommand)
                return null
            }

            val appShell = AppShell(process, executionCommand, appShellClient)
            if (isSynchronous) {
                try {
                    appShell.executeInner(currentPackageContext)
                } catch (e: IllegalThreadStateException) {
                    // TODO: Should either of these be handled or returned?
                } catch (e: InterruptedException) {
                    // TODO: Should either of these be handled or returned?
                }
            } else {
                object : Thread() {
                    override fun run() {
                        try {
                            appShell.executeInner(currentPackageContext)
                        } catch (e: IllegalThreadStateException) {
                            // TODO: Should either of these be handled or returned?
                        } catch (e: InterruptedException) {
                            // TODO: Should either of these be handled or returned?
                        }
                    }
                }.start()
            }

            return appShell
        }

        /**
         * Process the results of [AppShell] or [ExecutionCommand].
         *
         * Only one of `appShell` and `executionCommand` must be set.
         *
         * If the `appShell` and its [mAppShellClient] are not `null`,
         * then the [AppShellClient.onAppShellExited] callback will be called.
         *
         * @param appShell The [AppShell], which should be set if
         *                  [execute] successfully started the process.
         * @param executionCommand The [ExecutionCommand], which should be set if
         *                          [execute] failed to start the process.
         */
        @JvmStatic
        private fun processAppShellResult(appShell: AppShell?, executionCommand: ExecutionCommand?) {
            var cmd = executionCommand
            if (appShell != null) {
                cmd = appShell.executionCommand
            }

            if (cmd == null) return

            if (cmd.shouldNotProcessResults()) {
                Logger.logDebug(
                    LOG_TAG,
                    "Ignoring duplicate call to process \"" + cmd.commandIdAndLabelLogString + "\" AppShell result"
                )
                return
            }

            Logger.logDebug(
                LOG_TAG,
                "Processing \"" + cmd.commandIdAndLabelLogString + "\" AppShell result"
            )

            if (appShell != null && appShell.mAppShellClient != null) {
                appShell.mAppShellClient.onAppShellExited(appShell)
            } else {
                // If a callback is not set and execution command didn't fail, then we set success state now
                // Otherwise, the callback host can set it himself when its done with the appShell
                if (!cmd.isStateFailed) {
                    cmd.setState(ExecutionState.SUCCESS)
                }
            }
        }
    }
}
