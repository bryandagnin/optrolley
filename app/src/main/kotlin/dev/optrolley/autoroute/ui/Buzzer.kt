package dev.optrolley.autoroute.ui

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.optrolley.autoroute.Buzz

/**
 * Makes a buzz felt whatever the phone's touch-vibration setting says
 * (D22): through the vibrator, which `performHapticFeedback` is not, as
 * media, so it follows the phone's media vibration strength and its master
 * switch instead. Predefined effects, which a motor without amplitude
 * control still plays.
 */
class Buzzer(context: Context) {
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else context.getSystemService(Vibrator::class.java)

    fun buzz(buzz: Buzz) {
        val v = vibrator?.takeIf { it.hasVibrator() } ?: return
        val effect = VibrationEffect.createPredefined(
            if (buzz == Buzz.UNTICKED) VibrationEffect.EFFECT_TICK else VibrationEffect.EFFECT_HEAVY_CLICK,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
        }
    }
}

@Composable
fun rememberBuzzer(): Buzzer {
    val context = LocalContext.current
    return remember(context) { Buzzer(context.applicationContext) }
}
