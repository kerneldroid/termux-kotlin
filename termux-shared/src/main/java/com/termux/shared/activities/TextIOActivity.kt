package com.termux.shared.activities

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.termux.shared.R
import com.termux.shared.interact.ShareUtils
import com.termux.shared.logger.Logger
import com.termux.shared.models.TextIOInfo
import com.termux.shared.view.KeyboardUtils
import java.util.Locale

/**
 * An activity to edit or view text based on config passed as [TextIOInfo].
 *
 * Add Following to `AndroidManifest.xml` to use in an app:
 *
 * ` <activity android:name="com.termux.shared.activities.TextIOActivity" android:theme="@style/Theme.AppCompat.TermuxTextIOActivity" />`
 */
class TextIOActivity : AppCompatActivity() {

    private var mTextIOLabel: TextView? = null
    private var mTextIOLabelSeparator: View? = null
    private var mTextIOText: EditText? = null
    private var mTextIOHorizontalScrollView: HorizontalScrollView? = null
    private var mTextIOTextLinearLayout: LinearLayout? = null
    private var mTextIOTextCharacterUsage: TextView? = null

    private var mTextIOInfo: TextIOInfo? = null
    private var mBundle: Bundle? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.logVerbose(LOG_TAG, "onCreate")

        setContentView(R.layout.activity_text_io)

        mTextIOLabel = findViewById(R.id.text_io_label)
        mTextIOLabelSeparator = findViewById(R.id.text_io_label_separator)
        mTextIOText = findViewById(R.id.text_io_text)
        mTextIOHorizontalScrollView = findViewById(R.id.text_io_horizontal_scroll_view)
        mTextIOTextLinearLayout = findViewById(R.id.text_io_text_linear_layout)
        mTextIOTextCharacterUsage = findViewById(R.id.text_io_text_character_usage)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        if (toolbar != null) {
            setSupportActionBar(toolbar)
        }

        mBundle = null
        val intent = intent
        if (intent != null) {
            mBundle = intent.extras
        } else if (savedInstanceState != null) {
            mBundle = savedInstanceState
        }

        updateUI()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        Logger.logVerbose(LOG_TAG, "onNewIntent")

        // Views must be re-created since different configs for isEditingTextDisabled() and
        // isHorizontallyScrollable() will not work or at least reliably
        finish()
        if (intent != null) {
            startActivity(intent)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun updateUI() {
        val bundle = mBundle
        if (bundle == null) {
            finish()
            return
        }

        @Suppress("DEPRECATION")
        mTextIOInfo = bundle.getSerializable(EXTRA_TEXT_IO_INFO_OBJECT) as? TextIOInfo
        val textIOInfo = mTextIOInfo
        if (textIOInfo == null) {
            finish()
            return
        }

        val actionBar = supportActionBar
        if (actionBar != null) {
            if (textIOInfo.title != null) {
                actionBar.title = textIOInfo.title
            } else {
                actionBar.title = "Text Input"
            }

            if (textIOInfo.shouldShowBackButtonInActionBar()) {
                actionBar.setDisplayHomeAsUpEnabled(true)
                actionBar.setDisplayShowHomeEnabled(true)
            }
        }

        mTextIOLabel?.visibility = View.GONE
        mTextIOLabelSeparator?.visibility = View.GONE
        if (textIOInfo.isLabelEnabled) {
            mTextIOLabel?.visibility = View.VISIBLE
            mTextIOLabelSeparator?.visibility = View.VISIBLE
            mTextIOLabel?.text = textIOInfo.label
            mTextIOLabel?.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(TextIOInfo.LABEL_SIZE_LIMIT_IN_BYTES))
            mTextIOLabel?.textSize = textIOInfo.labelSize.toFloat()
            mTextIOLabel?.setTextColor(textIOInfo.labelColor)
            mTextIOLabel?.typeface = Typeface.create(textIOInfo.labelTypeFaceFamily, textIOInfo.labelTypeFaceStyle)
        }

        val textIOHorizontalScrollView = mTextIOHorizontalScrollView
        val textIOText = mTextIOText
        if (textIOInfo.isHorizontallyScrollable) {
            textIOHorizontalScrollView?.isEnabled = true
            textIOText?.setHorizontallyScrolling(true)
        } else {
            // Remove mTextIOHorizontalScrollView and add mTextIOText in its place
            if (textIOHorizontalScrollView != null && textIOText != null) {
                val parent = textIOHorizontalScrollView.parent as? ViewGroup
                if (parent != null && parent.indexOfChild(textIOText) < 0) {
                    val params = textIOHorizontalScrollView.layoutParams
                    val index = parent.indexOfChild(textIOHorizontalScrollView)
                    mTextIOTextLinearLayout?.removeAllViews()
                    textIOHorizontalScrollView.removeAllViews()
                    parent.removeView(textIOHorizontalScrollView)
                    parent.addView(textIOText, index, params)
                    textIOText.setHorizontallyScrolling(false)
                }
            }
        }

        textIOText?.setText(textIOInfo.text)
        textIOText?.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(textIOInfo.textLengthLimit))
        textIOText?.textSize = textIOInfo.textSize.toFloat()
        textIOText?.setTextColor(textIOInfo.textColor)
        textIOText?.typeface = Typeface.create(textIOInfo.textTypeFaceFamily, textIOInfo.textTypeFaceStyle)

