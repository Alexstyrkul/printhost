# PrintHost: ESP32 bridge - handoff (2026-09-21)

Read this first. It says what exists, what was verified, what was NOT, and what to do next.
Branch: `feature/esp32-bridge` (based on `feature/scheduled-print`; `master` untouched).

## 1. Architecture (decided by the user - do not change without asking)

| Device | Role |
|---|---|
| ESP32-S3 board (N16R8, OV3660 camera, 2x USB-C) | Printer control board: drives the Ender-3 V3 SE over USB (host), streams the camera, stores gcode on its SD card, reports its own vitals/log. Always powered (wall charger on the UART port). |
| Android phone (OnePlus 6T) | The server: runs the PrintHost app, serves the dashboard on `:8899`, owns the Tapo smart plug, schedule, 3D-preview cache. **Never connects to the printer any more** (that was the old design). |
| Mac / iOS / anything | Clients only: open `http://<phone>:8899/`. |

Addresses (DHCP, they can change - the user should reserve them in the router): ESP `192.168.50.190`, phone `192.168.50.85`.
Printer mainboard: Creality `CR4NS200320C14`, Marlin 1.0.6, USB chip **CH340 (1a86:7523)**, 115200 baud.
The camera is powered only while the printer's Tapo plug is on (phone pushes this to the ESP); "Turn on camera" in the dashboard forces it on for testing.

## 2. Where things are

- `esp32-firmware/` - PlatformIO project (Arduino as an ESP-IDF component). See its README for build/flash/OTA.
  - `src/main.cpp` HTTP servers, camera, Wi-Fi, OTA, log ring + SD log, file store endpoints.
  - `src/printer.cpp` print engine (task on core 1). `src/link_sim.cpp` fake Marlin. `src/link_usb.cpp` CH340 over IDF USB host.
  - `components/esp32-camera` v2.0.4 (vendored), `components/usb_host_cdc_acm` v2.0.6 (vendored, **patched**: `cdc_acm_host_rx_pause/resume`).
