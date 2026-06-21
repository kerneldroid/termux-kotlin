package com.termux.app

import androidx.compose.ui.platform.ComposeView
import com.termux.app.compose.TermuxToolbar

fun setToolbarContent(
    composeView: ComposeView,
    activity: TermuxActivity,
    savedTextInput: String?
) {
    composeView.setContent {
        com.termux.app.compose.TermuxTheme {
            TermuxToolbar(activity, savedTextInput)
        }
    }
}
