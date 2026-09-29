// Crash capture: the panic handler is wrapped (see src/CMakeLists.txt) so that the reason, the crashing task and a
// backtrace survive the reboot in RTC memory. Without it a PANIC left nothing behind but the reset reason (the
// backtrace only ever went to the UART, and nothing is plugged in there during a print).
#pragma once
#include <Arduino.h>

// One line describing the crash before this boot ("" when the last reset was not a crash or nothing was captured).
// Addresses can be decoded on the Mac with xtensa-esp32s3-elf-addr2line -pfiaC -e firmware.elf <addresses>.
String crashReport();

// Number of crashes recorded since power-up (RTC memory survives resets, not power loss).
uint32_t crashCount();

// Forgets the recorded crash (the user has seen it): crashReport() is "" and crashCount() 0 afterwards.
void crashClear();
