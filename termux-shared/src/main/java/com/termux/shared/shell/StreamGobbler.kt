/*
 * Copyright (C) 2012-2019 Jorrit "Chainfire" Jongma
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.termux.shared.shell

import androidx.annotation.AnyThread
import androidx.annotation.WorkerThread
import com.termux.shared.logger.Logger
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Locale

/**
 * Thread utility class continuously reading from an InputStream
 *
 * https://github.com/Chainfire/libsuperuser/blob/1.1.0.201907261845/libsuperuser/src/eu/chainfire/libsuperuser/Shell.java#L141
 * https://github.com/Chainfire/libsuperuser/blob/1.1.0.201907261845/libsuperuser/src/eu/chainfire/libsuperuser/StreamGobbler.java
 */
@Suppress("WeakerAccess")
open class StreamGobbler private constructor(
    private val shell: String,
    open val inputStream: InputStream,
    private val reader: BufferedReader,
    private val listWriter: MutableList<String>?,
    private val stringWriter: StringBuilder?,
    open val onLineListener: OnLineListener?,
    private val streamClosedListener: OnStreamClosedListener?,
    private val mLogLevel: Int?,
    threadName: String
) : Thread(threadName) {

    @Volatile
    private var active = true

    @Volatile
    private var calledOnClose = false

    /**
     * Line callback interface
     */
    fun interface OnLineListener {
        /**
         * <p>Line callback</p>
         *
         * <p>This callback should process the line as quickly as possible.
         * Delays in this callback may pause the native process or even
         * result in a deadlock</p>
         *
         * @param line String that was gobbled
         */
        fun onLine(line: String)
    }

    /**
     * Stream closed callback interface
     */
    fun interface OnStreamClosedListener {
        /**
         * <p>Stream closed callback</p>
         */
        fun onStreamClosed()
    }

    /**
     * <p>StreamGobbler constructor</p>
     *
     * <p>We use this class because shell STDOUT and STDERR should be read as quickly as
     * possible to prevent a deadlock from occurring, or Process.waitFor() never
     * returning (as the buffer is full, pausing the native process)</p>
     *
     * @param shell Name of the shell
     * @param inputStream InputStream to read from
     * @param outputList {@literal List<String>} to write to, or null
     * @param logLevel The custom log level to use for logging the command output. If set to
     *                 {@code null}, then {@link Logger#LOG_LEVEL_VERBOSE} will be used.
     */
    @AnyThread
    constructor(
        shell: String,
        inputStream: InputStream,
        outputList: MutableList<String>?,
        logLevel: Int?
    ) : this(
        shell = shell,
        inputStream = inputStream,
        reader = BufferedReader(InputStreamReader(inputStream)),
        listWriter = outputList,
        stringWriter = null,
        onLineListener = null,
        streamClosedListener = null,
        mLogLevel = logLevel,
        threadName = "Gobbler#" + incThreadCounter()
    )

    /**
     * <p>StreamGobbler constructor</p>
     *
     * <p>We use this class because shell STDOUT and STDERR should be read as quickly as
     * possible to prevent a deadlock from occurring, or Process.waitFor() never
     * returning (as the buffer is full, pausing the native process)</p>
     * Do not use this for concurrent reading for STDOUT and STDERR for the same StringBuilder since
     * its not synchronized.
     *
     * @param shell Name of the shell
     * @param inputStream InputStream to read from
     * @param outputString {@literal List<String>} to write to, or null
     * @param logLevel The custom log level to use for logging the command output. If set to
     *                 {@code null}, then {@link Logger#LOG_LEVEL_VERBOSE} will be used.
     */
    @AnyThread
    constructor(
        shell: String,
        inputStream: InputStream,
        outputString: StringBuilder?,
        logLevel: Int?
    ) : this(
        shell = shell,
        inputStream = inputStream,
        reader = BufferedReader(InputStreamReader(inputStream)),
        listWriter = null,
        stringWriter = outputString,
        onLineListener = null,
        streamClosedListener = null,
        mLogLevel = logLevel,
        threadName = "Gobbler#" + incThreadCounter()
    )

    /**
     * <p>StreamGobbler constructor</p>
     *
     * <p>We use this class because shell STDOUT and STDERR should be read as quickly as
     * possible to prevent a deadlock from occurring, or Process.waitFor() never
     * returning (as the buffer is full, pausing the native process)</p>
     *
     * @param shell Name of the shell
     * @param inputStream InputStream to read from
     * @param onLineListener OnLineListener callback
     * @param onStreamClosedListener OnStreamClosedListener callback
     * @param logLevel The custom log level to use for logging the command output. If set to
     *                 {@code null}, then {@link Logger#LOG_LEVEL_VERBOSE} will be used.
     */
    @AnyThread
    constructor(
        shell: String,
        inputStream: InputStream,
        onLineListener: OnLineListener?,
        onStreamClosedListener: OnStreamClosedListener?,
        logLevel: Int?
    ) : this(
        shell = shell,
        inputStream = inputStream,
        reader = BufferedReader(InputStreamReader(inputStream)),
        listWriter = null,
        stringWriter = null,
        onLineListener = onLineListener,
        streamClosedListener = onStreamClosedListener,
        mLogLevel = logLevel,
        threadName = "Gobbler#" + incThreadCounter()
    )

    override fun run() {
        val defaultLogTag = Logger.getDefaultLogTag()
        val loggingEnabled = Logger.shouldEnableLoggingForCustomLogLevel(mLogLevel)
        if (loggingEnabled) {
            Logger.logVerbose(
                LOG_TAG,
                "Using custom log level: $mLogLevel, current log level: ${Logger.getLogLevel()}"
            )
        }

        // keep reading the InputStream until it ends (or an error occurs)
        // optionally pausing when a command is executed that consumes the InputStream itself
        try {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val nonNullLine = line!!
                if (loggingEnabled) {
                    Logger.logVerboseForce(
                        defaultLogTag + "Command",
                        String.format(Locale.ENGLISH, "[%s] %s", shell, nonNullLine)
                    ) // This will get truncated by LOGGER_ENTRY_MAX_LEN, likely 4KB
                }

                stringWriter?.append(nonNullLine)?.append("\n")
                listWriter?.add(nonNullLine)
                onLineListener?.onLine(nonNullLine)
                while (!active) {
                    synchronized(this) {
                        try {
                            (this as java.lang.Object).wait(128)
                        } catch (e: InterruptedException) {
                            // no action
                        }
                    }
                }
            }
        } catch (e: IOException) {
            // reader probably closed, expected exit condition
            if (streamClosedListener != null) {
                calledOnClose = true
                streamClosedListener.onStreamClosed()
            }
        }

        // make sure our stream is closed and resources will be freed
        try {
            reader.close()
        } catch (e: IOException) {
            // read already closed
        }

        if (!calledOnClose) {
            if (streamClosedListener != null) {
                calledOnClose = true
                streamClosedListener.onStreamClosed()
            }
        }
    }

    /**
     * <p>Resume consuming the input from the stream</p>
     */
    @AnyThread
    open fun resumeGobbling() {
        if (!active) {
            synchronized(this) {
                active = true
                (this as java.lang.Object).notifyAll()
            }
        }
    }

    /**
     * <p>Suspend gobbling, so other code may read from the InputStream instead</p>
     *
     * <p>This should <i>only</i> be called from the OnLineListener callback!</p>
     */
    @AnyThread
    open fun suspendGobbling() {
        synchronized(this) {
            active = false
            (this as java.lang.Object).notifyAll()
        }
    }

    /**
     * <p>Wait for gobbling to be suspended</p>
     *
     * <p>Obviously this cannot be called from the same thread as {@link #suspendGobbling()}</p>
     */
    @WorkerThread
    open fun waitForSuspend() {
        synchronized(this) {
            while (active) {
                try {
                    (this as java.lang.Object).wait(32)
                } catch (e: InterruptedException) {
                    // no action
                }
            }
        }
    }

    /**
     * <p>Is gobbling suspended ?</p>
     *
     * @return is gobbling suspended?
     */
    @AnyThread
    open fun isSuspended(): Boolean {
        synchronized(this) {
            return !active
        }
    }

    @Throws(InterruptedException::class)
    internal fun conditionalJoin() {
        if (calledOnClose) return // deadlock from callback, we're inside exit procedure
        if (currentThread() === this) return // can't join self
        join()
    }

    companion object {
        private const val LOG_TAG = "StreamGobbler"

        private var threadCounter = 0

        private fun incThreadCounter(): Int {
            synchronized(StreamGobbler::class.java) {
                val ret = threadCounter
                threadCounter++
                return ret
            }
        }
    }
}
