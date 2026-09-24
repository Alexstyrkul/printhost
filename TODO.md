# PrintHost TODO (from 2026-09-24)

Open work for the ESP32 bridge (`esp32-firmware/`) and the phone app (`phone-app/`).
Background and evidence:
- `esp32-firmware/docs/HANDOFF_ESP32_BRIDGE.md`: history, findings, working agreements;
- `esp32-firmware/docs/IDF5_MIGRATION.md`: the deferred framework upgrade;
- `~/Downloads/printhost_log_2026-09-24_print2.txt`: the SD log of the 2026-09-24 print and both Wi-Fi incidents.

Working agreements (short):
- Ask before any connect / plug / gcode action on the real printer.
- Confirm before commit or push; no Co-Authored-By trailer.
- The phone is a headless server: adb only for install and read-only checks.
- Read board logs in chunks of 32 KB or less.

## Already done on 2026-09-24
- [x] The phone moved to 5 GHz (`Vasiliy_Pro_5G`). **Its IP is now 192.168.50.37** (was .85). adb: `192.168.50.37:5555`.
- [x] Router 2.4 GHz auto-channel is off, fixed on ch1 / 20 MHz.
- [x] Both Wi-Fi incidents analysed from the SD log (see "Why" in stage 2).

## Stage 2: resilience - DONE 2026-09-24, flashed (build 14:52) and tested: watchdog fired after 45 s of a black-holed gateway ping and rejoined in 6 s (`POST /debug/wifi?test=deaf`); setup-network fallback came back to the router after 30 s (`?test=setup`); a new stream viewer replaces the old one; the phone re-attached to a simulator print by itself and kept PRINTING through a 30 s board outage (the print must never depend on Wi-Fi)
Why. Twice on 2026-09-24 the board went "deaf while associated": RX died, ARP to it failed, the router showed it as connected with 0 download.
- Once it recovered only an hour later, via `wifi: DISCONNECTED reason 16`.
- Once it needed a power cycle.
- Heap was not the cause.
- Both happened during a phone-relay stream on the congested ch1.
- It looks like the known "S3 on IDF 4.4 loses connectivity while associated" problem.

Firmware:
- [x] Remove the "Wi-Fi down >120 s -> ESP.restart()" rule completely (user decision: never reboot because of the network).
- [x] RX watchdog: ping the gateway every few seconds. If no reply for 30-60 s: `esp_wifi_disconnect()` and reconnect, never reboot. Log each event with heap and RSSI. Back off between attempts.
- [x] Boot fallback: if the router is unreachable at boot, keep retrying STA in the background (AP+STA) instead of staying in setup-AP mode forever.
- [x] Own MJPEG stream server on :81 (a small task instead of esp_http_server):
  - a new viewer replaces the old one, so a stale tab or dead relay can no longer block the slot;
  - TCP keepalive;
  - a short send timeout.
- [x] Heap guard (stream part: sending pauses below 20 KB free internal RAM, resumes above 32 KB; log/read buffers moved to PSRAM; not seen triggering yet): the stream costs ~35 KB of internal heap and log reads dropped the minimum to 4 KB. Pause frame sending when free internal heap is low; make `/logs/read` use a small fixed buffer; answer 503 instead of starving Wi-Fi.
- [ ] (Experiment, later) AMPDU RX off or a different BA window, if deafness keeps happening. Decide on the IDF 5 migration from how often the watchdog fires.

Phone app:
- [x] Never drop a running print after a network gap. PollerThread must not end in ERROR while the board reports PRINTING/PAUSED; keep polling and show "board unreachable". Re-attach automatically when the board answers again (today this needed a manual `POST /connect`).
- [x] Never show `Start` + a stale percentage while the board is printing.

## Stage 3: dashboard / app requests from the user - DONE 2026-09-24 (app installed on the phone): first auto-connect 20 s after the plug goes on, quiet failures for 2 min; camera switch via `POST /camera/set`; "Printer" connect switch restored next to "Plug"
- [x] After the plug turns on, wait for the board and the printer to boot before connecting. No error/fallback messages during that window.
- [x] Camera ON by default when the board powers up. The user can switch it off and on at any time, including during a print (today the toggle is disabled mid-print). The camera no longer follows the plug.
- [x] Bring back a separate "Connect to printer" button (for debugging: printer switched off and on by hand).

