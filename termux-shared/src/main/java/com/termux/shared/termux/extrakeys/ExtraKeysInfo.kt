package com.termux.shared.termux.extrakeys

import com.termux.shared.termux.extrakeys.ExtraKeysConstants.EXTRA_KEY_DISPLAY_MAPS
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * A [Class] that defines the info needed by [ExtraKeysView] to display the extra key
 * views.
 *
 * The `propertiesInfo` passed to the constructors of this class must be json array of arrays.
 * Each array element of the json array will be considered a separate row of keys.
 * Each key can either be simple string that defines the name of the key or a json dict that defines
 * advance info for the key. The syntax can be `'KEY'` or `{key: 'KEY'}`.
 * For example `HOME` or `{key: 'HOME', ...}`.
 *
 * In advance json dict mode, the key can also be a sequence of space separated keys instead of one
 * key. This can be done by replacing `key` key/value pair of the dict with a `macro` key/value pair.
 * The syntax is `{macro: 'KEY COMBINATION'}`. For example `{macro: 'HOME RIGHT', ...}`.
 *
 * In advance json dict mode, you can define a nested json dict with the `popup` key which will be
 * used as the popup key and will be triggered on swipe up. The syntax can be
 * `{key: 'KEY', popup: 'POPUP_KEY'}` or `{key: 'KEY', popup: {macro: 'KEY COMBINATION', display: 'Key combo'}}`.
 * For example `{key: 'HOME', popup: {KEY: 'END', ...}, ...}`.
 *
 * In advance json dict mode, the key can also have a custom display name that can be used as the
 * text to display on the button by defining the `display` key. The syntax is `{display: 'DISPLAY'}`.
 * For example `{display: 'Custom name', ...}`.
 *
 * Examples:
 * ```
 * # Empty:
 * []
 *
 * # Single row:
 * [[ESC, TAB, CTRL, ALT, {key: '-', popup: '|'}, DOWN, UP]]
 *
 * # 2 row:
 * [['ESC','/',{key: '-', popup: '|'},'HOME','UP','END','PGUP'],
 * ['TAB','CTRL','ALT','LEFT','DOWN','RIGHT','PGDN']]
 *
 * # Advance:
 * [[
 *   {key: ESC, popup: {macro: "CTRL f d", display: "tmux exit"}},
 *   {key: CTRL, popup: {macro: "CTRL f BKSP", display: "tmux ←"}},
 *   {key: ALT, popup: {macro: "CTRL f TAB", display: "tmux →"}},
 *   {key: TAB, popup: {macro: "ALT a", display: A-a}},
 *   {key: LEFT, popup: HOME},
 *   {key: DOWN, popup: PGDN},
 *   {key: UP, popup: PGUP},
 *   {key: RIGHT, popup: END},
 *   {macro: "ALT j", display: A-j, popup: {macro: "ALT g", display: A-g}},
 *   {key: KEYBOARD, popup: {macro: "CTRL d", display: exit}}
 * ]]
 * ```
 *
 * Aliases are also allowed for the keys that you can pass as `extraKeyAliasMap`. Check
 * [ExtraKeysConstants.CONTROL_CHARS_ALIASES].
 *
 * Its up to the [ExtraKeysView.IExtraKeysView] client on how to handle individual key values
 * of an [ExtraKeyButton]. They are sent as is via
 * [ExtraKeysView.IExtraKeysView.onExtraKeyButtonClick]. The
 * [TerminalExtraKeys] which is an implementation of the interface,
 * checks if the key is one of [ExtraKeysConstants.PRIMARY_KEY_CODES_FOR_STRINGS] and generates
 * a [android.view.KeyEvent] for it, and if its not, then converts the key to code points by
 * calling [CharSequence.codePoints] and passes them to the terminal as literal strings.
 *
 * Examples:
 * ```
 * "ENTER" will trigger the ENTER keycode
 * "LEFT" will trigger the LEFT keycode and be displayed as "←"
 * "→" will input a "→" character
 * "−" will input a "−" character
 * "-_-" will input the string "-_-"
 * ```
 *
 * For more info, check https://wiki.termux.com/wiki/Touch_Keyboard.
 */
class ExtraKeysInfo {

    /**
     * Matrix of buttons to be displayed in [ExtraKeysView].
     */
    private val mButtons: Array<Array<ExtraKeyButton>>

