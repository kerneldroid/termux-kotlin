package com.termux.shared.data

import android.os.Bundle
import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream
import java.io.Serializable

object DataUtils {

    /** Max safe limit of data size to prevent TransactionTooLargeException when transferring data
     * inside or to other apps via transactions. */
    const val TRANSACTION_SIZE_LIMIT_IN_BYTES = 100 * 1024 // 100KB

    private val HEX_ARRAY = "0123456789ABCDEF".toCharArray()

    @JvmStatic
    fun getTruncatedCommandOutput(text: String?, maxLength: Int, fromEnd: Boolean, onNewline: Boolean, addPrefix: Boolean): String? {
        if (text == null) return null

        val prefix = "(truncated) "
        var adjustedMaxLength = maxLength

        if (addPrefix) {
            adjustedMaxLength -= prefix.length
        }

        if (adjustedMaxLength < 0 || text.length < adjustedMaxLength) return text

        var resultText = text
        if (fromEnd) {
            resultText = text.substring(0, adjustedMaxLength)
        } else {
            var cutOffIndex = text.length - adjustedMaxLength

            if (onNewline) {
                val nextNewlineIndex = text.indexOf('\n', cutOffIndex)
                if (nextNewlineIndex != -1 && nextNewlineIndex != text.length - 1) {
                    cutOffIndex = nextNewlineIndex + 1
                }
            }
            resultText = text.substring(cutOffIndex)
        }

        if (addPrefix) {
            resultText = prefix + resultText
        }

        return resultText
    }

    /**
     * Replace a sub string in each item of a {@link String[]}.
     *
     * @param array The {@link String[]} to replace in.
     * @param find The sub string to replace.
     * @param replace The sub string to replace with.
     */
    @JvmStatic
    fun replaceSubStringsInStringArrayItems(array: Array<String>?, find: String, replace: String) {
        if (array.isNullOrEmpty()) return

        for (i in array.indices) {
            array[i] = array[i].replace(find, replace)
        }
    }

    /**
     * Get the {@code float} from a {@link String}.
     *
     * @param value The {@link String} value.
     * @param def The default value if failed to read a valid value.
     * @return Returns the {@code float} value after parsing the {@link String} value, otherwise
     * returns default if failed to read a valid value, like in case of an exception.
     */
    @JvmStatic
    fun getFloatFromString(value: String?, def: Float): Float {
        if (value == null) return def

        return try {
            value.toFloat()
        } catch (e: Exception) {
            def
        }
    }

    /**
     * Get the {@code int} from a {@link String}.
     *
     * @param value The {@link String} value.
     * @param def The default value if failed to read a valid value.
     * @return Returns the {@code int} value after parsing the {@link String} value, otherwise
     * returns default if failed to read a valid value, like in case of an exception.
     */
    @JvmStatic
    fun getIntFromString(value: String?, def: Int): Int {
        if (value == null) return def

        return try {
            value.toInt()
        } catch (e: Exception) {
            def
        }
    }

    /**
     * Get the {@code String} from an {@link Integer}.
     *
     * @param value The {@link Integer} value.
     * @param def The default {@link String} value.
     * @return Returns {@code value} if it is not {@code null}, otherwise returns {@code def}.
     */
    @JvmStatic
    fun getStringFromInteger(value: Int?, def: String?): String? {
        return if (value == null) def else value.toString()
    }

    /**
     * Get the {@code hex string} from a {@link byte[]}.
     *
     * @param bytes The {@link byte[]} value.
     * @return Returns the {@code hex string} value.
     */
    @JvmStatic
    fun bytesToHex(bytes: ByteArray): String {
        val hexChars = CharArray(bytes.size * 2)
        for (j in bytes.indices) {
            val v = bytes[j].toInt() and 0xFF
            hexChars[j * 2] = HEX_ARRAY[v ushr 4]
            hexChars[j * 2 + 1] = HEX_ARRAY[v and 0x0F]
        }
        return String(hexChars)
    }

