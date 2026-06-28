package com.termux.app.api.file

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Patterns
import androidx.appcompat.app.AppCompatActivity
import com.termux.R
import com.termux.app.TermuxService
import com.termux.shared.android.PackageUtils
import com.termux.shared.data.DataUtils
import com.termux.shared.data.IntentUtils
import com.termux.shared.interact.MessageDialogUtils
import com.termux.shared.logger.Logger
import com.termux.shared.net.uri.UriScheme
import com.termux.shared.net.uri.UriUtils
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.TermuxConstants.TERMUX_APP
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE
import com.termux.shared.termux.interact.TextInputDialogUtils
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

class FileReceiverActivity : AppCompatActivity() {

    /**
     * If the activity should be finished when the name input dialog is dismissed. This is disabled
     * before showing an error dialog, since the act of showing the error dialog will cause the
     * name input dialog to be implicitly dismissed, and we do not want to finish the activity directly
     * when showing the error dialog.
     */
    var mFinishOnDismissNameDialog = true

    override fun onResume() {
        super.onResume()

        val intent = intent
        val action = intent.action
        val type = intent.type
        val scheme = intent.scheme

        Logger.logVerbose(LOG_TAG, "Intent Received:\n" + IntentUtils.getIntentString(intent))

        val sharedTitle = IntentUtils.getStringExtraIfSet(intent, Intent.EXTRA_TITLE, null)

        if (Intent.ACTION_SEND == action && type != null) {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            val sharedUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)

            if (sharedUri != null) {
                handleContentUri(sharedUri, sharedTitle)
            } else if (sharedText != null) {
                if (isSharedTextAnUrl(sharedText)) {
                    handleUrlAndFinish(sharedText)
                } else {
                    var subject = IntentUtils.getStringExtraIfSet(intent, Intent.EXTRA_SUBJECT, null)
                    if (subject == null) subject = sharedTitle
                    if (subject != null) subject += ".txt"
                    promptNameAndSave(ByteArrayInputStream(sharedText.toByteArray(StandardCharsets.UTF_8)), subject)
                }
            } else {
                showErrorDialogAndQuit("Send action without content - nothing to save.")
            }
        } else {
            val dataUri = intent.data

            if (dataUri == null) {
                showErrorDialogAndQuit("Data uri not passed.")
                return
            }

            when (scheme) {
                UriScheme.SCHEME_CONTENT -> {
                    handleContentUri(dataUri, sharedTitle)
                }
                UriScheme.SCHEME_FILE -> {
                    Logger.logVerbose(LOG_TAG, "uri: \"$dataUri\", path: \"${dataUri.path}\", fragment: \"${dataUri.fragment}\"")

                    // Get full path including fragment (anything after last "#")
                    val path = UriUtils.getUriFilePathWithFragment(dataUri)
                    if (DataUtils.isNullOrEmpty(path)) {
                        showErrorDialogAndQuit("File path from data uri is null, empty or invalid.")
                        return
                    }

                    val file = File(path)
                    try {
                        val inputStream = FileInputStream(file)
                        promptNameAndSave(inputStream, file.name)
                    } catch (e: FileNotFoundException) {
                        showErrorDialogAndQuit("Cannot open file: " + e.message + ".")
                    }
                }
                else -> {
                    showErrorDialogAndQuit("Unable to receive any file or URL.")
                }
            }
        }
    }

    fun showErrorDialogAndQuit(message: String?) {
        mFinishOnDismissNameDialog = false
        MessageDialogUtils.showMessage(
            this,
            API_TAG, message,
            null, { _, _ -> finish() },
            null, null
        ) { finish() }
    }

    fun handleContentUri(uri: Uri, subjectFromIntent: String?) {
        try {
            Logger.logVerbose(LOG_TAG, "uri: \"$uri\", path: \"${uri.path}\", fragment: \"${uri.fragment}\"")

            var attachmentFileName: String? = null

            val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
            contentResolver.query(uri, projection, null, null, null).use { c ->
                if (c != null && c.moveToFirst()) {
                    val fileNameColumnId = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (fileNameColumnId >= 0) attachmentFileName = c.getString(fileNameColumnId)
                }
            }

            if (attachmentFileName == null) attachmentFileName = subjectFromIntent
            if (attachmentFileName == null) attachmentFileName = UriUtils.getUriFileBasename(uri, true)

            val inputStream = contentResolver.openInputStream(uri) ?: throw IOException("Failed to open input stream")
            promptNameAndSave(inputStream, attachmentFileName)
        } catch (e: Exception) {
            showErrorDialogAndQuit("Unable to handle shared content:\n\n" + e.message)
            Logger.logStackTraceWithMessage(LOG_TAG, "handleContentUri(uri=$uri) failed", e)
        }
    }

    fun promptNameAndSave(inStream: InputStream, attachmentFileName: String?) {
        TextInputDialogUtils.textInput(
            this,
            R.string.title_file_received,
            attachmentFileName,
            R.string.action_file_received_edit,
            TextInputDialogUtils.TextSetListener { text ->
                val outFile = saveStreamWithName(inStream, text) ?: return@TextSetListener

                val editorProgramFile = File(EDITOR_PROGRAM)
                if (!editorProgramFile.isFile) {
                    showErrorDialogAndQuit(
                        "The following file does not exist:\n\$HOME/bin/termux-file-editor\n\n"
                                + "Create this file as a script or a symlink - it will be called with the received file as only argument."
                    )
                    return@TextSetListener
                }

                // Do this for the user if necessary:
                //noinspection ResultOfMethodCallIgnored
                editorProgramFile.setExecutable(true)

                val scriptUri = UriUtils.getFileUri(EDITOR_PROGRAM)

                val executeIntent = Intent(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE, scriptUri).apply {
                    setClass(this@FileReceiverActivity, TermuxService::class.java)
                    putExtra(TERMUX_SERVICE.EXTRA_ARGUMENTS, arrayOf(outFile.absolutePath))
                }
                startService(executeIntent)
                finish()
            },
            R.string.action_file_received_open_directory,
            TextInputDialogUtils.TextSetListener { text ->
                if (saveStreamWithName(inStream, text) == null) return@TextSetListener

                val executeIntent = Intent(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE).apply {
                    putExtra(TERMUX_SERVICE.EXTRA_WORKDIR, TERMUX_RECEIVEDIR)
                    setClass(this@FileReceiverActivity, TermuxService::class.java)
                }
                startService(executeIntent)
                finish()
            },
            android.R.string.cancel,
            TextInputDialogUtils.TextSetListener { finish() },
            {
                if (mFinishOnDismissNameDialog) finish()
            }
        )
    }

    fun saveStreamWithName(inStream: InputStream, attachmentFileName: String?): File? {
        val receiveDir = File(TERMUX_RECEIVEDIR)

        if (DataUtils.isNullOrEmpty(attachmentFileName)) {
            showErrorDialogAndQuit("File name cannot be null or empty")
            return null
        }

        if (!receiveDir.isDirectory && !receiveDir.mkdirs()) {
            showErrorDialogAndQuit("Cannot create directory: " + receiveDir.absolutePath)
            return null
        }

        return try {
            val outFile = File(receiveDir, attachmentFileName)
            FileOutputStream(outFile).use { f ->
                val buffer = ByteArray(4096)
                var readBytes: Int
                while (inStream.read(buffer).also { readBytes = it } > 0) {
                    f.write(buffer, 0, readBytes)
                }
            }
            outFile
        } catch (e: IOException) {
            showErrorDialogAndQuit("Error saving file:\n\n$e")
            Logger.logStackTraceWithMessage(LOG_TAG, "Error saving file", e)
            null
        }
    }

    fun handleUrlAndFinish(url: String) {
        val urlOpenerProgramFile = File(URL_OPENER_PROGRAM)
        if (!urlOpenerProgramFile.isFile) {
            showErrorDialogAndQuit(
                "The following file does not exist:\n\$HOME/bin/termux-url-opener\n\n"
                        + "Create this file as a script or a symlink - it will be called with the shared URL as the first argument."
            )
            return
        }

        // Do this for the user if necessary:
        //noinspection ResultOfMethodCallIgnored
        urlOpenerProgramFile.setExecutable(true)

        val urlOpenerProgramUri = UriUtils.getFileUri(URL_OPENER_PROGRAM)

        val executeIntent = Intent(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE, urlOpenerProgramUri).apply {
            setClass(this@FileReceiverActivity, TermuxService::class.java)
            putExtra(TERMUX_SERVICE.EXTRA_ARGUMENTS, arrayOf(url))
        }
        startService(executeIntent)
        finish()
    }

    companion object {
        const val TERMUX_RECEIVEDIR = "${TermuxConstants.TERMUX_FILES_DIR_PATH}/home/downloads"
        const val EDITOR_PROGRAM = "${TermuxConstants.TERMUX_HOME_DIR_PATH}/bin/termux-file-editor"
        const val URL_OPENER_PROGRAM = "${TermuxConstants.TERMUX_HOME_DIR_PATH}/bin/termux-url-opener"

        private const val API_TAG = "${TermuxConstants.TERMUX_APP_NAME}FileReceiver"
        private const val LOG_TAG = "FileReceiverActivity"

        @JvmStatic
        fun isSharedTextAnUrl(sharedText: String?): Boolean {
            if (sharedText.isNullOrEmpty()) return false

            return Patterns.WEB_URL.matcher(sharedText).matches()
                    || Pattern.matches("magnet:\\?xt=urn:btih:.*?", sharedText)
        }

        /**
         * Update {@link TERMUX_APP#FILE_SHARE_RECEIVER_ACTIVITY_CLASS_NAME} component state depending on
         * {@link TermuxPropertyConstants#KEY_DISABLE_FILE_SHARE_RECEIVER} value and
         * {@link TERMUX_APP#FILE_VIEW_RECEIVER_ACTIVITY_CLASS_NAME} component state depending on
         * {@link TermuxPropertyConstants#KEY_DISABLE_FILE_VIEW_RECEIVER} value.
         */
        @JvmStatic
        fun updateFileReceiverActivityComponentsState(context: Context) {
            Thread {
                val properties = TermuxAppSharedProperties.getProperties()
                if (properties != null) {
                    var state = !properties.isFileShareReceiverDisabled()
                    Logger.logVerbose(LOG_TAG, "Setting ${TERMUX_APP.FILE_SHARE_RECEIVER_ACTIVITY_CLASS_NAME} component state to $state")
                    var errmsg = PackageUtils.setComponentState(
                        context, TermuxConstants.TERMUX_PACKAGE_NAME,
                        TERMUX_APP.FILE_SHARE_RECEIVER_ACTIVITY_CLASS_NAME,
                        state, null, false, false
                    )
                    if (errmsg != null) Logger.logError(LOG_TAG, errmsg)

                    state = !properties.isFileViewReceiverDisabled()
                    Logger.logVerbose(LOG_TAG, "Setting ${TERMUX_APP.FILE_VIEW_RECEIVER_ACTIVITY_CLASS_NAME} component state to $state")
                    errmsg = PackageUtils.setComponentState(
                        context, TermuxConstants.TERMUX_PACKAGE_NAME,
                        TERMUX_APP.FILE_VIEW_RECEIVER_ACTIVITY_CLASS_NAME,
                        state, null, false, false
                    )
                    if (errmsg != null) Logger.logError(LOG_TAG, errmsg)
                }
            }.start()
        }
    }
}
