package com.termux.app.compose

import android.content.Context
import android.os.Build
import android.view.View
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import com.termux.app.TermuxActivity
import com.termux.shared.termux.extrakeys.ExtraKeyButton
import com.termux.shared.termux.extrakeys.ExtraKeysInfo
import com.termux.shared.termux.extrakeys.ExtraKeysView
import com.termux.shared.termux.extrakeys.SpecialButton
import com.termux.shared.termux.extrakeys.SpecialButtonState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ComposeExtraKeysView(context: Context) : ExtraKeysView(context, null) {
    var stateChangeCounter by mutableStateOf(0)
        private set

    fun notifyStateChanged() {
        stateChangeCounter++
    }

    override fun readSpecialButton(specialButton: SpecialButton?, autoSetInActive: Boolean): Boolean? {
        val activeBefore = getSpecialButtonActive(specialButton)
        val result = super.readSpecialButton(specialButton, autoSetInActive)
        val activeAfter = getSpecialButtonActive(specialButton)
        if (activeBefore != activeAfter) {
            notifyStateChanged()
        }
        return result
    }

    override fun reload(extraKeysInfo: ExtraKeysInfo?, heightPx: Float) {
        super.reload(extraKeysInfo, heightPx)
        notifyStateChanged()
    }

    private fun getSpecialButtonActive(specialButton: SpecialButton?): Boolean {
        if (specialButton == null) return false
        val state = mSpecialButtons?.get(specialButton) ?: return false
        return state.isActive
    }

    fun toggleSpecialButtonActive(buttonKey: String) {
        val specBtn = try { SpecialButton.valueOf(buttonKey) } catch (e: Exception) { null } ?: return
        val state = mSpecialButtons?.get(specBtn) ?: return
        state.isActive = !state.isActive
        if (!state.isActive) {
            state.isLocked = false
        }
        notifyStateChanged()
    }

    fun toggleSpecialButtonLocked(buttonKey: String) {
        val specBtn = try { SpecialButton.valueOf(buttonKey) } catch (e: Exception) { null } ?: return
        val state = mSpecialButtons?.get(specBtn) ?: return
        state.isLocked = !state.isLocked
        state.isActive = state.isLocked
        notifyStateChanged()
    }

    fun isSpecialButtonActive(buttonKey: String): Boolean {
        val specBtn = try { SpecialButton.valueOf(buttonKey) } catch (e: Exception) { null } ?: return false
        val state = mSpecialButtons?.get(specBtn) ?: return false
        return state.isActive
    }

    fun isSpecialButtonLocked(buttonKey: String): Boolean {
        val specBtn = try { SpecialButton.valueOf(buttonKey) } catch (e: Exception) { null } ?: return false
        val state = mSpecialButtons?.get(specBtn) ?: return false
        return state.isLocked
    }
}

@Composable
fun TermuxToolbar(
    activity: TermuxActivity,
    savedTextInput: String?
) {
    val pagerState = rememberPagerState(initialPage = 0) { 2 }
    val scope = rememberCoroutineScope()

    LaunchedEffect(pagerState.currentPage) {
        activity.setToolbarPage(pagerState.currentPage)
        if (pagerState.currentPage == 0) {
            activity.terminalView?.requestFocus()
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth()
        ) { page ->
            if (page == 0) {
                ExtraKeysPage(activity)
            } else {
                TextInputPage(activity, savedTextInput) {
                    scope.launch {
                        pagerState.animateScrollToPage(0)
                    }
                }
            }
        }
    }
}

