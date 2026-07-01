package com.termux.shared.net.socket.local

import android.content.Context
import com.termux.shared.errors.Error
import com.termux.shared.jni.models.JniResult
import com.termux.shared.logger.Logger

/**
 * Manager for an AF_UNIX/SOCK_STREAM local server.
 *
 * Usage:
 * 1. Implement the {@link ILocalSocketManager} that will receive call backs from the server including
 *    when client connects via {@link ILocalSocketManager#onClientAccepted(LocalSocketManager, LocalClientSocket)}.
 *    Optionally extend the {@link LocalSocketManagerClientBase} class that provides base implementation.
 * 2. Create a {@link LocalSocketRunConfig} instance with the run config of the server.
 * 3. Create a {@link LocalSocketManager} instance and call {@link #start()}.
 * 4. Stop server if needed with a call to {@link #stop()}.
 */
open class LocalSocketManager(
    context: Context,
    localSocketRunConfig: LocalSocketRunConfig
) {

    /** The {@link Context} that may needed for various operations. */
    @JvmField
    protected val mContext: Context = context.applicationContext

    /** The {@link LocalSocketRunConfig} containing run config for the {@link LocalSocketManager}. */
    @JvmField
    protected val mLocalSocketRunConfig: LocalSocketRunConfig = localSocketRunConfig

    /** The {@link LocalServerSocket} for the {@link LocalSocketManager}. */
    @JvmField
    protected val mServerSocket: LocalServerSocket = LocalServerSocket(this)

    /** The {@link ILocalSocketManager} client for the {@link LocalSocketManager}. */
    @JvmField
    protected val mLocalSocketManagerClient: ILocalSocketManager = mLocalSocketRunConfig.getLocalSocketManagerClient()

    /** The {@link Thread.UncaughtExceptionHandler} used for client thread started by {@link LocalSocketManager}. */
    @JvmField
    protected val mLocalSocketManagerClientThreadUEH: Thread.UncaughtExceptionHandler = getLocalSocketManagerClientThreadUEHOrDefault()

    /** Whether the {@link LocalServerSocket} managed by {@link LocalSocketManager} in running or not. */
    @JvmField
    protected var mIsRunning: Boolean = false

    // Kotlin properties for property access syntax (e.g. from AmSocketServer.kt)
    val context: Context
        @JvmName("contextProp") get() = mContext

    val localSocketRunConfig: LocalSocketRunConfig
        @JvmName("localSocketRunConfigProp") get() = mLocalSocketRunConfig

    val serverSocket: LocalServerSocket
        @JvmName("serverSocketProp") get() = mServerSocket

    val localSocketManagerClient: ILocalSocketManager
        @JvmName("localSocketManagerClientProp") get() = mLocalSocketManagerClient

    val localSocketManagerClientThreadUEH: Thread.UncaughtExceptionHandler
        @JvmName("localSocketManagerClientThreadUEHProp") get() = mLocalSocketManagerClientThreadUEH

    val isRunning: Boolean
        @JvmName("isRunningProp") get() = mIsRunning

    // Java-compatible getter methods (also called by other files in Kotlin)
    fun getContext(): Context = mContext
    fun getLocalSocketRunConfig(): LocalSocketRunConfig = mLocalSocketRunConfig
    fun getServerSocket(): LocalServerSocket = mServerSocket
    fun getLocalSocketManagerClient(): ILocalSocketManager = mLocalSocketManagerClient
    fun getLocalSocketManagerClientThreadUEH(): Thread.UncaughtExceptionHandler = mLocalSocketManagerClientThreadUEH
    fun isRunning(): Boolean = mIsRunning

    /**
     * Create the {@link LocalServerSocket} and start listening for new {@link LocalClientSocket}.
     */
    @Synchronized
    open fun start(): Error? {
        Logger.logDebugExtended(LOG_TAG, "start\n$mLocalSocketRunConfig")

        if (!localSocketLibraryLoaded) {
            try {
                Logger.logDebug(LOG_TAG, "Loading \"$LOCAL_SOCKET_LIBRARY\" library")
                System.loadLibrary(LOCAL_SOCKET_LIBRARY)
                localSocketLibraryLoaded = true
            } catch (t: Throwable) {
                val error = LocalSocketErrno.ERRNO_START_LOCAL_SOCKET_LIB_LOAD_FAILED_WITH_EXCEPTION.getError(t, LOCAL_SOCKET_LIBRARY, t.message)
                Logger.logErrorExtended(LOG_TAG, error.getErrorLogString())
                return error
            }
        }

        mIsRunning = true
        return mServerSocket.start()
    }

    /**
     * Stop the {@link LocalServerSocket} and stop listening for new {@link LocalClientSocket}.
     */
    @Synchronized
    open fun stop(): Error? {
        if (mIsRunning) {
            Logger.logDebugExtended(LOG_TAG, "stop\n$mLocalSocketRunConfig")
            mIsRunning = false
            return mServerSocket.stop()
        }
        return null
    }

    /** Wrapper for {@link #onError(LocalClientSocket, Error)} for {@code null} {@link LocalClientSocket}. */
    open fun onError(error: Error) {
        onError(null, error)
    }

    /** Wrapper to call {@link ILocalSocketManager#onError(LocalSocketManager, LocalClientSocket, Error)} in a new thread. */
    open fun onError(clientSocket: LocalClientSocket?, error: Error) {
        startLocalSocketManagerClientThread {
            mLocalSocketManagerClient.onError(this, clientSocket, error)
        }
    }

    /** Wrapper to call {@link ILocalSocketManager#onDisallowedClientConnected(LocalSocketManager, LocalClientSocket, Error)} in a new thread. */
    open fun onDisallowedClientConnected(clientSocket: LocalClientSocket, error: Error) {
        startLocalSocketManagerClientThread {
            mLocalSocketManagerClient.onDisallowedClientConnected(this, clientSocket, error)
        }
    }

    /** Wrapper to call {@link ILocalSocketManager#onClientAccepted(LocalSocketManager, LocalClientSocket)} in a new thread. */
    open fun onClientAccepted(clientSocket: LocalClientSocket) {
        startLocalSocketManagerClientThread {
            mLocalSocketManagerClient.onClientAccepted(this, clientSocket)
        }
    }

    /** All client accept logic must be run on separate threads so that incoming client acceptance is not blocked. */
    open fun startLocalSocketManagerClientThread(runnable: Runnable) {
        val thread = Thread(runnable)
        thread.uncaughtExceptionHandler = getLocalSocketManagerClientThreadUEH()
        try {
            thread.start()
        } catch (e: Exception) {
            Logger.logStackTraceWithMessage(LOG_TAG, "LocalSocketManagerClientThread start failed", e)
        }
    }

    /**
     * Get {@link Thread.UncaughtExceptionHandler} returned by call to
     * {@link ILocalSocketManager#getLocalSocketManagerClientThreadUEH(LocalSocketManager)}
     * or the default handler that just logs the exception.
     */
    protected open fun getLocalSocketManagerClientThreadUEHOrDefault(): Thread.UncaughtExceptionHandler {
        var uncaughtExceptionHandler = mLocalSocketManagerClient.getLocalSocketManagerClientThreadUEH(this)
        if (uncaughtExceptionHandler == null) {
            uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { t, e ->
                Logger.logStackTraceWithMessage(LOG_TAG, "Uncaught exception for $t in ${mLocalSocketRunConfig.getTitle()} server", e)
            }
        }
        return uncaughtExceptionHandler
    }

    companion object {

        const val LOG_TAG = "LocalSocketManager"

        /** The native JNI local socket library. */
        @JvmField
        var LOCAL_SOCKET_LIBRARY = "local-socket"

        /** Whether {@link #LOCAL_SOCKET_LIBRARY} has been loaded or not. */
        @JvmField
        var localSocketLibraryLoaded = false

        /*
         Note: Exceptions thrown from JNI must be caught with Throwable class instead of Exception,
         otherwise exception will be sent to UncaughtExceptionHandler of the thread.
        */

        /**
         * Creates an AF_UNIX/SOCK_STREAM local server socket at {@code path}, with the specified backlog.
         *
         * @param serverTitle The server title used for logging and errors.
         * @param path The path at which to create the socket.
         *             For a filesystem socket, this must be an absolute path to the socket file.
         *             For an abstract namespace socket, the first byte must be a null `\0` character.
         *             Max allowed length is 108 bytes as per sun_path size (UNIX_PATH_MAX) on Linux.
         * @param backlog The maximum length to which the queue of pending connections for the socket
         *                may grow. This value may be ignored or may not have one-to-one mapping
         *                in kernel implementation. Value must be greater than 0.
         * @return Returns the {@link JniResult}. If server creation was successful, then
         * {@link JniResult#retval} will be 0 and {@link JniResult#intData} will contain the server socket
         * fd.
         */
        @JvmStatic
        fun createServerSocket(serverTitle: String, path: ByteArray, backlog: Int): JniResult? {
            return try {
                createServerSocketNative(serverTitle, path, backlog)
            } catch (t: Throwable) {
                val message = "Exception in createServerSocketNative()"
                Logger.logStackTraceWithMessage(LOG_TAG, message, t)
                JniResult(message, t)
            }
        }

        /**
         * Closes the socket with fd.
         *
         * @param serverTitle The server title used for logging and errors.
         * @param fd The socket fd.
         * @return Returns the {@link JniResult}. If closing socket was successful, then
         * {@link JniResult#retval} will be 0.
         */
        @JvmStatic
        fun closeSocket(serverTitle: String, fd: Int): JniResult? {
            return try {
                closeSocketNative(serverTitle, fd)
            } catch (t: Throwable) {
                val message = "Exception in closeSocketNative()"
                Logger.logStackTraceWithMessage(LOG_TAG, message, t)
                JniResult(message, t)
            }
        }

        /**
         * Accepts a connection on the supplied server socket fd.
         *
         * @param serverTitle The server title used for logging and errors.
         * @param fd The server socket fd.
         * @return Returns the {@link JniResult}. If accepting socket was successful, then
         * {@link JniResult#retval} will be 0 and {@link JniResult#intData} will contain the client socket
         * fd.
         */
        @JvmStatic
        fun accept(serverTitle: String, fd: Int): JniResult? {
            return try {
                acceptNative(serverTitle, fd)
            } catch (t: Throwable) {
                val message = "Exception in acceptNative()"
                Logger.logStackTraceWithMessage(LOG_TAG, message, t)
                JniResult(message, t)
            }
        }

        /**
         * Attempts to read up to data buffer length bytes from file descriptor fd into the data buffer.
         * On success, the number of bytes read is returned (zero indicates end of file).
         * It is not an error if bytes read is smaller than the number of bytes requested; this may happen
         * for example because fewer bytes are actually available right now (maybe because we were close
         * to end-of-file, or because we are reading from a pipe), or because read() was interrupted by
         * a signal. On error, the {@link JniResult#errno} and {@link JniResult#errmsg} will be set.
         *
         * If while reading the deadline elapses but all the data has not been read, the call will fail.
         *
         * @param serverTitle The server title used for logging and errors.
         * @param fd The socket fd.
         * @param data The data buffer to read bytes into.
         * @param deadline The deadline milliseconds since epoch.
         * @return Returns the {@link JniResult}. If reading was successful, then {@link JniResult#retval}
         * will be 0 and {@link JniResult#intData} will contain the bytes read.
         */
        @JvmStatic
        fun read(serverTitle: String, fd: Int, data: ByteArray, deadline: Long): JniResult? {
            return try {
                readNative(serverTitle, fd, data, deadline)
            } catch (t: Throwable) {
                val message = "Exception in readNative()"
                Logger.logStackTraceWithMessage(LOG_TAG, message, t)
                JniResult(message, t)
            }
        }

        /**
         * Attempts to send data buffer to the file descriptor. On error, the {@link JniResult#errno} and
         * {@link JniResult#errmsg} will be set.
         *
         * If while sending the deadline elapses but all the data has not been sent, the call will fail.
         *
         * @param serverTitle The server title used for logging and errors.
         * @param fd The socket fd.
         * @param data The data buffer containing bytes to send.
         * @param deadline The deadline milliseconds since epoch.
         * @return Returns the {@link JniResult}. If sending was successful, then {@link JniResult#retval}
         * will be 0.
         */
        @JvmStatic
        fun send(serverTitle: String, fd: Int, data: ByteArray, deadline: Long): JniResult? {
            return try {
                sendNative(serverTitle, fd, data, deadline)
            } catch (t: Throwable) {
                val message = "Exception in sendNative()"
                Logger.logStackTraceWithMessage(LOG_TAG, message, t)
                JniResult(message, t)
            }
        }

        /**
         * Gets the number of bytes available to read on the socket.
         *
         * @param serverTitle The server title used for logging and errors.
         * @param fd The socket fd.
         * @return Returns the {@link JniResult}. If checking availability was successful, then
         * {@link JniResult#retval} will be 0 and {@link JniResult#intData} will contain the bytes available.
         */
        @JvmStatic
        fun available(serverTitle: String, fd: Int): JniResult? {
            return try {
                availableNative(serverTitle, fd)
            } catch (t: Throwable) {
                val message = "Exception in availableNative()"
                Logger.logStackTraceWithMessage(LOG_TAG, message, t)
                JniResult(message, t)
            }
        }

        /**
         * Set receiving (SO_RCVTIMEO) timeout in milliseconds for socket.
         *
         * @param serverTitle The server title used for logging and errors.
         * @param fd The socket fd.
         * @param timeout The timeout value in milliseconds.
         * @return Returns the {@link JniResult}. If setting timeout was successful, then
         * {@link JniResult#retval} will be 0.
         */
        @JvmStatic
        fun setSocketReadTimeout(serverTitle: String, fd: Int, timeout: Int): JniResult? {
            return try {
                setSocketReadTimeoutNative(serverTitle, fd, timeout)
            } catch (t: Throwable) {
                val message = "Exception in setSocketReadTimeoutNative()"
                Logger.logStackTraceWithMessage(LOG_TAG, message, t)
                JniResult(message, t)
            }
        }

        /**
         * Set sending (SO_SNDTIMEO) timeout in milliseconds for fd.
         *
         * @param serverTitle The server title used for logging and errors.
         * @param fd The socket fd.
         * @param timeout The timeout value in milliseconds.
         * @return Returns the {@link JniResult}. If setting timeout was successful, then
         * {@link JniResult#retval} will be 0.
         */
        @JvmStatic
        fun setSocketSendTimeout(serverTitle: String, fd: Int, timeout: Int): JniResult? {
            return try {
                setSocketSendTimeoutNative(serverTitle, fd, timeout)
            } catch (t: Throwable) {
                val message = "Exception in setSocketSendTimeoutNative()"
                Logger.logStackTraceWithMessage(LOG_TAG, message, t)
                JniResult(message, t)
            }
        }

        /**
         * Get the {@link PeerCred} for the socket.
         *
         * @param serverTitle The server title used for logging and errors.
         * @param fd The socket fd.
         * @param peerCred The {@link PeerCred} object that should be filled.
         * @return Returns the {@link JniResult}. If setting timeout was successful, then
         * {@link JniResult#retval} will be 0.
         */
        @JvmStatic
        fun getPeerCred(serverTitle: String, fd: Int, peerCred: PeerCred?): JniResult? {
            return try {
                getPeerCredNative(serverTitle, fd, peerCred)
            } catch (t: Throwable) {
                val message = "Exception in getPeerCredNative()"
                Logger.logStackTraceWithMessage(LOG_TAG, message, t)
                JniResult(message, t)
            }
        }

        /** Get an error log {@link String} for the {@link LocalSocketManager}. */
        @JvmStatic
        fun getErrorLogString(
            error: Error,
            localSocketRunConfig: LocalSocketRunConfig,
            clientSocket: LocalClientSocket?
        ): String {
            val logString = StringBuilder()

            logString.append(localSocketRunConfig.getTitle()).append(" Socket Server Error:\n")
            logString.append(error.getErrorLogString())
            logString.append("\n\n\n")

            logString.append(localSocketRunConfig.getLogString())

            if (clientSocket != null) {
                logString.append("\n\n\n")
                logString.append(clientSocket.getLogString())
            }

            return logString.toString()
        }

        /** Get an error markdown {@link String} for the {@link LocalSocketManager}. */
        @JvmStatic
        fun getErrorMarkdownString(
            error: Error,
            localSocketRunConfig: LocalSocketRunConfig,
            clientSocket: LocalClientSocket?
        ): String {
            val markdownString = StringBuilder()

            markdownString.append(error.getErrorMarkdownString())
            markdownString.append("\n##\n\n\n")

            markdownString.append(localSocketRunConfig.getMarkdownString())

            if (clientSocket != null) {
                markdownString.append("\n\n\n")
                markdownString.append(clientSocket.getMarkdownString())
            }

            return markdownString.toString()
        }

        @JvmStatic
        private external fun createServerSocketNative(serverTitle: String, path: ByteArray, backlog: Int): JniResult?

        @JvmStatic
        private external fun closeSocketNative(serverTitle: String, fd: Int): JniResult?

        @JvmStatic
        private external fun acceptNative(serverTitle: String, fd: Int): JniResult?

        @JvmStatic
        private external fun readNative(serverTitle: String, fd: Int, data: ByteArray, deadline: Long): JniResult?

        @JvmStatic
        private external fun sendNative(serverTitle: String, fd: Int, data: ByteArray, deadline: Long): JniResult?

        @JvmStatic
        private external fun availableNative(serverTitle: String, fd: Int): JniResult?

        @JvmStatic
        private external fun setSocketReadTimeoutNative(serverTitle: String, fd: Int, timeout: Int): JniResult?

        @JvmStatic
        private external fun setSocketSendTimeoutNative(serverTitle: String, fd: Int, timeout: Int): JniResult?

        @JvmStatic
        private external fun getPeerCredNative(serverTitle: String, fd: Int, peerCred: PeerCred?): JniResult?
    }
}
