# Move from ESP-IDF 4.4 to ESP-IDF 5.x (Arduino core 3.x): plan (2026-09-24)

Status: **deferred**. Decide after the RX watchdog has run for a few prints (see "When to do it").

## Where we are now
- The build is ESP-IDF **4.4.7** (`dependencies.lock`) with Arduino core 2.x. Env `esp32s3_tuned` builds Arduino as an ESP-IDF component (`framework = arduino, espidf`). `esp32s3_ota` extends it.
- Vendored components in `components/`: `esp32-camera` v2.0.4 and `usb_host_cdc_acm` v2.0.6 (**patched**, see below).
- `sdkconfig.defaults` uses IDF 5 option names (`CONFIG_ESP_WIFI_*`). IDF 4.4 expects `CONFIG_ESP32_WIFI_*`, so several Wi-Fi values **never applied**:
  - the RX BA window is 16, not 32;
  - TX buffers are static (PSRAM forces that) and 16 of them; `DYNAMIC_TX_BUFFER_NUM=32` is ignored;
  - the TX BA window is 6.

  The lwIP values did apply. Compare `sdkconfig.esp32s3_tuned` with `sdkconfig.defaults`.

## Why move
1. **Wi-Fi "deaf while associated" incidents on 2026-09-24.** Evidence is in `~/Downloads/printhost_log_2026-09-24_print2.txt`.
   - **Incident 1**, during a print. RX stopped at about 2830-2848 s of uptime: the phone's 30 s camera request never arrived and the stream send took 1.3 s per frame. The board stayed associated, so ARP to it failed everywhere and the router showed it as connected with 0 download. About 3567 s later (the router's hourly group-key rotation) it got `wifi: DISCONNECTED reason 16` (GROUP_KEY_UPDATE_TIMEOUT), rejoined within 1 s and worked again. The print was unaffected (USB link, 0 resends).
   - **Incident 2**, idle after the print. The stream dropped (`0xb006`) at 12702 s, the last inbound request was at 12715 s, then the board stayed deaf until a manual power cycle.
   - **Not heap exhaustion.** Free internal heap was about 30 KB during the stream and the minimum did not move.
   - **Common pattern.** A phone-relay MJPEG stream on a congested 2.4 GHz channel (ch1, the ISP gateway CGA2121 on the same channel), then the RX path dies while association stays up. `reason 16` is a symptom: the group-key EAPOL frames are not received either.
   - **Matches a known report:** "[ESP32S3] v4.4 WIFI losing connectivity temporarily or permanently" (https://github.com/espressif/esp-idf/issues/13212, closed as done internally, IDFGH-12977). A possible related mechanism is an AMPDU RX / block-ack stall (https://esp32.com/viewtopic.php?t=998).
2. Two-plus years of Wi-Fi driver fixes for the S3.
3. The existing `sdkconfig.defaults` names start working as written, which fixes the "tuning silently ignored" problem above.
4. Better Wi-Fi statistics and diagnostics APIs, useful for the fps work.

No guarantee that it fixes the deaf-RX bug; it raises the odds.

## When to do it
The **RX watchdog comes first** (stage 2, done on IDF 4.4): if no inbound frames arrive for 30-60 s, it calls `esp_wifi_disconnect()` + `esp_wifi_connect()` and never reboots. It recovers from the bug whatever the cause. Then read the logs:
- **The watchdog fires rarely** (a day or more between events): the migration can wait.
- **It fires often** (several times per print): the migration becomes the main fix. Schedule it as its own task.

## Work items
1. **Build platform.** The official PlatformIO `espressif32` platform stops at Arduino core 2.x. Arduino core 3.x (IDF 5.x) needs the community **pioarduino** platform (`platform = https://github.com/pioarduino/platform-espressif32/...`). Rework the `esp32s3` / `esp32s3_tuned` / `esp32s3_ota` envs. Check whether Arduino-as-component is still needed or whether pioarduino's own sdkconfig handling is enough.
2. **esp32-camera.** Move from vendored v2.0.4 to a release that supports IDF 5. Re-apply our changes (`CONFIG_CAMERA_TASK_STACK_SIZE=4096`, which fixed the FB-OVF crashes, plus any source edits). Re-check the OV3660 settings (the `set_aec2` fps issue).
3. **usb_host_cdc_acm (highest risk: this is the printer link).** Port our patches to the IDF 5 USB host / CDC-ACM component:
   - `in_buffer_size = 0` (IN transfers = 1 packet): the fix for the RX freeze on a printer reply of exactly 64 bytes;
   - ZERO_PACK on OUT;
   - the CH340 setup via vendor requests (0x9A, 115200 8N1);
   - DTR/RTS must never be touched: it would reset the printer;
   - open with no gcode sent;
   - the close/disconnect-callback race fix (commit 782044a).
4. **Arduino 3.x API changes.** mDNS is a separate component, a few Wi-Fi event and ledc names changed, `WiFi.setTxPower` / `setSleep` should still work. Mechanical work: follow the compiler errors.
5. **sdkconfig.defaults.** Re-validate every line under IDF 5 names and grep the generated sdkconfig to confirm each value applied. Include the Wi-Fi buffers and windows, lwIP TCP windows, RTO, IRAM options and the PSRAM Wi-Fi/lwIP allocation.
6. **Size.** IDF 5 binaries are bigger. Confirm the app still fits the OTA slots in `partitions.csv`.

## Risks and safety
- **No brick risk.** The ESP32-S3 ROM download mode always allows reflashing over the UART USB-C port. Never touch eFuses or the bootloader by other means.
- **The first IDF 5 flash should be over USB serial, not OTA.** OTA does not update the bootloader. After that, check that OTA works from IDF 5 to IDF 5.
- The user must be present for anything touching the real printer. Ask before every connect / plug / gcode action.

## Test plan after the move
1. Boot, `BOOT:` line in the log, heap/PSRAM numbers, SD mount, log rotation, `/logs` and `/logs/read` (small chunks).
2. Camera: all profiles, fps, stream through the phone relay, camera on/off.
3. OTA: IDF 5 to IDF 5.
4. DHT11 readings.
5. Printer via `sim` link: upload, print, pause/resume/stop, injected checksum errors, window 1/3/6.
6. Real printer (user present):
   - connect;
   - M115 / M105;
   - the 64-byte reply sweep (G92 E-value / M114 lengths);
   - a cold dry run `POST /printer/print?file=..&dry=N`;
   - then a short real print.
7. Soak: a long stream plus a print, then check the log for Wi-Fi drops, watchdog events, heap minimum and chip temperature.
