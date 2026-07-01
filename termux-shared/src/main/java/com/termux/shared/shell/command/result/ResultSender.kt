package com.termux.shared.shell.command.result

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.termux.shared.R
import com.termux.shared.data.DataUtils
import com.termux.shared.markdown.MarkdownUtils
import com.termux.shared.errors.Error
import com.termux.shared.file.FileUtils
import com.termux.shared.logger.Logger
import com.termux.shared.errors.FunctionErrno
import com.termux.shared.android.AndroidUtils
import com.termux.shared.shell.command.ShellCommandConstants.RESULT_SENDER

class ResultSender {

    companion object {
        private const val LOG_TAG = "ResultSender"

        /**
         * Send result stored in {@link ResultConfig} to command caller via
         * {@link ResultConfig#resultPendingIntent} and/or by writing it to files in
         * {@link ResultConfig#resultDirectoryPath}. If both are not {@code null}, then result will be
         * sent via both.
         *
         * @param context The {@link Context} for operations.
         * @param logTag The log tag to use for logging.
         * @param label The label for the command.
         * @param resultConfig The {@link ResultConfig} object containing information on how to send the result.
         * @param resultData The {@link ResultData} object containing result data.
         * @param logStdoutAndStderr Set to {@code true} if {@link ResultData#stdout} and {@link ResultData#stderr}
         *                           should be logged.
         * @return Returns the {@link Error} if failed to send the result, otherwise {@code null}.
         */
        @JvmStatic
        fun sendCommandResultData(
            context: Context?,
            logTag: String?,
            label: String?,
            resultConfig: ResultConfig?,
            resultData: ResultData?,
            logStdoutAndStderr: Boolean
        ): Error? {
            if (context == null || resultConfig == null || resultData == null) {
                return FunctionErrno.ERRNO_NULL_OR_EMPTY_PARAMETERS.getError(
                    "context, resultConfig or resultData",
                    "sendCommandResultData"
                )
            }

            val error: Error?

            if (resultConfig.resultPendingIntent != null) {
                error = sendCommandResultDataWithPendingIntent(
                    context,
                    logTag,
                    label,
                    resultConfig,
                    resultData,
                    logStdoutAndStderr
                )
                if (error != null || resultConfig.resultDirectoryPath == null) {
                    return error
                }
            }

            return if (resultConfig.resultDirectoryPath != null) {
                sendCommandResultDataToDirectory(
                    context,
                    logTag,
                    label,
                    resultConfig,
                    resultData,
                    logStdoutAndStderr
                )
            } else {
                FunctionErrno.ERRNO_UNSET_PARAMETERS.getError(
                    "resultConfig.resultPendingIntent or resultConfig.resultDirectoryPath",
                    "sendCommandResultData"
                )
            }
        }

        /**
         * Send result stored in {@link ResultConfig} to command caller via {@link ResultConfig#resultPendingIntent}.
         *
         * @param context The {@link Context} for operations.
         * @param logTag The log tag to use for logging.
         * @param label The label for the command.
         * @param resultConfig The {@link ResultConfig} object containing information on how to send the result.
         * @param resultData The {@link ResultData} object containing result data.
         * @param logStdoutAndStderr Set to {@code true} if {@link ResultData#stdout} and {@link ResultData#stderr}
         *                           should be logged.
         * @return Returns the {@link Error} if failed to send the result, otherwise {@code null}.
         */
        @JvmStatic
        fun sendCommandResultDataWithPendingIntent(
            context: Context?,
            logTag: String?,
            label: String?,
            resultConfig: ResultConfig?,
            resultData: ResultData?,
            logStdoutAndStderr: Boolean
        ): Error? {
            if (context == null || resultConfig == null || resultData == null || resultConfig.resultPendingIntent == null || resultConfig.resultBundleKey == null) {
                return FunctionErrno.ERRNO_NULL_OR_EMPTY_PARAMETER.getError(
                    "context, resultConfig, resultData, resultConfig.resultPendingIntent or resultConfig.resultBundleKey",
                    "sendCommandResultDataWithPendingIntent"
                )
            }

            val resolvedLogTag = DataUtils.getDefaultIfNull(logTag, LOG_TAG)

            Logger.logDebugExtended(
                resolvedLogTag,
                "Sending result for command \"" + label + "\":\n" + resultConfig.toString() + "\n" + ResultData.getResultDataLogString(
                    resultData,
                    logStdoutAndStderr
                )
            )

            var resultDataStdout = resultData.stdout.toString()
            var resultDataStderr = resultData.stderr.toString()

            var truncatedStdout: String? = null
            var truncatedStderr: String? = null

            val stdoutOriginalLength = resultDataStdout.length.toString()
            val stderrOriginalLength = resultDataStderr.length.toString()

            // Truncate stdout and stdout to max TRANSACTION_SIZE_LIMIT_IN_BYTES
            if (resultDataStderr.isEmpty()) {
                truncatedStdout = DataUtils.getTruncatedCommandOutput(
                    resultDataStdout,
                    DataUtils.TRANSACTION_SIZE_LIMIT_IN_BYTES,
                    false,
                    false,
                    false
                )
            } else if (resultDataStdout.isEmpty()) {
                truncatedStderr = DataUtils.getTruncatedCommandOutput(
                    resultDataStderr,
                    DataUtils.TRANSACTION_SIZE_LIMIT_IN_BYTES,
                    false,
                    false,
                    false
                )
            } else {
                truncatedStdout = DataUtils.getTruncatedCommandOutput(
                    resultDataStdout,
                    DataUtils.TRANSACTION_SIZE_LIMIT_IN_BYTES / 2,
                    false,
                    false,
                    false
                )
                truncatedStderr = DataUtils.getTruncatedCommandOutput(
                    resultDataStderr,
                    DataUtils.TRANSACTION_SIZE_LIMIT_IN_BYTES / 2,
                    false,
                    false,
                    false
                )
            }

            if (truncatedStdout != null && truncatedStdout.length < resultDataStdout.length) {
                Logger.logWarn(
                    resolvedLogTag,
                    "The result for command \"" + label + "\" stdout length truncated from " + stdoutOriginalLength + " to " + truncatedStdout.length
                )
                resultDataStdout = truncatedStdout
            }

            if (truncatedStderr != null && truncatedStderr.length < resultDataStderr.length) {
                Logger.logWarn(
                    resolvedLogTag,
                    "The result for command \"" + label + "\" stderr length truncated from " + stderrOriginalLength + " to " + truncatedStderr.length
                )
                resultDataStderr = truncatedStderr
            }

            var resultDataErrmsg: String? = null
            if (resultData.isStateFailed()) {
                resultDataErrmsg = ResultData.getErrorsListLogString(resultData)
                if (resultDataErrmsg.isEmpty()) resultDataErrmsg = null
            }

            val errmsgOriginalLength = if (resultDataErrmsg == null) null else resultDataErrmsg.length.toString()

            // Truncate error to max TRANSACTION_SIZE_LIMIT_IN_BYTES / 4
            // trim from end to preserve start of stacktraces
            val truncatedErrmsg = DataUtils.getTruncatedCommandOutput(
                resultDataErrmsg,
                DataUtils.TRANSACTION_SIZE_LIMIT_IN_BYTES / 4,
                true,
                false,
                false
            )
            if (truncatedErrmsg != null && resultDataErrmsg != null && truncatedErrmsg.length < resultDataErrmsg.length) {
                Logger.logWarn(
                    resolvedLogTag,
                    "The result for command \"" + label + "\" error length truncated from " + errmsgOriginalLength + " to " + truncatedErrmsg.length
                )
                resultDataErrmsg = truncatedErrmsg
            }

            val resultBundle = Bundle()
            resultBundle.putString(resultConfig.resultStdoutKey, resultDataStdout)
            resultBundle.putString(resultConfig.resultStdoutOriginalLengthKey, stdoutOriginalLength)
            resultBundle.putString(resultConfig.resultStderrKey, resultDataStderr)
            resultBundle.putString(resultConfig.resultStderrOriginalLengthKey, stderrOriginalLength)
            if (resultData.exitCode != null) {
                resultBundle.putInt(resultConfig.resultExitCodeKey, resultData.exitCode!!)
            }
            resultBundle.putInt(resultConfig.resultErrCodeKey, resultData.getErrCode())
            resultBundle.putString(resultConfig.resultErrmsgKey, resultDataErrmsg)

            val resultIntent = Intent()
            resultIntent.putExtra(resultConfig.resultBundleKey, resultBundle)

            try {
                resultConfig.resultPendingIntent!!.send(context, Activity.RESULT_OK, resultIntent)
            } catch (e: PendingIntent.CanceledException) {
                // The caller doesn't want the result? That's fine, just ignore
                Logger.logDebug(
                    resolvedLogTag,
                    "The command \"" + label + "\" creator " + resultConfig.resultPendingIntent!!.creatorPackage + " does not want the results anymore"
                )
            }

            return null
        }

        /**
         * Send result stored in {@link ResultConfig} to command caller by writing it to files in
         * {@link ResultConfig#resultDirectoryPath}.
         *
         * @param context The {@link Context} for operations.
         * @param logTag The log tag to use for logging.
         * @param label The label for the command.
         * @param resultConfig The {@link ResultConfig} object containing information on how to send the result.
         * @param resultData The {@link ResultData} object containing result data.
         * @param logStdoutAndStderr Set to {@code true} if {@link ResultData#stdout} and {@link ResultData#stderr}
         *                           should be logged.
         * @return Returns the {@link Error} if failed to send the result, otherwise {@code null}.
         */
        @JvmStatic
        fun sendCommandResultDataToDirectory(
            context: Context?,
            logTag: String?,
            label: String?,
            resultConfig: ResultConfig?,
            resultData: ResultData?,
            logStdoutAndStderr: Boolean
        ): Error? {
            if (context == null || resultConfig == null || resultData == null || DataUtils.isNullOrEmpty(resultConfig.resultDirectoryPath)) {
                return FunctionErrno.ERRNO_NULL_OR_EMPTY_PARAMETER.getError(
                    "context, resultConfig, resultData or resultConfig.resultDirectoryPath",
                    "sendCommandResultDataToDirectory"
                )
            }

            val resolvedLogTag = DataUtils.getDefaultIfNull(logTag, LOG_TAG)

            var error: Error?

            val resultDataStdout = resultData.stdout.toString()
            val resultDataStderr = resultData.stderr.toString()

            var resultDataExitCode = ""
            if (resultData.exitCode != null) {
                resultDataExitCode = resultData.exitCode.toString()
            }

            val resultDataErrmsg = if (resultData.isStateFailed()) {
                ResultData.getErrorsListLogString(resultData) ?: ""
            } else {
                ""
            }

            resultConfig.resultDirectoryPath = FileUtils.getCanonicalPath(resultConfig.resultDirectoryPath, null)

            Logger.logDebugExtended(
                resolvedLogTag,
                "Writing result for command \"" + label + "\":\n" + resultConfig.toString() + "\n" + ResultData.getResultDataLogString(
                    resultData,
                    logStdoutAndStderr
                )
            )

            // If resultDirectoryPath is not a directory, or is not readable or writable, then just return
            // Creation of missing directory and setting of read, write and execute permissions are
            // only done if resultDirectoryPath is under resultDirectoryAllowedParentPath.
            // We try to set execute permissions, but ignore if they are missing, since only read and write
            // permissions are required for working directories.
            error = FileUtils.validateDirectoryFileExistenceAndPermissions(
                "result", resultConfig.resultDirectoryPath,
                resultConfig.resultDirectoryAllowedParentPath, true,
                FileUtils.APP_WORKING_DIRECTORY_PERMISSIONS, true, true,
                true, true
            )
            if (error != null) {
                error.appendMessage("\n" + context.getString(R.string.msg_directory_absolute_path, "Result", resultConfig.resultDirectoryPath))
                return error
            }

            if (resultConfig.resultSingleFile) {
                // If resultFileBasename is null, empty or contains forward slashes "/"
                if (DataUtils.isNullOrEmpty(resultConfig.resultFileBasename) ||
                    resultConfig.resultFileBasename!!.contains("/")
                ) {
                    error = ResultSenderErrno.ERROR_RESULT_FILE_BASENAME_NULL_OR_INVALID.getError(resultConfig.resultFileBasename)
                    return error
                }

                val errorOrOutput: String

                if (resultData.isStateFailed()) {
                    try {
                        if (DataUtils.isNullOrEmpty(resultConfig.resultFileErrorFormat)) {
                            errorOrOutput = java.lang.String.format(
                                RESULT_SENDER.FORMAT_FAILED_ERR__ERRMSG__STDOUT__STDERR__EXIT_CODE,
                                MarkdownUtils.getMarkdownCodeForString(resultData.getErrCode().toString(), false),
                                MarkdownUtils.getMarkdownCodeForString(resultDataErrmsg, true),
                                MarkdownUtils.getMarkdownCodeForString(resultDataStdout, true),
                                MarkdownUtils.getMarkdownCodeForString(resultDataStderr, true),
                                MarkdownUtils.getMarkdownCodeForString(resultDataExitCode, false)
                            )
                        } else {
                            errorOrOutput = java.lang.String.format(
                                resultConfig.resultFileErrorFormat!!,
                                resultData.getErrCode(), resultDataErrmsg, resultDataStdout, resultDataStderr, resultDataExitCode
                            )
                        }
                    } catch (e: Exception) {
                        error = ResultSenderErrno.ERROR_FORMAT_RESULT_ERROR_FAILED_WITH_EXCEPTION.getError(e.message)
                        return error
                    }
                } else {
                    try {
                        if (DataUtils.isNullOrEmpty(resultConfig.resultFileOutputFormat)) {
                            if (resultDataStderr.isEmpty() && resultDataExitCode == "0") {
                                errorOrOutput = java.lang.String.format(RESULT_SENDER.FORMAT_SUCCESS_STDOUT, resultDataStdout)
                            } else if (resultDataStderr.isEmpty()) {
                                errorOrOutput = java.lang.String.format(
                                    RESULT_SENDER.FORMAT_SUCCESS_STDOUT__EXIT_CODE,
                                    resultDataStdout,
                                    MarkdownUtils.getMarkdownCodeForString(resultDataExitCode, false)
                                )
                            } else {
                                errorOrOutput = java.lang.String.format(
                                    RESULT_SENDER.FORMAT_SUCCESS_STDOUT__STDERR__EXIT_CODE,
                                    MarkdownUtils.getMarkdownCodeForString(resultDataStdout, true),
                                    MarkdownUtils.getMarkdownCodeForString(resultDataStderr, true),
                                    MarkdownUtils.getMarkdownCodeForString(resultDataExitCode, false)
                                )
                            }
                        } else {
                            errorOrOutput = java.lang.String.format(
                                resultConfig.resultFileOutputFormat!!,
                                resultDataStdout, resultDataStderr, resultDataExitCode
                            )
                        }
                    } catch (e: Exception) {
                        error = ResultSenderErrno.ERROR_FORMAT_RESULT_OUTPUT_FAILED_WITH_EXCEPTION.getError(e.message)
                        return error
                    }
                }

                // Write error or output to temp file
                // Check errCode file creation below for explanation for why temp file is used
                val tempFilename = resultConfig.resultFileBasename + "-" + AndroidUtils.getCurrentMilliSecondLocalTimeStamp()
                error = FileUtils.writeTextToFile(
                    tempFilename, resultConfig.resultDirectoryPath + "/" + tempFilename,
                    null, errorOrOutput, false
                )
                if (error != null) {
                    return error
                }

                // Move error or output temp file to final destination
                error = FileUtils.moveRegularFile(
                    "error or output temp file", resultConfig.resultDirectoryPath + "/" + tempFilename,
                    resultConfig.resultDirectoryPath + "/" + resultConfig.resultFileBasename, false
                )
                if (error != null) {
                    return error
                }
            } else {
                var filename: String

                // Default to no suffix, useful if user expects result in an empty directory, like created with mktemp
                if (resultConfig.resultFilesSuffix == null) {
                    resultConfig.resultFilesSuffix = ""
                }

                val resultFilesSuffix = resultConfig.resultFilesSuffix!!

                // If resultFilesSuffix contains forward slashes "/"
                if (resultFilesSuffix.contains("/")) {
                    error = ResultSenderErrno.ERROR_RESULT_FILES_SUFFIX_INVALID.getError(resultFilesSuffix)
                    return error
                }

                // Write result to result files under resultDirectoryPath

                // Write stdout to file
                if (resultDataStdout.isNotEmpty()) {
                    filename = RESULT_SENDER.RESULT_FILE_STDOUT_PREFIX + resultFilesSuffix
                    error = FileUtils.writeTextToFile(
                        filename, resultConfig.resultDirectoryPath + "/" + filename,
                        null, resultDataStdout, false
                    )
                    if (error != null) {
                        return error
                    }
                }

                // Write stderr to file
                if (resultDataStderr.isNotEmpty()) {
                    filename = RESULT_SENDER.RESULT_FILE_STDERR_PREFIX + resultFilesSuffix
                    error = FileUtils.writeTextToFile(
                        filename, resultConfig.resultDirectoryPath + "/" + filename,
                        null, resultDataStderr, false
                    )
                    if (error != null) {
                        return error
                    }
                }

                // Write exitCode to file
                if (resultDataExitCode.isNotEmpty()) {
                    filename = RESULT_SENDER.RESULT_FILE_EXIT_CODE_PREFIX + resultFilesSuffix
                    error = FileUtils.writeTextToFile(
                        filename, resultConfig.resultDirectoryPath + "/" + filename,
                        null, resultDataExitCode, false
                    )
                    if (error != null) {
                        return error
                    }
                }

                // Write errmsg to file
                if (resultData.isStateFailed() && !resultDataErrmsg.isNullOrEmpty()) {
                    filename = RESULT_SENDER.RESULT_FILE_ERRMSG_PREFIX + resultFilesSuffix
                    error = FileUtils.writeTextToFile(
                        filename, resultConfig.resultDirectoryPath + "/" + filename,
                        null, resultDataErrmsg, false
                    )
                    if (error != null) {
                        return error
                    }
                }

                // Write errCode to file
                // This must be created after writing to other result files has already finished since
                // caller should wait for this file to be created to be notified that the command has
                // finished and should then start reading from the rest of the result files if they exist.
                // Since there may be a delay between creation of errCode file and writing to it or flushing
                // to disk, we create a temp file first and then move it to the final destination, since
                // caller may otherwise read from an empty file in some cases.

                // Write errCode to temp file
                var tempFilename = RESULT_SENDER.RESULT_FILE_ERR_PREFIX + "-" + AndroidUtils.getCurrentMilliSecondLocalTimeStamp()
                if (resultFilesSuffix.isNotEmpty()) {
                    tempFilename = tempFilename + "-" + resultFilesSuffix
                }
                error = FileUtils.writeTextToFile(
                    tempFilename, resultConfig.resultDirectoryPath + "/" + tempFilename,
                    null, resultData.getErrCode().toString(), false
                )
                if (error != null) {
                    return error
                }

                // Move errCode temp file to final destination
                filename = RESULT_SENDER.RESULT_FILE_ERR_PREFIX + resultFilesSuffix
                error = FileUtils.moveRegularFile(
                    RESULT_SENDER.RESULT_FILE_ERR_PREFIX + " temp file", resultConfig.resultDirectoryPath + "/" + tempFilename,
                    resultConfig.resultDirectoryPath + "/" + filename, false
                )
                if (error != null) {
                    return error
                }
            }

            return null
        }
    }
}
