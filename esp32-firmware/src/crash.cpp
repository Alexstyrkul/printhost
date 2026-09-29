// See crash.h. The wrappers run inside the panic handler: everything they touch is in IRAM or RTC memory, and
// strings that may live in flash are only read while the flash cache is on (a panic can happen with it off).
#include "crash.h"

#include <esp_attr.h>
#include <esp_debug_helpers.h>
#include <esp_spi_flash.h>
#include <esp_system.h>
#include <esp_timer.h>
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
#include <freertos/xtensa_context.h>
#include <soc/cpu.h>

#include "esp_private/panic_internal.h"

namespace {

const uint32_t MAGIC = 0x50484352;  // "PHCR"
const int BT_N = 16;

struct CrashRec {
  uint32_t magic;
  uint32_t count;     // crashes since power-up
  uint32_t pending;   // 1: not reported yet
  int32_t core;
  int32_t exception;  // panic_exception_t
  uint32_t pc, exccause, excvaddr;
  uint32_t uptimeMs;
  char task[16];
  char reason[48];
  char details[120];  // abort() / assert / stack overflow message
  char errcheck[120]; // ESP_ERROR_CHECK: error, file:line, expression
  uint32_t bt[BT_N];
  int32_t btN;
};

RTC_NOINIT_ATTR CrashRec rec;

IRAM_ATTR void copyStr(char *dst, size_t cap, const char *src) {
  size_t i = 0;
  if (src)
    for (; i + 1 < cap && src[i]; i++) dst[i] = src[i];
  dst[i] = 0;
}

// A pointer that can be read right now: RAM always, flash only while the cache is on.
IRAM_ATTR bool readable(const void *p) {
  if (!p) return false;
  if (esp_ptr_internal(p) || esp_ptr_external_ram(p)) return true;
  return spi_flash_cache_enabled();
}

const char *EXC_NAMES[] = {"debug exception", "interrupt watchdog", "task watchdog", "abort", "CPU fault"};

String report;
bool built = false;

}  // namespace

extern "C" {

void __real_esp_panic_handler(panic_info_t *info);
void __real_panic_abort(const char *details) __attribute__((noreturn));
void __real__esp_error_check_failed(esp_err_t rc, const char *file, int line, const char *function, const char *expression)
    __attribute__((noreturn));

IRAM_ATTR void __wrap_esp_panic_handler(panic_info_t *info) {
  if (rec.magic != MAGIC) {
    rec.magic = MAGIC;
    rec.count = 0;
    rec.details[0] = rec.errcheck[0] = 0;
  }
  rec.count++;
  rec.pending = 1;
  rec.core = info->core;
  rec.exception = g_panic_abort ? PANIC_EXCEPTION_ABORT : info->exception;
  rec.pc = (uint32_t)info->addr;
  rec.reason[0] = rec.task[0] = 0;
  if (!g_panic_abort) {
    rec.details[0] = 0;  // left over from an earlier abort
    rec.errcheck[0] = 0;
  }
  if (!g_panic_abort && readable(info->reason)) copyStr(rec.reason, sizeof(rec.reason), info->reason);
  rec.uptimeMs = spi_flash_cache_enabled() ? (uint32_t)(esp_timer_get_time() / 1000) : 0;
  if (spi_flash_cache_enabled()) {
    TaskHandle_t t = xTaskGetCurrentTaskHandleForCPU(info->core);
    if (t) copyStr(rec.task, sizeof(rec.task), pcTaskGetTaskName(t));
  }
  rec.btN = 0;
  const XtExcFrame *xf = (const XtExcFrame *)info->frame;
  if (xf) {
    rec.exccause = xf->exccause;
    rec.excvaddr = xf->excvaddr;
    esp_backtrace_frame_t f = {.pc = (uint32_t)xf->pc, .sp = (uint32_t)xf->a1, .next_pc = (uint32_t)xf->a0, .exc_frame = xf};
    rec.bt[rec.btN++] = esp_cpu_process_stack_pc(f.pc);
    while (rec.btN < BT_N && f.next_pc != 0 && esp_backtrace_get_next_frame(&f)) rec.bt[rec.btN++] = esp_cpu_process_stack_pc(f.pc);
  }
  __real_esp_panic_handler(info);
}

IRAM_ATTR void __wrap_panic_abort(const char *details) {
  if (rec.magic != MAGIC) {
    rec.magic = MAGIC;
    rec.count = 0;
    rec.errcheck[0] = 0;
  }
  rec.details[0] = 0;
  if (readable(details)) copyStr(rec.details, sizeof(rec.details), details);
  __real_panic_abort(details);
}

void __wrap__esp_error_check_failed(esp_err_t rc, const char *file, int line, const char *function, const char *expression) {
  if (rec.magic != MAGIC) {
    rec.magic = MAGIC;
    rec.count = 0;
  }
  const char *base = strrchr(file, '/');
  snprintf(rec.errcheck, sizeof(rec.errcheck), "ESP_ERROR_CHECK %s (0x%x) at %s:%d: %s", esp_err_to_name(rc), (unsigned)rc,
           base ? base + 1 : file, line, expression);
  __real__esp_error_check_failed(rc, file, line, function, expression);
}

}  // extern "C"

String crashReport() {
  if (built) return report;
  built = true;
  esp_reset_reason_t r = esp_reset_reason();
  if (r == ESP_RST_POWERON || r == ESP_RST_BROWNOUT) {  // RTC memory holds garbage after a power loss
    memset(&rec, 0, sizeof(rec));
    return report;
  }
  if (rec.magic != MAGIC || !rec.pending) return report;
  rec.pending = 0;
  char buf[640];
  int n = snprintf(buf, sizeof(buf), "%s on core %d in task '%s' after %us",
                   rec.exception >= 0 && rec.exception < 5 ? EXC_NAMES[rec.exception] : "panic", (int)rec.core, rec.task,
                   (unsigned)(rec.uptimeMs / 1000));
  if (rec.reason[0]) n += snprintf(buf + n, sizeof(buf) - n, ", %s", rec.reason);
  if (rec.exception == PANIC_EXCEPTION_FAULT)
    n += snprintf(buf + n, sizeof(buf) - n, " (EXCCAUSE %u, EXCVADDR 0x%08x)", (unsigned)rec.exccause, (unsigned)rec.excvaddr);
  if (rec.details[0]) n += snprintf(buf + n, sizeof(buf) - n, "; %s", rec.details);
  if (rec.errcheck[0]) n += snprintf(buf + n, sizeof(buf) - n, "; %s", rec.errcheck);
  n += snprintf(buf + n, sizeof(buf) - n, "; PC 0x%08x; backtrace", (unsigned)rec.pc);
  for (int i = 0; i < rec.btN && i < BT_N && n < (int)sizeof(buf) - 12; i++) n += snprintf(buf + n, sizeof(buf) - n, " 0x%08x", (unsigned)rec.bt[i]);
  report = buf;
  for (size_t i = 0; i < report.length(); i++)  // it goes into JSON and single log lines
    if (report[i] == '"' || report[i] == '\n' || report[i] == '\r') report.setCharAt(i, ' ');
  return report;
}

uint32_t crashCount() { return rec.magic == MAGIC ? rec.count : 0; }

void crashClear() {
  memset(&rec, 0, sizeof(rec));
  report = "";
  built = true;
}
