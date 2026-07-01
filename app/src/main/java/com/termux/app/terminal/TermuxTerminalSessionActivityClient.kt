package com.termux.app.terminal

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.SoundPool
import android.text.TextUtils
import com.termux.R
import com.termux.app.TermuxActivity
import com.termux.app.TermuxService
import com.termux.shared.interact.ShareUtils
import com.termux.shared.logger.Logger
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.interact.TextInputDialogUtils
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase
import com.termux.shared.termux.terminal.io.BellHandler
import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalSession
import com.termux.terminal.TextStyle
import java.io.File
import java.io.FileInputStream
import java.util.Properties

/** The [com.termux.terminal.TerminalSessionClient] implementation that may require an [android.app.Activity] for its interface methods. */
class TermuxTerminalSessionActivityClient(private val mActivity: TermuxActivity) : TermuxTerminalSessionClientBase() {

    private var mBellSoundPool: SoundPool? = null

    private var mBellSoundId = 0

    /**
     * Should be called when mActivity.onCreate() is called
     */
    fun onCreate() {
        // Set terminal fonts and colors
        checkForFontAndColors()
    }

    /**
     * Should be called when mActivity.onStart() is called
     */
    fun onStart() {
        // The service has connected, but data may have changed since we were last in the foreground.
        // Get the session stored in shared preferences stored by {@link #onStop} if its valid,
        // otherwise get the last session currently running.
        if (mActivity.termuxService != null) {
            setCurrentSession(getCurrentStoredSessionOrLast())
            termuxSessionListNotifyUpdated()
        }

        // The current terminal session may have changed while being away, force
        // a refresh of the displayed terminal.
        mActivity.terminalView.onScreenUpdated()
    }

    /**
     * Should be called when mActivity.onResume() is called
     */
    fun onResume() {
        // Just initialize the mBellSoundPool and load the sound, otherwise bell might not run
        // the first time bell key is pressed and play() is called, since sound may not be loaded
        // quickly enough before the call to play(). https://stackoverflow.com/questions/35435625
        loadBellSoundPool()
    }

    /**
     * Should be called when mActivity.onStop() is called
     */
    fun onStop() {
        // Store current session in shared preferences so that it can be restored later in
        // {@link #onStart} if needed.
        setCurrentStoredSession()

        // Release mBellSoundPool resources, specially to prevent exceptions like the following to be thrown
        // java.util.concurrent.TimeoutException: android.media.SoundPool.finalize() timed out after 10 seconds
        // Bell is not played in background anyways
        // Related: https://stackoverflow.com/a/28708351/14686958
        releaseBellSoundPool()
    }

    /**
     * Should be called when mActivity.reloadActivityStyling() is called
     */
    fun onReloadActivityStyling() {
        // Set terminal fonts and colors
        checkForFontAndColors()
    }

    override fun onTextChanged(changedSession: TerminalSession) {
        if (!mActivity.isVisible) return

        if (mActivity.getCurrentSession() === changedSession) mActivity.terminalView.onScreenUpdated()
    }

