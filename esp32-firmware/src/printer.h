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
  // Cheap check whether the printer's USB device is plugged in and powered (no traffic to it). The simulator is
  // always "present".
  virtual bool devicePresent() { return true; }
};

void printerBegin();                                   // starts the engine task; touches no hardware
String printerStatusJson();
bool printerSelectLink(const String &kind, String &err);  // "sim" | "usb" | "none"
bool printerConnect(String &err);
bool printerDisconnect(String &err);
// dryLines > 0: rehearsal - heater commands are skipped and the run stops once file line dryLines
// has been read (no heating needed). skipLines > 0: jump straight to that file line instead of
// running the whole file up to it - only the first G28 before the target is sent for real (for a
// homed reference); see runPrint()'s comment. Both are the SAME coordinate (the original file's
// own line numbers, 1-based) - e.g. skip=220000&dry=225000 sends only lines 220000-225000, not
// "225000 lines after the skip". dryLines must be greater than skipLines or nothing gets sent.
// badEvery > 0: resend-recovery test - deliberately corrupt every Nth sent line's checksum so real
// Marlin asks for a resend, to validate the FIFO resync path against real firmware (dry runs only).
bool printerStartPrint(const String &file, String &err, uint32_t dryLines = 0, uint32_t skipLines = 0, uint32_t badEvery = 0);
bool printerPause(String &err);
bool printerResume(String &err);
bool printerStop(String &err);
// One-off command (not while printing). Returns the printer's answer text (ends with "ok").
bool printerGcode(const String &cmd, uint32_t timeoutMs, String &reply, String &err);
// Simulator tuning: motion lines per second, and inject a checksum error every N lines (0 = never).
bool printerSimTune(int linesPerSec, int resendEvery, int latencyMs, String &err);
// Lines the engine keeps in flight (1 = stop-and-wait, default 3, max 6).
void printerSetWindow(int n);
int printerGetWindow();
// True if the engine is using this file (the file store refuses to delete/overwrite it).
bool printerFileInUse(const String &name);

PrinterLink *makeSimLink();
PrinterLink *makeUsbLink();

// Read-only snapshot for the runtime log (once-a-second samples and the SD log).
struct PrinterSnap {
  uint8_t state;  // PrinterState
  uint32_t line, bytesDone, bytesTotal;
  float lps;  // lines per second over the last ~10 s window
  uint32_t ackAvgUs, ackMaxUs, gapAvgUs, gapMaxUs, txBps, rxBps, resends;
  float hot, hotT, bed, bedT;
};
void printerSnapshot(PrinterSnap &s);