@Composable
fun ExtraKeysPage(activity: TermuxActivity) {
    val extraKeysView = activity.extraKeysView as? ComposeExtraKeysView ?: return
    val termuxTerminalExtraKeys = activity.termuxTerminalExtraKeys ?: return
    val extraKeysInfo = termuxTerminalExtraKeys.extraKeysInfo ?: return
    val matrix = extraKeysInfo.matrix ?: return

    // Observe stateChangeCounter to trigger recomposition when special buttons update
    val stateCounter = extraKeysView.stateChangeCounter

    val scaleFactor = activity.properties.terminalToolbarHeightScaleFactor
    val baseHeight = 40.dp
    val buttonHeight = baseHeight * scaleFactor

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp)
    ) {
        for (rowButtons in matrix) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                for (buttonInfo in rowButtons) {
                    if (buttonInfo != null) {
                        ExtraKeyButtonView(
                            modifier = Modifier.weight(1f),
                            buttonInfo = buttonInfo,
                            extraKeysView = extraKeysView,
                            activity = activity,
                            buttonHeight = buttonHeight
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ExtraKeyButtonView(
    modifier: Modifier = Modifier,
    buttonInfo: ExtraKeyButton,
    extraKeysView: ComposeExtraKeysView,
    activity: TermuxActivity,
    buttonHeight: androidx.compose.ui.unit.Dp
) {
    val scope = rememberCoroutineScope()
    val view = LocalView.current

    val isSpecial = extraKeysView.isSpecialButton(buttonInfo)
    val isRepetitive = extraKeysView.repetitiveKeys?.contains(buttonInfo.key) == true
    val popup = buttonInfo.popup
    val hasPopup = popup != null

    val longPressTimeout = extraKeysView.longPressTimeout
    val longPressRepeatDelay = extraKeysView.longPressRepeatDelay

    var isPressed by remember { mutableStateOf(false) }
    var showPopup by remember { mutableStateOf(false) }

    val isActive = if (isSpecial) extraKeysView.isSpecialButtonActive(buttonInfo.key) else false
    val isLocked = if (isSpecial) extraKeysView.isSpecialButtonLocked(buttonInfo.key) else false

    val displayLabel = if (activity.properties.shouldExtraKeysTextBeAllCaps()) {
        buttonInfo.display.uppercase()
    } else {
        buttonInfo.display
    }

    Box(
        modifier = modifier
            .padding(horizontal = 2.dp, vertical = 2.dp)
            .height(buttonHeight)
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    isPressed -> MaterialTheme.colorScheme.surfaceDim
                    isLocked -> MaterialTheme.colorScheme.secondaryContainer
                    isActive -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.surfaceContainerHigh
                }
            )
            .pointerInput(buttonInfo) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    isPressed = true
                    showPopup = false
                    val pressTime = System.currentTimeMillis()

                    view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)

                    val job = scope.launch {
                        delay(longPressTimeout.toLong())
                        if (isRepetitive) {
                            while (true) {
                                extraKeysView.extraKeysViewClient?.onExtraKeyButtonClick(null, buttonInfo, null)
                                view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                                delay(longPressRepeatDelay.toLong())
                            }
                        } else if (isSpecial) {
                            extraKeysView.toggleSpecialButtonLocked(buttonInfo.key)
                            view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        }
                    }

                    var isSwipeUp = false
                    val pointerId = down.id

                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == pointerId }
                            if (change == null || !change.pressed) {
                                break
                            }

                            val pos = change.position
                            if (hasPopup && pos.y < -30f) {
                                if (!isSwipeUp) {
                                    isSwipeUp = true
                                    job.cancel()
                                    showPopup = true
                                    view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                                }
                            } else {
                                if (isSwipeUp) {
                                    isSwipeUp = false
                                    showPopup = false
                                }
                            }
                        }
                    } finally {
                        isPressed = false
                        job.cancel()
                        showPopup = false

                        val duration = System.currentTimeMillis() - pressTime
                        if (isSwipeUp) {
                            if (popup != null) {
                                extraKeysView.extraKeysViewClient?.onExtraKeyButtonClick(null, popup, null)
                            }
                        } else {
                            if (isSpecial) {
                                if (duration < longPressTimeout) {
                                    extraKeysView.toggleSpecialButtonActive(buttonInfo.key)
                                }
                            } else {
                                if (!isRepetitive || duration < longPressTimeout) {
                                    extraKeysView.extraKeysViewClient?.onExtraKeyButtonClick(null, buttonInfo, null)
                                }
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = displayLabel,
                color = when {
                    isLocked -> MaterialTheme.colorScheme.onSecondaryContainer
                    isActive -> MaterialTheme.colorScheme.onPrimaryContainer
                    else -> MaterialTheme.colorScheme.onSurface
                },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
            if (isLocked) {
                Spacer(modifier = Modifier.width(2.dp))
                Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = "Locked",
                    modifier = Modifier.size(10.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }

        if (showPopup && popup != null) {
            Popup(
                alignment = Alignment.TopCenter,
                offset = IntOffset(0, -70)
            ) {
                Card(
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = popup.display,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TextInputPage(
    activity: TermuxActivity,
    savedTextInput: String?,
    onBackToKeys: () -> Unit
) {
    var text by remember { mutableStateOf(savedTextInput ?: "") }
    val focusRequester = remember { FocusRequester() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBackToKeys) {
            Icon(
                imageVector = Icons.Rounded.Keyboard,
                contentDescription = "Show Keys",
                tint = MaterialTheme.colorScheme.primary
            )
        }

        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                activity.mToolbarTextInput = it
            },
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester),
            textStyle = MaterialTheme.typography.bodyMedium,
            placeholder = { Text("Send to terminal...", fontSize = 14.sp) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline
            ),
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Send
            ),
            keyboardActions = KeyboardActions(
                onSend = {
                    val session = activity.currentSession
                    if (session != null) {
                        if (session.isRunning) {
                            val textToSend = if (text.isEmpty()) "\r" else text
                            session.write(textToSend)
                        } else {
                            activity.termuxTerminalSessionClient.removeFinishedSession(session)
                        }
                        text = ""
                        activity.mToolbarTextInput = ""
                    }
                }
            )
        )

        Spacer(modifier = Modifier.width(4.dp))

        IconButton(
            onClick = {
                val session = activity.currentSession
                if (session != null) {
                    if (session.isRunning) {
                        val textToSend = if (text.isEmpty()) "\r" else text
                        session.write(textToSend)
                    } else {
                        activity.termuxTerminalSessionClient.removeFinishedSession(session)
                    }
                    text = ""
                    activity.mToolbarTextInput = ""
                }
            }
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.Send,
                contentDescription = "Send",
                tint = MaterialTheme.colorScheme.primary
            )
        }

        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }
    }
}
