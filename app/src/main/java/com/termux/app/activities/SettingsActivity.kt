package com.termux.app.activities

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.termux.app.compose.setSettingsContent
import com.termux.shared.activity.media.AppCompatActivityUtils
import com.termux.shared.theme.NightMode

open class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().name, true)

        setSettingsContent(this)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}
