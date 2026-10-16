package com.abemu.coaster.web

/**
 * Landing page for the tablet's web server, themed as a Ballin'-Radley ride-control console: one
 * illuminated push-button tile per tool. Add new browser tools to [tools].
 */
object WebHome {
    private class Tool(
        val path: String, val lamp: String, val tag: String, val title: String, val text: String,
        val locked: Boolean = false, val note: String? = null,
    )

    private fun tools(instructorOn: Boolean) = listOf(
        Tool(
            "/map", "green", "STATION BOARD", "Live ride map",
            "Watch every train go round: block occupancy, brakes, lift and the first-out alarm, live from the PLC. Read-only — safe for the whole crew.",
        ),
        Tool(
            "/instructor", "amber", "INSTRUCTOR KEY", "Remote instructor",
            "Throw faults at the trainee, whip up a storm cell, crank the sim speed and run graded drills — all without touching their tablet.",
            locked = true,
            note = if (instructorOn) null else "KEY OUT — turn on REMOTE INSTR. on the tablet's INSTRUCTOR page.",
        ),
    )

    fun page(instructorOn: Boolean): String = buildString {
        append(HEAD)
        tools(instructorOn).forEach { t ->
            val off = t.note != null
            append("<a class=\"tile${if (off) " off" else ""}\" href=\"${if (off) "#" else t.path}\">")
            append("<div class=\"btn ${if (off) "dark" else t.lamp}\"><span></span></div>")
            append("<div><div class=\"tag\">${t.tag}${if (t.locked) " · 🔑 PIN" else ""}</div><h2>${t.title}</h2><p>${t.text}</p>")
            t.note?.let { append("<p class=\"note\">$it</p>") }
            append("</div></a>")
        }
        append(TAIL)
    }

    private val HEAD = """
<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Ballin'-Radley Ride Control</title>
<style>
 :root{--blue:#1f5aa8;--amber:#e5a400}
 body{margin:0;background:radial-gradient(ellipse at top,#1b2a3d 0%,#0b1016 70%) fixed;color:#dfe6ec;font:15px system-ui,sans-serif;min-height:100vh}
 .plate{display:flex;align-items:center;gap:12px;margin:14px;padding:6px 10px;border:2px solid var(--blue);border-radius:8px;
  background:linear-gradient(90deg,#fff 0%,#fff 32%,#4e86cc 62%,#2f68b5 100%)}
 .logo{width:62px;height:40px;border-radius:50%;background:var(--blue);border:2px solid #fff;box-shadow:0 0 0 1px var(--blue);
  display:flex;flex-direction:column;align-items:center;justify-content:center;color:#fff;font-weight:900;line-height:1}
 .logo b{font-size:18px} .logo small{font-size:7px;font-weight:700;letter-spacing:.06em}
 .brand{color:var(--blue);font-size:26px;font-weight:900;font-style:italic;flex:1;letter-spacing:-.01em}
 .pill{background:#fff;color:var(--blue);border-radius:99px;padding:3px 14px;font-weight:700;font-style:italic;font-size:14px;white-space:nowrap}
 .hero{position:relative;margin:0 14px;height:220px;border-radius:8px;overflow:hidden;
  background:linear-gradient(#0e2440 0%,#284f7c 55%,#e08a3c 100%);border:1px solid #2f3b46}
 .hero svg{position:absolute;inset:0;width:100%;height:100%}
 .hero .title{position:absolute;right:20px;top:14px;text-align:right;text-shadow:0 2px 6px #000}
 .hero h1{margin:0;font-size:28px;font-weight:900;font-style:italic;letter-spacing:.02em}
 .hero .sub{color:#ffe1a8;font-size:13px;font-weight:600}
 .status{display:flex;flex-wrap:wrap;gap:8px;margin:12px 14px 0;font-size:13px}
 .chip{padding:5px 12px;border-radius:4px;background:#1a2229;border:1px solid #2f3b46;font-weight:700}
 .chip i{font-style:normal;color:#8a96a1;font-weight:600;margin-right:6px}
 main{display:grid;grid-template-columns:repeat(auto-fill,minmax(min(320px,100%),1fr));gap:16px;padding:16px 14px;max-width:1100px}
 .tile{display:flex;gap:16px;align-items:flex-start;padding:16px;border-radius:8px;color:inherit;text-decoration:none;
  background:linear-gradient(#3a4148,#262b30);border:1px solid #4a535c;box-shadow:inset 0 1px 0 #5c656e,0 4px 14px #0008;transition:transform .08s}
 .tile:hover{transform:translateY(-2px);border-color:#7fa9d9} .tile:active{transform:translateY(1px)}
 .tile.off{filter:saturate(.3);opacity:.7;cursor:not-allowed}
 .btn{flex:none;width:62px;height:62px;border-radius:50%;background:linear-gradient(#cfd4d8,#7d858c);display:flex;align-items:center;justify-content:center;
  box-shadow:0 3px 0 #1b1f23,0 5px 10px #000a}
 .btn span{width:46px;height:46px;border-radius:50%;box-shadow:inset 0 -4px 8px #0006,inset 0 4px 6px #fff6}
 .btn.green span{background:radial-gradient(circle at 40% 35%,#9dff9d,#1e9e48 60%,#0d5a26);box-shadow:inset 0 -4px 8px #0006,inset 0 4px 6px #fff6,0 0 18px #2ecc71aa;animation:glow 2.4s infinite}
 .btn.amber span{background:radial-gradient(circle at 40% 35%,#fff0a8,#e5a400 60%,#7a5600);box-shadow:inset 0 -4px 8px #0006,inset 0 4px 6px #fff6,0 0 18px #e5a400aa;animation:glow 2.4s infinite .6s}
 .btn.dark span{background:radial-gradient(circle at 40% 35%,#777,#3a3a3a 60%,#1a1a1a)}
 @keyframes glow{50%{filter:brightness(1.25)}}
 .tag{font-size:11px;font-weight:800;letter-spacing:.12em;color:#e5a400}
 h2{margin:2px 0 6px;font-size:19px} p{margin:0;color:#b7c0c8;font-size:13px;line-height:1.45}
 .note{color:#ffb74d;margin-top:8px;font-weight:600}
 footer{margin:6px 14px 18px}
 @media (max-width:560px){.pill{display:none}.brand{font-size:22px}.hero h1{font-size:20px}.hero .sub{font-size:11px}}
 .hazard{height:14px;border-radius:3px;background:repeating-linear-gradient(-45deg,#e5a400 0 14px,#111 14px 28px)}
 .warn{text-align:center;font-weight:800;letter-spacing:.14em;font-size:12px;color:#e5a400;padding:8px 0}
</style></head><body>
<div class="plate"><div class="logo"><b>BR</b><small>BALLIN'</small></div><div class="brand">Ballin'-Radley</div><div class="pill">Ride Control Network</div></div>
<div class="hero">
 <svg id="scene" viewBox="0 0 1200 220" preserveAspectRatio="xMidYMax meet">
  <defs>
   <radialGradient id="sunglow"><stop offset="0" stop-color="#ffe2a0"/><stop offset=".25" stop-color="#ffc56b" stop-opacity=".9"/><stop offset="1" stop-color="#ff9a3c" stop-opacity="0"/></radialGradient>
   <linearGradient id="rail" x1="0" x2="0" y1="0" y2="1"><stop offset="0" stop-color="#ff6b4a"/><stop offset="1" stop-color="#b3261e"/></linearGradient>
   <mask id="railmask" maskUnits="userSpaceOnUse" x="-600" y="-50" width="2400" height="320">
    <use href="#trk" style="stroke:#fff;stroke-width:6.4"/><use href="#trk" style="stroke:#000;stroke-width:3.6"/>
   </mask>
  </defs>
  <circle cx="1010" cy="168" r="120" fill="url(#sunglow)"/>
  <circle cx="1010" cy="168" r="24" fill="#ffe7b0"/>
  <path d="M-600 205 L-600 170 C-300 150 -100 175 120 160 C300 148 420 172 600 158 C780 145 900 170 1100 156 C1300 145 1600 165 1800 160 L1800 205Z" fill="#2a3f5e" opacity=".75"/>
  <g id="supports" style="stroke:#0c141d;stroke-linecap:round"></g>
  <path id="trk" fill="none" d="M-160 190 L150 190 C175 190 185 185 200 172 L360 42 C375 30 392 28 405 40 C440 75 480 175 520 188
   C550 196 570 185 590 150 C615 105 630 82 650 82 C672 82 690 110 715 155 C735 188 760 190 785 170 C805 150 820 125 845 125
   C868 125 885 150 905 175 C920 190 940 190 970 190 L1400 190"/>
  <use href="#trk" style="stroke:#0c141d;stroke-width:9;stroke-dasharray:1.4 3.6"/>
  <rect x="-600" y="-50" width="2400" height="320" fill="url(#rail)" mask="url(#railmask)"/>
  <g id="train"></g>
  <g fill="#0c141d">
   <path d="M20 192 L20 168 L10 168 L80 150 L150 168 L140 168 L140 192Z"/>
   <rect x="34" y="174" width="92" height="3" fill="#ffcf6b" opacity=".8"/>
   <path id="trees"/>
   <rect x="-600" y="203" width="2400" height="40"/>
  </g>
 </svg>
 <div class="title"><h1>WELCOME TO THE BOOTH</h1><div class="sub" id="ride">Pick a station below — the queue's short today.</div></div>
</div>
<div class="status" id="status"></div>
<main>
"""

    private val TAIL = """
</main>
<footer><div class="hazard"></div><div class="warn">KEEP HANDS, ARMS, FEET AND LAPTOPS INSIDE THE RIDE AT ALL TIMES</div><div class="hazard"></div></footer>
<script>
const colors={POWER_OFF:'#8e959c',STOPPED:'#2c6fd1',STARTING:'#e5a400',RUNNING:'#1e9e48',CSTOP:'#e5a400',ESTOP:'#d42020'};
fetch('/geometry').then(r=>r.json()).then(g=>{if(g.name)document.getElementById('ride').textContent='Now operating: '+g.name+(g.park?' · '+g.park:'')}).catch(()=>{});
const esc=t=>String(t).replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
function chip(k,v,c){return '<span class="chip"'+(c?' style="background:'+c+';border-color:'+c+'"':'')+'><i>'+esc(k)+'</i>'+esc(v)+'</span>'}
async function poll(){try{const s=await (await fetch('/state')).json();if(s.state){
 document.getElementById('status').innerHTML=chip('RIDE',s.state,colors[s.stateKey])+chip('MODE',s.mode)+(s.desk?chip('DESK MODE',s.desk,'#1b6b3a'):'')+chip('TRAINS',s.trains.length)+
  chip('DISPATCHES',s.dispatches)+chip('AIR',s.air+' psi')+(s.alarm?chip(s.alarm.sev,s.alarm.text,s.alarm.stop?'#b71c1c':'#8a6400'):chip('ALARMS','ALL CLEAR'))}}catch(e){}
 setTimeout(poll,1000)}
poll();
(function(){
 const NS='http://www.w3.org/2000/svg',trk=document.getElementById('trk'),L=trk.getTotalLength(),G=203;
 const el=(n,a,p)=>{const e=document.createElementNS(NS,n);for(const k in a)e.setAttribute(k,a[k]);if(p)p.appendChild(e);return e};
 // supports: columns every ~22 units down to the ground, X-bracing between neighbours
 const sup=document.getElementById('supports');let prev=null;
 for(let s=0;s<L;s+=4){const p=trk.getPointAtLength(s);if(p.x<180||p.x>960||p.y>184)continue;
  if(prev&&p.x-prev.x<22)continue;
  el('line',{x1:p.x,y1:p.y+3,x2:p.x,y2:G,'stroke-width':p.y<120?2.2:1.6},sup);
  if(prev&&p.x-prev.x<40){const top=Math.max(p.y,prev.y)+6;for(let y=top;y<G-8;y+=26){
   el('line',{x1:prev.x,y1:y,x2:p.x,y2:Math.min(G,y+26),'stroke-width':.8},sup);el('line',{x1:p.x,y1:y,x2:prev.x,y2:Math.min(G,y+26),'stroke-width':.8},sup)}}
  prev=p}
 // tree line
 let d='';for(let x=-600;x<1800;x+=7+((x*7919)%7+7)%7){if(x>5&&x<165)continue;const r=6+((x*31)%7+7)%7,y=206-r*0.6;d+='M'+(x-r)+' 206V'+y+'a'+r+' '+r*0.8+' 0 1 1 '+2*r+' 0V206Z'}
 document.getElementById('trees').setAttribute('d',d);
 // train: 5 cars driven by a chain lift, then energy conservation
 const N=5,gap=15,cars=[],tg=document.getElementById('train');
 for(let k=0;k<N;k++){const g=el('g',{},tg);el('rect',{x:-6.5,y:-8,width:13,height:6,rx:1.5,fill:k?'#1565c0':'#1e88e5'},g);
  el('circle',{cx:-2.5,cy:-9.5,r:1.6,fill:'#0c141d'},g);el('circle',{cx:2.5,cy:-9.5,r:1.6,fill:'#0c141d'},g);cars.push(g)}
 let liftA=0,top=0,yTop=1e9;for(let s=0;s<L;s+=2){const p=trk.getPointAtLength(s);if(!liftA&&p.x>=190)liftA=s;if(p.x<500&&p.y<yTop){yTop=p.y;top=s}}
 const brake=(()=>{for(let s=top;s<L;s+=2)if(trk.getPointAtLength(s).x>=960)return s;return L})();
 let s=liftA-120,v=40,last=performance.now();
 function at(q){const a=trk.getPointAtLength(Math.max(0,Math.min(L,q))),b=trk.getPointAtLength(Math.max(0,Math.min(L,q+1)));return [a.x,a.y,Math.atan2(b.y-a.y,b.x-a.x)*57.3]}
 function frame(t){const dt=Math.min(.05,(t-last)/1000);last=t;
  if(s<top)v=s<liftA?45:28;else if(s<brake)v=Math.sqrt(28*28+2*260*Math.max(0,trk.getPointAtLength(s).y-yTop));else v=Math.max(40,v-120*dt);
  s+=v*dt;if(s-N*gap>trk.getPointAtLength(L).x+400||s>L)s=0;
  cars.forEach((c,k)=>{const p=at(s-k*gap);c.setAttribute('transform','translate('+p[0]+' '+p[1]+') rotate('+p[2]+')')});
  requestAnimationFrame(frame)}
 requestAnimationFrame(frame)})();
</script></body></html>
"""
}
