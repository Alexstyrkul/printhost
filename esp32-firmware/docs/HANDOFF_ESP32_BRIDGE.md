# PrintHost: handoff (current state, 2026-09-29)

Read this first. It describes what exists and runs today, what must not be changed or retried, and where open work
lives. The history of how it got here (2026-09-21...29, day by day) is in git: `git log -p -- esp32-firmware/docs/HANDOFF_ESP32_BRIDGE.md`.

- **Main branch: `master`.** The phone and the board run it. New work goes on `master` or a branch off it.
- **Open work: [`/TODO.md`](../../TODO.md).** The deferred ESP-IDF 5 upgrade: [`IDF5_MIGRATION.md`](IDF5_MIGRATION.md).

## 1. Devices and roles (the user's decision, do not change without asking)

| Device | Role |
|---|---|
| ESP32-S3 board (N16R8, OV3660 camera, SD card) | Printer control board: drives the Ender-3 V3 SE over USB host (CH340 `1a86:7523`, 115200, Marlin 1.0.6), feeds gcode from its own SD card, streams the camera, keeps the logs. Powered from the same Tapo smart plug as the printer. |
| Android phone (OnePlus 6T, Android 11) | The server: PrintHost app, dashboard on `:8899`, Tapo plug, scheduled prints, 3D-preview copy of every uploaded file, camera relay, alerts. Headless: never drive its screen. |
| Mac / iPhone / anything | Clients only: a browser. |

Addresses (DHCP, not reserved): board `192.168.50.190`, phone `192.168.50.37` (5 GHz `Vasiliy_Pro_5G`).
Open the dashboard as `http://OnePlus-6T:8899/`, from outside home via Tailscale `http://100.101.233.103:8899/`
(Tailscale runs on the phone; "Always-on VPN" should be on). `http://printhost-cam.local/app` redirects to the phone.
Router: ASUS TUF-AX3000 V2, 2.4 GHz fixed on **channel 1 / 20 MHz**. The ISP modem (CGA2121, no access) has its own
Wi-Fi that hops channels by itself. **Never propose channel changes** (ch11 was tried and was worse).

## 2. Where things are

- `esp32-firmware/` (PlatformIO, Arduino as an ESP-IDF 4.4 component; envs `esp32s3_tuned` = USB, `esp32s3_ota` = Wi-Fi):
  - `src/main.cpp` - Wi-Fi (reconnect, RX watchdog), HTTP API, camera and its own MJPEG server on `:81`, file store,
    logs, OTA, `/status`.
  - `src/printer.cpp` - print engine (task on core 1), print journal, crash recovery. `src/link_usb.cpp` CH340 link,
    `src/link_sim.cpp` simulated Marlin. `src/crash.cpp` crash capture. `src/dht11.cpp` room sensor (RMT, GPIO 21).
  - `components/`: vendored `esp32-camera` v2.0.4 and `usb_host_cdc_acm` v2.0.6 (patched).
  - `CMakeLists.txt` puts lwIP buffers in PSRAM (see 5.1). `sdkconfig.defaults` holds every tuned option.
- `phone-app/` (plain `build.sh`, no Gradle): `src/dev/oleksandr/printhost/` and `assets/dashboard.html` (the whole UI).
  - `PrinterService` - state, polling, auto-connect, uploads, schedule, alerts. `EspPrinterConnection` /
    `EspStoreConnection` - HTTP to the board. `DashboardRouter` - the phone's HTTP API and proxies. `EspCameraRelay`.

## 3. How it works

**Printing.** The phone uploads the file to the board's SD card (`/gcode`), then tells the board to print it. The board
sends numbered lines with checksums, a window of 3 lines in flight, resend recovery, M105 temperature polls. The print
never depends on Wi-Fi: the phone and dashboard only watch. Verified on real prints up to 13.3 h (0 resends).

**Crash recovery (board).** A panic/abort leaves reason + backtrace in RTC memory -> SD log and `/status` `crash`.
`/print.job` on the SD card is rewritten every second (file, offset of the last acknowledged line, XYZ/E/feed/temps/
fan/modes). After a restart mid-print the board lifts the nozzle 5 mm and resumes by itself from the journal, at most
twice per print; if it restarts again within 10 min (crash loop) it switches all heaters off and waits. Manual:
phone `POST /print/recover?mode=auto|kept|restarted` (board `/printer/recover`) and `/print/recover/discard`.

**Alerts (phone).** Interrupted print, board crash, heaters on 15 min without a print, board unreachable 2 min:
phone notification, Mac listener, optional ntfy topic; shown in the dashboard alert box (Resume/Discard for an
interrupted print, Dismiss otherwise). `/status` fields: `alert`, `alertAtMillis`, `interrupted{...}`, `boardCrash`.

**Uploads.** Browser -> phone (kept as the preview copy) -> board. Phone -> board goes over a plain socket with a
64 KB send buffer (the progress bar follows what the board took), a 90 s stall watchdog and a Cancel that closes the
connection. If the board's `/status` says `"uploadZ":1` the file is sent zlib-packed (`/files?name=&z=1&size=&crc=`);
the board unpacks on the fly and keeps the file only if adler32 holds and size + CRC32 equal the original's, and the
phone checks the board's CRC32 again. 24.7 MB in ~35 s packed (SD-card bound, ~800 KB/s), ~50 s raw.
Each upload logs `files: longest wait: network N ms, card N ms`.

**Scheduled print.** A file chosen while scheduling (or with the plug off) stays on the phone; before the start time
the phone powers the plug, sends the file to the board and starts on time. "Change time" moves a job. A print that is
still running at the start time makes the job wait / fail, it never interrupts it.

