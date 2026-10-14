package com.abemu.coaster

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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.abemu.coaster.ui.HmiTerminal
import com.abemu.coaster.ui.OperatorConsole
import com.abemu.coaster.ui.RideSounds

class MainActivity : ComponentActivity() {
    private val vm: SimViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm.tagLoader = { com.abemu.coaster.dev.TagStore.loadLayout(this, it) }
        vm.reconfigure()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                RideSounds(vm)
                Row(Modifier.fillMaxSize().background(Color(0xFF111316))) {
                    HmiTerminal(vm, Modifier.weight(0.6f).fillMaxHeight())
                    androidx.compose.foundation.layout.Spacer(Modifier.width(2.dp).fillMaxHeight().background(Color.Black))
                    OperatorConsole(vm, Modifier.weight(0.4f).fillMaxHeight())
                }
            }
        }
    }
}
