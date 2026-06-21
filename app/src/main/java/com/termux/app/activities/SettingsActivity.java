package com.termux.app.activities;

import android.os.Bundle;
import androidx.activity.compose.ComponentActivityKt;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.app.compose.SettingsComposeKt;
import com.termux.shared.activity.media.AppCompatActivityUtils;
import com.termux.shared.theme.NightMode;

public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().getName(), true);

        com.termux.app.compose.SettingsComposeHelperKt.setSettingsContent(this);
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }
}