    override fun onTitleChanged(updatedSession: TerminalSession) {
        if (!mActivity.isVisible) return

        if (updatedSession !== mActivity.getCurrentSession()) {
            // Only show toast for other sessions than the current one, since the user
            // probably consciously caused the title change to change in the current session
            // and don't want an annoying toast for that.
            mActivity.showToast(toToastTitle(updatedSession), true)
        }

        termuxSessionListNotifyUpdated()
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        val service = mActivity.termuxService

        if (service == null || service.wantsToStop()) {
            // The service wants to stop as soon as possible.
            mActivity.finishActivityIfNotFinishing()
            return
        }

        val index = service.getIndexOfSession(finishedSession)

        // For plugin commands that expect the result back, we should immediately close the session
        // and send the result back instead of waiting fo the user to press enter.
        // The plugin can handle/show errors itself.
        var isPluginExecutionCommandWithPendingResult = false
        val termuxSession = service.getTermuxSession(index)
        if (termuxSession != null) {
            isPluginExecutionCommandWithPendingResult = termuxSession.executionCommand.isPluginExecutionCommandWithPendingResult
            if (isPluginExecutionCommandWithPendingResult) {
                Logger.logVerbose(LOG_TAG, "The \"" + finishedSession.mSessionName + "\" session will be force finished automatically since result in pending.")
            }
        }

        if (mActivity.isVisible && finishedSession !== mActivity.getCurrentSession()) {
            // Show toast for non-current sessions that exit.
            // Verify that session was not removed before we got told about it finishing:
            if (index >= 0) {
                mActivity.showToast(toToastTitle(finishedSession) + " - exited", true)
            }
        }

        if (mActivity.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
            // On Android TV devices we need to use older behaviour because we may
            // not be able to have multiple launcher icons.
            if (service.termuxSessionsSize > 1 || isPluginExecutionCommandWithPendingResult) {
                removeFinishedSession(finishedSession)
            }
        } else {
            // Once we have a separate launcher icon for the failsafe session, it
            // should be safe to auto-close session on exit code '0' or '130'.
            if (finishedSession.exitStatus == 0 || finishedSession.exitStatus == 130 || isPluginExecutionCommandWithPendingResult) {
                removeFinishedSession(finishedSession)
            }
        }
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
        if (!mActivity.isVisible) return

        ShareUtils.copyTextToClipboard(mActivity, text)
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        if (!mActivity.isVisible) return

        val text = ShareUtils.getTextStringFromClipboardIfSet(mActivity, true)
        if (text != null) {
            mActivity.terminalView.mEmulator?.paste(text)
        }
    }

    override fun onBell(session: TerminalSession) {
        if (!mActivity.isVisible) return

        when (mActivity.properties.bellBehaviour) {
            TermuxPropertyConstants.IVALUE_BELL_BEHAVIOUR_VIBRATE -> {
                BellHandler.getInstance(mActivity).doBell()
            }
            TermuxPropertyConstants.IVALUE_BELL_BEHAVIOUR_BEEP -> {
                loadBellSoundPool()
                mBellSoundPool?.play(mBellSoundId, 1.0f, 1.0f, 1, 0, 1.0f)
            }
            TermuxPropertyConstants.IVALUE_BELL_BEHAVIOUR_IGNORE -> {
                // Ignore the bell character.
            }
        }
    }

    override fun onColorsChanged(changedSession: TerminalSession) {
        if (mActivity.getCurrentSession() === changedSession) {
            updateBackgroundColor()
        }
    }

    override fun onTerminalCursorStateChange(state: Boolean) {
        // Do not start cursor blinking thread if activity is not visible
        if (state && !mActivity.isVisible) {
            Logger.logVerbose(LOG_TAG, "Ignoring call to start cursor blinking since activity is not visible")
            return
        }

        // If cursor is to enabled now, then start cursor blinking if blinking is enabled
        // otherwise stop cursor blinking
        mActivity.terminalView.setTerminalCursorBlinkerState(state, false)
    }

    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {
        val service = mActivity.termuxService ?: return

        val termuxSession = service.getTermuxSessionForTerminalSession(session)
        if (termuxSession != null) {
            termuxSession.executionCommand.mPid = pid
        }
    }

    /**
     * Should be called when mActivity.onResetTerminalSession() is called
     */
    fun onResetTerminalSession() {
        // Ensure blinker starts again after reset if cursor blinking was disabled before reset like
        // with "tput civis" which would have called onTerminalCursorStateChange()
        mActivity.terminalView.setTerminalCursorBlinkerState(true, true)
    }

    override fun getTerminalCursorStyle(): Int? {
        return mActivity.properties.terminalCursorStyle
    }

