package com.termux.app.compose

import android.app.Activity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.termux.app.compose.TermuxSettingsScreen

fun setSettingsContent(activity: Activity) {
    val componentActivity = activity as androidx.activity.ComponentActivity
    componentActivity.enableEdgeToEdge()
    componentActivity.setContent {
        TermuxSettingsScreen(activity)
    }
}