## Stage 4: camera fps
Findings:
- The board-to-router leg on 2.4 GHz ch1 is the bottleneck. The ISP gateway CGA2121 sits on the same channel at -54 dBm; the user has no access to it and will not contact the ISP.
- Signal is fine (-51..-56).
- fps avg ~9-10 with deep dips; ping to the board shows 12-19% loss.
- The phone moving to 5 GHz did not change fps much.

Tasks:
- [x] Diagnostics: gateway ping replies/lost and RTT avg/max on every SD log line (`gw=ok/lost rtt=avg/max`); `/status` has `channel`; `GET /debug/tx?sec=N` raw upload test (no camera); `GET /debug/wifi` counters.
- [x] Channel survey: `GET /debug/survey?ms=1000` runs in the background (the board drops the router for ~13 x ms, refused while printing) and `?result=1` returns per-channel air time and top talkers. RESULT 2026-09-24: from the board ch11 looked empty, but on ch11 the stream collapsed (0.1-3 fps, 32-630 KB/s swinging). The router's receive side is what matters: a Mac scan next to the router shows a 40 MHz open network (ch6, -58) and a ch8 network (-59) overlapping ch6 and ch11. **Decision: stay on ch1** (co-channel neighbours such as the ISP modem only defer, while partial overlap corrupts frames). Lesson: survey BOTH ends; confirm with real throughput.
- [x] Self-interference / XCLK: stays at **20 MHz**. 24 MHz (harmonics outside ch1) looked faster in a test but produced CORRUPTED frames (colour bands) on the OV3660, so the test numbers were invalid; 10 MHz halves the fps. Camera off vs on showed no clear self-interference on ch11.
- [x] `sdkconfig`: Wi-Fi options now use the IDF 4.4 `CONFIG_ESP32_WIFI_*` names (the IDF 5 names were silently ignored). A/B-tested (interleaved, 2 rounds): TX_BA_WIN 6->16 gave raw upload 658/648 vs 490/436 KB/s; DYNAMIC_RX 32->20 caps internal RAM held by received frames. The stream at VGA was the same (~20-22 fps, sensor-bound on a good channel). A clean build from `sdkconfig.defaults` was verified to pick them up.
- [x] Heap guard for log reads: `/logs/read` capped at 32 KB, 503 + Retry-After when internal RAM < 36 KB, drain waits between pieces; the dashboard retries on 503 and uses 32 KB pieces.
- [x] Stream: IP_TOS video priority is available (`/control?var=tos&val=1`) but 5 interleaved A/B rounds showed no consistent gain, so it stays OFF. Frame cap `/control?var=fpscap&val=N` exists, default off.
- [x] Resolution: VGA measured ~1.5x the fps of XGA on the same link (15.5 vs 10.5, 12.9 vs 8.4). An adaptive XGA/VGA switch was built and tested, then REMOVED at the user's request. **Default is now VGA ("fast")**; the choice is saved in NVS (`cam`/`profile`) and switched on the fly from the dashboard sidebar ("Camera quality": only VGA "smooth" and XGA "sharp", per the user; via the phone`s `POST /camera/quality?profile=fast|xga`).
- [ ] Remaining stream experiments: 802.11b off, lower TCP RTO.
- [ ] If dips remain: UDP from the board to the phone (frame id + chunks, skip incomplete frames, optional FEC); the phone re-serves MJPEG.
- Deferred: direct board-phone link (Android 11 has no STA+STA; Wi-Fi Direct is possible without root but shares one radio); external antenna; a 5 GHz board (ESP32-P4 + C5).

## Finding the phone without a fixed IP
- [x] `http://OnePlus-6T:8899/` (the ASUS resolves the phone's DHCP name) and `http://printhost-cam.local/app` (the board redirects to the phone's last-seen IP, learned from its Dalvik requests). Both verified from the Mac.

## Older open items (from the handoff)
- [-] SKIPPED (user, 2026-09-24): Pause/stop: park the nozzle away from the part (today stop turns the heaters and fan off; the nozzle stays over the print).
- [-] SKIPPED (user, 2026-09-24): Long file names instead of the 8.3 scheme (`SdFilenameMap`), now that storage is on the board.
- [-] SKIPPED for now (user: skip everything router-side): Router DHCP reservations for the board (.190) and the phone (.37): the user does this in the ASUS UI.
- [ ] Remove the `badEvery=` resend-injection test parameter when it is no longer needed.
- [x] Update `HANDOFF_ESP32_BRIDGE.md` (done 2026-09-24): the phone IP, the signal at the board, today's findings, a link to this file and to `IDF5_MIGRATION.md`.