- `src/dev/oleksandr/printhost/` (Android app): `EspPrinterConnection` (translates the app's printer calls into ESP HTTP calls), `EspStoreConnection` (file store on the ESP), `PrinterService` (always uses the ESP connection; `CameraSyncThread`), `DashboardRouter` (`/camera/force`).
- `assets/dashboard.html` - the dashboard (camera card = ESP stream + board stats + log modal; schedule now lives in the main card).

## 3. What was done and verified

- Camera stream (MJPEG :81), default profile **xga 1024x768 (4:3, full field of view, ~18 fps)**; other profiles: fast, balanced, sharp (1280x720, crops top/bottom), sxga. Adaptive JPEG quality steers on frame send time. Image is rotated 180 degrees (`CAM_VFLIP`/`CAM_HMIRROR` = 1 in main.cpp) - confirmed upright.
- Wi-Fi provisioning fallback AP, mDNS `printhost-cam.local`, auto-reconnect, **OTA** (works over Wi-Fi, password in `secrets.h`), 2 OTA slots on 16 MB flash.
- Runtime diagnostics: `/status`, `/log` (5 min ring + events), persistent log on SD at `/logs/printhost.log` (1 MB cap, keeps newest half), chip temperature, CPU load, reset reason (BROWNOUT/TASK_WDT/PANIC shown).
- File store on the ESP SD (`/gcode`): streamed upload with size+CRC check, list, delete, long names (FAT LFN on). Verified with a real 26 MB file. Phone app upload -> ESP works with the printer disconnected.
- Print engine, verified **only against the simulator**: numbered lines + XOR checksum, exactly one command in flight, Resend recovery (both "Resend+ok" and "Resend only"), M105 poll every 2 s, pause/resume/stop, silence watchdog, refuses M502/M997 and manual gcode while printing, protects the file being printed. A full real 985,189-line file ran to 100% in the simulator, also with injected checksum errors.
- Phone <-> ESP flow verified end to end in simulator mode: upload, select, start, phone disconnect/reconnect mid-print (ESP keeps printing, phone resumes), pause/resume, finish.
- **First real connection to the printer worked**: CH340 opened, Marlin V1.0.6 / Ender-3 V3 SE identified, temperatures read (25.x C), no restarts or brownout.

## 4. What was NOT verified

- **No real print has ever been run through the bridge.** Pause/resume/stop behaviour on the real printer, Resend on real firmware, layer/fan display during a real print - all untested.
- Wi-Fi behaviour **while printing** (see finding 5.1). Idle+connected is fine; sustained USB traffic is not measured yet.
- Stability over hours; `min free heap` dipped to ~31 KB once (a stream + 10 MB upload); leaks not ruled out.

## 5. Findings you must know (each cost hours)

1. **USB IN polling wrecks the ESP's Wi-Fi.** With the CH340 session open and the driver polling bulk IN continuously, ping to the board goes from ~20 ms to 1.5-6 s with 70-80% loss (CPU is idle, RSSI unchanged -> interference, not load). Paused RX = 14-18 ms. The patched driver + `UsbLink::applyRxPolicy()` polls only for ~40-60 ms after a write / while data flows, plus 8 ms look-ins per 100 ms while a reply is pending, and not at all when idle. Idle+connected is now ~17-24 ms. **During a print RX is on almost continuously -> expect degradation.** `POST /printer/usbtune?idlePoll=&lookIn=&gate=` exists for experiments (temporary).
2. OV3660: `set_aec2(1)` drops fps to ~9 in dim light; `set_denoise` **crashes the board** (PANIC). Don't add either.
3. esp32-camera task stack default 2 KB overflowed while logging FB-OVF -> random reboots. `CONFIG_CAMERA_TASK_STACK_SIZE=4096` and log level of `cam_hal` silenced.
4. Stock Arduino builds have a ~5.7 KB TCP window; we build with `sdkconfig.defaults` (32 KB window, Wi-Fi buffers, PSRAM alloc for Wi-Fi/lwIP).
5. **Never let a stalled Wi-Fi client reboot the board**: a task watchdog on the stream handler rebooted the ESP (would abort a print). Removed.
6. The FAT driver only accepted 8.3 names until `CONFIG_FATFS_LFN_HEAP=y`. The phone app still uses its own 8.3 short names + `SdFilenameMap` for display names (legacy from the printer SD). Files with spaces put on the card by hand are invisible in the dashboard list (the M20-style parser splits on the first space).
7. Only ONE stream viewer at a time on :81 (a stale browser tab blocks new ones).
8. After changing `sdkconfig.defaults`, delete `sdkconfig.esp32s3_*` or the change is silently ignored. `pio ... upload` for the OTA env often prints FAILED/`Result: 496` after the update actually succeeded - check `/log` BOOT build time.
9. Wi-Fi at the mounting spot is weak (RSSI -65..-71 dBm); router 2.4 GHz channel set to 11/20 MHz. Camera scene is dark -> big noisy JPEGs -> lower fps; more light helps most.
10. Wireless adb to the phone (`adb connect 192.168.50.85:5555`) needs `adb kill-server && adb start-server` first on macOS; `adb tcpip 5555` needs USB once. The phone's IP changed once (DHCP).
11. **The big stream slowdowns were the router's 2.4 GHz channel, not firmware (2026-09-21).** On channel 11 (a neighbour at -48 dBm on the same channel, others on 1/6/8) raw TCP from the board was 150-500 KB/s, ping 80-140 ms, JPEG quality fell to 23 and fps to 2-11, with the CPU 95% idle. The phone showed the same latency pattern. After fixing the router at **channel 1, 20 MHz**: RSSI -53..-55, raw TCP ~1.0-1.17 MB/s, ping 11-23 ms, quality 8 and 20-23 fps. Radio settings (802.11b on/off, TX power 8.5-19.5 dBm) and a synthetic interrupt load on core 0 or 1 made no visible difference. **Do not turn on router auto-channel**; if the stream degrades again, re-scan channels first.
12. **USB does not hurt the stream at the loads tested (2026-09-21).** With the CH340 open: idle ping ~23 ms, fps 18-21; with ~29 `M105`/s for 30 s: ping 12 ms avg, fps 16-20, 0% loss, quality 8. Not tested: a real print (100-200 lines/s continuously). Finding 5.1 above was measured on the crowded channel 11 and may have been partly an artefact of it - re-measure during the first print.

## 6. Suggested next steps (priority order)

1. **With the user's explicit permission**, measure a print-like USB load without moving the printer: send `M105` at ~100-200/s for 30 s via the engine and watch ping/fps. If Wi-Fi collapses: (a) ferrite/shorter/away-from-antenna cable, cable without VBUS; (b) window >1 (several numbered lines in flight, batch acks, RX polling in short bursts) - prove it in the simulator first (extend `link_sim.cpp` to model Marlin's behaviour with several lines outstanding).
2. First real print, supervised, small file, user next to the printer. Check pause/resume/stop, Resend, heater targets, and that Wi-Fi/camera stay usable.
3. Auto-connect the ESP to the printer at boot and re-open the CH340 on hot-plug/`device disconnected`; make the phone re-sync when the ESP reboots (it can think it is connected while the ESP is `NO_LINK`).
4. Consider a park move on pause and what `stop` should do about the nozzle position (currently: heaters off, fan off, nothing else).
5. Cleanup: remove `/printer/usbtune`, trace logging in `printer.cpp` (`traceBudget`), unused phone code (`CameraController`, direct-USB code in `PrinterConnection`/`PrinterService`), `PHLOG` migration lines in `setup()`.
6. Show real long file names (drop the 8.3 short-name scheme now that storage is on the ESP).
7. Router DHCP reservations for the ESP and phone; consider external antenna / lighting.

