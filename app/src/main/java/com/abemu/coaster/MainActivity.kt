package com.abemu.coaster

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.abemu.coaster.ui.StationMusic
import com.abemu.coaster.ui.DeskPa
import com.abemu.coaster.ui.Haptics
import com.abemu.coaster.ui.HmiTerminal
import com.abemu.coaster.ui.MapPane
import com.abemu.coaster.ui.OperatorConsole
import com.abemu.coaster.ui.RadioPlayer
import com.abemu.coaster.ui.RideHaptics
import com.abemu.coaster.ui.RideSounds

class MainActivity : ComponentActivity() {
    private val vm: SimViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm.tagLoader = { com.abemu.coaster.dev.TagStore.loadLayout(this, it) }
        com.abemu.coaster.dev.CustomRideStore.load(this)
        vm.reconfigure()
        Haptics.init(this)
        // Freeze the simulation while the app is off screen.
        lifecycle.addObserver(androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> vm.paused = true
                androidx.lifecycle.Lifecycle.Event.ON_START -> vm.paused = false
                else -> Unit
            }
        })
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Nothing here needs a fast panel (the UI redraws at 30 fps at most): ask for the slowest refresh mode.
        if (Build.VERSION.SDK_INT >= 30) {
            display?.supportedModes?.filter { it.physicalWidth == display?.mode?.physicalWidth }?.minByOrNull { it.refreshRate }
                ?.let { m -> window.attributes = window.attributes.apply { preferredDisplayModeId = m.modeId } }
        }
        // Draw under the Galaxy Tab S9 Ultra's notch instead of letterboxing; it only covers bezel branding.
        if (Build.VERSION.SDK_INT >= 30) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            // Material's bodyLarge fixes line height at 24sp whatever the font size, which double-spaces the
            // small HMI text; scale it with the font instead.
            val type = androidx.compose.material3.Typography().let { t ->
                t.copy(bodyLarge = t.bodyLarge.copy(lineHeight = 1.4.em))
            }
            MaterialTheme(colorScheme = darkColorScheme(), typography = type) {
                androidx.compose.runtime.LaunchedEffect(Unit) { com.abemu.coaster.ui.Boot.run() }
                RideSounds(vm)
                StationMusic(vm)
                DeskPa(vm)
                RideHaptics(vm)
                RadioPlayer(vm)
                ScaledToDesign {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxSize().background(Color(0xFF111316))) {
                    val divider = @androidx.compose.runtime.Composable {
                        androidx.compose.foundation.layout.Spacer(Modifier.width(2.dp).fillMaxHeight().background(Color.Black))
                    }
                    when (vm.layout) {
                        ScreenLayout.HMI_CONSOLE -> {
                            HmiTerminal(vm, Modifier.weight(0.6f).fillMaxHeight())
                            divider()
                            OperatorConsole(vm, Modifier.weight(0.4f).fillMaxHeight())
                        }
                        ScreenLayout.HMI_MAP_CONSOLE -> {
                            HmiTerminal(vm, Modifier.weight(0.39f).fillMaxHeight())
                            divider()
                            MapPane(vm, Modifier.weight(0.23f).fillMaxHeight())
                            divider()
                            OperatorConsole(vm, Modifier.weight(0.38f).fillMaxHeight())
                        }
                        ScreenLayout.MAP_CONSOLE -> {
                            MapPane(vm, Modifier.weight(0.6f).fillMaxHeight())
                            divider()
                            OperatorConsole(vm, Modifier.weight(0.4f).fillMaxHeight())
                        }
                    }
                }
                if (vm.showAbout) com.abemu.coaster.ui.AboutScreen(vm)
                }
                }
            }
        }
    }
}

/** Layout size the UI was designed and verified at (Galaxy Tab S9 Ultra at 340 dpi). */
private const val DESIGN_WIDTH_DP = 1393f
private const val DESIGN_HEIGHT_DP = 870f

/**
 * Scales the whole UI up on screens with more room than the design size, so controls keep their
 * intended proportions (the real S9 Ultra runs at 280 dpi, which would otherwise shrink everything ~20%).
 */
@androidx.compose.runtime.Composable
private fun ScaledToDesign(content: @androidx.compose.runtime.Composable () -> Unit) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val scale = minOf(maxWidth.value / DESIGN_WIDTH_DP, maxHeight.value / DESIGN_HEIGHT_DP).coerceAtLeast(1f)
        val d = androidx.compose.ui.platform.LocalDensity.current
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density * scale, d.fontScale),
        ) { content() }
    }
}
