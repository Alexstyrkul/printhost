# PrintHost ESP32-S3 firmware

Firmware for the ESP32-S3-WROOM-1 N16R8 + OV3660 board that drives the printer, streams the camera and stores gcode.
Project state, findings and next steps: [`docs/HANDOFF_ESP32_BRIDGE.md`](docs/HANDOFF_ESP32_BRIDGE.md).

## Build

PlatformIO, Arduino framework built as an ESP-IDF component (needed to tune lwIP/Wi-Fi buffers via `sdkconfig.defaults`).

```bash
cp src/secrets.example.h src/secrets.h     # then fill in WIFI_SSID / WIFI_PASS / OTA_PASS (git-ignored)
uvx --from platformio pio run -e esp32s3_tuned                       # build
uvx --from platformio pio run -e esp32s3_tuned -t upload --upload-port /dev/cu.usbmodemXXXX   # over USB (UART port)
export PRINTHOST_OTA_PASS=<OTA_PASS>; uvx --from platformio pio run -e esp32s3_ota -t upload  # over Wi-Fi
```

- Environments: `esp32s3_tuned` (USB), `esp32s3_ota` (same code, espota upload), `esp32s3` (stock Arduino, old fallback without the tuning).
- Delete `sdkconfig.esp32s3_*` after editing `sdkconfig.defaults` (otherwise the change is ignored).
- Flash layout: `partitions.csv` (2 x 3 MB OTA slots). Changing it requires one USB flash.
- The board's two USB-C ports: **UART** (CH343, power + serial log + flashing) and **OTG** (native USB; used as USB *host* to the printer). Power the board from the UART port with a plain 5 V charger.

## Source map

| File | What |
|---|---|
| `src/main.cpp` | HTTP servers (:80 API, :81 stream), camera + profiles + adaptive quality, Wi-Fi/AP/OTA, log ring + SD log, file store, endpoint glue |
| `src/printer.cpp/.h` | Print engine task, command mailbox, status JSON |
| `src/link_sim.cpp` | Fake Marlin (line numbers, checksums, resend, temperatures) |
| `src/link_usb.cpp` | CH340 over the IDF USB host, RX polling policy |
| `src/dht11.cpp/.h` | Room temperature/humidity, read via RMT (not bit-banged - see the HANDOFF doc) |
| `src/common.h` | Shared declarations |
| `src/test_ui.h` | Debug page served at `/` |
| `components/esp32-camera` | v2.0.4 (vendored) |
| `components/usb_host_cdc_acm` | v2.0.6 (vendored, adds `cdc_acm_host_rx_pause/resume`) |

## HTTP API (port 80 unless noted)

- `GET /status` `GET /log` (RAM ring, streamed) `GET /log/sd` (alias: tail of the newest log file) `GET /capture` `GET :81/stream` (one viewer at a time)
- `GET /logs` (list of `/logs/log-NNNN.txt` files: name, size, current) `GET /logs/read?name=&from=&max=` (a piece of one file; without `from` it is the tail, headers `X-Log-Size`/`X-Log-From`/`X-Log-Next`)
- `POST /camera?on=1|0` power the camera; `GET /control?var=profile&val=fast|balanced|sharp|xga|sxga`
- `POST /files?name=<n>` (raw body, streamed, returns bytes + crc32), `GET /files`, `POST /files/delete?name=<n>`
- `GET /printer/status` (includes `dry`: true while the active/last run was a rehearsal); `POST /printer/{link?kind=usb|sim|none, connect, disconnect, print?file=&dry=N&skip=M&badEvery=K, pause, resume, stop, gcode?cmd=&timeout=, sim?speed=&resendEvery=&latency=, window?n=}`
  - `skip=M` (rehearsal only, needs `dry`): jump straight to file line M instead of reading up to it in real time. Only the first `G28` before M is sent for real (for a homed reference) - refuses to run if none is found. `dry` and `skip` are the SAME coordinate (the file's own line numbers) - `skip=220000&dry=225000` sends lines 220000-225000, not "225000 more lines after the skip"; `dry` must be greater than `skip`.
  - `badEvery=K` (rehearsal only, needs `dry`): deliberately corrupts every Kth sent line's checksum so real Marlin rejects it and asks for a resend - for validating the FIFO resync path against real firmware.
- `POST /wifi` (form ssid/pass) - used by the setup AP page. OTA: ArduinoOTA, host `printhost-cam`.

Safety: nothing is sent to the printer until a client selects the `usb` link and connects; connect only performs USB control transfers, then the app reads `M115`/`M851`/`M105`. DTR/RTS are never touched.