## 7. Working agreements with the user (important)

- **Never connect to, plug, or send gcode to the real printer without asking first** - every time, no standing consent. Read-only queries count. The user often has a print running.
- Never author gcode for tests; use real slicer files (e.g. `~/Downloads/clamp right side of the table_PETG_4h55m.gcode`).
- Do not use the phone's own screen/browser to verify UI (it is a headless server); check via HTTP or ask the user to look. adb only to install the APK.
- Do not risk the ESP board: software-only changes, no eFuse/bootloader tricks. Flashing over UART is always recoverable.
- No `Co-Authored-By` trailer in commits. Confirm before commit/push (the user asked for this one explicitly).
- The user prefers to give exact layout instructions; for open-ended UI changes show options first.
- Secrets: Wi-Fi and OTA passwords live only in `esp32-firmware/src/secrets.h` (git-ignored). Never print or commit them.

## 8. Handy commands

```bash
# build + flash over USB (first time / partition changes)
cd esp32-firmware && uvx --from platformio pio run -e esp32s3_tuned -t upload --upload-port /dev/cu.usbmodem*
# flash over Wi-Fi
export PRINTHOST_OTA_PASS=$(grep -o 'OTA_PASS "[^"]*"' src/secrets.h | sed 's/.*"\(.*\)"/\1/')
uvx --from platformio pio run -e esp32s3_ota -t upload
# Android app: build and install (phone wireless adb)
./build.sh && ~/Library/Android/sdk/platform-tools/adb -s 192.168.50.85:5555 install -r out/signed.apk
# board health
curl http://192.168.50.190/status ; curl http://192.168.50.190/log
```

---
# UPDATE 2026-09-22 - first real print, root causes, what changed (supersedes parts of sections 4-6 above)

**Result of the first real print through the board:** 233,541 lines, 96.4 min (slicer estimate 89), finished cleanly; 5 resends, all in the first ~2700 lines; no USB/Wi-Fi/reboot events; chip 63-72 C (plateau ~72). Two real defects were found in the log:

