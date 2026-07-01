package com.termux.terminal

import android.view.KeyEvent.*
import java.util.HashMap

object KeyHandler {

    const val KEYMOD_ALT = 0x80000000.toInt()
    const val KEYMOD_CTRL = 0x40000000
    const val KEYMOD_SHIFT = 0x20000000
    const val KEYMOD_NUM_LOCK = 0x10000000

    private val TERMCAP_TO_KEYCODE = HashMap<String, Int>().apply {
        // terminfo: http://pubs.opengroup.org/onlinepubs/7990989799/xcurses/terminfo.html
        // termcap: http://man7.org/linux/man-pages/man5/termcap.5.html
        put("%i", KEYMOD_SHIFT or KEYCODE_DPAD_RIGHT)
        put("#2", KEYMOD_SHIFT or KEYCODE_MOVE_HOME) // Shifted home
        put("#4", KEYMOD_SHIFT or KEYCODE_DPAD_LEFT)
        put("*7", KEYMOD_SHIFT or KEYCODE_MOVE_END) // Shifted end key

        put("k1", KEYCODE_F1)
        put("k2", KEYCODE_F2)
        put("k3", KEYCODE_F3)
        put("k4", KEYCODE_F4)
        put("k5", KEYCODE_F5)
        put("k6", KEYCODE_F6)
        put("k7", KEYCODE_F7)
        put("k8", KEYCODE_F8)
        put("k9", KEYCODE_F9)
        put("k;", KEYCODE_F10)
        put("F1", KEYCODE_F11)
        put("F2", KEYCODE_F12)
        put("F3", KEYMOD_SHIFT or KEYCODE_F1)
        put("F4", KEYMOD_SHIFT or KEYCODE_F2)
        put("F5", KEYMOD_SHIFT or KEYCODE_F3)
        put("F6", KEYMOD_SHIFT or KEYCODE_F4)
        put("F7", KEYMOD_SHIFT or KEYCODE_F5)
        put("F8", KEYMOD_SHIFT or KEYCODE_F6)
        put("F9", KEYMOD_SHIFT or KEYCODE_F7)
        put("FA", KEYMOD_SHIFT or KEYCODE_F8)
        put("FB", KEYMOD_SHIFT or KEYCODE_F9)
        put("FC", KEYMOD_SHIFT or KEYCODE_F10)
        put("FD", KEYMOD_SHIFT or KEYCODE_F11)
        put("FE", KEYMOD_SHIFT or KEYCODE_F12)

        put("kb", KEYCODE_DEL) // backspace key

        put("kd", KEYCODE_DPAD_DOWN) // terminfo=kcud1, down-arrow key
        put("kh", KEYCODE_MOVE_HOME)
        put("kl", KEYCODE_DPAD_LEFT)
        put("kr", KEYCODE_DPAD_RIGHT)

        // K1=Upper left of keypad:
        // t_K1 <kHome> keypad home key
        // t_K3 <kPageUp> keypad page-up key
        // t_K4 <kEnd> keypad end key
        // t_K5 <kPageDown> keypad page-down key
        put("K1", KEYCODE_MOVE_HOME)
        put("K3", KEYCODE_PAGE_UP)
        put("K4", KEYCODE_MOVE_END)
        put("K5", KEYCODE_PAGE_DOWN)

        put("ku", KEYCODE_DPAD_UP)

        put("kB", KEYMOD_SHIFT or KEYCODE_TAB) // termcap=kB, terminfo=kcbt: Back-tab
        put("kD", KEYCODE_FORWARD_DEL) // terminfo=kdch1, delete-character key
        put("kDN", KEYMOD_SHIFT or KEYCODE_DPAD_DOWN) // non-standard shifted arrow down
        put("kF", KEYMOD_SHIFT or KEYCODE_DPAD_DOWN) // terminfo=kind, scroll-forward key
        put("kI", KEYCODE_INSERT)
        put("kP", KEYCODE_PAGE_UP)
        put("kN", KEYCODE_PAGE_DOWN)
        put("kR", KEYMOD_SHIFT or KEYCODE_DPAD_UP) // terminfo=kri, scroll-backward key
        put("kUP", KEYMOD_SHIFT or KEYCODE_DPAD_UP) // non-standard shifted up

        put("@7", KEYCODE_MOVE_END)
        put("@8", KEYCODE_NUMPAD_ENTER)
    }

