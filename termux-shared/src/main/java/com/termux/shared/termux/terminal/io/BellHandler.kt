package com.termux.shared.termux.terminal.io

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import com.termux.shared.logger.Logger

class BellHandler private constructor(vibrator: Vibrator?) {

    private val handler = Handler(Looper.getMainLooper())
    private var lastBell = 0L
    private val bellRunnable = Runnable {
        if (vibrator != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(DURATION, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(DURATION)
                }
            } catch (e: Exception) {
                // Issue on samsung devices on android 8
                // java.lang.NullPointerException: Attempt to read from field 'android.os.VibrationEffect com.android.server.VibratorService$Vibration.mEffect' on a null object reference
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to run vibrator", e)
            }
        }
    }

    @Synchronized
    fun doBell() {
        val now = now()
        val timeSinceLastBell = now - lastBell

        if (timeSinceLastBell < 0) {
            // there is a next bell pending; don't schedule another one
        } else if (timeSinceLastBell < MIN_PAUSE) {
            // there was a bell recently, schedule the next one
            handler.postDelayed(bellRunnable, MIN_PAUSE - timeSinceLastBell)
            lastBell = lastBell + MIN_PAUSE
        } else {
            // the last bell was long ago, do it now
            bellRunnable.run()
            lastBell = now
        }
    }

    private fun now(): Long {
        return SystemClock.uptimeMillis()
    }

    companion object {
        private const val LOG_TAG = "BellHandler"
        private const val DURATION = 50L
        private const val MIN_PAUSE = 3 * DURATION

        @Volatile
        private var instance: BellHandler? = null

        @JvmStatic
        fun getInstance(context: Context): BellHandler {
            return instance ?: synchronized(this) {
                instance ?: BellHandler(
                    context.applicationContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                ).also { instance = it }
            }
        }
    }
}
