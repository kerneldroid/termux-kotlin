package com.termux.shared.termux.interact

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.text.Selection
import android.util.TypedValue
import android.view.KeyEvent
import android.view.ViewGroup.LayoutParams
import android.widget.EditText
import android.widget.LinearLayout

class TextInputDialogUtils private constructor() {

    fun interface TextSetListener {
        fun onTextSet(text: String)
    }

    companion object {
        @JvmStatic
        fun textInput(
            activity: Activity,
            titleText: Int,
            initialText: String?,
            positiveButtonText: Int,
            onPositive: TextSetListener,
            neutralButtonText: Int,
            onNeutral: TextSetListener?,
            negativeButtonText: Int,
            onNegative: TextSetListener?,
            onDismiss: DialogInterface.OnDismissListener?
        ) {
            val input = EditText(activity)
            input.setSingleLine()
            if (initialText != null) {
                input.setText(initialText)
                Selection.setSelection(input.text, initialText.length)
            }

            var dialog: AlertDialog? = null
            input.setImeActionLabel(
                activity.resources.getString(positiveButtonText),
                KeyEvent.KEYCODE_ENTER
            )
            input.setOnEditorActionListener { _, _, _ ->
                onPositive.onTextSet(input.text.toString())
                dialog?.dismiss()
                true
            }

            val dipInPixels = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                1f,
                activity.resources.displayMetrics
            )
            // https://www.google.com/design/spec/components/dialogs.html#dialogs-specs
            val paddingTopAndSides = Math.round(16 * dipInPixels)
            val paddingBottom = Math.round(24 * dipInPixels)

            val layout = LinearLayout(activity)
            layout.orientation = LinearLayout.VERTICAL
            layout.layoutParams = LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            )
            layout.setPadding(
                paddingTopAndSides,
                paddingTopAndSides,
                paddingTopAndSides,
                paddingBottom
            )
            layout.addView(input)

            val builder = AlertDialog.Builder(activity)
                .setTitle(titleText).setView(layout)
                .setPositiveButton(positiveButtonText) { _, _ ->
                    onPositive.onTextSet(input.text.toString())
                }

            if (onNeutral != null) {
                builder.setNeutralButton(neutralButtonText) { _, _ ->
                    onNeutral.onTextSet(input.text.toString())
                }
            }

            if (onNegative == null) {
                builder.setNegativeButton(android.R.string.cancel, null)
            } else {
                builder.setNegativeButton(negativeButtonText) { _, _ ->
                    onNegative.onTextSet(input.text.toString())
                }
            }

            if (onDismiss != null) {
                builder.setOnDismissListener(onDismiss)
            }

            dialog = builder.create()
            dialog.setCanceledOnTouchOutside(false)
            dialog.show()
        }
    }
}