    @JvmStatic
    @JvmName("getCodeFromTermcap")
    internal fun getCodeFromTermcap(termcap: String?, cursorKeysApplication: Boolean, keypadApplication: Boolean): String? {
        val keyCodeAndMod = TERMCAP_TO_KEYCODE[termcap] ?: return null
        var keyCode = keyCodeAndMod
        var keyMod = 0
        if ((keyCode and KEYMOD_SHIFT) != 0) {
            keyMod = keyMod or KEYMOD_SHIFT
            keyCode = keyCode and KEYMOD_SHIFT.inv()
        }
        if ((keyCode and KEYMOD_CTRL) != 0) {
            keyMod = keyMod or KEYMOD_CTRL
            keyCode = keyCode and KEYMOD_CTRL.inv()
        }
        if ((keyCode and KEYMOD_ALT) != 0) {
            keyMod = keyMod or KEYMOD_ALT
            keyCode = keyCode and KEYMOD_ALT.inv()
        }
        if ((keyCode and KEYMOD_NUM_LOCK) != 0) {
            keyMod = keyMod or KEYMOD_NUM_LOCK
            keyCode = keyCode and KEYMOD_NUM_LOCK.inv()
        }
        return getCode(keyCode, keyMod, cursorKeysApplication, keypadApplication)
    }

