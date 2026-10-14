package com.abemu.coaster.web

import com.abemu.coaster.sim.Drill
import com.abemu.coaster.sim.RidePlc
import com.abemu.coaster.sim.Severity
import kotlin.math.abs

/** JSON snapshots and the browser page for [MapServer]. */
object WebMap {
    private fun q(s: String) = buildString {
        append('"')
        for (ch in s) when (ch) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n")
            else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
        }
        append('"')
    }

    private fun d(v: Double) = "%.2f".format(java.util.Locale.US, v)

    /** Static track layout: the plan polyline sampled every [step] metres, plus block extents. */
    fun geometry(plc: RidePlc, version: Int): String {
        val p = plc.profile
        val step = 1.0
        val n = (p.length / step).toInt()
        return buildString {
            append("{\"version\":$version,\"name\":${q(p.name)},\"park\":${q(p.spec.park ?: "")},")
            append("\"length\":${d(p.length)},\"step\":$step,\"trainLength\":${d(p.trainLength)},\"points\":[")
            for (i in 0..n) {
                val (x, y) = p.planPoint(i * step)
                if (i > 0) append(',')
                append("[${d(x)},${d(y)}]")
            }
            append("],\"blocks\":[")
            p.blocks.forEachIndexed { k, b ->
                if (k > 0) append(',')
                append("{\"tag\":${q(b.tag)},\"name\":${q(b.name)},\"kind\":${q(b.kind.name)},")
                append("\"start\":${d(b.start)},\"end\":${d(b.end)},\"stop\":${b.stopPos?.let { d(it) } ?: "null"}}")
            }
            append("]}")
        }
    }

    /** Live state, published each frame while a browser is polling. */
    fun state(plc: RidePlc, drill: Drill?, version: Int): String {
        val pl = plc.plant
        val top = plc.alarms.firstOut ?: plc.alarms.active.firstOrNull()
        return buildString {
            append("{\"version\":$version,\"state\":${q(plc.state.label)},\"stateKey\":${q(plc.state.name)},")
            append("\"mode\":${q(plc.inputs.mode.label)},\"status\":${q(plc.statusText)},")
            append("\"air\":${pl.airPsi.toInt()},\"chain\":${d(pl.chainSpeed)},\"dispatches\":${plc.dispatchCount},")
            append("\"forces\":${plc.forces.all.size},\"safetyOn\":${plc.safety.outputsOn},")
            append("\"alarm\":")
            if (top == null) append("null") else {
                append("{\"sev\":${q(top.severity.label)},\"stop\":${top.severity.ordinal <= Severity.CSTOP.ordinal},")
                append("\"text\":${q(top.text)},\"firstOut\":${plc.alarms.isFirstOut(top)},\"count\":${plc.alarms.active.size}}")
            }
            append(",\"occ\":[${plc.occ.joinToString(",")}],")
            append("\"brakes\":[${pl.brakes.joinToString(",") { if (it.openProx) "1" else if (it.closedProx) "0" else "0.5" }}],")
            append("\"trains\":[")
            pl.trains.forEachIndexed { k, t ->
                if (k > 0) append(',')
                append("{\"id\":${t.id},\"s\":${d(t.s)},\"v\":${d(abs(t.v))}}")
            }
            append("],\"drill\":")
            if (drill == null || drill.finished) append("null") else {
                val next = drill.nextStep?.let { drill.steps[it].text } ?: ""
                append("{\"title\":${q(drill.scenario.title)},\"t\":${d(drill.t)},\"next\":${q(next)},\"score\":${drill.score}}")
            }
            append('}')
        }
    }

    val PAGE = """
<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Ride map</title>
<style>
 body{margin:0;background:#0f1418;color:#dfe6ec;font:14px system-ui,sans-serif;display:flex;flex-direction:column;height:100vh}
 header{display:flex;gap:12px;align-items:center;padding:8px 12px;background:#1e3550}
 header h1{font-size:17px;margin:0;flex:1}
 .chip{padding:3px 9px;border-radius:3px;font-weight:700;font-size:13px}
 #alarm{padding:6px 12px;font-weight:600;background:#2a3036;color:#9aa4ad}
 #drill{padding:4px 12px;background:#6a3fb5;color:#fff;display:none}
 main{flex:1;display:flex;min-height:0}
 canvas{flex:1;min-width:0}
 aside{width:300px;overflow:auto;padding:8px;background:#161c21}
 table{width:100%;border-collapse:collapse;font-size:13px} td,th{padding:3px 4px;text-align:left} th{color:#8a96a1;font-weight:600}
 .dot{display:inline-block;width:10px;height:10px;border-radius:2px;margin-right:4px}
 .kv{display:flex;justify-content:space-between;padding:2px 0;color:#aab4bd} .kv b{color:#fff}
 #conn{font-size:12px;color:#8a96a1}
 @media (max-width:700px){main{flex-direction:column} aside{width:auto}}
</style></head><body>
<header><h1 id="name">Ride map</h1><span id="mode" class="chip" style="border:1px solid #afc4dd"></span><span id="state" class="chip"></span><span id="conn"></span></header>
<div id="alarm">No active alarms</div>
<div id="drill"></div>
<main><canvas id="c"></canvas><aside>
 <table><thead><tr><th>Block</th><th>Status</th><th>Train</th><th>Brake</th></tr></thead><tbody id="blocks"></tbody></table>
 <div style="margin-top:10px" id="data"></div>
</aside></main>
<script>
const stateColors={POWER_OFF:'#8e959c',STOPPED:'#2c6fd1',STARTING:'#e5a400',RUNNING:'#1e9e48',CSTOP:'#e5a400',ESTOP:'#d42020'};
const trainColors=['#1565c0','#8e24aa','#00897b','#ef6c00'];
let geo=null,st=null,fail=0;
const cv=document.getElementById('c'),cx=cv.getContext('2d');
const $=id=>document.getElementById(id);
function wrap(s){const L=geo.length;return ((s%L)+L)%L}
function pt(s){s=wrap(s);const f=s/geo.step,i=Math.floor(f),k=f-i,P=geo.points;const a=P[Math.min(i,P.length-1)],b=P[Math.min(i+1,P.length-1)];return [a[0]+(b[0]-a[0])*k,a[1]+(b[1]-a[1])*k]}
function span(a,b){let d=wrap(b-a);return d}
let X={};
function fit(){const r=cv.getBoundingClientRect(),dpr=devicePixelRatio||1;cv.width=r.width*dpr;cv.height=r.height*dpr;cx.setTransform(dpr,0,0,dpr,0,0);
 if(!geo)return;let x0=1e9,y0=1e9,x1=-1e9,y1=-1e9;for(const p of geo.points){x0=Math.min(x0,p[0]);y0=Math.min(y0,p[1]);x1=Math.max(x1,p[0]);y1=Math.max(y1,p[1])}
 const pad=30,sc=Math.min((r.width-2*pad)/(x1-x0||1),(r.height-2*pad)/(y1-y0||1));X={sc,ox:pad+((r.width-2*pad)-(x1-x0)*sc)/2-x0*sc,oy:pad+((r.height-2*pad)-(y1-y0)*sc)/2-y0*sc,w:r.width,h:r.height}}
function P(s){const p=pt(s);return [X.ox+p[0]*X.sc,X.oy+p[1]*X.sc]}
function path(a,b){const n=Math.max(2,Math.ceil(span(a,b)/geo.step));cx.beginPath();for(let i=0;i<=n;i++){const q=P(a+span(a,b)*i/n);i?cx.lineTo(q[0],q[1]):cx.moveTo(q[0],q[1])}cx.stroke()}
function draw(){if(!geo)return;cx.clearRect(0,0,X.w,X.h);cx.lineCap='round';
 cx.strokeStyle='#3c4650';cx.lineWidth=8;path(0,geo.length-0.01);
 geo.blocks.forEach((b,i)=>{const occ=st&&st.occ[i]!==-1;cx.strokeStyle=occ?'#c62828':'#2e7d32';cx.lineWidth=4;path(b.start,b.end);
  const q=P(b.start);cx.fillStyle='#fff';cx.beginPath();cx.arc(q[0],q[1],3.5,0,7);cx.fill();
  const m=P(b.start+span(b.start,b.end)/2);cx.font='bold 13px system-ui';cx.fillStyle='#cfd8dc';cx.fillText(b.tag,m[0]+8,m[1]-8);
  if(b.stop!=null&&st){const o=st.brakes[i];const s=P(b.stop);cx.fillStyle=o==1?'#43a047':o==0?'#e53935':'#ffb300';cx.fillRect(s[0]-5,s[1]-5,10,10)}});
 if(st)st.trains.forEach(t=>{const c=trainColors[(t.id-1)%trainColors.length];cx.strokeStyle=c;cx.lineWidth=11;path(t.s-geo.trainLength,t.s);
  const f=P(t.s);cx.fillStyle=c;cx.font='bold 13px system-ui';const label='T'+t.id+' '+t.v.toFixed(1);const w=cx.measureText(label).width;
  cx.fillRect(f[0]+6,f[1]-22,w+8,18);cx.fillStyle='#fff';cx.fillText(label,f[0]+10,f[1]-8)})}
function panel(){if(!st||!geo)return;
 $('state').textContent=st.state;$('state').style.background=stateColors[st.stateKey]||'#555';$('mode').textContent='MODE: '+st.mode;
 const a=$('alarm');if(st.alarm){a.textContent=(st.alarm.firstOut?'1ST OUT · ':'')+st.alarm.sev+'  '+st.alarm.text+(st.alarm.count>1?'  (+'+(st.alarm.count-1)+' more)':'');
  a.style.background=st.alarm.stop?'#b71c1c':'#8a6400';a.style.color='#fff'}else{a.textContent=st.status||'No active alarms';a.style.background='#2a3036';a.style.color='#9aa4ad'}
 const d=$('drill');if(st.drill){d.style.display='block';d.textContent='DRILL '+st.drill.title+' · '+Math.floor(st.drill.t/60)+':'+String(Math.floor(st.drill.t%60)).padStart(2,'0')+' · NEXT: '+st.drill.next+' · score '+st.drill.score}else d.style.display='none';
 $('blocks').innerHTML=geo.blocks.map((b,i)=>{const o=st.occ[i];const br=b.stop==null?(b.kind=='LIFT'?(st.chain>0.1?'CHAIN RUN':'CHAIN STOP'):'—'):(st.brakes[i]==1?'OPEN':st.brakes[i]==0?'CLOSED':'MOVING');
  return '<tr><td>'+b.tag+' '+b.name+'</td><td><span class="dot" style="background:'+(o!==-1?'#c62828':'#2e7d32')+'"></span>'+(o!==-1?'OCC':'CLEAR')+'</td><td>'+(o!==-1?'T'+o:'—')+'</td><td>'+br+'</td></tr>'}).join('');
 $('data').innerHTML=[['Main air',st.air+' psi'],['Dispatches',st.dispatches],['Safety outputs',st.safetyOn?'ON':'OFF'],['I/O forces',st.forces]].map(r=>'<div class="kv"><span>'+r[0]+'</span><b>'+r[1]+'</b></div>').join('')}
async function loadGeo(){geo=await (await fetch('/geometry')).json();$('name').textContent=geo.name+(geo.park?' – '+geo.park:'');fit()}
async function poll(){try{const s=await (await fetch('/state')).json();if(s.version!==undefined){if(!geo||s.version!==geo.version)await loadGeo();st=s}fail=0;$('conn').textContent=''}
 catch(e){if(++fail>3)$('conn').textContent='● disconnected'}
 panel();draw();setTimeout(poll,200)}
addEventListener('resize',()=>{fit();draw()});
poll();
</script></body></html>
""".trimIndent()
}
