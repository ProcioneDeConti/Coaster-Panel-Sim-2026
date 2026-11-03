package com.abemu.coaster.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abemu.coaster.SimViewModel

/**
 * Power-on sequence, once per app process: the HMI shows a runtime splash while the console does a
 * lamp test (every lamp lit together, as an operator would check for a blown LED before opening).
 */
object Boot {
    private const val LAMP_ON = 0.35f
    private const val LAMP_OFF = 2.0f
    const val END = 3.8f

    /** Seconds since boot started. */
    var elapsed by mutableFloatStateOf(0f); private set
    private var started = false

    // Derived, so devices only recompose when the lamp test switches, not on every boot frame.
    private val lamp = derivedStateOf { elapsed in LAMP_ON..LAMP_OFF }
    private val splashState = derivedStateOf { elapsed < END }
    val lampTest: Boolean get() = lamp.value
    val splash: Boolean get() = splashState.value

    suspend fun run() {
        if (started) return
        started = true
        val t0 = withFrameNanos { it }
        while (elapsed < END) withFrameNanos { elapsed = ((it - t0) / 1e9f).coerceAtLeast(elapsed) }
    }

    /** Tap the splash to skip. */
    fun skip() { elapsed = END }
}

private val SplashBlue = Color(0xFF1F5AA8)

/** PanelView-style runtime splash shown over the HMI screen during [Boot]. */
@Composable
fun BootSplash(vm: SimViewModel, modifier: Modifier = Modifier) {
    AnimatedVisibility(Boot.splash, modifier, enter = androidx.compose.animation.EnterTransition.None, exit = fadeOut(tween(400))) {
        val app = remember(vm.plc.profile.name) { vm.plc.profile.name.uppercase().replace(Regex("[^A-Z0-9]+"), "_").trim('_') }
        val t = Boot.elapsed / Boot.END
        val step = when {
            t < 0.08f -> "Power-on self test"
            t < 0.55f -> "Lamp test"
            t < 0.68f -> "Starting runtime"
            t < 0.82f -> "Loading application: $app.mer"
            t < 0.94f -> "Connecting to controller 192.168.1.10, slot 0"
            else -> "Starting displays"
        }
        Box(
            Modifier.fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF0B1A2E), Color(0xFF04080F))))
                .clickable(remember { MutableInteractionSource() }, null) { Boot.skip() },
        ) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Column(
                    Modifier.width(110.dp).height(70.dp).background(SplashBlue, RoundedCornerShape(50))
                        .border(3.dp, Color.White, RoundedCornerShape(50)),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Text("BR", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Black, lineHeight = 32.sp)
                    Text("BALLIN'", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, lineHeight = 12.sp)
                }
                Spacer(Modifier.height(14.dp))
                Text("Ballin'-Radley", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, fontStyle = FontStyle.Italic)
                Text("PanelView Plus 1000  ·  ME Runtime", color = Color(0xFF8FA9C8), fontSize = 13.sp)
                Spacer(Modifier.height(22.dp))
                // Segmented progress bar, as on embedded terminals.
                Row(
                    Modifier.width(320.dp).border(1.dp, Color(0xFF5B7598)).padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    val segs = 24
                    val lit = (t.coerceIn(0f, 1f) * segs).toInt()
                    repeat(segs) { k ->
                        Box(Modifier.weight(1f).height(12.dp).background(if (k < lit) Color(0xFF4E9BFF) else Color(0xFF14233A)))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(step + if (flasher()) " _" else "", color = Color(0xFFCFE0F5), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(10.dp)) {
                Text("Series C  ·  FW 13.100.4", color = Color(0xFF5B7598), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.weight(1f))
                Text("tap to skip", color = Color(0xFF5B7598), fontSize = 10.sp)
            }
            Box(Modifier.align(Alignment.TopEnd).padding(10.dp).size(10.dp).background(if (Boot.lampTest || flasher()) Color(0xFF22C55E) else Color(0xFF0E3A1E), RoundedCornerShape(50)))
        }
    }
}
