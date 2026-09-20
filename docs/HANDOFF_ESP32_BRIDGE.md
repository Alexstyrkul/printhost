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
