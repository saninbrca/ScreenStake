package com.detox.app.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

object HapticManager {

    fun light(context: Context) = vibrate(context, 30, 80)

    fun medium(context: Context) = vibrate(context, 60, 120)

    fun success(context: Context) =
        vibratePattern(context, longArrayOf(0, 40, 60, 40, 60, 80))

    fun warning(context: Context) =
        vibratePattern(context, longArrayOf(0, 100, 80, 100))

    fun error(context: Context) = vibrate(context, 200, 255)

    private fun vibrate(context: Context, duration: Long, amplitude: Int) {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = VibrationEffect.createOneShot(
                duration,
                if (vibrator.hasAmplitudeControl()) amplitude else VibrationEffect.DEFAULT_AMPLITUDE
            )
            vibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(duration)
        }
    }

    private fun vibratePattern(context: Context, pattern: LongArray) {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }
}