    /**
     * Initialize [ExtraKeysInfo].
     *
     * @param propertiesInfo The [String] containing the info to create the [ExtraKeysInfo].
     *                       Check the class javadoc for details.
     * @param style The style to pass to [getCharDisplayMapForStyle] to get the
     *              [ExtraKeysConstants.ExtraKeyDisplayMap] that defines the display text
     *              mapping for the keys if a custom value is not defined by
     *              [ExtraKeyButton.KEY_DISPLAY_NAME] for a key.
     * @param extraKeyAliasMap The [ExtraKeysConstants.ExtraKeyDisplayMap] that defines the
     *                           aliases for the actual key names. You can create your own or
     *                           optionally pass [ExtraKeysConstants.CONTROL_CHARS_ALIASES].
     */
    @Throws(JSONException::class)
    constructor(
        propertiesInfo: String,
        style: String?,
        extraKeyAliasMap: ExtraKeysConstants.ExtraKeyDisplayMap
    ) {
        mButtons = initExtraKeysInfo(propertiesInfo, getCharDisplayMapForStyle(style), extraKeyAliasMap)
    }

    /**
     * Initialize [ExtraKeysInfo].
     *
     * @param propertiesInfo The [String] containing the info to create the [ExtraKeysInfo].
     *                       Check the class javadoc for details.
     * @param extraKeyDisplayMap The [ExtraKeysConstants.ExtraKeyDisplayMap] that defines the
     *                           display text mapping for the keys if a custom value is not defined
     *                           by [ExtraKeyButton.KEY_DISPLAY_NAME] for a key. You can create
     *                           your own or optionally pass one of the values defined in
     *                           [getCharDisplayMapForStyle].
     * @param extraKeyAliasMap The [ExtraKeysConstants.ExtraKeyDisplayMap] that defines the
     *                           aliases for the actual key names. You can create your own or
     *                           optionally pass [ExtraKeysConstants.CONTROL_CHARS_ALIASES].
     */
    @Throws(JSONException::class)
    constructor(
        propertiesInfo: String,
        extraKeyDisplayMap: ExtraKeysConstants.ExtraKeyDisplayMap,
        extraKeyAliasMap: ExtraKeysConstants.ExtraKeyDisplayMap
    ) {
        mButtons = initExtraKeysInfo(propertiesInfo, extraKeyDisplayMap, extraKeyAliasMap)
    }

    @Throws(JSONException::class)
    private fun initExtraKeysInfo(
        propertiesInfo: String,
        extraKeyDisplayMap: ExtraKeysConstants.ExtraKeyDisplayMap,
        extraKeyAliasMap: ExtraKeysConstants.ExtraKeyDisplayMap
    ): Array<Array<ExtraKeyButton>> {
        // Convert String propertiesInfo to Array of Arrays
        val arr = JSONArray(propertiesInfo)
        val buttons = Array(arr.length()) { i ->
            val line = arr.getJSONArray(i)
            Array(line.length()) { j ->
                val key = line.get(j)
                val jobject = normalizeKeyConfig(key)
                if (!jobject.has(ExtraKeyButton.KEY_POPUP)) {
                    // no popup
                    ExtraKeyButton(jobject, extraKeyDisplayMap, extraKeyAliasMap)
                } else {
                    // a popup
                    val popupJobject = normalizeKeyConfig(jobject.get(ExtraKeyButton.KEY_POPUP))
                    val popup = ExtraKeyButton(popupJobject, extraKeyDisplayMap, extraKeyAliasMap)
                    ExtraKeyButton(jobject, popup, extraKeyDisplayMap, extraKeyAliasMap)
                }
            }
        }
        return buttons
    }

    val matrix: Array<Array<ExtraKeyButton>>
        get() = mButtons

    companion object {
        @JvmStatic
        fun getCharDisplayMapForStyle(style: String?): ExtraKeysConstants.ExtraKeyDisplayMap {
            return when (style) {
                "arrows-only" -> EXTRA_KEY_DISPLAY_MAPS.ARROWS_ONLY_CHAR_DISPLAY
                "arrows-all" -> EXTRA_KEY_DISPLAY_MAPS.LOTS_OF_ARROWS_CHAR_DISPLAY
                "all" -> EXTRA_KEY_DISPLAY_MAPS.FULL_ISO_CHAR_DISPLAY
                "none" -> ExtraKeysConstants.ExtraKeyDisplayMap()
                else -> EXTRA_KEY_DISPLAY_MAPS.DEFAULT_CHAR_DISPLAY
            }
        }

        @Throws(JSONException::class)
        @JvmStatic
        private fun normalizeKeyConfig(key: Any?): JSONObject {
            return when (key) {
                is String -> {
                    val jobject = JSONObject()
                    jobject.put(ExtraKeyButton.KEY_KEY_NAME, key)
                    jobject
                }
                is JSONObject -> key
                else -> throw JSONException("An key in the extra-key matrix must be a string or an object")
            }
        }
    }
}
