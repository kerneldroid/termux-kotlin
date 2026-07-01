package com.termux.terminal

/**
 * The interface for communication between {@link TerminalSession} and its client. It is used to
 * send callbacks to the client when {@link TerminalSession} changes or for sending other
 * back data to the client like logs.
 */
interface TerminalSessionClient {

    fun onTextChanged(changedSession: TerminalSession)

    fun onTitleChanged(updatedSession: TerminalSession)

    fun onSessionFinished(finishedSession: TerminalSession)

    fun onCopyTextToClipboard(session: TerminalSession, text: String?)

    fun onPasteTextFromClipboard(session: TerminalSession?)

    fun onBell(session: TerminalSession)

    fun onColorsChanged(changedSession: TerminalSession)

    fun onTerminalCursorStateChange(state: Boolean)

    fun setTerminalShellPid(session: TerminalSession, pid: Int)

    fun getTerminalCursorStyle(): Int?

    fun logError(tag: String?, message: String?)

    fun logWarn(tag: String?, message: String?)

    fun logInfo(tag: String?, message: String?)

    fun logDebug(tag: String?, message: String?)

    fun logVerbose(tag: String?, message: String?)

    fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?)

    fun logStackTrace(tag: String?, e: Exception?)

}
