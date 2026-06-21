package com.termux.shared.net.socket.local

import com.termux.shared.data.DataUtils
import com.termux.shared.errors.Error
import com.termux.shared.jni.models.JniResult
import com.termux.shared.logger.Logger
import com.termux.shared.markdown.MarkdownUtils
import java.io.BufferedWriter
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter

/** The client socket for {@link LocalSocketManager}. */
open class LocalClientSocket(
    @JvmField protected val mLocalSocketManager: LocalSocketManager,
    fd: Int,
    @JvmField protected val mPeerCred: PeerCred
) : Closeable {

    /** The {@link LocalSocketRunConfig} containing run config for the {@link LocalClientSocket}. */
    @JvmField
    protected val mLocalSocketRunConfig: LocalSocketRunConfig = mLocalSocketManager.getLocalSocketRunConfig()

    /**
     * The {@link LocalClientSocket} file descriptor.
     * Value will be `>= 0` if socket has been connected and `-1` if closed.
     */
    @JvmField
    protected var mFD = 0

    /** The creation time of {@link LocalClientSocket}. This is also used for deadline. */
    @JvmField
    protected val mCreationTime: Long = System.currentTimeMillis()

    /** The {@link OutputStream} implementation for the {@link LocalClientSocket}. */
    @JvmField
    protected val mOutputStream: SocketOutputStream = SocketOutputStream()

    /** The {@link InputStream} implementation for the {@link LocalClientSocket}. */
    @JvmField
    protected val mInputStream: SocketInputStream = SocketInputStream()

    init {
        setFD(fd)
        mPeerCred.fillPeerCred(mLocalSocketManager.getContext())
    }

    /** Close client socket. */
    @Synchronized
    fun closeClientSocket(logErrorMessage: Boolean): Error? {
        try {
            close()
        } catch (e: IOException) {
            val error = LocalSocketErrno.ERRNO_CLOSE_CLIENT_SOCKET_FAILED_WITH_EXCEPTION.getError(
                e,
                mLocalSocketRunConfig.getTitle(),
                e.message
            )
            if (logErrorMessage) {
                Logger.logErrorExtended(LOG_TAG, error.getErrorLogString())
            }
            return error
        }
        return null
    }

    /** Implementation for {@link Closeable#close()} to close client socket. */
    @Throws(IOException::class)
    override fun close() {
        if (mFD >= 0) {
            Logger.logVerbose(
                LOG_TAG,
                "Client socket close for \"" + mLocalSocketRunConfig.getTitle() + "\" server: " + getPeerCred().getMinimalString()
            )
            val result = LocalSocketManager.closeSocket(
                mLocalSocketRunConfig.getLogTitle() + " (client)",
                mFD
            )
            if (result == null || result.retval != 0) {
                throw IOException(JniResult.getErrorString(result))
            }
            // Update fd to signify that client socket has been closed
            setFD(-1)
        }
    }

    /**
     * Attempts to read up to data buffer length bytes from file descriptor into the data buffer.
     * On success, the number of bytes read is returned (zero indicates end of file) in bytesRead.
     * It is not an error if bytesRead is smaller than the number of bytes requested; this may happen
     * for example because fewer bytes are actually available right now (maybe because we were close
     * to end-of-file, or because we are reading from a pipe), or because read() was interrupted by
     * a signal.
     *
     * If while reading the {@link #mCreationTime} + the milliseconds returned by
     * {@link LocalSocketRunConfig#getDeadline()} elapses but all the data has not been read, an
     * error would be returned.
     *
     * This is a wrapper for {@link LocalSocketManager#read(String, int, byte[], long)}, which can
     * be called instead if you want to get access to errno int value instead of {@link JniResult}
     * error {@link String}.
     *
     * @param data The data buffer to read bytes into.
     * @param bytesRead The actual bytes read.
     * @return Returns the {@code error} if reading was not successful containing {@link JniResult}
     * error {@link String}, otherwise {@code null}.
     */
    fun read(data: ByteArray, bytesRead: MutableInt): Error? {
        bytesRead.value = 0
        if (mFD < 0) {
            return LocalSocketErrno.ERRNO_USING_CLIENT_SOCKET_WITH_INVALID_FD.getError(
                mFD,
                mLocalSocketRunConfig.getTitle()
            )
        }
        val result = LocalSocketManager.read(
            mLocalSocketRunConfig.getLogTitle() + " (client)",
            mFD,
            data,
            if (mLocalSocketRunConfig.getDeadline() > 0) mCreationTime + mLocalSocketRunConfig.getDeadline() else 0
        )
        if (result == null || result.retval != 0) {
            return LocalSocketErrno.ERRNO_READ_DATA_FROM_CLIENT_SOCKET_FAILED.getError(
                mLocalSocketRunConfig.getTitle(),
                JniResult.getErrorString(result)
            )
        }
        bytesRead.value = result.intData
        return null
    }

    /**
     * Attempts to send data buffer to the file descriptor.
     *
     * If while sending the {@link #mCreationTime} + the milliseconds returned by
     * {@link LocalSocketRunConfig#getDeadline()} elapses but all the data has not been sent, an
     * error would be returned.
     *
     * This is a wrapper for {@link LocalSocketManager#send(String, int, byte[], long)}, which can
     * be called instead if you want to get access to errno int value instead of {@link JniResult}
     * error {@link String}.
     *
     * @param data The data buffer containing bytes to send.
     * @return Returns the {@code error} if sending was not successful containing {@link JniResult}
     * error {@link String}, otherwise {@code null}.
     */
    fun send(data: ByteArray): Error? {
        if (mFD < 0) {
            return LocalSocketErrno.ERRNO_USING_CLIENT_SOCKET_WITH_INVALID_FD.getError(
                mFD,
                mLocalSocketRunConfig.getTitle()
            )
        }
        val result = LocalSocketManager.send(
            mLocalSocketRunConfig.getLogTitle() + " (client)",
            mFD,
            data,
            if (mLocalSocketRunConfig.getDeadline() > 0) mCreationTime + mLocalSocketRunConfig.getDeadline() else 0
        )
        if (result == null || result.retval != 0) {
            return LocalSocketErrno.ERRNO_SEND_DATA_TO_CLIENT_SOCKET_FAILED.getError(
                mLocalSocketRunConfig.getTitle(),
                JniResult.getErrorString(result)
            )
        }
        return null
    }

    /**
     * Attempts to read all the bytes available on {@link SocketInputStream} and appends them to
     * {@code data} {@link StringBuilder}.
     *
     * This is a wrapper for {@link #read(byte[], MutableInt)} called via {@link SocketInputStream#read()}.
     *
     * @param data The data {@link StringBuilder} to append the bytes read into.
     * @param closeStreamOnFinish If set to {@code true}, then underlying input stream will closed
     *                            and further attempts to read from socket will fail.
     * @return Returns the {@code error} if reading was not successful containing {@link JniResult}
     * error {@link String}, otherwise {@code null}.
     */
    fun readDataOnInputStream(data: StringBuilder, closeStreamOnFinish: Boolean): Error? {
        var c: Int
        val inputStreamReader = getInputStreamReader()
        try {
            while (inputStreamReader.read().also { c = it } > 0) {
                data.append(c.toChar())
            }
        } catch (e: IOException) {
            // The SocketInputStream.read() throws the Error message in an IOException,
            // so just read the exception message and not the stack trace, otherwise it would result
            // in a messy nested error message.
            return LocalSocketErrno.ERRNO_READ_DATA_FROM_INPUT_STREAM_OF_CLIENT_SOCKET_FAILED_WITH_EXCEPTION.getError(
                mLocalSocketRunConfig.getTitle(),
                DataUtils.getSpaceIndentedString(e.message, 1)
            )
        } catch (e: Exception) {
            return LocalSocketErrno.ERRNO_READ_DATA_FROM_INPUT_STREAM_OF_CLIENT_SOCKET_FAILED_WITH_EXCEPTION.getError(
                e,
                mLocalSocketRunConfig.getTitle(),
                e.message
            )
        } finally {
            if (closeStreamOnFinish) {
                try {
                    inputStreamReader.close()
                } catch (e: IOException) {
                    // Ignore
                }
            }
        }
        return null
    }

    /**
     * Attempts to send all the bytes passed to {@link SocketOutputStream} .
     *
     * This is a wrapper for {@link #send(byte[])} called via {@link SocketOutputStream#write(int)}.
     *
     * @param data The {@link String} bytes to send.
     * @param closeStreamOnFinish If set to {@code true}, then underlying output stream will closed
     *                            and further attempts to send to socket will fail.
     * @return Returns the {@code error} if sending was not successful containing {@link JniResult}
     * error {@link String}, otherwise {@code null}.
     */
    fun sendDataToOutputStream(data: String, closeStreamOnFinish: Boolean): Error? {
        val outputStreamWriter = getOutputStreamWriter()
        try {
            BufferedWriter(outputStreamWriter).use { byteStreamWriter ->
                byteStreamWriter.write(data)
                byteStreamWriter.flush()
            }
        } catch (e: IOException) {
            // The SocketOutputStream.write() throws the Error message in an IOException,
            // so just read the exception message and not the stack trace, otherwise it would result
            // in a messy nested error message.
            return LocalSocketErrno.ERRNO_SEND_DATA_TO_OUTPUT_STREAM_OF_CLIENT_SOCKET_FAILED_WITH_EXCEPTION.getError(
                mLocalSocketRunConfig.getTitle(),
                DataUtils.getSpaceIndentedString(e.message, 1)
            )
        } catch (e: Exception) {
            return LocalSocketErrno.ERRNO_SEND_DATA_TO_OUTPUT_STREAM_OF_CLIENT_SOCKET_FAILED_WITH_EXCEPTION.getError(
                e,
                mLocalSocketRunConfig.getTitle(),
                e.message
            )
        } finally {
            if (closeStreamOnFinish) {
                try {
                    outputStreamWriter.close()
                } catch (e: IOException) {
                    // Ignore
                }
            }
        }
        return null
    }

    /** Wrapper for {@link #available(MutableInt, boolean)} that checks deadline. The
     * {@link SocketInputStream} calls this. */
    fun available(available: MutableInt): Error? {
        return available(available, true)
    }

    /**
     * Get available bytes on {@link #mInputStream} and optionally check if value returned by
     * {@link LocalSocketRunConfig#getDeadline()} has passed.
     */
    fun available(available: MutableInt, checkDeadline: Boolean): Error? {
        available.value = 0
        if (mFD < 0) {
            return LocalSocketErrno.ERRNO_USING_CLIENT_SOCKET_WITH_INVALID_FD.getError(
                mFD,
                mLocalSocketRunConfig.getTitle()
            )
        }
        if (checkDeadline && mLocalSocketRunConfig.getDeadline() > 0 && System.currentTimeMillis() > mCreationTime + mLocalSocketRunConfig.getDeadline()) {
            return null
        }
        val result = LocalSocketManager.available(
            mLocalSocketRunConfig.getLogTitle() + " (client)",
            mLocalSocketRunConfig.getFD()
        )
        if (result == null || result.retval != 0) {
            return LocalSocketErrno.ERRNO_CHECK_AVAILABLE_DATA_ON_CLIENT_SOCKET_FAILED.getError(
                mLocalSocketRunConfig.getTitle(),
                JniResult.getErrorString(result)
            )
        }
        available.value = result.intData
        return null
    }

    /** Set {@link LocalClientSocket} receiving (SO_RCVTIMEO) timeout to value returned by {@link LocalSocketRunConfig#getReceiveTimeout()}. */
    fun setReadTimeout(): Error? {
        if (mFD >= 0) {
            val result = LocalSocketManager.setSocketReadTimeout(
                mLocalSocketRunConfig.getLogTitle() + " (client)",
                mFD,
                mLocalSocketRunConfig.getReceiveTimeout()
            )
            if (result == null || result.retval != 0) {
                return LocalSocketErrno.ERRNO_SET_CLIENT_SOCKET_READ_TIMEOUT_FAILED.getError(
                    mLocalSocketRunConfig.getTitle(),
                    mLocalSocketRunConfig.getReceiveTimeout(),
                    JniResult.getErrorString(result)
                )
            }
        }
        return null
    }

    /** Set {@link LocalClientSocket} sending (SO_SNDTIMEO) timeout to value returned by {@link LocalSocketRunConfig#getSendTimeout()}. */
    fun setWriteTimeout(): Error? {
        if (mFD >= 0) {
            val result = LocalSocketManager.setSocketSendTimeout(
                mLocalSocketRunConfig.getLogTitle() + " (client)",
                mFD,
                mLocalSocketRunConfig.getSendTimeout()
            )
            if (result == null || result.retval != 0) {
                return LocalSocketErrno.ERRNO_SET_CLIENT_SOCKET_SEND_TIMEOUT_FAILED.getError(
                    mLocalSocketRunConfig.getTitle(),
                    mLocalSocketRunConfig.getSendTimeout(),
                    JniResult.getErrorString(result)
                )
            }
        }
        return null
    }

    /** Get {@link #mFD} for the client socket. */
    fun getFD(): Int {
        return mFD
    }

    /** Set {@link #mFD}. Value must be greater than 0 or -1. */
    private fun setFD(fd: Int) {
        if (fd >= 0) mFD = fd else mFD = -1
    }

    /** Get {@link #mPeerCred} for the client socket. */
    fun getPeerCred(): PeerCred {
        return mPeerCred
    }

    /** Get {@link #mCreationTime} for the client socket. */
    fun getCreationTime(): Long {
        return mCreationTime
    }

    /** Get {@link #mOutputStream} for the client socket. The stream will automatically close when client socket is closed. */
    fun getOutputStream(): OutputStream {
        return mOutputStream
    }

    /** Get {@link OutputStreamWriter} for {@link #mOutputStream} for the client socket. The stream will automatically close when client socket is closed. */
    fun getOutputStreamWriter(): OutputStreamWriter {
        return OutputStreamWriter(getOutputStream())
    }

    /** Get {@link #mInputStream} for the client socket. The stream will automatically close when client socket is closed. */
    fun getInputStream(): InputStream {
        return mInputStream
    }

    /** Get {@link InputStreamReader} for {@link #mInputStream} for the client socket. The stream will automatically close when client socket is closed. */
    fun getInputStreamReader(): InputStreamReader {
        return InputStreamReader(getInputStream())
    }

    /** Get a log {@link String} for the {@link LocalClientSocket}. */
    fun getLogString(): String {
        val logString = StringBuilder()
        logString.append("Client Socket:")
        logString.append("\n").append(Logger.getSingleLineLogStringEntry("FD", mFD, "-"))
        logString.append("\n").append(Logger.getSingleLineLogStringEntry("Creation Time", mCreationTime, "-"))
        logString.append("\n\n\n")
        logString.append(mPeerCred.getLogString())
        return logString.toString()
    }

    /** Get a markdown {@link String} for the {@link LocalClientSocket}. */
    fun getMarkdownString(): String {
        val markdownString = StringBuilder()
        markdownString.append("## ").append("Client Socket")
        markdownString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("FD", mFD, "-"))
        markdownString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Creation Time", mCreationTime, "-"))
        markdownString.append("\n\n\n")
        markdownString.append(mPeerCred.getMarkdownString())
        return markdownString.toString()
    }

    /** Wrapper class to allow pass by reference of int values. */
    class MutableInt(@JvmField var value: Int)

    /** The {@link InputStream} implementation for the {@link LocalClientSocket}. */
    protected inner class SocketInputStream : InputStream() {
        private val mBytes = ByteArray(1)

        @Throws(IOException::class)
        override fun read(): Int {
            val bytesRead = MutableInt(0)
            val error = this@LocalClientSocket.read(mBytes, bytesRead)
            if (error != null) {
                throw IOException(error.getErrorMarkdownString())
            }
            if (bytesRead.value == 0) {
                return -1
            }
            return mBytes[0].toInt()
        }

        @Throws(IOException::class)
        override fun read(bytes: ByteArray): Int {
            val bytesRead = MutableInt(0)
            val error = this@LocalClientSocket.read(bytes, bytesRead)
            if (error != null) {
                throw IOException(error.getErrorMarkdownString())
            }
            if (bytesRead.value == 0) {
                return -1
            }
            return bytesRead.value
        }

        @Throws(IOException::class)
        override fun available(): Int {
            val available = MutableInt(0)
            val error = this@LocalClientSocket.available(available)
            if (error != null) {
                throw IOException(error.getErrorMarkdownString())
            }
            return available.value
        }
    }

    /** The {@link OutputStream} implementation for the {@link LocalClientSocket}. */
    protected inner class SocketOutputStream : OutputStream() {
        private val mBytes = ByteArray(1)

        @Throws(IOException::class)
        override fun write(b: Int) {
            mBytes[0] = b.toByte()
            val error = this@LocalClientSocket.send(mBytes)
            if (error != null) {
                throw IOException(error.getErrorMarkdownString())
            }
        }

        @Throws(IOException::class)
        override fun write(bytes: ByteArray) {
            val error = this@LocalClientSocket.send(bytes)
            if (error != null) {
                throw IOException(error.getErrorMarkdownString())
            }
        }
    }

    companion object {
        const val LOG_TAG = "LocalClientSocket"

        /** Close client socket that exists at fd. */
        @JvmStatic
        fun closeClientSocket(localSocketManager: LocalSocketManager, fd: Int) {
            LocalClientSocket(localSocketManager, fd, PeerCred()).closeClientSocket(true)
        }
    }
}
