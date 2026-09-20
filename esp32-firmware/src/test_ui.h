// Debug page served by the ESP32 itself: live camera + board vitals + diagnostics chart/log.
// (The real user-facing UI is the PrintHost dashboard; this page is for looking at the board directly.)
#pragma once
#include <Arduino.h>

static const char TEST_UI_HTML[] PROGMEM = R"rawliteral(<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>PrintHost ESP32 CAM</title>
<style>
  :root {
    color-scheme: light dark;
    --bg: #111214; --panel: #1c1e22; --panel-2: #202226; --panel-border: #2a2d32;
    --text: #f2f3f5; --text-dim: #9a9da3; --text-faint: #6b6e74;
    --accent: #00c853; --accent-dim: #0a3d24; --warn: #f0b90b; --danger: #ff6b6b;
    --radius: 14px; --radius-sm: 9px;
  }
  * { box-sizing: border-box; }
  body { margin: 0; padding: 12px; background: var(--bg); color: var(--text);
         font-family: -apple-system, system-ui, sans-serif; font-size: 13px; }
  .num { font-variant-numeric: tabular-nums; }
  h1 { font-size: 16px; margin: 0 0 10px; font-weight: 700; }
  h2 { font-size: 11px; margin: 0 0 8px; color: var(--text-faint); text-transform: uppercase; letter-spacing: .06em; }
  button { background: var(--panel-2); color: var(--text); border: 1px solid var(--panel-border);
           border-radius: var(--radius-sm); padding: 10px 0; font-size: 12.5px; font-weight: 700; cursor: pointer; flex: 1; }
  .card { background: var(--panel); border: 1px solid var(--panel-border); border-radius: var(--radius); padding: 16px; }
  .grid { display: grid; grid-template-columns: minmax(0, 1.6fr) minmax(0, 1fr); gap: 12px; align-items: start; }
  @media (max-width: 720px) { body { padding: 8px; } .grid { grid-template-columns: 1fr; gap: 10px; } .card { padding: 12px; } }
  .frame { aspect-ratio: 16/9; width: 100%; border-radius: var(--radius-sm); background: #000; position: relative; overflow: hidden; }
  #cameraImg { width: 100%; height: 100%; object-fit: contain; display: block; }
  .badge { position: absolute; top: 10px; left: 10px; background: rgba(0,0,0,0.55); color: var(--text-dim);
           font-size: 10.5px; font-weight: 600; padding: 3px 8px; border-radius: 20px; }
  .kv { display: flex; justify-content: space-between; padding: 4px 0; color: var(--text-dim); }
  .kv b { color: var(--text); font-weight: 600; }
  .stack { display: flex; flex-direction: column; gap: 12px; }
  .row { display: flex; gap: 8px; margin-top: 8px; }
  .msg { font-size: 11.5px; color: var(--text-dim); margin-top: 6px; }
  input { background: var(--panel-2); color: var(--text); border: 1px solid var(--panel-border); border-radius: var(--radius-sm);
          padding: 7px 8px; font-size: 12px; width: 100%; margin-bottom: 8px; }
</style>
</head>
<body>
<h1>PrintHost &middot; ESP32 CAM</h1>
<div class="grid">
  <div class="card">
    <div class="frame">
      <img id="cameraImg" alt="camera stream">
      <span class="badge" id="badge">LIVE</span>
    </div>
  </div>

  <div class="stack">
    <div class="card">
      <h2>Board</h2>
      <div class="kv"><span>Stream FPS</span><b class="num" id="fps">&ndash;</b></div>
      <div class="kv"><span>Wi-Fi signal</span><b class="num" id="rssi">&ndash;</b></div>
      <div class="kv"><span>Chip temperature</span><b class="num" id="temp">&ndash;</b></div>
      <div class="kv"><span>CPU load</span><b class="num" id="cpu">&ndash;</b></div>
      <div class="kv"><span>Frame size</span><b class="num" id="fbytes">&ndash;</b></div>
      <div class="kv"><span>Camera&rarr;Wi-Fi delay</span><b class="num" id="lat">&ndash;</b></div>
      <div class="kv"><span>IP</span><b class="num" id="ip">&ndash;</b></div>
      <div class="kv"><span>Free PSRAM</span><b class="num" id="psram">&ndash;</b></div>
      <div class="kv"><span>SD card</span><b id="sd">&ndash;</b></div>
      <div class="kv"><span>Uptime</span><b class="num" id="uptime">&ndash;</b></div>
      <div class="kv"><span>Last restart</span><b id="reset">&ndash;</b></div>
      <div class="kv"><span>Wi-Fi drops</span><b class="num" id="drops">&ndash;</b></div>
    </div>

    <div class="card">
      <h2>Diagnostics</h2>
      <canvas id="chart" width="600" height="120" style="width:100%;height:120px;background:#0a0a0c;border-radius:8px"></canvas>
      <div class="msg" style="display:flex;justify-content:space-between"><span><span style="color:var(--accent)">&#9632;</span> fps (0&ndash;30)</span><span><span style="color:var(--warn)">&#9632;</span> Wi-Fi signal</span></div>
      <div class="row">
        <button id="logBtn">Show log</button>
        <button id="logCopy">Copy log</button>
      </div>
      <pre id="logView" style="display:none;max-height:260px;overflow:auto;font-size:10.5px;color:var(--text-dim);white-space:pre-wrap;margin:8px 0 0"></pre>
    </div>

    <div class="card" id="wifiCard" style="display:none">
      <h2>Wi-Fi setup</h2>
      <input type="text" id="ssid" autocomplete="off" placeholder="SSID (2.4 GHz)">
      <input type="password" id="pass" autocomplete="off" placeholder="Password">
      <button id="wifiBtn" style="width:100%">Save &amp; reboot</button>
      <div class="msg" id="wifiMsg"></div>
    </div>
  </div>
</div>

<script>
var $ = function (id) { return document.getElementById(id); };
$("cameraImg").src = "http://" + location.hostname + ":81/stream?_=" + Date.now();

function fmtUp(s) { var h = Math.floor(s / 3600), m = Math.floor(s % 3600 / 60); return h + "h " + m + "m " + (s % 60) + "s"; }
function poll() {
  fetch("/status").then(function (r) { return r.json(); }).then(function (s) {
    $("fps").textContent = s.fps.toFixed(1);
    $("badge").textContent = "LIVE · " + s.fps.toFixed(0) + " fps";
    $("rssi").textContent = s.mode === "sta" ? s.rssi + " dBm" : "setup network";
    $("temp").textContent = s.tempC.toFixed(0) + " °C";
    $("cpu").textContent = s.cpu0 + "% / " + s.cpu1 + "%";
    $("fbytes").textContent = (s.frameBytes / 1024).toFixed(1) + " KB";
    $("lat").textContent = s.latencyMs + " ms";
    $("ip").textContent = s.ip;
    $("psram").textContent = (s.freePsram / 1048576).toFixed(1) + " MB";
    $("sd").textContent = s.sd;
    $("uptime").textContent = fmtUp(s.uptime);
    $("reset").textContent = s.reset;
    $("drops").textContent = s.wifiDrops;
    $("wifiCard").style.display = s.mode === "ap" ? "block" : "none";
  }).catch(function () {});
}
poll(); setInterval(poll, 2000);

function drawChart(rows) {
  var c = $("chart"), g = c.getContext("2d"), W = c.width, H = c.height;
  g.clearRect(0, 0, W, H);
  g.strokeStyle = "#2a2d32"; g.lineWidth = 1;
  [0.25, 0.5, 0.75].forEach(function (f) { g.beginPath(); g.moveTo(0, H * f); g.lineTo(W, H * f); g.stroke(); });
  if (rows.length < 2) return;
  function line(idx, lo, hi, color) {
    g.strokeStyle = color; g.lineWidth = 2; g.beginPath();
    rows.forEach(function (r, i) {
      var v = Math.max(lo, Math.min(hi, r[idx])), x = i * W / (rows.length - 1), y = H - (v - lo) / (hi - lo) * (H - 6) - 3;
      if (i === 0) g.moveTo(x, y); else g.lineTo(x, y);
    });
    g.stroke();
  }
  line(1, 0, 30, "#00c853");
  line(2, -90, -40, "#f0b90b");
}
var lastLog = "";
function refreshLog(show) {
  fetch("/log").then(function (r) { return r.text(); }).then(function (txt) {
    lastLog = txt;
    var i = txt.indexOf("--- samples");
    var rows = txt.slice(i).split("\n").slice(1).filter(Boolean).map(function (l) { return l.split(",").map(Number); });
    drawChart(rows.filter(function (r) { return r.length > 3 && !isNaN(r[1]); }));
    if (show) $("logView").textContent = txt.slice(0, i) + "(samples are in the chart; use Copy log for the raw numbers)";
  }).catch(function () {});
}
$("logBtn").onclick = function () {
  var v = $("logView"), open = v.style.display === "none";
  v.style.display = open ? "block" : "none"; $("logBtn").textContent = open ? "Hide log" : "Show log";
  if (open) refreshLog(true);
};
$("logCopy").onclick = function () {
  var done = function () { $("logCopy").textContent = "Copied"; setTimeout(function () { $("logCopy").textContent = "Copy log"; }, 1500); };
  if (navigator.clipboard) navigator.clipboard.writeText(lastLog).then(done); else window.open("/log", "_blank");
};
refreshLog(false); setInterval(function () { refreshLog($("logView").style.display !== "none"); }, 3000);

$("wifiBtn").onclick = function () {
  var body = "ssid=" + encodeURIComponent($("ssid").value) + "&pass=" + encodeURIComponent($("pass").value);
  fetch("/wifi", { method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" }, body: body })
    .then(function () { $("wifiMsg").textContent = "Saved. Rebooting - reconnect to your home Wi-Fi."; })
    .catch(function () { $("wifiMsg").textContent = "Saved (connection dropped while rebooting)."; });
};
</script>
</body>
</html>
)rawliteral";