1. **Finding 5.1 above is WRONG.** "Polling USB IN wrecks Wi-Fi" was measured on a crowded 2.4 GHz channel 11; on channel 1 open USB with continuous RX does not hurt Wi-Fi or the stream. The RX "gating" it led to (pause/resume of the IN transfer) LOST the first bytes of printer lines and swallowed replies (this caused the mysterious "no ok after G28", the frozen temperatures at 218 C and the 4 s stalls). Gating is removed; RX is always on.
2. **A printer reply of exactly 64 bytes froze RX** (bulk IN transfer with a 512 B buffer only completes on a short packet; the CH340 sends no zero-length packet). Reproducible at idle: `G92 E-1234.56` then `M114` (reply line of 60 chars) timed out. Fixed by `dc.in_buffer_size = 0` in `link_usb.cpp` (IN transfers = 1 packet = 64 B) and `USB_TRANSFER_FLAG_ZERO_PACK` on OUT transfers in the vendored `cdc_acm_host.c`. This showed up in the print as every `G92 E0` on layers Z>=12.65 mm waiting 4 s (until the M105 probe pushed more bytes).
3. **Stop-and-wait starved the printer on fine detail:** 25% of the print time (48% of lines) was fed at ~86 lines/s, ack ~10.7 ms, USB at ~3 of 11.5 KB/s: latency-bound, not bandwidth-bound. Fixed with a **window of lines in flight** (see below).

**Print engine (printer.cpp, rewritten):** FIFO of in-flight commands (`S_PRINT`, `S_POLL`, `S_FINISH`); Marlin acks in order so each `ok` pops the head. Window `g_window` (default 3, 1-6, `POST /printer/window?n=`), window 1 for the first 8 lines and after every resync, in-flight bytes capped at 200. On the first `Error:`/`Resend:` the engine stops sending, waits until the line is quiet for 250 ms, drops what was in flight and re-sends from the requested line from a 16-line history. `ok T:` is matched to the first M105 in flight: everything before it is treated as done (lost-ok safety net); an unanswered head triggers an M105 probe after 5 s of silence. `PrintRun` is reset in place (a stack temporary overflowed the engine task stack; task stack is now 14 KB). Simulator: `POST /printer/sim?speed=&resendEvery=&latency=`: 4000 lines took 50.5 s at window 1 vs 18.9 s at window 3 (latency 10 ms); recovery verified with injected checksum errors at windows 1/3/6; pause/resume/stop/finish verified. **Not yet validated on the real printer:** speed gain on fine detail, resend recovery with several lines in flight on real Marlin, pause/resume/stop, a full print with window 3. A cold rehearsal (`POST /printer/print?file=..&dry=N`, heaters skipped) runs at REAL speed (30k lines ~ 1 h): use short pieces; idea: `skip=N` to start inside the fine-detail region (lines > ~22,700).

**Logs:** continuous `/logs/log-NNNN.txt` on the SD card, 10 MB per file, newest 5 kept, never trimmed/copied (the old 1 MB trim could stall the SD), old files deleted only when no print runs. Endpoints: `GET /logs` (list), `GET /logs/read?name=&from=&max=` (tail, or follow by offset; headers X-Log-Size/From/Next), `GET /log` (RAM ring, streamed), `GET /log/sd` (alias: tail of the newest file). Print-line TX/ack pairs are logged for every non-move command (M*, G28, G92...) and the first 40 lines. `/status` now has `freeHeapKB`, `minHeapKB`, `totalHeapKB`; a `memory:` event is logged below 30 KB (min free heap once fell to 8 KB after heavy log reads).

