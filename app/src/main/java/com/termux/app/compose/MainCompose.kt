package com.termux.app.compose

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import com.termux.app.TermuxActivity
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession
import kotlinx.coroutines.launch

class MainScreenStateHolder {
    val sessions = mutableStateListOf<TermuxSession>()
    var currentSession by mutableStateOf<TermuxSession?>(null)
}

@Composable
fun TermuxMainScreen(
    activity: TermuxActivity,
    savedTextInput: String?,
    stateHolder: MainScreenStateHolder,
    contextMenuStateHolder: ContextMenuStateHolder
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()

    // Bind drawer control lambdas to Java activity
    DisposableEffect(Unit) {
        activity.mDrawerOpenRunnable = Runnable {
            coroutineScope.launch { drawerState.open() }
        }
        activity.mDrawerCloseRunnable = Runnable {
            coroutineScope.launch { drawerState.close() }
        }
        activity.mDrawerIsOpenCheck = java.util.concurrent.Callable {
            drawerState.isOpen
        }
        onDispose {
            activity.mDrawerOpenRunnable = null
            activity.mDrawerCloseRunnable = null
            activity.mDrawerIsOpenCheck = null
        }
    }

    var gesturesEnabled by remember { mutableStateOf(true) }
    DisposableEffect(Unit) {
        activity.mDrawerGesturesEnabledSetter = { enabled ->
            gesturesEnabled = enabled
        }
        onDispose {
            activity.mDrawerGesturesEnabledSetter = null
        }
    }

    // Observe toolbar visibility state from preferences
    val showToolbar = rememberPreferenceString("show_terminal_toolbar", "true").value == "true"

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = gesturesEnabled,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.width(300.dp).imePadding()
            ) {
                TermuxDrawerContent(
                    activity = activity,
                    sessions = stateHolder.sessions,
                    currentSession = stateHolder.currentSession,
                    onSessionSelected = { session ->
                        activity.termuxTerminalSessionClient?.setCurrentSession(session.terminalSession)
                        coroutineScope.launch { drawerState.close() }
                    },
                    onSessionRename = { session ->
                        activity.termuxTerminalSessionClient?.renameSession(session.terminalSession)
                    },
                    onNewSession = {
                        activity.termuxTerminalSessionClient?.addNewSession(false, null)
                    },
                    onToggleKeyboard = {
                        activity.termuxTerminalViewClient?.onToggleSoftKeyboardRequest()
                    },
                    onToggleToolbar = {
                        activity.toggleTerminalToolbar()
                    }
                )
            }
        }
    ) {
    Scaffold(
        modifier = Modifier.imePadding(),
        bottomBar = {
            if (showToolbar) {
                TermuxToolbar(activity, savedTextInput)
            }
        }
    ) { paddingValues ->
        val marginHorizontal = activity.properties.terminalMarginHorizontal.dp
        val marginVertical = activity.properties.terminalMarginVertical.dp
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = marginHorizontal, vertical = marginVertical)
        ) {
            AndroidView(
                factory = {
                    activity.terminalView.apply {
                        post { requestFocus() }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
    }

    // Context Menu Overlay
    ContextMenuOverlay(activity = activity, stateHolder = contextMenuStateHolder)
}

fun setMainContent(
    composeView: ComposeView,
    activity: TermuxActivity,
    savedTextInput: String?,
    stateHolder: MainScreenStateHolder,
    contextMenuStateHolder: ContextMenuStateHolder
) {
    composeView.setContent {
        TermuxTheme {
            TermuxMainScreen(activity, savedTextInput, stateHolder, contextMenuStateHolder)
        }
    }
}
