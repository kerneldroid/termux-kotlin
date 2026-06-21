package com.termux.shared.jni.models

import androidx.annotation.Keep
import com.termux.shared.logger.Logger

/**
 * A class that can be used to return result for JNI calls with support for multiple fields to easily
 * return success and error states.
 *
 * https://docs.oracle.com/javase/7/docs/technotes/guides/jni/spec/functions.html
 * https://developer.android.com/training/articles/perf-jni
 */
@Keep
class JniResult @JvmOverloads constructor(
    @JvmField var retval: Int,
    @JvmField var errno: Int,
    @JvmField var errmsg: String?,
    @JvmField var intData: Int = 0
) {

    /**
     * Create an new instance of [JniResult] from a [Throwable] with [retval] -1.
     *
     * @param message The error message.
     * @param throwable The [Throwable] value.
     */
    constructor(message: String?, throwable: Throwable?) : this(
        -1,
        0,
        Logger.getMessageAndStackTraceString(message, throwable)
    )

    /** Get error [String] for [JniResult]. */
    fun getErrorString(): String {
        val logString = StringBuilder()

        logString.append(Logger.getSingleLineLogStringEntry("Retval", retval, "-"))

        if (errno != 0) {
            logString.append("\n").append(Logger.getSingleLineLogStringEntry("Errno", errno, "-"))
        }

        if (!errmsg.isNullOrEmpty()) {
            logString.append("\n").append(Logger.getMultiLineLogStringEntry("Errmsg", errmsg, "-"))
        }

        return logString.toString()
    }

    companion object {
        /**
         * Get error [String] for [JniResult].
         *
         * @param result The [JniResult] to get error from.
         * @return Returns the error [String].
         */
        @JvmStatic
        fun getErrorString(result: JniResult?): String {
            if (result == null) return "null"
            return result.getErrorString()
        }
    }
}
