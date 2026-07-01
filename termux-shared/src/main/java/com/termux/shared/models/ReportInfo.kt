package com.termux.shared.models

import androidx.annotation.Keep
import com.termux.shared.android.AndroidUtils
import com.termux.shared.markdown.MarkdownUtils
import java.io.Serializable

/**
 * An object that stored info for [com.termux.shared.activities.ReportActivity].
 */
class ReportInfo(
    @JvmField val userAction: String?,
    @JvmField val sender: String?,
    @JvmField val reportTitle: String?
) : Serializable {

    /** The timestamp for the report. */
    @JvmField
    val reportTimestamp: String? = AndroidUtils.getCurrentMilliSecondUTCTimeStamp()

    /** The markdown report text prefix. Will not be part of copy and share operations, etc. */
    @JvmField
    var reportStringPrefix: String? = null

    /** The markdown report text. */
    @JvmField
    var reportString: String? = null

    /** The markdown report text suffix. Will not be part of copy and share operations, etc. */
    @JvmField
    var reportStringSuffix: String? = null

    /** If set to `true`, then report header info will be added to the report when markdown is
     * generated. */
    @JvmField
    var addReportInfoHeaderToMarkdown = false

    /** The label for the report file to save if user selects menu_item_save_report_to_file. */
    @JvmField
    var reportSaveFileLabel: String? = null

    /** The path for the report file to save if user selects menu_item_save_report_to_file. */
    @JvmField
    var reportSaveFilePath: String? = null

    fun setReportStringPrefix(reportStringPrefix: String?) {
        this.reportStringPrefix = reportStringPrefix
    }

    fun setReportString(reportString: String?) {
        this.reportString = reportString
    }

    fun setReportStringSuffix(reportStringSuffix: String?) {
        this.reportStringSuffix = reportStringSuffix
    }

    fun setAddReportInfoHeaderToMarkdown(addReportInfoHeaderToMarkdown: Boolean) {
        this.addReportInfoHeaderToMarkdown = addReportInfoHeaderToMarkdown
    }

    fun setReportSaveFileLabelAndPath(reportSaveFileLabel: String?, reportSaveFilePath: String?) {
        setReportSaveFileLabel(reportSaveFileLabel)
        setReportSaveFilePath(reportSaveFilePath)
    }

    fun setReportSaveFileLabel(reportSaveFileLabel: String?) {
        this.reportSaveFileLabel = reportSaveFileLabel
    }

    fun setReportSaveFilePath(reportSaveFilePath: String?) {
        this.reportSaveFilePath = reportSaveFilePath
    }

    companion object {
        /**
         * Explicitly define `serialVersionUID` to prevent exceptions on deserialization.
         *
         * Like when calling `Bundle.getSerializable()` on Android.
         * `android.os.BadParcelableException: Parcelable encountered IOException reading a Serializable object` (name = <class_name>)
         * `java.io.InvalidClassException: <class_name>; local class incompatible`
         *
         * The `@Keep` annotation is necessary to prevent the field from being removed by proguard when
         * app is compiled, even if its kept during library compilation.
         *
         * **See Also:**
         * - https://docs.oracle.com/javase/8/docs/platform/serialization/spec/version.html#a6678
         * - https://docs.oracle.com/javase/8/docs/platform/serialization/spec/class.html#a4100
         */
        @Keep
        private const val serialVersionUID = 1L

        /**
         * Get a markdown [String] for [ReportInfo].
         *
         * @param reportInfo The [ReportInfo] to convert.
         * @return Returns the markdown [String].
         */
        @JvmStatic
        fun getReportInfoMarkdownString(reportInfo: ReportInfo?): String {
            if (reportInfo == null) return "null"

            val markdownString = StringBuilder()

            if (reportInfo.addReportInfoHeaderToMarkdown) {
                markdownString.append("## Report Info\n\n")
                markdownString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("User Action", reportInfo.userAction, "-"))
                markdownString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Sender", reportInfo.sender, "-"))
                markdownString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Report Timestamp", reportInfo.reportTimestamp, "-"))
                markdownString.append("\n##\n\n")
            }

            markdownString.append(reportInfo.reportString)

            return markdownString.toString()
        }
    }
}
