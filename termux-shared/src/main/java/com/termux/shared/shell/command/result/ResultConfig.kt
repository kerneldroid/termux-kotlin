package com.termux.shared.shell.command.result

import android.app.PendingIntent
import com.termux.shared.logger.Logger
import com.termux.shared.markdown.MarkdownUtils

class ResultConfig {

    /** Defines [PendingIntent] that should be sent with the result of the command. We cannot
     * implement [java.io.Serializable] because [PendingIntent] cannot be serialized. */
    @JvmField
    var resultPendingIntent: PendingIntent? = null

    /** The key with which to send result [android.os.Bundle] in [resultPendingIntent]. */
    @JvmField
    var resultBundleKey: String? = null

    /** The key with which to send [ResultData.stdout] in [resultPendingIntent]. */
    @JvmField
    var resultStdoutKey: String? = null

    /** The key with which to send [ResultData.stderr] in [resultPendingIntent]. */
    @JvmField
    var resultStderrKey: String? = null

    /** The key with which to send [ResultData.exitCode] in [resultPendingIntent]. */
    @JvmField
    var resultExitCodeKey: String? = null

    /** The key with which to send [ResultData.errorsList] errCode in [resultPendingIntent]. */
    @JvmField
    var resultErrCodeKey: String? = null

    /** The key with which to send [ResultData.errorsList] errmsg in [resultPendingIntent]. */
    @JvmField
    var resultErrmsgKey: String? = null

    /** The key with which to send original length of [ResultData.stdout] in [resultPendingIntent]. */
    @JvmField
    var resultStdoutOriginalLengthKey: String? = null

    /** The key with which to send original length of [ResultData.stderr] in [resultPendingIntent]. */
    @JvmField
    var resultStderrOriginalLengthKey: String? = null


    /** Defines the directory path in which to write the result of the command. */
    @JvmField
    var resultDirectoryPath: String? = null

    /** Defines the directory path under which [resultDirectoryPath] can exist. */
    @JvmField
    var resultDirectoryAllowedParentPath: String? = null

    /** Defines whether the result should be written to a single file or multiple files
     * (err, error, stdout, stderr, exit_code) in [resultDirectoryPath]. */
    @JvmField
    var resultSingleFile: Boolean = false

    /** Defines the basename of the result file that should be created in [resultDirectoryPath]
     * if [resultSingleFile] is `true`. */
    @JvmField
    var resultFileBasename: String? = null

    /** Defines the output [java.util.Formatter] format of the [resultFileBasename] result file. */
    @JvmField
    var resultFileOutputFormat: String? = null

    /** Defines the error [java.util.Formatter] format of the [resultFileBasename] result file. */
    @JvmField
    var resultFileErrorFormat: String? = null

    /** Defines the suffix of the result files that should be created in [resultDirectoryPath]
     * if [resultSingleFile] is `true`. */
    @JvmField
    var resultFilesSuffix: String? = null


    fun isCommandWithPendingResult(): Boolean {
        return resultPendingIntent != null || resultDirectoryPath != null
    }

    override fun toString(): String {
        return getResultConfigLogString(this, true)
    }

    fun getResultPendingIntentVariablesLogString(ignoreNull: Boolean): String {
        val pendingIntent = resultPendingIntent ?: return "Result PendingIntent Creator: -"

        val resultPendingIntentVariablesString = StringBuilder()

        resultPendingIntentVariablesString.append("Result PendingIntent Creator: `").append(pendingIntent.creatorPackage).append("`")

        if (!ignoreNull || resultBundleKey != null) {
            resultPendingIntentVariablesString.append("\n").append(Logger.getSingleLineLogStringEntry("Result Bundle Key", resultBundleKey, "-"))
        }
        if (!ignoreNull || resultStdoutKey != null) {
            resultPendingIntentVariablesString.append("\n").append(Logger.getSingleLineLogStringEntry("Result Stdout Key", resultStdoutKey, "-"))
        }
        if (!ignoreNull || resultStderrKey != null) {
            resultPendingIntentVariablesString.append("\n").append(Logger.getSingleLineLogStringEntry("Result Stderr Key", resultStderrKey, "-"))
        }
        if (!ignoreNull || resultExitCodeKey != null) {
            resultPendingIntentVariablesString.append("\n").append(Logger.getSingleLineLogStringEntry("Result Exit Code Key", resultExitCodeKey, "-"))
        }
        if (!ignoreNull || resultErrCodeKey != null) {
            resultPendingIntentVariablesString.append("\n").append(Logger.getSingleLineLogStringEntry("Result Err Code Key", resultErrCodeKey, "-"))
        }
        if (!ignoreNull || resultErrmsgKey != null) {
            resultPendingIntentVariablesString.append("\n").append(Logger.getSingleLineLogStringEntry("Result Error Key", resultErrmsgKey, "-"))
        }
        if (!ignoreNull || resultStdoutOriginalLengthKey != null) {
            resultPendingIntentVariablesString.append("\n").append(Logger.getSingleLineLogStringEntry("Result Stdout Original Length Key", resultStdoutOriginalLengthKey, "-"))
        }
        if (!ignoreNull || resultStderrOriginalLengthKey != null) {
            resultPendingIntentVariablesString.append("\n").append(Logger.getSingleLineLogStringEntry("Result Stderr Original Length Key", resultStderrOriginalLengthKey, "-"))
        }

        return resultPendingIntentVariablesString.toString()
    }