**Phone app:** `EspCameraRelay` re-serves the board's MJPEG stream to any number of viewers (one upstream connection; the board's :81 server feeds one stream); `/camera/relay`, `/camera/relay/stats`, `/esp/status|log|log/sd|printer/status|logs|logs/read` proxies (dashboard works through the phone, also remotely). `AutoConnectThread` connects the board to the printer after the plug goes on (retries 2 min) and reconnects when the board rebooted behind the phone's back (only while the phone is idle). Dashboard: separate "Board status" block (Free RAM, Board delay, Resends, Printer reply, colour = health, tooltips only there), "Turn on camera" reflects the real camera state, no Screen/Printer toggles. The Log dropdown lists files from `/logs` and opens a viewer that follows the file being written by offset - see the UPDATE below.

**Other facts:** router 2.4 GHz channel fixed to 1 / 20 MHz (auto-channel makes the stream degrade again); camera stream via relay ~19 fps at 1024x768; chip temperature 63-72 C, ~72 plateau; a DHT11 room sensor is planned (GPIO 14, wiring instruction in the Notion project doc) - firmware/dashboard for it not written yet, waiting on the user to solder it and confirm the GPIO.
**Testing hazard:** the phone reconnects the USB link within seconds if the board reports NO_LINK/DISCONNECTED while the phone is idle: when testing the simulator, do `link=sim` then `connect` back to back.

---
# UPDATE 2026-09-22 (later) - DHT11 wired up on GPIO 21, three unrelated camera-stream stalls diagnosed

**DHT11 done, GPIO 14 -> 21.** Adafruit's DHT library (and the original hand-rolled attempt before it) both bit-bang the pin via `digitalRead()` in a busy-wait loop - on this board's Arduino-as-ESP-IDF-component build (`esp32s3_tuned`) that reliably lost the last 2-3 bits of every single 40-bit frame, 100% reproducible, confirmed with a standalone test sketch that passed 12/12 on plain `framework=arduino` and failed 11/11 with the exact same code under `framework=arduino,espidf` - traced to `CONFIG_ARDUINO_ISR_IRAM` defaulting off in the hybrid build (now set in `sdkconfig.defaults`), but even with that fixed the library still failed the same way. Replaced with a hardware RMT-based reader (`dht11.cpp`, RMT channel 4 - S3's channels 0-3 are TX-only) that samples the line independent of CPU scheduling; Adafruit's DHT library dependency and the local IRAM patch of it are gone. `dht11` task moved to core 0 (WiFi/BT + the normally-idle USB-host task live there; core 1 has `loop()`/HTTP/camera plus the printer engine task, the one that gets busy while printing). Verified live under real WiFi+camera+SD load and during a simulator print, `envOk` stayed true throughout. Dashboard shows Temp/Humidity under Board Status.

**Camera "says on, no picture" - three separate, unrelated causes found in one session, don't assume it's the same one next time:**
1. **A leftover direct (`?direct=1`) browser tab still open on the machine used for testing.** The board's :81 stream server only serves one viewer (see item 7 above); an abandoned tab silently holds that slot forever. Always close test tabs, not just stop actively using them.
2. **A stale relay connection on the phone after reinstalling the APK.** `adb install -r` + `am start` is not enough - the old process's socket to the board's :81 stream lingers and the board's `sendMs` climbs into the hundreds of ms trying to write into it (visible as repeated `stream: viewer ... connected` / `left (err 0xb006)` flapping in `/logs`). Fix: `adb shell am force-stop <pkg>` before relaunching, not just relaunch.
3. **A router channel change takes visible time to actually apply.** After switching the router from channel 1 to 11, the board kept associating on ch1 for several reboots before it actually moved - checking the router's admin page is not proof the client side sees it yet. `wifi: associated chN` in `/logs` is the ground truth. Added a **persisted Wi-Fi scan on every boot** (`wifi scan: 'ssid' chN ddBm` in `/logs`, was Serial-only before) specifically so a "did the channel actually change" or "is there a second AP with the same SSID on another channel" question can be answered after the fact without USB serial attached.

**Slip:** printed the OTA password in a shell command's argv (visible in this session's transcript) instead of using the `$(grep ... secrets.h)` form from section 8 below. Rotate `OTA_PASS` in `src/secrets.h` if that matters here.
**Testing hazard, second one:** camera direct-stream test tabs (`?direct=1`) count as viewers exactly like a real client - closing the Browser pane isn't automatic cleanup if multiple tabs were opened during a session; check `tabs_context` before concluding the board itself is broken.