        // setTextIsSelectable must be called after changing KeyListener to regain focusability and selectivity
        if (textIOInfo.isEditingTextDisabled) {
            textIOText?.isCursorVisible = false
            textIOText?.keyListener = null
            textIOText?.setTextIsSelectable(true)
        }

        val textIOTextCharacterUsage = mTextIOTextCharacterUsage
        if (textIOInfo.shouldShowTextCharacterUsage()) {
            textIOTextCharacterUsage?.visibility = View.VISIBLE
            updateTextIOTextCharacterUsage(textIOInfo.text)

            textIOText?.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(editable: Editable?) {
                    if (editable != null) {
                        updateTextIOTextCharacterUsage(editable.toString())
                    }
                }
            })
        } else {
            textIOTextCharacterUsage?.visibility = View.GONE
        }
    }

    private fun updateTextIOInfoText() {
        val textIOText = mTextIOText
        if (textIOText != null) {
            mTextIOInfo?.text = textIOText.text.toString()
        }
    }

    private fun updateTextIOTextCharacterUsage(text: String?) {
        val resolvedText = text ?: ""
        val textIOTextCharacterUsage = mTextIOTextCharacterUsage
        val textIOInfo = mTextIOInfo
        if (textIOTextCharacterUsage != null && textIOInfo != null) {
            textIOTextCharacterUsage.text = String.format(Locale.getDefault(), "%1\$d/%2\$d", resolvedText.length, textIOInfo.textLengthLimit)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        updateTextIOInfoText()
        outState.putSerializable(EXTRA_TEXT_IO_INFO_OBJECT, mTextIOInfo)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val inflater = menuInflater
        inflater.inflate(R.menu.menu_text_io, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        var text = ""
        val textIOText = mTextIOText
        if (textIOText != null) {
            text = textIOText.text.toString()
        }

        val id = item.itemId
        if (id == android.R.id.home) {
            confirm()
        }
        if (id == R.id.menu_item_cancel) {
            cancel()
        } else if (id == R.id.menu_item_share_text) {
            ShareUtils.shareText(this, mTextIOInfo?.title, text)
        } else if (id == R.id.menu_item_copy_text) {
            ShareUtils.copyTextToClipboard(this, text, null)
        }

        return false
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        confirm()
    }

    /** Confirm current text and send it back to calling [Activity]. */
    private fun confirm() {
        updateTextIOInfoText()
        val textIOText = mTextIOText
        if (textIOText != null) {
            KeyboardUtils.hideSoftKeyboard(this, textIOText)
        }
        setResult(RESULT_OK, getResultIntent())
        finish()
    }

    /** Cancel current text and notify calling [Activity]. */
    private fun cancel() {
        val textIOText = mTextIOText
        if (textIOText != null) {
            KeyboardUtils.hideSoftKeyboard(this, textIOText)
        }
        setResult(RESULT_CANCELED, getResultIntent())
        finish()
    }

    private fun getResultIntent(): Intent {
        val intent = Intent()
        val bundle = Bundle()
        bundle.putSerializable(EXTRA_TEXT_IO_INFO_OBJECT, mTextIOInfo)
        intent.putExtras(bundle)
        return intent
    }

    companion object {
        private val CLASS_NAME: String = ReportActivity::class.java.canonicalName ?: "com.termux.shared.activities.ReportActivity"

        @JvmField
        val EXTRA_TEXT_IO_INFO_OBJECT = "$CLASS_NAME.EXTRA_TEXT_IO_INFO_OBJECT"

        private const val LOG_TAG = "TextIOActivity"

        /**
         * Get the [Intent] that can be used to start the [TextIOActivity].
         *
         * @param context The [Context] for operations.
         * @param textIOInfo The [TextIOInfo] containing info for the edit text.
         */
        @JvmStatic
        fun newInstance(context: Context, textIOInfo: TextIOInfo): Intent {
            val intent = Intent(context, TextIOActivity::class.java)
            val bundle = Bundle()
            bundle.putSerializable(EXTRA_TEXT_IO_INFO_OBJECT, textIOInfo)
            intent.putExtras(bundle)
            return intent
        }
    }
}
