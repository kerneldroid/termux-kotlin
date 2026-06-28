package com.termux.app

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.view.ContextMenu
import android.view.ContextMenu.ContextMenuInfo
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import androidx.appcompat.app.AppCompatActivity
import androidx.drawerlayout.widget.DrawerLayout
import com.termux.R
import com.termux.app.activities.HelpActivity
import com.termux.app.activities.SettingsActivity
import com.termux.app.api.file.FileReceiverActivity
import com.termux.app.compose.ContextMenuStateHolder
import com.termux.app.compose.MainScreenStateHolder
import com.termux.app.compose.setMainContent
import com.termux.app.compose.ComposeExtraKeysView
import com.termux.app.terminal.TermuxActivityRootView
import com.termux.app.terminal.TermuxTerminalSessionActivityClient
import com.termux.app.terminal.io.TermuxTerminalExtraKeys
import com.termux.app.terminal.TermuxTerminalViewClient
import com.termux.shared.activities.ReportActivity
import com.termux.shared.activity.ActivityUtils
import com.termux.shared.activity.media.AppCompatActivityUtils
import com.termux.shared.data.IntentUtils
import com.termux.shared.android.PermissionUtils
import com.termux.shared.data.DataUtils
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY
import com.termux.shared.termux.crash.TermuxCrashUtils
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences
import com.termux.shared.termux.extrakeys.ExtraKeysView
import com.termux.shared.termux.interact.TextInputDialogUtils
import com.termux.shared.logger.Logger
import com.termux.shared.termux.TermuxUtils
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties
import com.termux.shared.termux.theme.TermuxThemeUtils
import com.termux.shared.theme.NightMode
import com.termux.shared.view.ViewUtils
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.util.Arrays

/**
 * A terminal emulator activity.
 * <p/>
 * See
 * <ul>
 * <li>http://www.mongrel-phones.com.au/default/how_to_make_a_local_service_and_bind_to_it_in_android</li>
 * <li>https://code.google.com/p/android/issues/detail?id=6426</li>
 * </ul>
 * about memory leaks.
 */
class TermuxActivity : AppCompatActivity(), ServiceConnection {

    /**
     * The connection to the {@link TermuxService}. Requested in {@link #onCreate(Bundle)} with a call to
     * {@link #bindService(Intent, ServiceConnection, int)}, and obtained and stored in
     * {@link #onServiceConnected(ComponentName, IBinder)}.
     */
    @JvmField
    var mTermuxService: TermuxService? = null

    val termuxService: TermuxService?
        get() = mTermuxService

    @JvmName("getTermuxService_compat")
    fun getTermuxService(): TermuxService? = termuxService

    /**
     * The {@link TerminalView} shown in  {@link TermuxActivity} that displays the terminal.
     */
    @JvmField
    var mTerminalView: TerminalView? = null

    val terminalView: TerminalView
        get() = mTerminalView!!

    @JvmName("getTerminalView_compat")
    fun getTerminalView(): TerminalView = terminalView

    /**
     *  The {@link TerminalViewClient} interface implementation to allow for communication between
     *  {@link TerminalView} and {@link TermuxActivity}.
     */
    @JvmField
    var mTermuxTerminalViewClient: TermuxTerminalViewClient? = null

    val termuxTerminalViewClient: TermuxTerminalViewClient
        get() = mTermuxTerminalViewClient!!

    @JvmName("getTermuxTerminalViewClient_compat")
    fun getTermuxTerminalViewClient(): TermuxTerminalViewClient = termuxTerminalViewClient

    /**
     *  The {@link TerminalSessionClient} interface implementation to allow for communication between
     *  {@link TerminalSession} and {@link TermuxActivity}.
     */
    @JvmField
    var mTermuxTerminalSessionActivityClient: TermuxTerminalSessionActivityClient? = null

    val termuxTerminalSessionClient: TermuxTerminalSessionActivityClient
        get() = mTermuxTerminalSessionActivityClient!!

    @JvmName("getTermuxTerminalSessionClient_compat")
    fun getTermuxTerminalSessionClient(): TermuxTerminalSessionActivityClient = termuxTerminalSessionClient

    /**
     * Termux app shared preferences manager.
     */
    @JvmField
    var mPreferences: TermuxAppSharedPreferences? = null

    val preferences: TermuxAppSharedPreferences
        get() = mPreferences!!

    @JvmName("getPreferences_compat")
    fun getPreferences(): TermuxAppSharedPreferences = preferences

    /**
     * Termux app SharedProperties loaded from termux.properties
     */
    @JvmField
    var mProperties: TermuxAppSharedProperties? = null

