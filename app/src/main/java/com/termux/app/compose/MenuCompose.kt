package com.termux.app.compose

import android.app.Activity
import android.content.Intent
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.app.activities.SettingsActivity
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession
import androidx.compose.ui.text.font.Font
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toShape
import com.termux.R
import android.content.SharedPreferences
import java.io.File
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences

val GoogleSansCodeFamily = FontFamily(
    Font(resId = R.font.google_sans_code)
)

val TermuxTypography = Typography(
    displayLarge = TextStyle(fontFamily = GoogleSansCodeFamily),
    displayMedium = TextStyle(fontFamily = GoogleSansCodeFamily),
    displaySmall = TextStyle(fontFamily = GoogleSansCodeFamily),
    headlineLarge = TextStyle(fontFamily = GoogleSansCodeFamily, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontFamily = GoogleSansCodeFamily, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontFamily = GoogleSansCodeFamily, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontFamily = GoogleSansCodeFamily, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontFamily = GoogleSansCodeFamily, fontWeight = FontWeight.Bold),
    titleSmall = TextStyle(fontFamily = GoogleSansCodeFamily, fontWeight = FontWeight.Bold),
    bodyLarge = TextStyle(fontFamily = GoogleSansCodeFamily),
    bodyMedium = TextStyle(fontFamily = GoogleSansCodeFamily),
    bodySmall = TextStyle(fontFamily = GoogleSansCodeFamily),
    labelLarge = TextStyle(fontFamily = GoogleSansCodeFamily, fontWeight = FontWeight.Bold),
    labelMedium = TextStyle(fontFamily = GoogleSansCodeFamily, fontWeight = FontWeight.Bold),
    labelSmall = TextStyle(fontFamily = GoogleSansCodeFamily, fontWeight = FontWeight.Bold),
)

@Composable
fun rememberPreferenceString(key: String, defaultValue: String): State<String> {
    val context = LocalContext.current
    val prefs = remember { TermuxAppSharedPreferences.build(context, true)?.sharedPreferences }
    val state = remember { mutableStateOf(prefs?.getString(key, defaultValue) ?: defaultValue) }
    
    DisposableEffect(key) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
            if (changedKey == key) {
                state.value = prefs?.getString(key, defaultValue) ?: defaultValue
            }
        }
        prefs?.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            prefs?.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }
    
    return state
}

