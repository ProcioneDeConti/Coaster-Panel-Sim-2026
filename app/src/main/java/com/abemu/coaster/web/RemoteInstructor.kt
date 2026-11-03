package com.abemu.coaster.web

import com.abemu.coaster.AccessLevel
import com.abemu.coaster.SimViewModel
import com.abemu.coaster.sim.InjectedFaults
import com.abemu.coaster.sim.ProfileType
import com.abemu.coaster.sim.RidePlc
import com.abemu.coaster.sim.Scenarios
import com.abemu.coaster.sim.WeatherPreset

/**
 * Remote instructor console: the INSTRUCTOR page's fault injection, weather, sim speed and drills,
 * served to a browser so an instructor can run a session from a laptop or phone without touching the
 * trainee's tablet. Everything here runs on the sim thread ([MapServer] queues commands and asks for
 * snapshots from [MapServer.publish]).
 */
object RemoteInstructor {
    private class Toggle(val key: String, val label: String, val get: (InjectedFaults) -> Boolean, val set: (InjectedFaults, Boolean) -> Unit)

    private fun toggles(plc: RidePlc) = buildList {
        add(Toggle("safety", "Safety circuit open (remote E-Stop)", { it.safetyCircuit }) { f, v -> f.safetyCircuit = v })
        add(Toggle("air", "Air leak (pressure decay)", { it.airLeak }) { f, v -> f.airLeak = v })
        if (plc.profile.type == ProfileType.LIFT) {
            add(Toggle("liftVfd", "Lift VFD fault", { it.liftVfd }) { f, v -> f.liftVfd = v })
            add(Toggle("liftOverload", "Lift motor overload trip", { it.liftOverload }) { f, v -> f.liftOverload = v })
        } else {
            add(Toggle("lsm", "LSM drive fault", { it.lsmFault }) { f, v -> f.lsmFault = v })
            add(Toggle("underspeed", "Launch underspeed (causes rollback)", { it.launchUnderspeed }) { f, v -> f.launchUnderspeed = v })
            if (!plc.profile.spec.lsmLift) add(Toggle("derate", "LSM derated – marginal launch", { it.lsmDerate }) { f, v -> f.lsmDerate = v })
            if (plc.hasSwitch) add(Toggle("switch", "Switch track jammed", { it.switchStuck }) { f, v -> f.switchStuck = v })
        }
        add(Toggle("gate", "Station gate stuck open", { it.gateStuck }) { f, v -> f.gateStuck = v })
        add(Toggle("safetyIo", "Safety I/O module connection fault", { it.safetyIoFault }) { f, v -> f.safetyIoFault = v })
    }

    fun pinOk(pin: String?) = pin == AccessLevel.INSTRUCTOR.pin

    /** Applies one command (already PIN-checked). Unknown or invalid commands are ignored. */
    fun apply(vm: SimViewModel, p: Map<String, String>) {
        val plc = vm.plc
        val f = plc.plant.faults
        val on = p["on"] == "1"
        val n = p["n"]?.toIntOrNull()
        val blocks = plc.profile.blocks.indices
        when (p["cmd"]) {
            "fault" -> toggles(plc).firstOrNull { it.key == p["key"] }?.set(f, on)
            "estopB" -> if (n != null && n in -1 until plc.safety.stations.size) f.estopWeldedB = n
            "restraintRow" -> if (n != null && n in -1 until plc.profile.rows) f.restraintRow = n
            "restraintSeat" -> f.restraintSeat = if (on) (0 until plc.profile.rows * plc.profile.spec.ridersPerCar).random() else -1
            "sensorOn" -> if (n in blocks) { if (on) f.sensorStuckOn += n!! else f.sensorStuckOn -= n!! }
            "sensorOff" -> if (n in blocks) { if (on) f.sensorStuckOff += n!! else f.sensorStuckOff -= n!! }
            "brakeOpen" -> if (n in blocks && plc.profile.blocks[n!!].stopPos != null) plc.plant.brakes[n].stuckOpen = on
            "brakeClosed" -> if (n in blocks && plc.profile.blocks[n!!].stopPos != null) plc.plant.brakes[n].stuckClosed = on
            "clearAll" -> {
                toggles(plc).forEach { it.set(f, false) }
                f.estopWeldedB = -1; f.restraintRow = -1; f.restraintSeat = -1
                f.sensorStuckOn.clear(); f.sensorStuckOff.clear()
                plc.plant.brakes.forEach { it.stuckOpen = false; it.stuckClosed = false }
            }
            "weather" -> WeatherPreset.entries.firstOrNull { it.name == p["preset"] }?.let { vm.weather.preset = it }
            "speed" -> p["x"]?.toDoubleOrNull()?.takeIf { it in listOf(1.0, 2.0, 4.0) }?.let { vm.timeScale = it }
            "dispatchTarget" -> p["x"]?.toDoubleOrNull()?.takeIf { it in listOf(1.0, 1.15, 1.3, 1.5) }?.let { plc.targetScale = it }
            "drill" -> if (vm.drill?.finished != false) Scenarios.all.firstOrNull { it.id == p["id"] }?.let { vm.startDrill(it) }
            "desk" -> vm.switchDeskMode(on)
            "deskFaults" -> vm.deskRandomFaults = on
            "abortDrill" -> vm.abortDrill()
            "dismissDrill" -> vm.dismissDrill()
        }
    }