    val properties: TermuxAppSharedProperties
        get() = mProperties!!

    @JvmName("getProperties_compat")
    fun getProperties(): TermuxAppSharedProperties = properties

    /**
     * The root view of the {@link TermuxActivity}.
     */
    @JvmField
    var mTermuxActivityRootView: TermuxActivityRootView? = null

    val termuxActivityRootView: TermuxActivityRootView
        get() = mTermuxActivityRootView!!

    @JvmName("getTermuxActivityRootView_compat")
    fun getTermuxActivityRootView(): TermuxActivityRootView = termuxActivityRootView

    /**
     * The space at the bottom of {@link @mTermuxActivityRootView} of the {@link TermuxActivity}.
     */
    @JvmField
    var mTermuxActivityBottomSpaceView: View? = null

    val termuxActivityBottomSpaceView: View
        get() = mTermuxActivityBottomSpaceView!!

    @JvmName("getTermuxActivityBottomSpaceView_compat")
    fun getTermuxActivityBottomSpaceView(): View = termuxActivityBottomSpaceView

    /**
     * The terminal extra keys view.
     */
    @JvmField
    var mExtraKeysView: ExtraKeysView? = null

    var extraKeysView: ExtraKeysView
        get() = mExtraKeysView!!
        set(value) {
            mExtraKeysView = value
        }

    @JvmName("getExtraKeysView_compat")
    fun getExtraKeysView(): ExtraKeysView = extraKeysView

    @JvmName("setExtraKeysView_compat")
    fun setExtraKeysView(extraKeysView: ExtraKeysView) {
        this.extraKeysView = extraKeysView
    }

    /**
     * The client for the {@link #mExtraKeysView}.
     */
    @JvmField
    var mTermuxTerminalExtraKeys: TermuxTerminalExtraKeys? = null

    val termuxTerminalExtraKeys: TermuxTerminalExtraKeys
        get() = mTermuxTerminalExtraKeys!!

    @JvmName("getTermuxTerminalExtraKeys_compat")
    fun getTermuxTerminalExtraKeys(): TermuxTerminalExtraKeys = termuxTerminalExtraKeys

    private lateinit var mContextMenuStateHolder: ContextMenuStateHolder
    private lateinit var mMainScreenStateHolder: MainScreenStateHolder

    /**
     * The {@link TermuxActivity} broadcast receiver for various things like terminal style configuration changes.
     */
    private val mTermuxActivityBroadcastReceiver: BroadcastReceiver = TermuxActivityBroadcastReceiver()

    /**
     * The last toast shown, used cancel current toast before showing new in {@link #showToast(String, boolean)}.
     */
    @JvmField
    var mLastToast: Toast? = null

    /**
     * If between onResume() and onStop(). Note that only one session is in the foreground of the terminal view at the
     * time, so if the session causing a change is not in the foreground it should probably be treated as background.
     */
    private var mIsVisible = false

    /**
     * If onResume() was called after onCreate().
     */
    private var mIsOnResumeAfterOnCreate = false

    /**
     * If activity was restarted like due to call to {@link #recreate()} after receiving
     * {@link TERMUX_ACTIVITY#ACTION_RELOAD_STYLE}, system dark night mode was changed or activity
     * was killed by android.
     */
    private var mIsActivityRecreated = false

    /**
     * The {@link TermuxActivity} is in an invalid state and must not be run.
     */
    private var mIsInvalidState = false

    private var mNavBarHeight = 0

    private var mTerminalToolbarDefaultHeight = 0f

    @JvmField
    var mDrawerOpenRunnable: Runnable? = null
    @JvmField
    var mDrawerCloseRunnable: Runnable? = null
    @JvmField
    var mDrawerIsOpenCheck: java.util.concurrent.Callable<Boolean>? = null
    @JvmField
    var mDrawerGesturesEnabledSetter: ((Boolean) -> Unit)? = null

    @JvmField
    var mCurrentToolbarPage = 0
    @JvmField
    var mToolbarTextInput = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        Logger.logDebug(LOG_TAG, "onCreate")
        mIsOnResumeAfterOnCreate = true

        if (savedInstanceState != null) {
            mIsActivityRecreated = savedInstanceState.getBoolean(ARG_ACTIVITY_RECREATED, false)
        }

        // Delete ReportInfo serialized object files from cache older than 14 days
        ReportActivity.deleteReportInfoFilesOlderThanXDays(this, 14, false)

        // Load Termux app SharedProperties from disk
        mProperties = TermuxAppSharedProperties.getProperties()
        reloadProperties()

