// Things shared between main.cpp (HTTP server, camera, logging) and the printer bridge (printer.cpp).
#pragma once
#include <Arduino.h>

// Timestamped event line: printed to serial, kept in the RAM ring, appended to the SD log. (main.cpp)
void logEvent(const char *fmt, ...);

// One user of the SD card at a time. Hold it only for short operations (a chunk read/write), never for
// a whole upload, or the print feeder would starve.
extern SemaphoreHandle_t sdMutex;

// Folder on the SD card holding the gcode files the printer can print.
extern const char *GCODE_DIR;

// True when no usable SD card was found at boot.
bool sdMissing();

// Validates a file name coming from the network (no path tricks, sane length).
bool fileNameOk(const String &n);