    /**
     * Get an {@code int} from {@link Bundle} that is stored as a {@link String}.
     *
     * @param bundle The {@link Bundle} to get the value from.
     * @param key The key for the value.
     * @param def The default value if failed to read a valid value.
     * @return Returns the {@code int} value after parsing the {@link String} value stored in
     * {@link Bundle}, otherwise returns default if failed to read a valid value,
     * like in case of an exception.
     */
    @JvmStatic
    fun getIntStoredAsStringFromBundle(bundle: Bundle?, key: String?, def: Int): Int {
        if (bundle == null) return def
        return getIntFromString(bundle.getString(key) ?: def.toString(), def)
    }

    /**
     * If value is not in the range [min, max], set it to either min or max.
     */
    @JvmStatic
    fun clamp(value: Int, min: Int, max: Int): Int {
        return value.coerceIn(min, max)
    }

    /**
     * If value is not in the range [min, max], set it to default.
     */
    @JvmStatic
    fun rangedOrDefault(value: Float, def: Float, min: Float, max: Float): Float {
        return if (value < min || value > max) def else value
    }

    /**
     * Add a space indent to a {@link String}. Each indent is 4 space characters long.
     *
     * @param string The {@link String} to add indent to.
     * @param count The indent count.
     * @return Returns the indented {@link String}.
     */
    @JvmStatic
    fun getSpaceIndentedString(string: String?, count: Int): String? {
        return if (string.isNullOrEmpty()) string else getIndentedString(string, "    ", count)
    }

    /**
     * Add a tab indent to a {@link String}. Each indent is 1 tab character long.
     *
     * @param string The {@link String} to add indent to.
     * @param count The indent count.
     * @return Returns the indented {@link String}.
     */
    @JvmStatic
    fun getTabIndentedString(string: String?, count: Int): String? {
        return if (string.isNullOrEmpty()) string else getIndentedString(string, "\t", count)
    }

    /**
     * Add an indent to a {@link String}.
     *
     * @param string The {@link String} to add indent to.
     * @param indent The indent characters.
     * @param count The indent count.
     * @return Returns the indented {@link String}.
     */
    @JvmStatic
    fun getIndentedString(string: String?, indent: String, count: Int): String? {
        return if (string.isNullOrEmpty()) {
            string
        } else {
            string.replace("(?m)^".toRegex(), indent.repeat(maxOf(count, 1)))
        }
    }

    /**
     * Get the object itself if it is not {@code null}, otherwise default.
     *
     * @param obj The {@link Object} to check.
     * @param def The default {@link Object}.
     * @return Returns {@code object} if it is not {@code null}, otherwise returns {@code def}.
     */
    @JvmStatic
    fun <T> getDefaultIfNull(obj: T?, def: T?): T? {
        return obj ?: def
    }

    /**
     * Get the {@link String} itself if it is not {@code null} or empty, otherwise default.
     *
     * @param value The {@link String} to check.
     * @param def The default {@link String}.
     * @return Returns {@code value} if it is not {@code null} or empty, otherwise returns {@code def}.
     */
    @JvmStatic
    fun getDefaultIfUnset(value: String?, def: String?): String? {
        return if (value.isNullOrEmpty()) def else value
    }

    /** Check if a string is null or empty. */
    @JvmStatic
    fun isNullOrEmpty(string: String?): Boolean {
        return string.isNullOrEmpty()
    }

    /** Get size of a serializable object. */
    @JvmStatic
    fun getSerializedSize(obj: Serializable?): Long {
        if (obj == null) return 0
        return try {
            val byteOutputStream = ByteArrayOutputStream()
            ObjectOutputStream(byteOutputStream).use { objectOutputStream ->
                objectOutputStream.writeObject(obj)
                objectOutputStream.flush()
            }
            byteOutputStream.toByteArray().size.toLong()
        } catch (e: Exception) {
            -1
        }
    }
}