        setActivityTheme()

        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_termux)

        // Load termux shared preferences
        // This will also fail if TermuxConstants.TERMUX_PACKAGE_NAME does not equal applicationId
        mPreferences = TermuxAppSharedPreferences.build(this, true)
        if (mPreferences == null) {
            // An AlertDialog should have shown to kill the app, so we don't continue running activity code
            mIsInvalidState = true
            return
        }

        mTermuxActivityRootView = TermuxActivityRootView(this)
        mTermuxActivityRootView!!.setActivity(this)
        mTermuxActivityBottomSpaceView = View(this)

        if (properties.isUsingFullScreen()) {
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }

        setTermuxTerminalViewAndClients()

        setTerminalToolbarView(savedInstanceState)

        mContextMenuStateHolder = ContextMenuStateHolder()
        mMainScreenStateHolder = MainScreenStateHolder()

        val composeView = findViewById<androidx.compose.ui.platform.ComposeView>(R.id.activity_termux_compose)
        if (composeView != null) {
            var savedTextInput: String? = null
            if (savedInstanceState != null) {
                savedTextInput = savedInstanceState.getString(ARG_TERMINAL_TOOLBAR_TEXT_INPUT)
                mToolbarTextInput = savedTextInput ?: ""
            }
            setMainContent(
                composeView,
                this,
                savedTextInput,
                mMainScreenStateHolder,
                mContextMenuStateHolder
            )
        }

        mTerminalView?.setOnContextMenuShowListener(object : TerminalView.OnContextMenuShowListener {
            override fun onShowContextMenu(view: View): Boolean {
                val currentSession = currentSession ?: return true

                mContextMenuStateHolder.pid = currentSession.pid
                mContextMenuStateHolder.isSessionRunning = currentSession.isRunning
                mContextMenuStateHolder.selectedText = mTerminalView?.getStoredSelectedText() ?: ""
                mContextMenuStateHolder.isAutoFillEnabled = mTerminalView?.isAutoFillEnabled() == true
                mContextMenuStateHolder.isKeepScreenOn = mPreferences?.shouldKeepScreenOn() == true
                mContextMenuStateHolder.isVisible = true
                return true
            }
        })

        FileReceiverActivity.updateFileReceiverActivityComponentsState(this)

        try {
            // Start the {@link TermuxService} and make it run regardless of who is bound to it
            val serviceIntent = Intent(this, TermuxService::class.java)
            startService(serviceIntent)

            // Attempt to bind to the service, this will call the {@link #onServiceConnected(ComponentName, IBinder)}
            // callback if it succeeds.
            if (!bindService(serviceIntent, this, 0)) {
                throw RuntimeException("bindService() failed")
            }
        } catch (e: Exception) {
            Logger.logStackTraceWithMessage(LOG_TAG, "TermuxActivity failed to start TermuxService", e)
            Logger.showToast(
                this,
                getString(
                    if (e.message != null && e.message!!.contains("app is in background")) {
                        R.string.error_termux_service_start_failed_bg
                    } else {
                        R.string.error_termux_service_start_failed_general
                    }
                ),
                true
            )
            mIsInvalidState = true
            return
        }

        // Send the {@link TermuxConstants#BROADCAST_TERMUX_OPENED} broadcast to notify apps that Termux
        // app has been opened.
        TermuxUtils.sendTermuxOpenedBroadcast(this)
    }

    override fun onStart() {
        super.onStart()

        Logger.logDebug(LOG_TAG, "onStart")

        if (mIsInvalidState) return

        mIsVisible = true

        mTermuxTerminalSessionActivityClient?.onStart()

        mTermuxTerminalViewClient?.onStart()

        if (preferences.isTerminalMarginAdjustmentEnabled()) {
            addTermuxActivityRootViewGlobalLayoutListener()
        }

        registerTermuxActivityBroadcastReceiver()
    }

    override fun onResume() {
        super.onResume()

        Logger.logVerbose(LOG_TAG, "onResume")

        if (mIsInvalidState) return

        mTermuxTerminalSessionActivityClient?.onResume()

        mTermuxTerminalViewClient?.onResume()

        // Check if a crash happened on last run of the app or if a plugin crashed and show a
        // notification with the crash details if it did
        TermuxCrashUtils.notifyAppCrashFromCrashLogFile(this, LOG_TAG)

        mIsOnResumeAfterOnCreate = false
    }

    override fun onStop() {
        super.onStop()

        Logger.logDebug(LOG_TAG, "onStop")

        if (mIsInvalidState) return

        mIsVisible = false

        mTermuxTerminalSessionActivityClient?.onStop()

        mTermuxTerminalViewClient?.onStop()

        removeTermuxActivityRootViewGlobalLayoutListener()

        unregisterTermuxActivityBroadcastReceiver()
        closeDrawer()
    }

    override fun onDestroy() {
        super.onDestroy()

        Logger.logDebug(LOG_TAG, "onDestroy")

        if (mIsInvalidState) return

        mTermuxService?.let { service ->
            // Do not leave service and session clients with references to activity.
            service.unsetTermuxTerminalSessionClient()
            mTermuxService = null
        }

        try {
            unbindService(this)
        } catch (e: Exception) {
            // ignore.
        }
    }

    override fun onSaveInstanceState(savedInstanceState: Bundle) {
        Logger.logVerbose(LOG_TAG, "onSaveInstanceState")

        super.onSaveInstanceState(savedInstanceState)
        saveTerminalToolbarTextInput(savedInstanceState)
        savedInstanceState.putBoolean(ARG_ACTIVITY_RECREATED, true)
    }

    /**
     * Part of the {@link ServiceConnection} interface. The service is bound with
     * {@link #bindService(Intent, ServiceConnection, int)} in {@link #onCreate(Bundle)} which will cause a call to this
     * callback method.
     */
    override fun onServiceConnected(componentName: ComponentName, service: IBinder) {
        Logger.logDebug(LOG_TAG, "onServiceConnected")

        mTermuxService = (service as TermuxService.LocalBinder).service

        setTermuxSessionsListView()

        val intent = intent
        setIntent(null)

        val termuxService = mTermuxService ?: return

        if (termuxService.isTermuxSessionsEmpty()) {
            if (mIsVisible) {
                TermuxInstaller.setupBootstrapIfNeeded(this) {
                    if (mTermuxService == null) return@setupBootstrapIfNeeded // Activity might have been destroyed.
                    try {
                        var launchFailsafe = false
                        if (intent != null && intent.extras != null) {
                            launchFailsafe = intent.extras!!.getBoolean(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false)
                        }
                        mTermuxTerminalSessionActivityClient?.addNewSession(launchFailsafe, null)
                    } catch (e: WindowManager.BadTokenException) {
                        // Activity finished - ignore.
                    }
                }
            } else {
                // The service connected while not in foreground - just bail out.
                finishActivityIfNotFinishing()
            }
        } else {
            // If termux was started from launcher "New session" shortcut and activity is recreated,
            // then the original intent will be re-delivered, resulting in a new session being re-added
            // each time.
            if (!mIsActivityRecreated && intent != null && Intent.ACTION_RUN == intent.action) {
                // Android 7.1 app shortcut from res/xml/shortcuts.xml.
                val isFailSafe = intent.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false)
                mTermuxTerminalSessionActivityClient?.addNewSession(isFailSafe, null)
            } else {
                mTermuxTerminalSessionActivityClient?.let { client ->
                    client.setCurrentSession(client.getCurrentStoredSessionOrLast())
                }
            }
        }

        // Update the {@link TerminalSession} and {@link TerminalEmulator} clients.
        termuxService.setTermuxTerminalSessionClient(termuxTerminalSessionClient)
    }

    override fun onServiceDisconnected(name: ComponentName) {
        Logger.logDebug(LOG_TAG, "onServiceDisconnected")

        // Respect being stopped from the {@link TermuxService} notification action.
        finishActivityIfNotFinishing()
    }

    private fun reloadProperties() {
        mProperties?.loadTermuxPropertiesFromDisk()
        mTermuxTerminalViewClient?.onReloadProperties()
    }

    private fun setActivityTheme() {
        // Update NightMode.APP_NIGHT_MODE
        TermuxThemeUtils.setAppNightMode(properties.nightMode)

        // Set activity night mode. If NightMode.SYSTEM is set, then android will automatically
        // trigger recreation of activity when uiMode/dark mode configuration is changed so that
        // day or night theme takes affect.
        AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().name, true)
    }

    private fun setMargins() {
        // No-op because Compose handles terminal margins dynamically inside TermuxMainScreen
    }

    fun addTermuxActivityRootViewGlobalLayoutListener() {
        // No-op because Compose handles keyboard resizing natively with .imePadding()
    }

    fun removeTermuxActivityRootViewGlobalLayoutListener() {
        // No-op
    }

    private fun setTermuxTerminalViewAndClients() {
        // Set termux terminal view and session clients
        mTermuxTerminalSessionActivityClient = TermuxTerminalSessionActivityClient(this)
        mTermuxTerminalViewClient = TermuxTerminalViewClient(this, mTermuxTerminalSessionActivityClient!!)

        // Set termux terminal view programmatically
        mTerminalView = TerminalView(this, null).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setTerminalViewClient(termuxTerminalViewClient)
        }

        mTermuxTerminalViewClient?.onCreate()
        mTermuxTerminalSessionActivityClient?.onCreate()
    }

    private fun setTermuxSessionsListView() {
        val mainScreenStateHolder = mMainScreenStateHolder
        val termuxService = mTermuxService
        if (mainScreenStateHolder != null && termuxService != null) {
            mainScreenStateHolder.sessions.clear()
            mainScreenStateHolder.sessions.addAll(termuxService.getTermuxSessions())
            mainScreenStateHolder.currentSession = termuxService.getTermuxSessionForTerminalSession(currentSession)
        }
    }

    private fun setTerminalToolbarView(savedInstanceState: Bundle?) {
        mTermuxTerminalExtraKeys = TermuxTerminalExtraKeys(
            this, terminalView,
            mTermuxTerminalViewClient, mTermuxTerminalSessionActivityClient
        )

        mExtraKeysView = ComposeExtraKeysView(this).apply {
            extraKeysViewClient = termuxTerminalExtraKeys
            buttonTextAllCaps = properties.shouldExtraKeysTextBeAllCaps()
            reload(termuxTerminalExtraKeys.extraKeysInfo, mTerminalToolbarDefaultHeight)
        }

        var savedTextInput: String? = null
        if (savedInstanceState != null) {
            savedTextInput = savedInstanceState.getString(ARG_TERMINAL_TOOLBAR_TEXT_INPUT)
            mToolbarTextInput = savedTextInput ?: ""
        }
    }

    private fun setTerminalToolbarHeight() {
        // Compose uses wrap_content and dynamically adjusts height to its children
    }

    fun toggleTerminalToolbar() {
        val showNow = mPreferences?.toogleShowTerminalToolbar() == true
        showToast(
            if (showNow) getString(R.string.msg_enabling_terminal_toolbar) else getString(R.string.msg_disabling_terminal_toolbar),
            true
        )
    }

    private fun saveTerminalToolbarTextInput(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) return
        if (mToolbarTextInput.isNotEmpty()) {
            savedInstanceState.putString(ARG_TERMINAL_TOOLBAR_TEXT_INPUT, mToolbarTextInput)
        }
    }

    @SuppressLint("RtlHardcoded")
    override fun onBackPressed() {
        if (isDrawerOpen) {
            closeDrawer()
        } else {
            finishActivityIfNotFinishing()
        }
    }

    fun finishActivityIfNotFinishing() {
        // prevent duplicate calls to finish() if called from multiple places
        if (!isFinishing) {
            finish()
        }
    }

    /** Show a toast and dismiss the last one if still visible. */
    fun showToast(text: String?, longDuration: Boolean) {
        if (text.isNullOrEmpty()) return
        mLastToast?.cancel()
        mLastToast = Toast.makeText(this, text, if (longDuration) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).apply {
            setGravity(Gravity.TOP, 0, 0)
            show()
        }
    }

    override fun onCreateContextMenu(menu: ContextMenu, v: View, menuInfo: ContextMenuInfo?) {
        val currentSession = currentSession ?: return
        val autoFillEnabled = mTerminalView?.isAutoFillEnabled() == true

        menu.add(Menu.NONE, CONTEXT_MENU_SELECT_URL_ID, Menu.NONE, R.string.action_select_url)
        menu.add(Menu.NONE, CONTEXT_MENU_SHARE_TRANSCRIPT_ID, Menu.NONE, R.string.action_share_transcript)
        if (!DataUtils.isNullOrEmpty(mTerminalView?.getStoredSelectedText())) {
            menu.add(Menu.NONE, CONTEXT_MENU_SHARE_SELECTED_TEXT, Menu.NONE, R.string.action_share_selected_text)
        }
        if (autoFillEnabled) {
            menu.add(Menu.NONE, CONTEXT_MENU_AUTOFILL_USERNAME, Menu.NONE, R.string.action_autofill_username)
        }
        if (autoFillEnabled) {
            menu.add(Menu.NONE, CONTEXT_MENU_AUTOFILL_PASSWORD, Menu.NONE, R.string.action_autofill_password)
        }
        menu.add(Menu.NONE, CONTEXT_MENU_RESET_TERMINAL_ID, Menu.NONE, R.string.action_reset_terminal)
        menu.add(
            Menu.NONE,
            CONTEXT_MENU_KILL_PROCESS_ID,
            Menu.NONE,
            resources.getString(R.string.action_kill_process, currentSession.pid)
        ).isEnabled = currentSession.isRunning
        menu.add(Menu.NONE, CONTEXT_MENU_STYLING_ID, Menu.NONE, R.string.action_style_terminal)
        menu.add(Menu.NONE, CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON, Menu.NONE, R.string.action_toggle_keep_screen_on)
            .setCheckable(true)
            .setChecked(mPreferences?.shouldKeepScreenOn() == true)
        menu.add(Menu.NONE, CONTEXT_MENU_HELP_ID, Menu.NONE, R.string.action_open_help)
        menu.add(Menu.NONE, CONTEXT_MENU_SETTINGS_ID, Menu.NONE, R.string.action_open_settings)
        menu.add(Menu.NONE, CONTEXT_MENU_REPORT_ID, Menu.NONE, R.string.action_report_issue)
    }

    /** Hook system menu to show context menu instead. */
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        mTerminalView?.showContextMenu()
        return false
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        val session = currentSession

        return when (item.itemId) {
            CONTEXT_MENU_SELECT_URL_ID -> {
                mTermuxTerminalViewClient?.showUrlSelection()
                true
            }
            CONTEXT_MENU_SHARE_TRANSCRIPT_ID -> {
                mTermuxTerminalViewClient?.shareSessionTranscript()
                true
            }
            CONTEXT_MENU_SHARE_SELECTED_TEXT -> {
                mTermuxTerminalViewClient?.shareSelectedText()
                true
            }
            CONTEXT_MENU_AUTOFILL_USERNAME -> {
                mTerminalView?.requestAutoFillUsername()
                true
            }
            CONTEXT_MENU_AUTOFILL_PASSWORD -> {
                mTerminalView?.requestAutoFillPassword()
                true
            }
            CONTEXT_MENU_RESET_TERMINAL_ID -> {
                onResetTerminalSession(session)
                true
            }
            CONTEXT_MENU_KILL_PROCESS_ID -> {
                showKillSessionDialog(session)
                true
            }
            CONTEXT_MENU_STYLING_ID -> {
                showStylingDialog()
                true
            }
            CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON -> {
                toggleKeepScreenOn()
                true
            }
            CONTEXT_MENU_HELP_ID -> {
                ActivityUtils.startActivity(this, Intent(this, HelpActivity::class.java))
                true
            }
            CONTEXT_MENU_SETTINGS_ID -> {
                ActivityUtils.startActivity(this, Intent(this, SettingsActivity::class.java))
                true
            }
            CONTEXT_MENU_REPORT_ID -> {
                mTermuxTerminalViewClient?.reportIssueFromTranscript()
                true
            }
            else -> super.onContextItemSelected(item)
        }
    }

    override fun onContextMenuClosed(menu: Menu) {
        super.onContextMenuClosed(menu)
        // onContextMenuClosed() is triggered twice if back button is pressed to dismiss instead of tap for some reason
        mTerminalView?.onContextMenuClosed(menu)
    }

    fun showKillSessionDialog(session: TerminalSession?) {
        if (session == null) return

        AlertDialog.Builder(this).apply {
            setIcon(android.R.drawable.ic_dialog_alert)
            setMessage(R.string.title_confirm_kill_process)
            setPositiveButton(android.R.string.yes) { dialog, _ ->
                dialog.dismiss()
                session.finishIfRunning()
            }
            setNegativeButton(android.R.string.no, null)
            show()
        }
    }

    fun onResetTerminalSession(session: TerminalSession?) {
        if (session != null) {
            session.reset()
            showToast(resources.getString(R.string.msg_terminal_reset), true)

            mTermuxTerminalSessionActivityClient?.onResetTerminalSession()
        }
    }

    fun showStylingDialog() {
        val stylingIntent = Intent().apply {
            setClassName(
                TermuxConstants.TERMUX_STYLING_PACKAGE_NAME,
                TermuxConstants.TERMUX_STYLING_APP.TERMUX_STYLING_ACTIVITY_NAME
            )
        }
        try {
            startActivity(stylingIntent)
        } catch (e: Exception) {
            // The startActivity() call is not documented to throw IllegalArgumentException.
            // However, crash reporting shows that it sometimes does, so catch it here.
            AlertDialog.Builder(this)
                .setMessage(getString(R.string.error_styling_not_installed))
                .setPositiveButton(R.string.action_styling_install) { _, _ ->
                    ActivityUtils.startActivity(
                        this,
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(TermuxConstants.TERMUX_STYLING_FDROID_PACKAGE_URL)
                        )
                    )
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    fun toggleKeepScreenOn() {
        val terminalView = mTerminalView ?: return
        val preferences = mPreferences ?: return
        if (terminalView.keepScreenOn) {
            terminalView.keepScreenOn = false
            preferences.setKeepScreenOn(false)
        } else {
            terminalView.keepScreenOn = true
            preferences.setKeepScreenOn(true)
        }
    }

    /**
     * For processes to access primary external storage (/sdcard, /storage/emulated/0, ~/storage/shared),
     * termux needs to be granted legacy WRITE_EXTERNAL_STORAGE or MANAGE_EXTERNAL_STORAGE permissions
     * if targeting targetSdkVersion 30 (android 11) and running on sdk 30 (android 11) and higher.
     */
    fun requestStoragePermission(isPermissionCallback: Boolean) {
        Thread {
            // Do not ask for permission again
            val requestCode = if (isPermissionCallback) -1 else PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION

            // If permission is granted, then also setup storage symlinks.
            if (PermissionUtils.checkAndRequestLegacyOrManageExternalStoragePermission(
                    this@TermuxActivity, requestCode, !isPermissionCallback
                )
            ) {
                if (isPermissionCallback) {
                    Logger.logInfoAndShowToast(
                        this@TermuxActivity, LOG_TAG,
                        getString(com.termux.shared.R.string.msg_storage_permission_granted_on_request)
                    )
                }

                TermuxInstaller.setupStorageSymlinks(this@TermuxActivity)
            } else {
                if (isPermissionCallback) {
                    Logger.logInfoAndShowToast(
                        this@TermuxActivity, LOG_TAG,
                        getString(com.termux.shared.R.string.msg_storage_permission_not_granted_on_request)
                    )
                }
            }
        }.start()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Logger.logVerbose(
            LOG_TAG,
            "onActivityResult: requestCode: $requestCode, resultCode: $resultCode, data: " + IntentUtils.getIntentString(
                data
            )
        )
        if (requestCode == PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION) {
            requestStoragePermission(true)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Logger.logVerbose(
            LOG_TAG,
            "onRequestPermissionsResult: requestCode: $requestCode, permissions: " + Arrays.toString(
                permissions
            ) + ", grantResults: " + Arrays.toString(grantResults)
        )
        if (requestCode == PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION) {
            requestStoragePermission(true)
        }
    }

    val navBarHeight: Int
        get() = mNavBarHeight

    @JvmName("getNavBarHeight_compat")
    fun getNavBarHeight(): Int = navBarHeight

    val terminalToolbarDefaultHeight: Float
        get() = mTerminalToolbarDefaultHeight

    @JvmName("getTerminalToolbarDefaultHeight_compat")
    fun getTerminalToolbarDefaultHeight(): Float = terminalToolbarDefaultHeight

    fun openDrawer() {
        mDrawerOpenRunnable?.run()
    }

    fun closeDrawer() {
        mDrawerCloseRunnable?.run()
    }

    val isDrawerOpen: Boolean
        get() {
            return try {
                mDrawerIsOpenCheck != null && mDrawerIsOpenCheck!!.call()
            } catch (e: Exception) {
                false
            }
        }

    @JvmName("isDrawerOpen_compat")
    fun isDrawerOpen(): Boolean = isDrawerOpen

    fun setDrawerGesturesEnabled(enabled: Boolean) {
        mDrawerGesturesEnabledSetter?.invoke(enabled)
    }

    fun getDrawer(): DrawerLayout? {
        return null
    }

    fun setToolbarPage(page: Int) {
        mCurrentToolbarPage = page
    }

    fun isTerminalViewSelected(): Boolean {
        return mCurrentToolbarPage == 0
    }

    fun isTerminalToolbarTextInputViewSelected(): Boolean {
        return mCurrentToolbarPage == 1
    }

    fun termuxSessionListNotifyUpdated() {
        setTermuxSessionsListView()
    }

    val isVisible: Boolean
        get() = mIsVisible

    @JvmName("isVisible_compat")
    fun isVisible(): Boolean = isVisible

    val isOnResumeAfterOnCreate: Boolean
        get() = mIsOnResumeAfterOnCreate

    @JvmName("isOnResumeAfterOnCreate_compat")
    fun isOnResumeAfterOnCreate(): Boolean = isOnResumeAfterOnCreate

    val isActivityRecreated: Boolean
        get() = mIsActivityRecreated

    @JvmName("isActivityRecreated_compat")
    fun isActivityRecreated(): Boolean = isActivityRecreated

    val currentSession: TerminalSession?
        get() = mTerminalView?.currentSession

    @JvmName("getCurrentSession_compat")
    fun getCurrentSession(): TerminalSession? = currentSession

    private fun fixTermuxActivityBroadcastReceiverIntent(intent: Intent?) {
        if (intent == null) return

        val extraReloadStyle = intent.getStringExtra(TERMUX_ACTIVITY.EXTRA_RELOAD_STYLE)
        if ("storage" == extraReloadStyle) {
            intent.removeExtra(TERMUX_ACTIVITY.EXTRA_RELOAD_STYLE)
            intent.action = TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS
        }
    }

    private fun registerTermuxActivityBroadcastReceiver() {
        val intentFilter = IntentFilter().apply {
            addAction(TERMUX_ACTIVITY.ACTION_NOTIFY_APP_CRASH)
            addAction(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE)
            addAction(TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS)
        }

        registerReceiver(mTermuxActivityBroadcastReceiver, intentFilter)
    }

    private fun unregisterTermuxActivityBroadcastReceiver() {
        unregisterReceiver(mTermuxActivityBroadcastReceiver)
    }

    inner class TermuxActivityBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent == null) return

            if (mIsVisible) {
                fixTermuxActivityBroadcastReceiverIntent(intent)

                when (intent.action) {
                    TERMUX_ACTIVITY.ACTION_NOTIFY_APP_CRASH -> {
                        Logger.logDebug(LOG_TAG, "Received intent to notify app crash")
                        TermuxCrashUtils.notifyAppCrashFromCrashLogFile(context, LOG_TAG)
                    }
                    TERMUX_ACTIVITY.ACTION_RELOAD_STYLE -> {
                        Logger.logDebug(LOG_TAG, "Received intent to reload styling")
                        reloadActivityStyling(
                            intent.getBooleanExtra(
                                TERMUX_ACTIVITY.EXTRA_RECREATE_ACTIVITY,
                                true
                            )
                        )
                    }
                    TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS -> {
                        Logger.logDebug(LOG_TAG, "Received intent to request storage permissions")
                        requestStoragePermission(false)
                    }
                }
            }
        }
    }

    private fun reloadActivityStyling(recreateActivity: Boolean) {
        if (mProperties != null) {
            reloadProperties()

            mExtraKeysView?.apply {
                buttonTextAllCaps = properties.shouldExtraKeysTextBeAllCaps()
                reload(termuxTerminalExtraKeys.extraKeysInfo, mTerminalToolbarDefaultHeight)
            }

            // Update NightMode.APP_NIGHT_MODE
            TermuxThemeUtils.setAppNightMode(properties.nightMode)
        }

        setMargins()
        setTerminalToolbarHeight()

        FileReceiverActivity.updateFileReceiverActivityComponentsState(this)

        mTermuxTerminalSessionActivityClient?.onReloadActivityStyling()

        mTermuxTerminalViewClient?.onReloadActivityStyling()

        // To change the activity and drawer theme, activity needs to be recreated.
        // It will destroy the activity, including all stored variables and views, and onCreate()
        // will be called again. Extra keys input text, terminal sessions and transcripts will be preserved.
        if (recreateActivity) {
            Logger.logDebug(LOG_TAG, "Recreating activity")
            this@TermuxActivity.recreate()
        }
    }

    companion object {
        private const val CONTEXT_MENU_SELECT_URL_ID = 0
        private const val CONTEXT_MENU_SHARE_TRANSCRIPT_ID = 1
        private const val CONTEXT_MENU_SHARE_SELECTED_TEXT = 10
        private const val CONTEXT_MENU_AUTOFILL_USERNAME = 11
        private const val CONTEXT_MENU_AUTOFILL_PASSWORD = 2
        private const val CONTEXT_MENU_RESET_TERMINAL_ID = 3
        private const val CONTEXT_MENU_KILL_PROCESS_ID = 4
        private const val CONTEXT_MENU_STYLING_ID = 5
        private const val CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON = 6
        private const val CONTEXT_MENU_HELP_ID = 7
        private const val CONTEXT_MENU_SETTINGS_ID = 8
        private const val CONTEXT_MENU_REPORT_ID = 9

        private const val ARG_TERMINAL_TOOLBAR_TEXT_INPUT = "terminal_toolbar_text_input"
        private const val ARG_ACTIVITY_RECREATED = "activity_recreated"

        private const val LOG_TAG = "TermuxActivity"

        @JvmStatic
        fun updateTermuxActivityStyling(context: Context, recreateActivity: Boolean) {
            // Make sure that terminal styling is always applied.
            val stylingIntent = Intent(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE).apply {
                putExtra(TERMUX_ACTIVITY.EXTRA_RECREATE_ACTIVITY, recreateActivity)
            }
            context.sendBroadcast(stylingIntent)
        }

        @JvmStatic
        fun startTermuxActivity(context: Context) {
            ActivityUtils.startActivity(context, newInstance(context))
        }

        @JvmStatic
        fun newInstance(context: Context): Intent {
            return Intent(context, TermuxActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        }
    }
}
