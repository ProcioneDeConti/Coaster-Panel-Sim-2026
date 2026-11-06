package com.abemu.coaster.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abemu.coaster.SimViewModel

/** Project home, where the source code for every release is published. */
const val SOURCE_URL = "https://github.com/ProcioneDeConti/Coaster-Panel-Sim-2026"

/**
 * The author attribution required by the additional terms in NOTICE (GPL v3 section 7(b)). Modified versions must
 * keep showing it here, in the app's Appropriate Legal Notices.
 */
const val ATTRIBUTION = "Based on Coaster Panel Sim 2026 by ProcioneDeConti"

/** Header chip that opens the About / legal notices screen. */
@Composable
fun AboutChip(vm: SimViewModel) {
    Box(
        Modifier.background(Color(0xFF3A3F45), RoundedCornerShape(3.dp)).border(1.dp, Color(0xFF8A939C), RoundedCornerShape(3.dp))
            .clickable { Haptics.tick(); vm.showAbout = true }
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text("ⓘ ABOUT", color = Color(0xFFD5DAE0), fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

private enum class AboutView { INFO, LICENSE, NOTICE }

/**
 * About screen: the app's Appropriate Legal Notices under the GPL v3 (copyright, author attribution, no-warranty
 * statement, how to get the source and how to read the license), plus third-party notices.
 */
@Composable
fun AboutScreen(vm: SimViewModel) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    var view by remember { mutableStateOf(AboutView.INFO) }
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    fun asset(name: String) = runCatching { context.assets.open("legal/$name").bufferedReader().use { it.readText() } }
        .getOrDefault("The $name file is missing from this build. See $SOURCE_URL")

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable { vm.showAbout = false },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(820.dp).heightIn(max = 760.dp).background(Hmi.Screen).border(2.dp, Color.Black)
                .clickable(enabled = false) {},
        ) {
            Row(Modifier.fillMaxWidth().background(Hmi.Header).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(when (view) { AboutView.INFO -> "ABOUT"; AboutView.LICENSE -> "GNU GENERAL PUBLIC LICENSE v3"; AboutView.NOTICE -> "NOTICE" },
                    color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("v$version", color = Color(0xFFB0BEC5), fontSize = 12.sp)
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (view) {
                    AboutView.INFO -> {
                        Text("Coaster Panel Sim 2026", color = Hmi.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        HmiLabel("Roller coaster ride control panel simulator · version $version", 13.sp, color = Hmi.Dim)
                        HmiLabel("Copyright © 2026 ProcioneDeConti", 14.sp, bold = true)
                        HmiLabel(ATTRIBUTION, 13.sp)
                        HmiLabel(SOURCE_URL, 13.sp, color = Hmi.Info, modifier = Modifier.clickable { runCatching { uri.openUri(SOURCE_URL) } })
                        Section("LICENSE")
                        HmiLabel("This program is free software: you can redistribute it and/or modify it under the terms of the GNU " +
                            "General Public License as published by the Free Software Foundation, either version 3 of the License, " +
                            "or (at your option) any later version.", 12.sp)
                        HmiLabel("This program comes with ABSOLUTELY NO WARRANTY; without even the implied warranty of " +
                            "MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for details.", 12.sp)
                        HmiLabel("Additional terms (GPL v3 section 7(b)): copies and modified versions must preserve the author " +
                            "attribution above, in their source code and on their legal notices screen. See NOTICE.", 12.sp)
                        HmiLabel("The complete source code is available at the address above.", 12.sp)
                        Section("THIRD-PARTY NOTICES")
                        HmiLabel("Track layouts and scenery: map data © OpenStreetMap contributors, available under the Open " +
                            "Database License (ODbL). openstreetmap.org/copyright", 12.sp)
                        HmiLabel("AndroidX and Jetpack Compose libraries: © The Android Open Source Project, Apache License 2.0.", 12.sp)
                        HmiLabel("This is a fan project, not affiliated with or endorsed by any amusement park, ride manufacturer or " +
                            "controls vendor. Ride and park names and logos belong to their respective owners. It is a simulation: " +
                            "never use it as a reference for operating a real attraction.", 12.sp)
                    }
                    AboutView.LICENSE -> Text(remember { asset("LICENSE") }, color = Hmi.Text, fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace, lineHeight = 14.sp)
                    AboutView.NOTICE -> Text(remember { asset("NOTICE") }, color = Hmi.Text, fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace, lineHeight = 14.sp)
                }
            }
            Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HmiButton("ABOUT", Modifier.weight(1f), active = view == AboutView.INFO, activeColor = Hmi.Info) { view = AboutView.INFO }
                HmiButton("VIEW LICENSE", Modifier.weight(1f), active = view == AboutView.LICENSE, activeColor = Hmi.Info) { view = AboutView.LICENSE }
                HmiButton("VIEW NOTICE", Modifier.weight(1f), active = view == AboutView.NOTICE, activeColor = Hmi.Info) { view = AboutView.NOTICE }
                HmiButton("SOURCE CODE", Modifier.weight(1f)) { runCatching { uri.openUri(SOURCE_URL) } }
                HmiButton("CLOSE", Modifier.weight(1f), active = true, activeColor = Hmi.Ok) { vm.showAbout = false }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    HmiLabel(title, 12.sp, bold = true, color = Hmi.Dim, modifier = Modifier.padding(top = 6.dp))
}