**Camera.** The board's `:81` server feeds one viewer; the phone's `EspCameraRelay` holds that one connection and
re-serves it to any number of browsers (`/camera/relay`, `?raw=1` = octet-stream for iPhone/WebKit). The dashboard reads
the stream with fetch and restarts it after 6 s without a frame. Default VGA "fast" (~20-25 fps), XGA "sharp" in
the sidebar. XCLK 20 MHz (24 MHz corrupts frames).

**Auto-connect (phone).** Attaches to the board when the board reports the printer; after the plug goes on it retries
for 2 min; gives up after 3 failed tries in a row ("press Connect"). No limit while the board reports a running print.

**Logs (board).** `/logs/log-NNNN.txt` on SD, 10 MB each, newest 5 kept, sample line every 10 s printing / 60 s idle
(fps, rssi, heap, `gw=` ping, printer stats). `/logs`, `/logs/read?name=&from=&max=` (max 32 KB per request, slow:
~2-5 KB/s), `/log` (RAM ring + events). Dashboard: the Log window (Events / Files, Copy, Download).

**Dashboard (layout A).** Camera + print progress left; temperatures 2x2 (nozzle, bed, fan from the gcode's M106,
room), 3D preview (desktop only: it runs out of memory on phones), files, schedule card right; board vitals in one
bottom line; Plug / Printer / Camera switches in the header. One screen from 520 px window height; stacks on phones.

## 4. Findings you must know (each cost hours)

1. **lwIP ignored its PSRAM option on the S3** (IDF 4.4 checks the old name `CONFIG_WIFI_LWIP_ALLOCATION_FROM_SPIRAM_FIRST`).
   `CMakeLists.txt` defines it; without it free internal RAM fell to 9 KB while streaming.
2. **sdkconfig:** IDF 4.4 uses `CONFIG_ESP32_WIFI_*` names (IDF 5 names are silently ignored). After editing
   `sdkconfig.defaults` delete `sdkconfig.esp32s3_*` or the change is ignored.
3. **The SD driver cannot DMA from PSRAM:** buffers written to the card must be internal DMA-capable RAM, else it
   bounces through a small buffer and is 2-3x slower.
4. **"Deaf while associated" Wi-Fi** (S3 on IDF 4.4, esp-idf #13212): RX dies, ARP fails, the router still lists the
   board. The RX watchdog rejoins after 45 s. The board never reboots because of the network.
5. The USB 64-byte-reply freeze (`in_buffer_size = 0`, ZERO_PACK on OUT) and the close/disconnect race in
   `link_usb.cpp` (atomic `claimDevForClose`) are fixed; if "cannot connect" symptoms do not match reality, get live
   UART serial: panics inside vendored components never reach the SD log.
6. OV3660: no `set_aec2(1)` (fps drop), no `set_denoise` (crash). Camera task stack must stay 4096.
7. `pio ... -t upload` for the OTA env often prints FAILED after the update actually succeeded: check the `BOOT` line's
   build time in the log.
8. Reinstalling the APK: `am force-stop` before `am start`, or the old relay socket to `:81` lingers. Every reinstall
   loses the phone's selected file (select it again) and kills a running browser upload.
9. Pages are served over plain http: no `navigator.clipboard`, no service workers; copy uses a textarea fallback.

## 5. Measured and rejected (do not retry)

- TCP window 64 KB + DYNAMIC_RX 32 + RX_BA_WIN 32: uploads fell to 27-60 KB/s with 15 s stalls.
- Pre-allocating the upload file (seek to the end) and 64 KB card writes: both slower.
- Router channel 11; video priority (IP_TOS); adaptive XGA/VGA switching (removed at the user's request).
- Compressing uploads is kept ONLY with the end-to-end checks above; the user will not accept it without them.

## 6. Working agreements with the user

- **Ask before every connect / plug / gcode action on the real printer.** No standing consent.
- Confirm before commit and push; no `Co-Authored-By` trailer.
- Never author gcode for tests; use real slicer files from `~/Downloads`.
- The phone is a headless server: adb only to install and read logs, never its screen.
- The user tests the live system while you work: say before reinstalling the app or flashing the board.
- Flash the board only when no print is running (OTA is fine then). Never risk bricking it (no eFuse/bootloader).
- Secrets (Wi-Fi, OTA password) live only in `esp32-firmware/src/secrets.h` (git-ignored); never print them.

## 7. Handy commands

```bash
# firmware over Wi-Fi (check that nothing prints first)
cd esp32-firmware
export PRINTHOST_OTA_PASS=$(grep -o 'OTA_PASS[^"]*"[^"]*"' src/secrets.h | sed 's/.*"\(.*\)"/\1/')
uvx --from platformio pio run -e esp32s3_ota -t upload
# firmware over USB (UART port)
uvx --from platformio pio run -e esp32s3_tuned -t upload --upload-port /dev/cu.usbmodem*
# phone app
cd phone-app && ./build.sh && A=~/Library/Android/sdk/platform-tools/adb && $A connect 192.168.50.37:5555 \
  && $A install -r out/signed.apk && $A shell am force-stop dev.oleksandr.printhost \
  && $A shell am start -n dev.oleksandr.printhost/.MainActivity
# health
curl http://192.168.50.190/status; curl http://192.168.50.190/printer/status; curl http://192.168.50.37:8899/status
curl "http://192.168.50.190/logs/read?name=log-0002.txt&max=8000"
```