    fun getResultDirectoryVariablesLogString(ignoreNull: Boolean): String {
        val directoryPath = resultDirectoryPath ?: return "Result Directory Path: -"

        val resultDirectoryVariablesString = StringBuilder()

        resultDirectoryVariablesString.append(Logger.getSingleLineLogStringEntry("Result Directory Path", directoryPath, "-"))

        resultDirectoryVariablesString.append("\n").append(Logger.getSingleLineLogStringEntry("Result Single File", resultSingleFile, "-"))
        if (!ignoreNull || resultFileBasename != null) {
            resultDirectoryVariablesString.append("\n").append(Logger.getSingleLineLogStringEntry("Result File Basename", resultFileBasename, "-"))
        }
        if (!ignoreNull || resultFileOutputFormat != null) {
            resultDirectoryVariablesString.append("\n").append(Logger.getSingleLineLogStringEntry("Result File Output Format", resultFileOutputFormat, "-"))
        }
        if (!ignoreNull || resultFileErrorFormat != null) {
            resultDirectoryVariablesString.append("\n").append(Logger.getSingleLineLogStringEntry("Result File Error Format", resultFileErrorFormat, "-"))
        }
        if (!ignoreNull || resultFilesSuffix != null) {
            resultDirectoryVariablesString.append("\n").append(Logger.getSingleLineLogStringEntry("Result Files Suffix", resultFilesSuffix, "-"))
        }

        return resultDirectoryVariablesString.toString()
    }

    companion object {
        /**
         * Get a log friendly [String] for [ResultConfig] parameters.
         *
         * @param resultConfig The [ResultConfig] to convert.
         * @param ignoreNull Set to `true` if non-critical `null` values are to be ignored.
         * @return Returns the log friendly [String].
         */
        @JvmStatic
        fun getResultConfigLogString(resultConfig: ResultConfig?, ignoreNull: Boolean): String {
            if (resultConfig == null) return "null"

            val logString = StringBuilder()

            logString.append("Result Pending: `").append(resultConfig.isCommandWithPendingResult()).append("`\n")

            if (resultConfig.resultPendingIntent != null) {
                logString.append(resultConfig.getResultPendingIntentVariablesLogString(ignoreNull))
                if (resultConfig.resultDirectoryPath != null) {
                    logString.append("\n")
                }
            }

            val directoryPath = resultConfig.resultDirectoryPath
            if (directoryPath != null && directoryPath.isNotEmpty()) {
                logString.append(resultConfig.getResultDirectoryVariablesLogString(ignoreNull))
            }

            return logString.toString()
        }

        /**
         * Get a markdown [String] for [ResultConfig].
         *
         * @param resultConfig The [ResultConfig] to convert.
         * @return Returns the markdown [String].
         */
        @JvmStatic
        fun getResultConfigMarkdownString(resultConfig: ResultConfig?): String {
            if (resultConfig == null) return "null"

            val markdownString = StringBuilder()

            val pendingIntent = resultConfig.resultPendingIntent
            if (pendingIntent != null) {
                markdownString.append(MarkdownUtils.getSingleLineMarkdownStringEntry("Result PendingIntent Creator", pendingIntent.creatorPackage, "-"))
            } else {
                markdownString.append("**Result PendingIntent Creator:** -  ")
            }

            val directoryPath = resultConfig.resultDirectoryPath
            if (directoryPath != null) {
                markdownString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Result Directory Path", directoryPath, "-"))
                markdownString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Result Single File", resultConfig.resultSingleFile, "-"))
                markdownString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Result File Basename", resultConfig.resultFileBasename, "-"))
                markdownString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Result File Output Format", resultConfig.resultFileOutputFormat, "-"))
                markdownString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Result File Error Format", resultConfig.resultFileErrorFormat, "-"))
                markdownString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Result Files Suffix", resultConfig.resultFilesSuffix, "-"))
            }

            return markdownString.toString()
        }
    }
}
