package com.abemu.coaster.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abemu.coaster.SimViewModel
import com.abemu.coaster.sim.KeyMode
import com.abemu.coaster.sim.LaunchState
import com.abemu.coaster.sim.ProfileType
import com.abemu.coaster.sim.RideState
import com.abemu.coaster.sim.Severity

/** The physical operator console: hardwired devices wired to PLC input cards. */
@Composable
fun OperatorConsole(vm: SimViewModel, modifier: Modifier = Modifier) {
    vm.frame.longValue // recompose each update
    val plc = vm.plc
    val inp = plc.inputs
    val blink = flasher()
    val powered = plc.powered
    val hasFault = plc.alarms.anyActive(Severity.ESTOP) || plc.alarms.anyActive(Severity.CSTOP)

    Column(
        modifier
            .background(Brush.verticalGradient(listOf(Color(0xFF3A3F45), Color(0xFF26292D))))
            .padding(10.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("OPERATOR CONSOLE  ·  OC-1", color = Color(0xFFD0D6DD), fontSize = 13.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp, modifier = Modifier.weight(1f))
            Text("120 VAC / 24 VDC", color = Color(0xFF8A929C), fontSize = 10.sp)
        }

        // --- Power / mode / E-Stop ---
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            KeySwitch(
                "MODE SELECT", KeyMode.entries.map { it.label }, inp.mode.ordinal,
                onSelect = { inp.mode = KeyMode.entries[it] },
            )
            PanelSection("RIDE POWER") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PushButton("POWER\nON", Lens.White, powered, { inp.powerOn = it })
                    PushButton("POWER\nOFF", Lens.Black, false, { inp.powerOff = it }, illuminated = false)
                }
            }
            EStopButton(inp.estopPressed, { inp.estopPressed = it })
        }

        // --- Status lamps ---
        PanelSection("STATUS") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                PilotLight("FAULT", Lens.Red, powered && hasFault && (blink || plc.alarms.anyUnacked.not()))
                PilotLight("AIR\nPRESSURE OK", Lens.Green, plc.plant.airPsi >= 75)
                if (plc.profile.type == ProfileType.LIFT)
                    PilotLight("LIFT\nRUNNING", Lens.Amber, plc.plant.chainSpeed > 0.1)
                else
                    PilotLight("LSM\nARMED", Lens.Amber, plc.launchState == LaunchState.CHARGING && blink ||
                        plc.launchState == LaunchState.FIRING)
                PilotLight("RIDE\nRUNNING", Lens.Green, plc.state == RideState.RUNNING ||
                    (plc.state == RideState.STARTING && blink))
                PilotLight("BLOCK\nCLEAR", Lens.White, powered && plc.nextClear(0))
                PilotLight("TRACK CLEAR\nWARNING", Lens.Amber, plc.trackClearActive && blink)
            }
        }

        // --- Ride control ---
        PanelSection("RIDE CONTROL") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                val pw = 70.dp
                PushButton("RIDE\nSTART", Lens.Green,
                    plc.state == RideState.RUNNING || (plc.state == RideState.STOPPED && inp.mode == KeyMode.AUTO && !hasFault && blink),
                    { inp.rideStart = it }, plateWidth = pw)
                PushButton("CONTROLLED\nSTOP", Lens.Red, plc.state == RideState.CSTOP && blink,
                    { inp.controlledStop = it }, plateWidth = pw)
                PushButton("FAULT\nRESET", Lens.Blue, powered && plc.resetNeeded && blink,
                    { inp.faultReset = it }, plateWidth = pw)
                PushButton("ALARM\nSILENCE", Lens.Amber, plc.hornActive && blink,
                    { inp.silence = it }, plateWidth = pw)
                if (plc.profile.type == ProfileType.LIFT)
                    PushButton("LIFT JOG\n(HOLD)", Lens.Black, false, { inp.liftJog = it }, illuminated = false, plateWidth = pw)
                else
                    PushButton("MANUAL\nLAUNCH", Lens.Black, false, { inp.manualRelease[1] = it }, illuminated = false, plateWidth = pw)
                PushButton("STATION\nRELEASE", Lens.Black, false, { inp.manualRelease[0] = it }, illuminated = false, plateWidth = pw)
            }
        }

        // --- Station ---
        val unlocked = plc.plant.restraintUnlockCmd
        PanelSection("STATION") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                // Lamps flash while an action is in progress and go solid once it is confirmed.
                val plant = plc.plant
                val locking = powered && !unlocked && plc.trainInStation && !plc.allRowsLocked
                val locked = powered && !unlocked && plc.allRowsLocked
                val gatesOpening = plant.gatesOpenCmd && plant.gatePos < 0.97
                val gatesClosing = powered && !plant.gatesOpenCmd && !plant.gateClosedSensor
                PushButton("RESTRAINTS\nOPEN", Lens.Amber, unlocked, { inp.restraintOpen = it })
                PushButton("RESTRAINTS\nLOCK", Lens.Green, locked || (locking && blink),
                    { inp.restraintClose = it })
                PushButton("GATES\nOPEN", Lens.Amber, (plant.gatesOpenCmd && !gatesOpening) || (gatesOpening && blink),
                    { inp.gatesOpen = it })
                PushButton("GATES\nCLOSE", Lens.Green, (powered && plant.gateClosedSensor && !plant.gatesOpenCmd) ||
                    (gatesClosing && blink), { inp.gatesClose = it })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                OperatorStand("LOAD STAND", inp.loadStandEstop, { inp.loadStandEstop = it }, plc.loadReady) { inp.loadReady = it }
                OperatorStand("UNLOAD STAND", inp.unloadStandEstop, { inp.unloadStandEstop = it }, plc.unloadReady) { inp.unloadReady = it }
            }
        }

        // --- Dispatch (two-hand) ---
        // Engraved legends only: ride status and mode messages belong on the HMI, not the hardware panel.
        PanelSection("DISPATCH") {
            val ready = plc.dispatchReady
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                PushButton("DISPATCH", Lens.Green, ready && blink || plc.releaseLatch[0], { inp.dispatchL = it }, size = 56.dp)
                Column(Modifier.weight(1f).padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    LegendPlate("TWO-HAND DISPATCH\nPRESS BOTH TOGETHER", 150.dp)
                }
                PushButton("DISPATCH", Lens.Green, ready && blink || plc.releaseLatch[0], { inp.dispatchR = it }, size = 56.dp)
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}


/** Remote operator stand: its own E-Stop plus that side's READY pushbutton. */
@Composable
private fun OperatorStand(
    name: String,
    estop: Boolean,
    onEstop: (Boolean) -> Unit,
    ready: Boolean,
    onReady: (Boolean) -> Unit,
) {
    Column(
        Modifier
            .background(Color(0xFF2F3338), RoundedCornerShape(6.dp))
            .border(1.dp, Color(0xFF6A7178), RoundedCornerShape(6.dp))
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(name, color = Color(0xFFB8C0CA), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            EStopButton(estop, onEstop, size = 48.dp, label = "E-STOP", plateWidth = 64.dp)
            PushButton("READY", Lens.White, ready, onReady, size = 42.dp, plateWidth = 56.dp)
        }
    }
}
