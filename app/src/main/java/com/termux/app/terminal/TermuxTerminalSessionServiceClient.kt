package com.termux.app.terminal

import android.app.Service
import com.termux.app.TermuxService
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

/** The {@link TerminalSessionClient} implementation that may require a {@link Service} for its interface methods. */
class TermuxTerminalSessionServiceClient(private val mService: TermuxService) : TermuxTerminalSessionClientBase() {

    override fun setTerminalShellPid(terminalSession: TerminalSession, pid: Int) {
        val termuxSession: TermuxSession? = mService.getTermuxSessionForTerminalSession(terminalSession)
        if (termuxSession != null) {
            termuxSession.executionCommand.mPid = pid
        }
    }

    companion object {
        private const val LOG_TAG = "TermuxTerminalSessionServiceClient"
    }
}
