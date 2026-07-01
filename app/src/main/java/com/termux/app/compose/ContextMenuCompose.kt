package com.termux.app.compose

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.termux.app.TermuxActivity
import com.termux.app.activities.HelpActivity
import com.termux.app.activities.SettingsActivity

class ContextMenuStateHolder {
    var isVisible by mutableStateOf(false)
    var pid by mutableStateOf(0)
    var isSessionRunning by mutableStateOf(false)
    var selectedText by mutableStateOf("")
    var isAutoFillEnabled by mutableStateOf(false)
    var isKeepScreenOn by mutableStateOf(false)
}

@Composable
fun ContextMenuOverlay(
    activity: TermuxActivity,
    stateHolder: ContextMenuStateHolder
) {
    if (!stateHolder.isVisible) return

    Dialog(onDismissRequest = { stateHolder.isVisible = false }) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp)
                .wrapContentHeight(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    text = "Terminal Actions",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp, start = 8.dp)
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Plaque 1: Sharing & Clipboard
                    item {
                        ActionGroupCard {
                            ContextMenuItem(
                                icon = Icons.Rounded.Link,
                                label = "Select URL",
                                onClick = {
                                    stateHolder.isVisible = false
                                    activity.mTermuxTerminalViewClient?.showUrlSelection()
                                }
                            )
                            ContextMenuItem(
                                icon = Icons.Rounded.Share,
                                label = "Share transcript",
                                onClick = {
                                    stateHolder.isVisible = false
                                    activity.mTermuxTerminalViewClient?.shareSessionTranscript()
                                }
                            )
                            if (stateHolder.selectedText.isNotEmpty()) {
                                ContextMenuItem(
                                    icon = Icons.Rounded.ContentCopy,
                                    label = "Share selected text",
                                    onClick = {
                                        stateHolder.isVisible = false
                                        activity.mTermuxTerminalViewClient?.shareSelectedText()
                                    }
                                )
                            }
                        }
                    }

                    // Plaque 2: Autofill (if enabled)
                    if (stateHolder.isAutoFillEnabled) {
                        item {
                            ActionGroupCard {
                                ContextMenuItem(
                                    icon = Icons.Rounded.Person,
                                    label = "Autofill username",
                                    onClick = {
                                        stateHolder.isVisible = false
                                        activity.terminalView?.requestAutoFillUsername()
                                    }
                                )
                                ContextMenuItem(
                                    icon = Icons.Rounded.VpnKey,
                                    label = "Autofill password",
                                    onClick = {
                                        stateHolder.isVisible = false
                                        activity.terminalView?.requestAutoFillPassword()
                                    }
                                )
                            }
                        }
                    }

                    // Plaque 3: Terminal Control
                    item {
                        ActionGroupCard {
                            ContextMenuItem(
                                icon = Icons.Rounded.RestartAlt,
                                label = "Reset terminal",
                                onClick = {
                                    stateHolder.isVisible = false
                                    activity.onResetTerminalSession(activity.currentSession)
                                }
                            )
                            ContextMenuItem(
                                icon = Icons.Rounded.Cancel,
                                label = if (stateHolder.pid > 0) "Kill process (${stateHolder.pid})" else "Kill process",
                                enabled = stateHolder.isSessionRunning,
                                onClick = {
                                    stateHolder.isVisible = false
                                    activity.showKillSessionDialog(activity.currentSession)
                                }
                            )
                            ContextMenuItem(
                                icon = Icons.Rounded.LightMode,
                                label = "Keep screen on",
                                onClick = {
                                    activity.toggleKeepScreenOn()
                                    stateHolder.isKeepScreenOn = !stateHolder.isKeepScreenOn
                                },
                                trailingContent = {
                                    Switch(
                                        checked = stateHolder.isKeepScreenOn,
                                        onCheckedChange = {
                                            activity.toggleKeepScreenOn()
                                            stateHolder.isKeepScreenOn = it
                                        }
                                    )
                                }
                            )
                        }
                    }

                    // Plaque 4: Configuration & Support
                    item {
                        ActionGroupCard {
                            ContextMenuItem(
                                icon = Icons.Rounded.Palette,
                                label = "Style",
                                onClick = {
                                    stateHolder.isVisible = false
                                    activity.showStylingDialog()
                                }
                            )
                            ContextMenuItem(
                                icon = Icons.Rounded.Help,
                                label = "Help",
                                onClick = {
                                    stateHolder.isVisible = false
                                    activity.startActivity(Intent(activity, HelpActivity::class.java))
                                }
                            )
                            ContextMenuItem(
                                icon = Icons.Rounded.Settings,
                                label = "Settings",
                                onClick = {
                                    stateHolder.isVisible = false
                                    activity.startActivity(Intent(activity, SettingsActivity::class.java))
                                }
                            )
                            ContextMenuItem(
                                icon = Icons.Rounded.BugReport,
                                label = "Report Issue",
                                onClick = {
                                    stateHolder.isVisible = false
                                    activity.mTermuxTerminalViewClient?.reportIssueFromTranscript()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ActionGroupCard(
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            content = content
        )
    }
}

@Composable
fun ContextMenuItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    trailingContent: @Composable (() -> Unit)? = null
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                )
            }
            if (trailingContent != null) {
                trailingContent()
            }
        }
    }
}
