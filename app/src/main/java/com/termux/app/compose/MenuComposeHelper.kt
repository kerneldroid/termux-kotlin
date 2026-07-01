package com.termux.app

import android.app.Activity
import androidx.compose.ui.platform.ComposeView
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession

fun setDrawerContent(
    composeView: ComposeView,
    activity: Activity,
    sessions: List<TermuxSession>,
    currentSession: TermuxSession?,
    onSessionSelected: (TermuxSession) -> Unit,
    onSessionRename: (TermuxSession) -> Unit,
    onSessionKill: (TermuxSession) -> Unit,
    onNewSession: () -> Unit,
    onToggleKeyboard: () -> Unit,
    onToggleToolbar: () -> Unit
) {
    composeView.setContent {
        com.termux.app.compose.TermuxDrawerContent(
            activity, sessions, currentSession, onSessionSelected, onSessionRename, onSessionKill, onNewSession, onToggleKeyboard, onToggleToolbar
        )
    }
}