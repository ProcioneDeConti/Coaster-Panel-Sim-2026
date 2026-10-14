package com.abemu.coaster.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.abemu.coaster.SimViewModel
import com.abemu.coaster.sim.RideState
import kotlinx.coroutines.delay

/** Vibration feedback for button presses and ride events. Silently does nothing without a vibrator. */
object Haptics {
    var enabled = true
    private var vibrator: Vibrator? = null

    fun init(context: Context) {
        vibrator = if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private fun play(effect: () -> VibrationEffect) {
        val v = vibrator ?: return
        if (enabled && v.hasVibrator()) v.vibrate(effect())
    }

    private fun predefined(id: Int, fallbackMs: Long, amp: Int) =
        if (Build.VERSION.SDK_INT >= 29) VibrationEffect.createPredefined(id) else VibrationEffect.createOneShot(fallbackMs, amp)

    private fun pulses(vararg onMs: Long, amp: Int) = VibrationEffect.createWaveform(
        LongArray(onMs.size * 2) { if (it % 2 == 0) (if (it == 0) 0 else 80) else onMs[it / 2] },
        IntArray(onMs.size * 2) { if (it % 2 == 0) 0 else amp },
        -1,
    )

    /** HMI touch key. */
    fun tick() = play { predefined(VibrationEffect.EFFECT_TICK, 10, 60) }
    /** Console pushbutton or key switch. */
    fun click() = play { predefined(VibrationEffect.EFFECT_CLICK, 20, 160) }
    fun dispatch() = play { pulses(40, 40, amp = 200) }
    fun alarm() = play { pulses(120, 120, amp = 180) }
    fun estop() = play { pulses(350, 350, amp = 255) }
}

/** Watches the simulation and fires event haptics: E-Stop, dispatch, and new stop-level alarms. */
@Composable
fun RideHaptics(vm: SimViewModel) {
    LaunchedEffect(Unit) {
        var lastPlc = vm.plc
        var lastState = lastPlc.state
        var lastDispatch = lastPlc.dispatchCount
        var lastHorn = lastPlc.hornActive
        while (true) {
            delay(40)
            Haptics.enabled = vm.hapticsOn
            val plc = vm.plc
            if (plc !== lastPlc) {
                lastPlc = plc; lastState = plc.state; lastDispatch = plc.dispatchCount; lastHorn = plc.hornActive
            }
            when {
                plc.state == RideState.ESTOP && lastState != RideState.ESTOP -> Haptics.estop()
                plc.dispatchCount > lastDispatch -> Haptics.dispatch()
                plc.hornActive && !lastHorn -> Haptics.alarm()
            }
            lastState = plc.state
            lastDispatch = plc.dispatchCount
            lastHorn = plc.hornActive
        }
    }
}