    /** Load mBellSoundPool */
    @Synchronized
    private fun loadBellSoundPool() {
        if (mBellSoundPool == null) {
            mBellSoundPool = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(
                AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build()
            ).build()

            try {
                mBellSoundId = mBellSoundPool!!.load(mActivity, com.termux.shared.R.raw.bell, 1)
            } catch (e: Exception) {
                // Catch java.lang.RuntimeException: Unable to resume activity {com.termux/com.termux.app.TermuxActivity}: android.content.res.Resources$NotFoundException: File res/raw/bell.ogg from drawable resource ID
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to load bell sound pool", e)
            }
        }
    }

    /** Release mBellSoundPool resources */
    @Synchronized
    private fun releaseBellSoundPool() {
        mBellSoundPool?.let {
            it.release()
            mBellSoundPool = null
        }
    }

    /** Try switching to session. */
    fun setCurrentSession(session: TerminalSession?) {
        if (session == null) return

        if (mActivity.terminalView.attachSession(session)) {
            // notify about switched session if not already displaying the session
            notifyOfSessionChange()
        }

        // We call the following even when the session is already being displayed since config may
        // be stale, like current session not selected or scrolled to.
        checkAndScrollToSession(session)
        updateBackgroundColor()
    }

    internal fun notifyOfSessionChange() {
        if (!mActivity.isVisible) return

        if (!mActivity.properties.areTerminalSessionChangeToastsDisabled()) {
            val session = mActivity.getCurrentSession()
            mActivity.showToast(toToastTitle(session), false)
        }
    }

    fun switchToSession(forward: Boolean) {
        val service = mActivity.termuxService ?: return

        val currentTerminalSession = mActivity.getCurrentSession()
        var index = service.getIndexOfSession(currentTerminalSession)
        val size = service.termuxSessionsSize
        if (forward) {
            if (++index >= size) index = 0
        } else {
            if (--index < 0) index = size - 1
        }

        val termuxSession = service.getTermuxSession(index)
        if (termuxSession != null) {
            setCurrentSession(termuxSession.terminalSession)
        }
    }

    fun switchToSession(index: Int) {
        val service = mActivity.termuxService ?: return

        val termuxSession = service.getTermuxSession(index)
        if (termuxSession != null) {
            setCurrentSession(termuxSession.terminalSession)
        }
    }

    @SuppressLint("InflateParams")
    fun renameSession(sessionToRename: TerminalSession?) {
        if (sessionToRename == null) return

        TextInputDialogUtils.textInput(
            mActivity,
            R.string.title_rename_session,
            sessionToRename.mSessionName,
            R.string.action_rename_session_confirm,
            { text ->
                renameSession(sessionToRename, text)
                termuxSessionListNotifyUpdated()
            },
            -1,
            null,
            -1,
            null,
            null
        )
    }

    private fun renameSession(sessionToRename: TerminalSession?, text: String) {
        if (sessionToRename == null) return
        sessionToRename.mSessionName = text
        val service = mActivity.termuxService
        if (service != null) {
            val termuxSession = service.getTermuxSessionForTerminalSession(sessionToRename)
            if (termuxSession != null) {
                termuxSession.executionCommand.shellName = text
            }
        }
    }

    fun addNewSession(isFailSafe: Boolean, sessionName: String?) {
        val service = mActivity.termuxService ?: return

        if (service.termuxSessionsSize >= MAX_SESSIONS) {
            AlertDialog.Builder(mActivity)
                .setTitle(R.string.title_max_terminals_reached)
                .setMessage(R.string.msg_max_terminals_reached)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        } else {
            val currentSession = mActivity.getCurrentSession()

            val workingDirectory = if (currentSession == null) {
                mActivity.properties.defaultWorkingDirectory
            } else {
                currentSession.cwd
            }

            val newTermuxSession = service.createTermuxSession(
                null,
                null,
                null,
                workingDirectory,
                isFailSafe,
                sessionName
            ) ?: return

            val newTerminalSession = newTermuxSession.terminalSession
            setCurrentSession(newTerminalSession)

            mActivity.closeDrawer()
        }
    }

    fun setCurrentStoredSession() {
        val currentSession = mActivity.getCurrentSession()
        if (currentSession != null) {
            mActivity.preferences.setCurrentSession(currentSession.mHandle)
        } else {
            mActivity.preferences.setCurrentSession(null)
        }
    }