@Composable
fun TermuxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorMode = rememberPreferenceString("ui_color_scheme_mode", "monet").value
    
    val colorScheme = if (colorMode == "termux") {
        val bgInt = com.termux.terminal.TerminalColors.COLOR_SCHEME.mDefaultColors[com.termux.terminal.TextStyle.COLOR_INDEX_BACKGROUND]
        val fgInt = com.termux.terminal.TerminalColors.COLOR_SCHEME.mDefaultColors[com.termux.terminal.TextStyle.COLOR_INDEX_FOREGROUND]
        val primaryInt = com.termux.terminal.TerminalColors.COLOR_SCHEME.mDefaultColors[com.termux.terminal.TextStyle.COLOR_INDEX_CURSOR]
        
        val bg = Color(bgInt)
        val fg = Color(fgInt)
        val primary = if (primaryInt == 0xffffffff.toInt() || primaryInt == 0xff000000.toInt()) {
            Color(com.termux.terminal.TerminalColors.COLOR_SCHEME.mDefaultColors[21]) // dim blue (color 21: 0xff6495ed)
        } else {
            Color(primaryInt)
        }

        val isDark = com.termux.terminal.TerminalColors.getPerceivedBrightnessOfColor(bgInt) < 130
        
        if (isDark) {
            darkColorScheme(
                primary = primary,
                onPrimary = bg,
                primaryContainer = primary.copy(alpha = 0.2f),
                onPrimaryContainer = fg,
                surface = bg,
                onSurface = fg,
                background = bg,
                onBackground = fg,
                surfaceContainer = bg.copy(alpha = 0.9f),
                surfaceContainerLow = bg.copy(alpha = 0.95f),
                surfaceContainerHigh = Color(android.graphics.Color.rgb(
                    (android.graphics.Color.red(bgInt) + 15).coerceAtMost(255),
                    (android.graphics.Color.green(bgInt) + 15).coerceAtMost(255),
                    (android.graphics.Color.blue(bgInt) + 15).coerceAtMost(255)
                )),
                surfaceContainerHighest = Color(android.graphics.Color.rgb(
                    (android.graphics.Color.red(bgInt) + 30).coerceAtMost(255),
                    (android.graphics.Color.green(bgInt) + 30).coerceAtMost(255),
                    (android.graphics.Color.blue(bgInt) + 30).coerceAtMost(255)
                )),
                onSurfaceVariant = fg.copy(alpha = 0.7f),
                outline = fg.copy(alpha = 0.3f)
            )
        } else {
            lightColorScheme(
                primary = primary,
                onPrimary = bg,
                primaryContainer = primary.copy(alpha = 0.2f),
                onPrimaryContainer = fg,
                surface = bg,
                onSurface = fg,
                background = bg,
                onBackground = fg,
                surfaceContainer = bg.copy(alpha = 0.9f),
                surfaceContainerLow = bg.copy(alpha = 0.95f),
                surfaceContainerHigh = Color(android.graphics.Color.rgb(
                    (android.graphics.Color.red(bgInt) - 15).coerceAtLeast(0),
                    (android.graphics.Color.green(bgInt) - 15).coerceAtLeast(0),
                    (android.graphics.Color.blue(bgInt) - 15).coerceAtLeast(0)
                )),
                surfaceContainerHighest = Color(android.graphics.Color.rgb(
                    (android.graphics.Color.red(bgInt) - 30).coerceAtLeast(0),
                    (android.graphics.Color.green(bgInt) - 30).coerceAtLeast(0),
                    (android.graphics.Color.blue(bgInt) - 30).coerceAtLeast(0)
                )),
                onSurfaceVariant = fg.copy(alpha = 0.7f),
                outline = fg.copy(alpha = 0.3f)
            )
        }
    } else {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }
            darkTheme -> darkColorScheme()
            else -> lightColorScheme()
        }
    }

    val fontChoice = rememberPreferenceString("ui_font_choice", "google_sans_code").value
    
    val uiFontFamily = when (fontChoice) {
        "system" -> FontFamily.Default
        "termux_font" -> {
            val fontFile = File("/data/data/com.termux/files/home/.termux/font.ttf")
            if (fontFile.exists() && fontFile.length() > 0) {
                try {
                    FontFamily(Font(file = fontFile))
                } catch (e: Exception) {
                    FontFamily.Monospace
                }
            } else {
                FontFamily.Monospace
            }
        }
        else -> GoogleSansCodeFamily
    }
    
    val typography = remember(uiFontFamily) {
        Typography(
            displayLarge = TextStyle(fontFamily = uiFontFamily),
            displayMedium = TextStyle(fontFamily = uiFontFamily),
            displaySmall = TextStyle(fontFamily = uiFontFamily),
            headlineLarge = TextStyle(fontFamily = uiFontFamily, fontWeight = FontWeight.Bold),
            headlineMedium = TextStyle(fontFamily = uiFontFamily, fontWeight = FontWeight.Bold),
            headlineSmall = TextStyle(fontFamily = uiFontFamily, fontWeight = FontWeight.Bold),
            titleLarge = TextStyle(fontFamily = uiFontFamily, fontWeight = FontWeight.Bold),
            titleMedium = TextStyle(fontFamily = uiFontFamily, fontWeight = FontWeight.Bold),
            titleSmall = TextStyle(fontFamily = uiFontFamily, fontWeight = FontWeight.Bold),
            bodyLarge = TextStyle(fontFamily = uiFontFamily),
            bodyMedium = TextStyle(fontFamily = uiFontFamily),
            bodySmall = TextStyle(fontFamily = uiFontFamily),
            labelLarge = TextStyle(fontFamily = uiFontFamily, fontWeight = FontWeight.Bold),
            labelMedium = TextStyle(fontFamily = uiFontFamily, fontWeight = FontWeight.Bold),
            labelSmall = TextStyle(fontFamily = uiFontFamily, fontWeight = FontWeight.Bold),
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        content = content
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)
@Composable
fun TermuxDrawerContent(
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
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<TermuxSession?>(null) }
    var renameText by remember { mutableStateOf("") }

    TermuxTheme {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Sessions",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(MaterialShapes.Cookie12Sided.toShape())
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable {
                                activity.startActivity(Intent(activity, SettingsActivity::class.java))
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(sessions) { index, session ->
                        val isSelected = session == currentSession
                        val backgroundColor by animateColorAsState(
                            if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                            animationSpec = spring(stiffness = Spring.StiffnessLow),
                            label = "backgroundColor"
                        )
                        val contentColor by animateColorAsState(
                            if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                            animationSpec = spring(stiffness = Spring.StiffnessLow),
                            label = "contentColor"
                        )

                        var showContextMenu by remember { mutableStateOf(false) }

                        Box {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(backgroundColor)
                                    .combinedClickable(
                                        onClick = { onSessionSelected(session) },
                                        onLongClick = { showContextMenu = true }
                                    )
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "${index + 1}",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                                Spacer(modifier = Modifier.width(16.dp))
                                Column {
                                    val sessionName = session.terminalSession?.mSessionName
                                    val title = session.terminalSession?.title ?: "Terminal"
                                    if (sessionName?.isNotEmpty() == true) {
                                        Text(
                                            text = sessionName,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = contentColor
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = title,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = contentColor.copy(alpha = 0.8f)
                                        )
                                    } else {
                                        Text(
                                            text = title,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = contentColor
                                        )
                                    }
                                }
                            }

                            DropdownMenu(
                                expanded = showContextMenu,
                                onDismissRequest = { showContextMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Kill") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Rounded.Delete,
                                            contentDescription = null,
                                            tint = Color.Red
                                        )
                                    },
                                    onClick = {
                                        showContextMenu = false
                                        onSessionKill(session)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Rename") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Rounded.Edit,
                                            contentDescription = null
                                        )
                                    },
                                    onClick = {
                                        showContextMenu = false
                                        renameTarget = session
                                        renameText = session.terminalSession?.mSessionName ?: ""
                                        showRenameDialog = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Switch to") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Rounded.PushPin,
                                            contentDescription = null
                                        )
                                    },
                                    onClick = {
                                        showContextMenu = false
                                        onSessionSelected(session)
                                    }
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    FilledTonalButton(
                        onClick = onToggleKeyboard,
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Rounded.Keyboard, contentDescription = "Keyboard")
                    }
                    
                    Button(
                        onClick = onNewSession,
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = "New Session")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("New", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }

    if (showRenameDialog && renameTarget != null) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename Session") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Session name") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    renameTarget!!.terminalSession?.mSessionName = renameText
                    showRenameDialog = false
                }) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
            }
        )
    }
}