    @JvmStatic
    fun getCode(keyCode: Int, keyMode: Int, cursorApp: Boolean, keypadApplication: Boolean): String? {
        val numLockOn = (keyMode and KEYMOD_NUM_LOCK) != 0
        var keyMode = keyMode and KEYMOD_NUM_LOCK.inv()
        return when (keyCode) {
            KEYCODE_DPAD_CENTER -> "\r"

            KEYCODE_DPAD_UP -> if (keyMode == 0) (if (cursorApp) "\u001BOA" else "\u001B[A") else transformForModifiers("\u001B[1", keyMode, 'A')
            KEYCODE_DPAD_DOWN -> if (keyMode == 0) (if (cursorApp) "\u001BOB" else "\u001B[B") else transformForModifiers("\u001B[1", keyMode, 'B')
            KEYCODE_DPAD_RIGHT -> if (keyMode == 0) (if (cursorApp) "\u001BOC" else "\u001B[C") else transformForModifiers("\u001B[1", keyMode, 'C')
            KEYCODE_DPAD_LEFT -> if (keyMode == 0) (if (cursorApp) "\u001BOD" else "\u001B[D") else transformForModifiers("\u001B[1", keyMode, 'D')

            KEYCODE_MOVE_HOME ->
                // Note that KEYCODE_HOME is handled by the system and never delivered to applications.
                // On a Logitech k810 keyboard KEYCODE_MOVE_HOME is sent by FN+LeftArrow.
                if (keyMode == 0) (if (cursorApp) "\u001BOH" else "\u001B[H") else transformForModifiers("\u001B[1", keyMode, 'H')
            KEYCODE_MOVE_END ->
                if (keyMode == 0) (if (cursorApp) "\u001BOF" else "\u001B[F") else transformForModifiers("\u001B[1", keyMode, 'F')

            // An xterm can send function keys F1 to F4 in two modes: vt100 compatible or
            // not. Because Vim may not know what the xterm is sending, both types of keys
            // are recognized. The same happens for the <Home> and <End> keys.
            // normal vt100 ~
            // <F1> t_k1 <Esc>[11~ <xF1> <Esc>OP *<xF1>-xterm*
            // <F2> t_k2 <Esc>[12~ <xF2> <Esc>OQ *<xF2>-xterm*
            // <F3> t_k3 <Esc>[13~ <xF3> <Esc>OR *<xF3>-xterm*
            // <F4> t_k4 <Esc>[14~ <xF4> <Esc>OS *<xF4>-xterm*
            // <Home> t_kh <Esc>[7~ <xHome> <Esc>OH *<xHome>-xterm*
            // <End> t_@7 <Esc>[4~ <xEnd> <Esc>OF *<xEnd>-xterm*
            KEYCODE_F1 -> if (keyMode == 0) "\u001BOP" else transformForModifiers("\u001B[1", keyMode, 'P')
            KEYCODE_F2 -> if (keyMode == 0) "\u001BOQ" else transformForModifiers("\u001B[1", keyMode, 'Q')
            KEYCODE_F3 -> if (keyMode == 0) "\u001BOR" else transformForModifiers("\u001B[1", keyMode, 'R')
            KEYCODE_F4 -> if (keyMode == 0) "\u001BOS" else transformForModifiers("\u001B[1", keyMode, 'S')
            KEYCODE_F5 -> transformForModifiers("\u001B[15", keyMode, '~')
            KEYCODE_F6 -> transformForModifiers("\u001B[17", keyMode, '~')
            KEYCODE_F7 -> transformForModifiers("\u001B[18", keyMode, '~')
            KEYCODE_F8 -> transformForModifiers("\u001B[19", keyMode, '~')
            KEYCODE_F9 -> transformForModifiers("\u001B[20", keyMode, '~')
            KEYCODE_F10 -> transformForModifiers("\u001B[21", keyMode, '~')
            KEYCODE_F11 -> transformForModifiers("\u001B[23", keyMode, '~')
            KEYCODE_F12 -> transformForModifiers("\u001B[24", keyMode, '~')

            KEYCODE_SYSRQ -> "\u001B[32~" // Sys Request / Print
            // Is this Scroll lock? case Cancel: return "\033[33~";
            KEYCODE_BREAK -> "\u001B[34~" // Pause/Break

            KEYCODE_ESCAPE, KEYCODE_BACK -> "\u001B"

            KEYCODE_INSERT -> transformForModifiers("\u001B[2", keyMode, '~')
            KEYCODE_FORWARD_DEL -> transformForModifiers("\u001B[3", keyMode, '~')

            KEYCODE_PAGE_UP -> transformForModifiers("\u001B[5", keyMode, '~')
            KEYCODE_PAGE_DOWN -> transformForModifiers("\u001B[6", keyMode, '~')
            KEYCODE_DEL -> {
                val prefix = if ((keyMode and KEYMOD_ALT) == 0) "" else "\u001B"
                // Just do what xterm and gnome-terminal does:
                prefix + if ((keyMode and KEYMOD_CTRL) == 0) "\u007F" else "\u0008"
            }
            KEYCODE_NUM_LOCK -> if (keypadApplication) "\u001BOP" else null
            KEYCODE_SPACE ->
                // If ctrl is not down, return null so that it goes through normal input processing (which may e.g. cause a
                // combining accent to be written):
                if ((keyMode and KEYMOD_CTRL) == 0) null else "\u0000"
            KEYCODE_TAB ->
                // This is back-tab when shifted:
                if ((keyMode and KEYMOD_SHIFT) == 0) "\u0009" else "\u001B[Z"
            KEYCODE_ENTER -> if ((keyMode and KEYMOD_ALT) == 0) "\r" else "\u001B\r"

            KEYCODE_NUMPAD_ENTER -> if (keypadApplication) transformForModifiers("\u001BO", keyMode, 'M') else "\n"
            KEYCODE_NUMPAD_MULTIPLY -> if (keypadApplication) transformForModifiers("\u001BO", keyMode, 'j') else "*"
            KEYCODE_NUMPAD_ADD -> if (keypadApplication) transformForModifiers("\u001BO", keyMode, 'k') else "+"
            KEYCODE_NUMPAD_COMMA -> ","
            KEYCODE_NUMPAD_DOT -> if (numLockOn) {
                if (keypadApplication) "\u001BOn" else "."
            } else {
                // DELETE
                transformForModifiers("\u001B[3", keyMode, '~')
            }
            KEYCODE_NUMPAD_SUBTRACT -> if (keypadApplication) transformForModifiers("\u001BO", keyMode, 'm') else "-"
            KEYCODE_NUMPAD_DIVIDE -> if (keypadApplication) transformForModifiers("\u001BO", keyMode, 'o') else "/"
            KEYCODE_NUMPAD_0 -> if (numLockOn) {
                if (keypadApplication) transformForModifiers("\u001BO", keyMode, 'p') else "0"
            } else {
                // INSERT
                transformForModifiers("\u001B[2", keyMode, '~')
            }
            KEYCODE_NUMPAD_1 -> if (numLockOn) {
                if (keypadApplication) transformForModifiers("\u001BO", keyMode, 'q') else "1"
            } else {
                // END
                if (keyMode == 0) (if (cursorApp) "\u001BOF" else "\u001B[F") else transformForModifiers("\u001B[1", keyMode, 'F')
            }
            KEYCODE_NUMPAD_2 -> if (numLockOn) {
                if (keypadApplication) transformForModifiers("\u001BO", keyMode, 'r') else "2"
            } else {
                // DOWN
                if (keyMode == 0) (if (cursorApp) "\u001BOB" else "\u001B[B") else transformForModifiers("\u001B[1", keyMode, 'B')
            }
            KEYCODE_NUMPAD_3 -> if (numLockOn) {
                if (keypadApplication) transformForModifiers("\u001BO", keyMode, 's') else "3"
            } else {
                // PGDN
                "\u001B[6~"
            }
            KEYCODE_NUMPAD_4 -> if (numLockOn) {
                if (keypadApplication) transformForModifiers("\u001BO", keyMode, 't') else "4"
            } else {
                // LEFT
                if (keyMode == 0) (if (cursorApp) "\u001BOD" else "\u001B[D") else transformForModifiers("\u001B[1", keyMode, 'D')
            }
            KEYCODE_NUMPAD_5 -> if (keypadApplication) transformForModifiers("\u001BO", keyMode, 'u') else "5"
            KEYCODE_NUMPAD_6 -> if (numLockOn) {
                if (keypadApplication) transformForModifiers("\u001BO", keyMode, 'v') else "6"
            } else {
                // RIGHT
                if (keyMode == 0) (if (cursorApp) "\u001BOC" else "\u001B[C") else transformForModifiers("\u001B[1", keyMode, 'C')
            }
            KEYCODE_NUMPAD_7 -> if (numLockOn) {
                if (keypadApplication) transformForModifiers("\u001BO", keyMode, 'w') else "7"
            } else {
                // HOME
                if (keyMode == 0) (if (cursorApp) "\u001BOH" else "\u001B[H") else transformForModifiers("\u001B[1", keyMode, 'H')
            }
            KEYCODE_NUMPAD_8 -> if (numLockOn) {
                if (keypadApplication) transformForModifiers("\u001BO", keyMode, 'x') else "8"
            } else {
                // UP
                if (keyMode == 0) (if (cursorApp) "\u001BOA" else "\u001B[A") else transformForModifiers("\u001B[1", keyMode, 'A')
            }
            KEYCODE_NUMPAD_9 -> if (numLockOn) {
                if (keypadApplication) transformForModifiers("\u001BO", keyMode, 'y') else "9"
            } else {
                // PGUP
                "\u001B[5~"
            }
            KEYCODE_NUMPAD_EQUALS -> if (keypadApplication) transformForModifiers("\u001BO", keyMode, 'X') else "="
            else -> null
        }
    }

    private fun transformForModifiers(start: String, keymod: Int, lastChar: Char): String {
        val modifier: Int = when (keymod) {
            KEYMOD_SHIFT -> 2
            KEYMOD_ALT -> 3
            KEYMOD_SHIFT or KEYMOD_ALT -> 4
            KEYMOD_CTRL -> 5
            KEYMOD_SHIFT or KEYMOD_CTRL -> 6
            KEYMOD_ALT or KEYMOD_CTRL -> 7
            KEYMOD_SHIFT or KEYMOD_ALT or KEYMOD_CTRL -> 8
            else -> return start + lastChar
        }
        return start + ";" + modifier + lastChar
    }
}
