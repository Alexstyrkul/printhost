// Printer bridge: streams a gcode file from the SD card to a Marlin printer line by line
// (numbered + checksummed, one line in flight, waits for "ok", resends on request) while polling
// temperatures. The physical link is pluggable: a simulated Marlin (for testing without a printer)
// or the CH340 USB-serial chip found on the printer's mainboard (USB host on the OTG port).
//
// Nothing is ever sent to the printer until a client explicitly connects, and no gcode is sent on
// connect: only USB control transfers that set the serial speed.
#pragma once
#include <Arduino.h>

enum PrinterState { PS_NO_LINK, PS_DISCONNECTED, PS_IDLE, PS_PRINTING, PS_PAUSED, PS_ERROR };

// The wire to the printer.
struct PrinterLink {
  virtual ~PrinterLink() {}
  virtual const char *name() = 0;
  virtual bool open(String &err) = 0;  // blocks up to a few seconds
  virtual void close() = 0;
  virtual bool isOpen() = 0;
  virtual bool writeBytes(const uint8_t *data, size_t n) = 0;
  // Returns the line length (without CR/LF), or -1 on timeout.
  virtual int readLine(char *buf, size_t cap, uint32_t timeoutMs) = 0;
  virtual void flushInput() = 0;
  virtual void tick() {}  // called regularly by the engine; lets the link manage its own polling
};

void printerBegin();                                   // starts the engine task; touches no hardware
String printerStatusJson();
bool printerSelectLink(const String &kind, String &err);  // "sim" | "usb" | "none"
bool printerConnect(String &err);
bool printerDisconnect(String &err);
bool printerStartPrint(const String &file, String &err);
bool printerPause(String &err);
bool printerResume(String &err);
bool printerStop(String &err);
// One-off command (not while printing). Returns the printer's answer text (ends with "ok").
bool printerGcode(const String &cmd, uint32_t timeoutMs, String &reply, String &err);
// Simulator tuning: motion lines per second, and inject a checksum error every N lines (0 = never).
bool printerSimTune(int linesPerSec, int resendEvery, String &err);
// Diagnostics for the USB link: idle temperature polling, brief look-ins, and gated IN polling (all default on).
void printerUsbTune(int idlePoll, int lookIn, int gate);
// True if the engine is using this file (the file store refuses to delete/overwrite it).
bool printerFileInUse(const String &name);

PrinterLink *makeSimLink();
PrinterLink *makeUsbLink();
