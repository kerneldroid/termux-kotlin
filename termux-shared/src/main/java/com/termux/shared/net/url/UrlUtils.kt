package com.termux.shared.net.url

import com.termux.shared.data.DataUtils
import com.termux.shared.logger.Logger
import java.net.MalformedURLException
import java.net.URL

object UrlUtils {

    /** The parts of a {@link URL}. */
    enum class UrlPart {
        AUTHORITY,
        FILE,
        HOST,
        REF,
        FRAGMENT,
        PATH,
        PORT,
        PROTOCOL,
        QUERY,
        USER_INFO
    }

    private const val LOG_TAG = "UrlUtils"

    private val PROTOCOL_REGEX = "^(http[s]?://www\\.|http[s]?://|www\\.)".toRegex()
    private val TRAILING_SLASHES_REGEX = "/+$".toRegex()

    /**
     * Join a url base and destination.
     *
     * @param base The base url to open.
     * @param destination The destination url to open.
     * @param logError If an error message should be logged.
     * @return Returns the joined {@link String} Url, otherwise {@code null}.
     */
    @JvmStatic
    fun joinUrl(base: String?, destination: String?, logError: Boolean): String? {
        if (base.isNullOrEmpty()) return null
        return try {
            URL(URL(base), destination).toString()
        } catch (e: MalformedURLException) {
            if (logError) {
                Logger.logError(LOG_TAG, "Failed to join url base \"$base\" and destination \"$destination\": ${e.message}")
            }
            null
        }
    }

    /**
     * Get {@link URL} from url string.
     *
     * @param urlString The urlString string.
     * @return Returns the {@link URL} if a valid urlString, otherwise {@code null}.
     */
    @JvmStatic
    fun getUrl(urlString: String?): URL? {
        if (urlString.isNullOrEmpty()) return null
        return try {
            URL(urlString)
        } catch (e: MalformedURLException) {
            null
        }
    }

    /**
     * Get a {@link URL} part from url string.
     *
     * @param urlString The urlString string.
     * @param urlPart The part to get.
     * @return Returns the {@link URL} part if a valid urlString and part, otherwise {@code null}.
     */
    @JvmStatic
    fun getUrlPart(urlString: String?, urlPart: UrlPart?): String? {
        val url = getUrl(urlString) ?: return null
        return when (urlPart) {
            UrlPart.AUTHORITY -> url.authority
            UrlPart.FILE -> url.file
            UrlPart.HOST -> url.host
            UrlPart.REF, UrlPart.FRAGMENT -> url.ref
            UrlPart.PATH -> url.path
            UrlPart.PORT -> url.port.toString()
            UrlPart.PROTOCOL -> url.protocol
            UrlPart.QUERY -> url.query
            UrlPart.USER_INFO -> url.userInfo
            else -> null
        }
    }

    /** Remove "https://www.", "https://", "www.", etc */
    @JvmStatic
    fun removeProtocol(urlString: String?): String? {
        if (urlString == null) return null
        return urlString.replaceFirst(PROTOCOL_REGEX, "")
    }

    @JvmStatic
    fun areUrlsEqual(url1: String?, url2: String?): Boolean {
        if (url1 == null && url2 == null) return true
        if (url1 == null || url2 == null) return false
        val cleanUrl1 = removeProtocol(url1)?.replace(TRAILING_SLASHES_REGEX, "")
        val cleanUrl2 = removeProtocol(url2)?.replace(TRAILING_SLASHES_REGEX, "")
        return cleanUrl1 == cleanUrl2
    }
}
