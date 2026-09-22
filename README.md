# PrintHost

A Wi-Fi print server for a USB-only 3D printer, no Raspberry Pi required. Built
for and tested against a **Creality Ender-3 V3 SE** (Marlin V1.0.6), but the
serial protocol layer is plain Marlin gcode and should work with most
Marlin-based printers with minor tweaks.

Two components, each in its own folder with its own README:

- **[esp32-firmware/](esp32-firmware)** - an ESP32-S3 board wired to the
  printer's USB port. Talks to the printer, runs the camera, and stores gcode
  on its own SD card.
- **[phone-app/](phone-app)** - an Android app that serves the Wi-Fi dashboard
  (upload/start/pause/stop prints, live camera, temperatures) and proxies the
  board's status/camera/logs. No app installs beyond this one, no cloud
  account, no port forwarding.

![PrintHost dashboard](phone-app/docs/dashboard.png)

Start with whichever half you're setting up: [esp32-firmware/README.md](esp32-firmware/README.md)
or [phone-app/README.md](phone-app/README.md). Project history, findings and
open questions: [esp32-firmware/docs/HANDOFF_ESP32_BRIDGE.md](esp32-firmware/docs/HANDOFF_ESP32_BRIDGE.md).

## License

MIT - see [LICENSE](LICENSE).