    /** The current session as stored or the last one if that does not exist. */
    fun getCurrentStoredSessionOrLast(): TerminalSession? {
        val stored = getCurrentStoredSession()

        return if (stored != null) {
            // If a stored session is in the list of currently running sessions, then return it
            stored
        } else {
            // Else return the last session currently running
            val service = mActivity.termuxService ?: return null

            val termuxSession = service.lastTermuxSession
            termuxSession?.terminalSession
        }
    }

    private fun getCurrentStoredSession(): TerminalSession? {
        val sessionHandle = mActivity.preferences.getCurrentSession() ?: return null

        // Check if the session handle found matches one of the currently running sessions
        val service = mActivity.termuxService ?: return null

        return service.getTerminalSessionForHandle(sessionHandle)
    }

    fun removeFinishedSession(finishedSession: TerminalSession) {
        // Return pressed with finished session - remove it.
        val service = mActivity.termuxService ?: return

        var index = service.removeTermuxSession(finishedSession)

        val size = service.termuxSessionsSize
        if (size == 0) {
            // There are no sessions to show, so finish the activity.
            mActivity.finishActivityIfNotFinishing()
        } else {
            if (index >= size) {
                index = size - 1
            }
            val termuxSession = service.getTermuxSession(index)
            if (termuxSession != null) {
                setCurrentSession(termuxSession.terminalSession)
            }
        }
    }

    fun termuxSessionListNotifyUpdated() {
        mActivity.termuxSessionListNotifyUpdated()
    }

    fun checkAndScrollToSession(session: TerminalSession) {
        if (!mActivity.isVisible) return
        val service = mActivity.termuxService ?: return

        val indexOfSession = service.getIndexOfSession(session)
        if (indexOfSession < 0) return

        mActivity.termuxSessionListNotifyUpdated()
    }

    internal fun toToastTitle(session: TerminalSession?): String? {
        if (session == null) return null
        val service = mActivity.termuxService ?: return null

        val indexOfSession = service.getIndexOfSession(session)
        if (indexOfSession < 0) return null
        val toastTitle = StringBuilder("[" + (indexOfSession + 1) + "]")
        if (!TextUtils.isEmpty(session.mSessionName)) {
            toastTitle.append(" ").append(session.mSessionName)
        }
        val title = session.title
        if (!TextUtils.isEmpty(title)) {
            // Space to "[${NR}] or newline after session name:
            toastTitle.append(if (session.mSessionName == null) " " else "\n")
            toastTitle.append(title)
        }
        return toastTitle.toString()
    }

    fun checkForFontAndColors() {
        try {
            val colorsFile = TermuxConstants.TERMUX_COLOR_PROPERTIES_FILE
            val fontFile = TermuxConstants.TERMUX_FONT_FILE

            val props = Properties()
            if (colorsFile.isFile) {
                FileInputStream(colorsFile).use { `in` ->
                    props.load(`in`)
                }
            }

            TerminalColors.COLOR_SCHEME.updateWith(props)
            val session = mActivity.getCurrentSession()
            session?.emulator?.mColors?.reset()
            updateBackgroundColor()

            val newTypeface = if (fontFile.exists() && fontFile.length() > 0) {
                Typeface.createFromFile(fontFile)
            } else {
                Typeface.MONOSPACE
            }
            mActivity.terminalView.setTypeface(newTypeface)
        } catch (e: Exception) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Error in checkForFontAndColors()", e)
        }
    }

    fun updateBackgroundColor() {
        if (!mActivity.isVisible) return
        val session = mActivity.getCurrentSession()
        val emulator = session?.emulator
        if (emulator != null) {
            mActivity.window.decorView.setBackgroundColor(
                emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND]
            )
        }
    }

    companion object {
        private const val MAX_SESSIONS = 8
        private const val LOG_TAG = "TermuxTerminalSessionActivityClient"
    }
}