    /** Instructor snapshot: current fault settings, environment and drills. */
    fun state(vm: SimViewModel): String {
        val plc = vm.plc
        val f = plc.plant.faults
        val q = WebMap::q
        val d = WebMap::d
        return buildString {
            append("{\"ride\":${q(plc.profile.spec.name)},\"type\":${q(plc.profile.type.name)},")
            append("\"toggles\":[")
            append(toggles(plc).joinToString(",") { "{\"key\":${q(it.key)},\"label\":${q(it.label)},\"on\":${it.get(f)}}" })
            append("],\"stations\":[${plc.safety.stations.joinToString(",") { q(it) }}],\"estopB\":${f.estopWeldedB},")
            append("\"rows\":${plc.profile.rows},\"restraintRow\":${f.restraintRow},\"restraintSeat\":${f.restraintSeat},")
            append("\"blocks\":[")
            append(plc.profile.blocks.joinToString(",") { b ->
                val brk = plc.plant.brakes[b.index]
                "{\"tag\":${q(b.tag)},\"name\":${q(b.name)},\"brake\":${b.stopPos != null}," +
                    "\"sensorOn\":${b.index in f.sensorStuckOn},\"sensorOff\":${b.index in f.sensorStuckOff}," +
                    "\"brakeOpen\":${brk.stuckOpen},\"brakeClosed\":${brk.stuckClosed}}"
            })
            append("],\"weather\":{\"preset\":${q(vm.weather.preset.name)},\"current\":${q(vm.weather.current.label)},")
            append("\"wind\":${d(vm.weather.windMph)},\"strike\":${vm.weather.strikeMi?.let(d) ?: "null"},")
            append("\"windHold\":${plc.weather.windHold},\"lightningHold\":${plc.weather.lightningHold}},")
            append("\"presets\":[${WeatherPreset.entries.joinToString(",") { "[${q(it.name)},${q(it.label)}]" }}],")
            append("\"desk\":${vm.deskMode},\"deskFaults\":${vm.deskRandomFaults},\"deskStatus\":${q(vm.autoOp.status)},\"repairs\":${vm.autoOp.repairs},")
            append("\"speed\":${d(vm.timeScale)},\"dispatchTarget\":${d(plc.dispatchTarget)},\"targetScale\":${d(plc.targetScale)},\"ratedInterval\":${d(plc.profile.ratedInterval)},\"wait\":${vm.queue.postedWaitMin},")
            append("\"scenarios\":[${Scenarios.all.joinToString(",") { "{\"id\":${q(it.id)},\"title\":${q(it.title)},\"kind\":${q(it.kind.label)}}" }}],")
            append("\"drill\":")
            val dr = vm.drill
            if (dr == null) append("null") else {
                val next = dr.nextStep?.let { dr.steps[it].text } ?: ""
                append("{\"title\":${q(dr.scenario.title)},\"t\":${d(dr.t)},\"next\":${q(next)},\"score\":${dr.score},\"finished\":${dr.finished}}")
            }
            append(",\"results\":[${vm.drillResults.take(10).joinToString(",") {
                "{\"title\":${q(it.title)},\"score\":${it.score},\"grade\":${q(it.grade)},\"t\":${d(it.seconds)}}"
            }}]}")
        }
    }

    val PAGE = """
<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Remote instructor</title>
<style>
 body{margin:0;background:#0f1418;color:#dfe6ec;font:14px system-ui,sans-serif;display:flex;height:100vh}
 iframe{flex:1;border:0;min-width:0}
 #side{width:420px;overflow:auto;padding:8px;background:#161c21}
 h2{font-size:13px;margin:12px 0 6px;color:#8fb4dd;letter-spacing:.05em}
 button{background:#2a3440;color:#dfe6ec;border:1px solid #3d4a57;border-radius:3px;padding:5px 8px;margin:2px;font:inherit;font-size:12px;cursor:pointer}
 button.on{background:#b71c1c;border-color:#e53935;color:#fff} button.sel{background:#1e5fa8;border-color:#5a9be0;color:#fff}
 .row{display:flex;align-items:center;justify-content:space-between;gap:6px;padding:2px 0} .row span{flex:1}
 .dim{color:#8a96a1;font-size:12px} #drill{background:#6a3fb5;color:#fff;padding:6px;border-radius:3px;display:none}
 #login{position:fixed;inset:0;background:#0f1418;display:flex;align-items:center;justify-content:center;flex-direction:column;gap:8px}
 input{font-size:20px;width:120px;text-align:center;background:#222;color:#fff;border:1px solid #555}
 @media (max-width:800px){body{flex-direction:column;height:auto} iframe{height:55vh;flex:none} #side{width:auto}}
</style></head><body>
<iframe src="/map"></iframe>
<div id="side"></div>
<div id="login"><b>INSTRUCTOR PIN</b><input id="pin" type="password" inputmode="numeric" autofocus><button onclick="login()">SIGN IN</button><div id="err" class="dim"></div></div>
<script>
let pin=sessionStorage.getItem('pin')||'',s=null,fail=0;
const $=id=>document.getElementById(id);
const esc=t=>String(t).replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
function login(){pin=$('pin').value;poll()}
$('pin').addEventListener('keydown',e=>{if(e.key==='Enter')login()});
async function cmd(q){await fetch('/instructor/cmd?pin='+encodeURIComponent(pin)+'&'+q,{method:'POST'});setTimeout(poll,150)}
function b(label,on,q,cls){return '<button class="'+(on?(cls||'on'):'')+'" onclick="cmd(\''+q+'\')">'+esc(label)+'</button>'}
function render(){if(!s)return;let h='<a href="/" style="color:#8fb4dd;text-decoration:none;font-size:12px">◂ HOME</a>';
 const dr=s.drill;
 h+='<div id="drill" style="display:'+(dr?'block':'none')+'">'+(dr?('DRILL '+esc(dr.title)+' · '+Math.floor(dr.t/60)+':'+String(Math.floor(dr.t%60)).padStart(2,'0')+' · score '+dr.score+(dr.finished?' · FINISHED':' · NEXT: '+esc(dr.next))):'')+
  (dr?'<br>'+(dr.finished?b('DISMISS',false,'cmd=dismissDrill'):b('ABORT DRILL',false,'cmd=abortDrill')):'')+'</div>';
 h+='<h2>'+esc(s.ride)+'</h2>';
 h+='<div class="row"><span>Desk display (self-running)</span>'+b(s.desk?'ON':'OFF',s.desk,'cmd=desk&on='+(s.desk?0:1),'sel')+b('RANDOM FAULTS',s.deskFaults,'cmd=deskFaults&on='+(s.deskFaults?0:1),'sel')+'</div>';
 if(s.desk)h+='<div class="dim">Crew: '+esc(s.deskStatus)+' · repairs '+s.repairs+'</div>';
 h+='<div class="row"><span>Sim speed</span>'+[1,2,4].map(x=>b(x+'×',s.speed==x,'cmd=speed&x='+x,'sel')).join('')+'</div>';
 h+='<div class="row"><span>Dispatch target</span>'+[1,1.15,1.3,1.5].map(x=>b((x==1?'Rated ':'')+Math.round(s.ratedInterval*x)+' s',s.targetScale==x,'cmd=dispatchTarget&x='+x,'sel')).join('')+'</div>';
 h+='<h2>WEATHER</h2><div>'+s.presets.map(p=>b(p[1],s.weather.preset==p[0],'cmd=weather&preset='+p[0],'sel')).join('')+'</div>';
 h+='<div class="dim">Now: '+esc(s.weather.current)+' · wind '+(+s.weather.wind).toFixed(0)+' mph'+(s.weather.strike!=null?' · strike '+(+s.weather.strike).toFixed(1)+' mi':'')+
  (s.weather.windHold?' · WIND HOLD':'')+(s.weather.lightningHold?' · LIGHTNING HOLD':'')+' · posted wait '+s.wait+' min</div>';
 h+='<h2>FAULTS – SYSTEM '+b('CLEAR ALL',false,'cmd=clearAll')+'</h2>';
 s.toggles.forEach(t=>h+='<div class="row"><span>'+esc(t.label)+'</span>'+b(t.on?'CLEAR':'INJECT',t.on,'cmd=fault&key='+t.key+'&on='+(t.on?0:1))+'</div>');
 h+='<div class="row"><span>E-Stop ch B welded</span>'+b('–',s.estopB<0,'cmd=estopB&n=-1','sel')+s.stations.map((n,i)=>b(n.slice(0,4),s.estopB==i,'cmd=estopB&n='+i)).join('')+'</div>';
 h+='<div class="row"><span>Restraint fault row</span><span>'+b('–',s.restraintRow<0,'cmd=restraintRow&n=-1','sel');for(let r=0;r<s.rows;r++)h+=b(r+1,s.restraintRow==r,'cmd=restraintRow&n='+r);h+='</span></div>';
 h+='<div class="row"><span>Seat won\'t lock</span>'+b(s.restraintSeat<0?'RANDOM SEAT':'CLEAR (seat '+(s.restraintSeat+1)+')',s.restraintSeat>=0,'cmd=restraintSeat&on='+(s.restraintSeat<0?1:0))+'</div>';
 h+='<h2>FAULTS – BLOCK DEVICES</h2>';
 s.blocks.forEach((k,i)=>{h+='<div><b>'+esc(k.tag)+'</b> <span class="dim">'+esc(k.name)+'</span><br>'+
  b('Prox stuck ON',k.sensorOn,'cmd=sensorOn&n='+i+'&on='+(k.sensorOn?0:1))+b('Prox failed OFF',k.sensorOff,'cmd=sensorOff&n='+i+'&on='+(k.sensorOff?0:1))+
  (k.brake?b('Brake stuck open',k.brakeOpen,'cmd=brakeOpen&n='+i+'&on='+(k.brakeOpen?0:1))+b('Brake stuck closed',k.brakeClosed,'cmd=brakeClosed&n='+i+'&on='+(k.brakeClosed?0:1)):'')+'</div>'});
 h+='<h2>TRAINING DRILLS</h2>';
 s.scenarios.forEach(sc=>h+='<div class="row"><span>'+esc(sc.title)+' <span class="dim">'+esc(sc.kind)+'</span></span>'+(dr&&!dr.finished?'':b('START',false,'cmd=drill&id='+sc.id))+'</div>');
 if(s.results.length)h+='<h2>RESULTS</h2>'+s.results.map(r=>'<div class="row"><span>'+esc(r.title)+'</span><b>'+r.score+' '+esc(r.grade)+'</b></div>').join('');
 h+='<p class="dim">Faults act on the simulated field devices; the trainee diagnoses them from alarms and I/O on the tablet.</p>';
 $('side').innerHTML=h}
async function poll(){if(!pin)return;try{const r=await fetch('/instructor/state?pin='+encodeURIComponent(pin));
  if(r.status==403){$('login').style.display='flex';$('err').textContent='Wrong PIN, or remote instructor is switched off on the tablet.';pin='';sessionStorage.removeItem('pin');return}
  const t=await r.json();if(t.ride){s=t;sessionStorage.setItem('pin',pin);$('login').style.display='none';render()}fail=0}catch(e){fail++}}
poll();
setInterval(poll,500);
</script></body></html>
"""
}