---
# UPDATE 2026-09-22 (later the same day) - cleanup, log dashboard, a dry-run tracking bug (supersedes the old section 6 item 5, and the TODOs above)

**Cleanup done** (was section 6 item 5): `/printer/usbtune` was already gone. Removed `traceBudget` (`printer.cpp`'s Resend/manual-gcode debug tracer) - redundant now that every non-move command and the first 40 lines are logged permanently (`s.traced = no <= 40 || !isMoveCmd(text)`, added in the previous session). Deleted `CameraController.java` (the phone's own now-unused Camera2 code, from before the camera moved to the ESP) and everything that only existed to serve it: `PrinterConnection` is now an abstract class with no USB implementation left (`EspPrinterConnection`, talking HTTP to the board, is the only concrete class - `connect()` dropped its unused `UsbDevice` parameter); `PrinterService`'s USB-permission plumbing (`findCandidateDevice`, `requestUsbPermissionAndWait`, `UsbPermissionReceiver`, the `usbManager` field); `DashboardRouter`'s dead `/camera/start`, `/camera/stop`, `/camera/stream`, `/torch/on`, `/torch/off` routes (nothing in the dashboard called them - the camera toggle already reads the ESP's own `cameraOn` from `/esp/status`, an unrelated field with the same name); `PrinterState.cameraOn`/`torchOn`; the manifest's `usb.host` feature requirement, `USB_DEVICE_ATTACHED` intent-filter + `device_filter.xml`, and `CAMERA` permission/foreground-service-type. **Verified: firmware builds (`pio run -e esp32s3_tuned`) and the app builds (`./build.sh`) clean. Not flashed to the board or installed on the phone.**

**Dashboard log dropdown rewritten as two tabs** (closes the old TODO above; per the user, Events and a file manager are both wanted, not one or the other): "Log" opens on the **Events** tab - the RAM ring's `--- events` section from `/log`, newest first, polled every 10 s only while open (unchanged data source, just re-wired after the old single-pane version was briefly dropped and put back per the user's correction). The **Files** tab is the new part: fetches the file list from `/logs` once when opened - no polling - showing name/size/"writing" per file. Clicking a file opens a viewer reading its tail via `/logs/read` (offset + `X-Log-*` headers), which follows only the file currently being written (every 2.5 s, only while open) and has Earlier / Last print (searches backward through 64 KB chunks for `printer: print start`) / Copy / Download buttons - Download reads the whole file in 64 KB chunks client-side into a Blob, since the board caps one read at 64 KB (the user expects Download to be the easiest way to get a file off the board). `DashboardRouter` proxies `/esp/logs` -> `/logs` and `/esp/logs/read` -> `/logs/read`, forwarding `name`/`from`/`max` and the `X-Log-*` response headers. The Free RAM tile now reads `totalHeapKB` straight from `/status` instead of parsing a boot line out of the log text. Smoke-tested in a browser against mocked `/esp/log` + `/esp/logs` + `/esp/logs/read` (both tabs render, viewer loads/follows/jumps/returns correctly) - not yet exercised against the real board.

