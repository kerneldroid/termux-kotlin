package com.termux.shared.net.socket.local

import android.content.Context
import androidx.annotation.Keep
import com.termux.shared.android.ProcessUtils
import com.termux.shared.android.UserUtils
import com.termux.shared.logger.Logger
import com.termux.shared.markdown.MarkdownUtils

/** The [PeerCred] of the [LocalClientSocket] containing info of client/peer. */
@Keep
class PeerCred {

    /** Process Id. */
    @JvmField
    var pid: Int = -1

    /** Process Name. */
    @JvmField
    var pname: String? = null

    /** User Id. */
    @JvmField
    var uid: Int = -1

    /** User name. */
    @JvmField
    var uname: String? = null

    /** Group Id. */
    @JvmField
    var gid: Int = -1

    /** Group name. */
    @JvmField
    var gname: String? = null

    /** Command line that started the process. */
    @JvmField
    var cmdline: String? = null

    /** Set data that was not set by JNI. */
    fun fillPeerCred(context: Context) {
        fillUnameAndGname(context)
        fillPname(context)
    }

    /** Set [uname] and [gname] if not set. */
    fun fillUnameAndGname(context: Context) {
        uname = UserUtils.getNameForUid(context, uid)

        if (gid != uid) {
            gname = UserUtils.getNameForUid(context, gid)
        } else {
            gname = uname
        }
    }

    /** Set [pname] if not set. */
    fun fillPname(context: Context) {
        // If jni did not set process name since it wouldn't be able to access /proc/<pid> of other
        // users/apps, then try to see if any app has that pid, but this wouldn't check child
        // processes of the app.
        if (pid > 0 && pname == null) {
            pname = ProcessUtils.getAppProcessNameForPid(context, pid)
        }
    }

    /** Get a log [String] for the [PeerCred]. */
    fun getLogString(): String {
        val logString = StringBuilder()

        logString.append("Peer Cred:")
        logString.append("\n").append(Logger.getSingleLineLogStringEntry("Process", getProcessString(), "-"))
        logString.append("\n").append(Logger.getSingleLineLogStringEntry("User", getUserString(), "-"))
        logString.append("\n").append(Logger.getSingleLineLogStringEntry("Group", getGroupString(), "-"))

        if (cmdline != null) {
            logString.append("\n").append(Logger.getMultiLineLogStringEntry("Cmdline", cmdline, "-"))
        }

        return logString.toString()
    }

    /** Get a markdown [String] for the [PeerCred]. */
    fun getMarkdownString(): String {
        val markdownString = StringBuilder()

        markdownString.append("## ").append("Peer Cred")
        markdownString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Process", getProcessString(), "-"))
        markdownString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("User", getUserString(), "-"))
        markdownString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Group", getGroupString(), "-"))

        if (cmdline != null) {
            markdownString.append("\n").append(MarkdownUtils.getMultiLineMarkdownStringEntry("Cmdline", cmdline, "-"))
        }

        return markdownString.toString()
    }

    fun getMinimalString(): String {
        return "process=" + getProcessString() + ", user=" + getUserString() + ", group=" + getGroupString()
    }

    fun getProcessString(): String {
        val currentPname = pname
        return if (!currentPname.isNullOrEmpty()) "$pid ($currentPname)" else pid.toString()
    }

    fun getUserString(): String {
        val currentUname = uname
        return if (currentUname != null) "$uid ($currentUname)" else uid.toString()
    }

    fun getGroupString(): String {
        val currentGname = gname
        return if (currentGname != null) "$gid ($currentGname)" else gid.toString()
    }

    companion object {
        const val LOG_TAG = "PeerCred"

        /**
         * Get a log [String] for [PeerCred].
         *
         * @param peerCred The [PeerCred] to get info of.
         * @return Returns the log [String].
         */
        @JvmStatic
        fun getPeerCredLogString(peerCred: PeerCred?): String {
            if (peerCred == null) return "null"
            return peerCred.getLogString()
        }

        /**
         * Get a markdown [String] for [PeerCred].
         *
         * @param peerCred The [PeerCred] to get info of.
         * @return Returns the markdown [String].
         */
        @JvmStatic
        fun getPeerCredMarkdownString(peerCred: PeerCred?): String {
            if (peerCred == null) return "null"
            return peerCred.getMarkdownString()
        }
    }
}