**Board status colours calibrated** (user request after the first print): chip temperature yellow >=80 C / red >=90 C (was 65/75 - too low for this board's normal 60-72 C working range, and the sensor itself is only accurate to ~80 C anyway); resends are now rate-based, per 10,000 lines sent (green <1, yellow 1-10, red >10) instead of an absolute count, so a handful of resends early in a long print no longer shows yellow. Free RAM and Board delay thresholds untouched - the user wants to revisit those once more prints' worth of data exists.

**Dry-run tracking bug fixed** ("stale 100% + Start after a rehearsal"): `/printer/status` now reports `"dry":true` while the active/last run was a rehearsal (`printer.cpp`'s new `gDry`, set from `dry > 0` in the `C_START` handler - a rehearsal drives `gState`/`gFinished` exactly like a real print, with no other way to tell them apart). `EspPrinterConnection.pollProgress()` reports "Not SD printing" whenever `dry` is true, so `resumeActiveSdPrintIfAny()` can no longer mistake a rehearsal (typically started by curl, for engine validation) for a real print worth tracking. Before this fix, `AutoConnectThread` reconnecting mid-rehearsal would pick it up, and the rehearsal's end then looked exactly like a real print finishing: 100% stuck next to a Start button on the dashboard, and **auto-shutoff wrongly armed** for a run that never actually heated anything. Not exercised against a real rehearsal this session - worth confirming next time one runs.

**Left for next time, in order (see the two UPDATEs below for what's now done):**
1. A full real print, start to finish, compared against the first one (96.4 min, 25% of the time starved) - everything short of this is now validated (see the second UPDATE below). Needs the user's explicit permission, per section 7.
2. DHT11 - waiting on the user to solder it (instructions already sent, in the Notion project doc) and report which GPIO the data wire landed on.
3. Remove the `badEvery=` resend-injection test parameter (`printer.cpp`: `Cmd.arg3`, `buildNumbered`'s/`sendLine`'s `corrupt` argument, the `main.cpp` query param) once nobody needs it again - it's real, working test scaffolding, not left in by accident, but it has no reason to exist in a shipped build.
4. Still open from the original section 6, untouched this session: a park move on pause / what `stop` does with the nozzle position; long filenames instead of the 8.3 scheme now that storage is on the ESP; router DHCP reservations for the ESP and phone.

---
# UPDATE 2026-09-22 (real-hardware session) - skip=N built and validated: the fine-detail stall is fixed

**This session's earlier firmware/app changes flashed and installed**, then validated live against the real printer (empty bed, no filament, heaters never enabled - user present and approved each connect/print step).

**`skip=N` implemented** (was just an idea before): `POST /printer/print?file=&dry=N&skip=M` jumps straight to file line M instead of running the whole file up to it in real time - a rehearsal otherwise runs at real Marlin ack speed, and reaching line 200000+ that way takes the better part of an hour. Everything before M is read from the SD card and discarded without sending, **except the first `G28` found, which is sent for real and waited on synchronously** so the machine has an actual homed reference position before the single big positioning move that follows (jumping into arbitrary coordinates from an unknown physical position would not be safe). **If no `G28` turns up before M, the engine refuses to run** rather than guess - confirmed live: `skip=5` on this print file (whose first lines are slicer metadata, no `G28` that early) correctly errored with "no G28 found before line 5 - refusing to jump unhomed" instead of doing anything.

**`dry` and `skip` share one coordinate system** (both are the file's own 1-based line numbers) - `skip=220000&dry=225000` means "send lines 220000-225000", not "225000 more lines after the skip". **Get this wrong once already**: the first live run used `skip=220000&dry=225000` intending to stop after ~5000 lines, but `dry` was being compared against the engine's own send counter (which restarts at 0 after a skip) instead of the file-line counter - so it kept going for 225000 *more* lines (would have taken ~57 min) until caught and stopped manually at line ~227024. Fixed by tracking one `fileLineNo` counter (incremented for every line read, in both the skip scan and the main send loop) and comparing `dry` against that everywhere; added a guard (`dry` must be greater than `skip`, else the engine refuses immediately - "nothing would be sent"). Confirmed fixed with two small live runs: `skip=30&dry=40` homed, jumped, sent exactly 10 lines, and stopped and reported `finished` right on cue.

**The actual validation result (why this all mattered):** with the bug still live but before it was caught, `skip=220000&dry=225000` ran real lines **220000 through 227024** - squarely inside and past the first print's documented stall region (line 221514, Z>=12.65mm, where every `G92 E0` used to get no reply for up to 4 s). Over that whole stretch: **0 resends, `gapMaxMs` never exceeded ~260 ms** (was multi-second stalls before), lines/s climbed from single digits during the post-homing ramp-up to a sustained 50-74/s. This is the real-hardware confirmation that the windowed engine (window 3) fixes the "printer prints faster than we feed it" problem the first print hit - the one piece of the pipelining task's validation plan that most needed real Marlin timing to prove.

**Still not validated on real hardware** (per item 1 above): pause/resume/stop with the new engine, resend recovery with several lines in flight, and a full end-to-end real print. The skip=N run above never triggered a resend, so the FIFO resync path itself remains proven only in the simulator, not on this printer.

---
# UPDATE 2026-09-22 (same real-hardware session, continued) - pause/resume/stop confirmed; a real resend-recovery bug found AND fixed at end-of-file

**pause/resume/stop confirmed live**, on the same skip=220000 dry rehearsal setup: `pause` mid-run stopped all further line sends within about a second (progress held flat, `state: PAUSED`, confirmed steady over 5+ s of polling); `resume` continued sending from exactly where it left off; `stop` ended a still-running rehearsal cleanly (`state: IDLE` within ~2 s, event log shows `printer: print stopped`, `0 resends`, hotend/bed never left room temperature). No errors, no reconnect needed.

**`badEvery=K` added** (rehearsal-only, needs `dry`): deliberately flips the checksum of every Kth sent line so real Marlin rejects it and asks for a resend - `buildNumbered()`/`sendLine()` gained a `corrupt` argument, threaded through the same `Cmd.arg3` path as `skip`. Purpose: the FIFO resync path had only ever run in the simulator; this exercises it against real Marlin. (Left in as working test scaffolding for next time - see item 3 in "left for next time" above for removing it eventually.)

**Found a real bug this way, at the worst possible spot - right at end-of-file:** with `badEvery=40` on a `skip=220000&dry=222000` run, a corrupted line landed on the very last content line before the M400 "end of file" marker. Real Marlin rejected it and asked for a resend (`Error:checksum mismatch, Last Line: 1999`) - normal so far - but the engine **hung forever**: `lines`/`bytesDone` froze, `lps` dropped to 0, no error, no progress, for 100+ s straight (confirmed via the event log: `stats:` lines kept reporting `0 l/s ... rs 50` unchanged for 20+ s with nothing after). Root cause: the M400 marker is sent unnumbered (`sendRaw`, not through the checksummed FIFO), tracked only by a `S_FINISH` slot and `r.finishSent`. A resync's `fifoClear()` wipes that slot along with everything else in flight, but nothing ever re-armed `finishSent`, so the `if (r.eof && !r.finishSent)` branch that would resend M400 and re-track completion never fired again - the print had actually finished on the wire, but the engine had no way left to notice. **`POST /printer/stop` was a reliable escape hatch even while hung** (recovered to `IDLE` immediately) - worth remembering if this or a similar hang ever recurs on a real print.

**Fixed**: the resync-completion code (`printer.cpp`, where `fifoClear(r)` runs) now resets `r.finishSent = false` first if it was set, so the main loop resends M400 and re-tracks it properly - sending M400 twice is harmless. **Reproduced the exact same corrupted-last-line scenario again after the fix** (`skip=220000&dry=222000&badEvery=40`, identical `Error:checksum mismatch, Last Line: 1999` at the same spot) and confirmed it now completes cleanly: a second `TX M400 (end of file)` appears in the log immediately after the resync, followed by `printer: print finished` - `finished:1`, `bytesDone == bytesTotal`, 42 resends, no hang.

**Net result: every piece of item 1's real-hardware validation plan is now done** except the one full real print end-to-end (still item 1 in "left for next time" above). Fine-detail throughput, pause/resume/stop, and resend recovery (including the one edge case that was actually broken) are all confirmed working against the real printer, cold/no-heat throughout this entire session.
