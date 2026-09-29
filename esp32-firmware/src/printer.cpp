#include "printer.h"

#include <SD_MMC.h>
#include <esp_attr.h>
#include <esp_system.h>
#include <esp_timer.h>
#include <new>
#include <vector>
#include <stdarg.h>

#include "common.h"
#include "crash.h"

// ------------------------------------------------------------------------------------------------
// Engine: one FreeRTOS task owns the link. HTTP handlers post commands to it and wait for the answer,
// so nothing else ever touches the serial line.
// ------------------------------------------------------------------------------------------------
namespace {

enum CmdType { C_CONNECT, C_DISCONNECT, C_START, C_PAUSE, C_RESUME, C_STOP, C_GCODE, C_SELECT, C_RECOVER, C_DISCARD };

struct Cmd {
  CmdType type;
  String arg;
  uint32_t timeoutMs = 0;
  uint32_t arg2 = 0;  // C_START only: rehearsal skip target (file line number)
  uint32_t arg3 = 0;  // C_START only: rehearsal resend-recovery test - corrupt every Nth sent line
  float argF = 0;     // C_RECOVER only: zNow (cold resume), <= 0 = not given
  String reply;
  String err;
  bool ok = false;
  bool abandoned = false;  // the HTTP thread gave up waiting; the engine frees the command itself
  SemaphoreHandle_t done = nullptr;
};

QueueHandle_t cmdQ = nullptr;
portMUX_TYPE cmdMux = portMUX_INITIALIZER_UNLOCKED;
SemaphoreHandle_t stMtx = nullptr;

bool g_idlePoll = true;  // periodic M105 while idle (diagnostic switch)
PrinterLink *link = nullptr;      // only touched by the engine task (created via SELECT)
String pendingLinkKind;           // set by printerSelectLink, consumed by the engine

// Snapshot fields (written by the engine, read by HTTP handlers under stMtx).
PrinterState gState = PS_NO_LINK;
String gLinkName = "none", gFile, gErr, gFw;
uint32_t gBytesDone = 0, gBytesTotal = 0, gLines = 0, gFinished = 0;
uint32_t gStartMs = 0, gPausedAtMs = 0, gPausedTotalMs = 0, gEndMs = 0;
float gHot = 0, gHotT = 0, gBed = 0, gBedT = 0;
bool gDry = false;  // true while gFile/gState reflect a rehearsal (dry run), not a real print
char gBootId[12] = "";  // random per boot: a client that sees it change knows the board restarted
String gLastEnd;        // how the last print of this boot ended: finished / stopped / error text

void lock() { xSemaphoreTake(stMtx, portMAX_DELAY); }
void unlock() { xSemaphoreGive(stMtx); }

// ---- machine state along the file (for resuming) -----------------------------------------------------
struct ResumeState {
  bool absXYZ = true, relE = false;
  float x = 0, y = 0, z = 0, e = 0, f = 1500;
  float hotT = 0, bedT = 0;
  int fan = 0;  // 0-255
  int speedPct = 0, flowPct = 0;  // 0 = never set in the file
};

// ---- interrupted print --------------------------------------------------------------------------
// A print that did not end by itself or by Stop: the board crashed or lost power (found in the journal at boot), or the
// engine gave up on an error. Kept until it is resumed, discarded, or a new print starts.
struct Interrupted {
  bool valid = false;
  bool atBoot = false;       // found in the journal at boot (the board restarted mid-print)
  String file, reason;
  uint32_t bytes = 0, total = 0;
  bool scanned = false;      // the fields below were reconstructed from the file
  float x = 0, y = 0, z = 0, hotT = 0, bedT = 0;
  bool checked = false;      // the printer was looked at after the restart
  bool printerKept = false;  // Marlin kept running through it: still homed, heater targets still set
  bool parked = false;       // nozzle lifted by PARK_LIFT_MM, hotend off
  uint32_t parkedAtMs = 0;
  bool bedOffDone = false;
  uint32_t resumes = 0;     // resumes of this print so far (from the journal)
  ResumeState st;           // machine state at `bytes` (from the journal, or the file scan)
  bool haveState = false;
  bool autoTried = false;   // the automatic resume was started (or ruled out) for this interruption
  bool crashLoop = false;   // the board had been up less than CRASH_LOOP_SEC: never resume, switch everything off
};
Interrupted gInt;  // written by the engine, read by HTTP handlers under stMtx
const float PARK_LIFT_MM = 5;
const uint32_t PARK_BED_KEEP_MS = 30UL * 60 * 1000;  // the bed stays warm this long (the part keeps sticking)
// After a board crash with the printer still homed and hot the print continues by itself (the user's wish,
// 2026-09-29) - at most this many times per print, so a board that keeps crashing does not loop forever.
const uint32_t AUTO_RESUME_MAX = 2;
// ...and only if the board had been up this long before it went down: a board that restarts every few minutes must
// leave the printer alone (heaters off, no resume) instead of heating and moving it again and again.
const uint32_t CRASH_LOOP_SEC = 600;
// Uptime heartbeat in RTC memory (survives a crash, not a power loss): tells the next boot how long this one lasted.
struct Beat {
  uint32_t magic, uptimeSec;
};
RTC_NOINIT_ATTR Beat rtcBeat;
uint32_t gPrevUptimeSec = 0;  // how long the previous boot ran (0 = unknown, e.g. after a power loss)
void beat() { rtcBeat.uptimeSec = millis() / 1000; }

void setState(PrinterState s) {
  lock();
  gState = s;
  unlock();
}

void setError(const String &e) {
  logEvent("printer: ERROR %s", e.c_str());
  lock();
  gErr = e;
  gState = PS_ERROR;
  unlock();
}

// ---- temperature parsing -----------------------------------------------------------------------
// Handles "ok T:25.3 /0.0 B:22.1 /0.0 @:0 B@:0", "T:200.0 /210.0 B:60.0 /60.0 @:127", "T:25.3 E:0 B:22.1".
bool readTemp(const char *line, char tag, float &cur, float &target, bool &hasTarget) {
  for (const char *p = line; *p; p++) {
    if (*p == tag && p[1] == ':' && (p == line || p[-1] == ' ')) {
      char *end = nullptr;
      float v = strtof(p + 2, &end);
      if (end == p + 2) continue;
      cur = v;
      hasTarget = false;
      const char *q = end;
      while (*q == ' ') q++;
      if (*q == '/') {
        target = strtof(q + 1, nullptr);
        hasTarget = true;
      }
      return true;
    }
  }
  return false;
}

void parseTemps(const char *line) {
  float cur, tgt;
  bool hasT;
  if (readTemp(line, 'T', cur, tgt, hasT)) {
    lock();
    gHot = cur;
    if (hasT) gHotT = tgt;
    unlock();
  }
  if (readTemp(line, 'B', cur, tgt, hasT)) {
    lock();
    gBed = cur;
    if (hasT) gBedT = tgt;
    unlock();
  }
}

// ---- I/O helpers -------------------------------------------------------------------------------
bool sendRaw(const char *s) {
  return link && link->writeBytes((const uint8_t *)s, strlen(s));
}

// Marlin's line format: "N<number> <command>*<xor of everything before the '*'>"
// corrupt: deliberately flips the checksum so Marlin rejects the line and asks for a resend -
// resend-recovery testing only (see sendLine's `corrupt` parameter), never used outside a rehearsal.
void buildNumbered(uint32_t n, const char *text, char *out, size_t cap, bool corrupt = false) {
  int len = snprintf(out, cap, "N%u %s", (unsigned)n, text);
  uint8_t cs = 0;
  for (int i = 0; i < len; i++) cs ^= (uint8_t)out[i];
  if (corrupt) cs ^= 0xff;
  snprintf(out + len, cap - len, "*%u\n", (unsigned)cs);
}

bool isFatalLine(const char *l) {
  return strstr(l, "Printer halted") || strstr(l, "kill()") || strstr(l, "Thermal Runaway") ||
         strstr(l, "THERMAL RUNAWAY") || strstr(l, "MINTEMP") || strstr(l, "MAXTEMP") || strstr(l, "Heating failed");
}

// Refuse the two commands that could brick or wipe the printer from the network (factory reset, firmware update).
bool gcodeAllowed(const String &c) {
  String u = c;
  u.trim();
  u.toUpperCase();
  return !(u.startsWith("M502") || u.startsWith("M997"));
}

// ---- SD line reader ----------------------------------------------------------------------------
struct LineReader {
  File f;
  uint8_t buf[4096];
  size_t pos = 0, len = 0;
  uint32_t offset = 0;  // file offset of buf[pos]
  bool eof = false;

  bool fill() {
    if (xSemaphoreTake(sdMutex, pdMS_TO_TICKS(3000)) != pdTRUE) return false;
    int n = f.read(buf, sizeof(buf));
    xSemaphoreGive(sdMutex);
    if (n <= 0) {
      eof = true;
      len = pos = 0;
      return false;
    }
    len = n;
    pos = 0;
    return true;
  }

  // Next non-empty gcode line with comments stripped. bytesAfter = file offset just past its newline.
  // Returns 1 = line, 0 = end of file, -1 = SD busy (retry), -2 = line too long.
  int next(char *out, size_t cap, uint32_t &bytesAfter) {
    for (;;) {
      size_t n = 0;
      bool comment = false, gotNewline = false, tooLong = false;
      while (!gotNewline) {
        if (pos >= len && !fill()) {
          if (!eof) return -1;  // SD busy, try again later
          break;
        }
        uint8_t c = buf[pos++];
        offset++;
        if (c == '\n') {
          gotNewline = true;
          break;
        }
        if (c == '\r' || comment) continue;
        if (c == ';') {
          comment = true;
          continue;
        }
        if (n + 1 < cap) out[n++] = (char)c;
        else tooLong = true;
      }
      if (tooLong) return -2;
      while (n > 0 && (out[n - 1] == ' ' || out[n - 1] == '\t')) n--;
      size_t s = 0;
      while (s < n && (out[s] == ' ' || out[s] == '\t')) s++;
      if (s) memmove(out, out + s, n - s);
      n -= s;
      out[n] = 0;
      bytesAfter = offset;
      if (n > 0) return 1;
      if (!gotNewline) return 0;  // EOF without a trailing newline and nothing left
    }
  }
};

// ---- manual command ----------------------------------------------------------------------------
// Sends one line and collects the answer up to the first "ok". Caller must ensure nothing is printing.
bool execGcode(const String &cmd, uint32_t timeoutMs, String &reply, String &err) {
  if (!link || !link->isOpen()) {
    err = "printer not connected";
    return false;
  }
  link->flushInput();
  String line = cmd;
  line.trim();
  line += "\n";
  if (!sendRaw(line.c_str())) {
    err = "write to printer failed";
    return false;
  }
  uint32_t t0 = millis();
  char buf[300];
  reply = "";
  while (millis() - t0 < timeoutMs) {
    int n = link->readLine(buf, sizeof(buf), 100);
    if (n < 0) continue;
    parseTemps(buf);
    reply += buf;
    reply += "\n";
    if (strncmp(buf, "ok", 2) == 0) return true;
    if (isFatalLine(buf)) {
      err = String("printer reported: ") + buf;
      return false;
    }
  }
  err = "timed out waiting for the printer";
  return false;
}


ResumeState gAckState;  // the machine state right after the last line the printer acknowledged (under stMtx)

// ---- print journal ---------------------------------------------------------------------------------
// One fixed-size record on the SD card, rewritten in place about once a second while a real print runs:
// the file, the offset just past the last line the printer acknowledged, and the machine state at that line
// (position, extruder, temperatures, fan, modes). If the board restarts mid-print the record still says "active":
// the next boot knows where the print was cut off and can continue straight from there, without reading the file.
const char *JOB_PATH = "/print.job";
const size_t JOB_REC = 320;
uint32_t gJobResumes = 0;  // how often the current print was resumed after an interruption (kept in the journal)

void journalWrite(bool active, const String &file, uint32_t bytes, uint32_t total) {
  lock();
  ResumeState s = gAckState;
  unlock();
  char rec[JOB_REC + 1];
  int n = snprintf(rec, sizeof(rec), "PHJ3 %c %10u %10u %3u %.3f %.3f %.3f %.5f %.0f %.0f %.0f %d %d %d %d %d %s", active ? 'A' : '-',
                   (unsigned)bytes, (unsigned)total, (unsigned)gJobResumes, s.x, s.y, s.z, s.e, s.f, s.hotT, s.bedT, s.fan, s.absXYZ ? 1 : 0,
                   s.relE ? 1 : 0, s.speedPct, s.flowPct, file.c_str());
  if (n < 0) return;
  if ((size_t)n > JOB_REC - 1) n = JOB_REC - 1;
  memset(rec + n, ' ', JOB_REC - 1 - n);
  rec[JOB_REC - 1] = '\n';
  rec[JOB_REC] = 0;
  if (xSemaphoreTake(sdMutex, pdMS_TO_TICKS(500)) != pdTRUE) return;  // next second's write will catch up
  File f = SD_MMC.exists(JOB_PATH) ? SD_MMC.open(JOB_PATH, "r+") : SD_MMC.open(JOB_PATH, FILE_WRITE);
  if (f) {
    f.seek(0);
    f.write((const uint8_t *)rec, JOB_REC);
    f.close();
  }
  xSemaphoreGive(sdMutex);
}

// True if the journal says a print was running. haveState: the record carries the machine state (PHJ3).
bool journalRead(String &file, uint32_t &bytes, uint32_t &total, uint32_t &resumes, ResumeState &st, bool &haveState) {
  char rec[JOB_REC + 1] = "";
  if (xSemaphoreTake(sdMutex, pdMS_TO_TICKS(3000)) != pdTRUE) return false;
  File f = SD_MMC.open(JOB_PATH, FILE_READ);
  int n = 0;
  if (f) {
    n = f.read((uint8_t *)rec, JOB_REC);
    f.close();
  }
  xSemaphoreGive(sdMutex);
  if (n < 30) return false;
  rec[n] = 0;
  int ver = !strncmp(rec, "PHJ3 A ", 7) ? 3 : (!strncmp(rec, "PHJ2 A ", 7) ? 2 : (!strncmp(rec, "PHJ1 A ", 7) ? 1 : 0));
  if (!ver) return false;
  char *p = rec + 7, *end;
  bytes = strtoul(p, &end, 10);
  total = strtoul(end, &end, 10);
  resumes = ver >= 2 ? strtoul(end, &end, 10) : 0;
  haveState = false;
  if (ver == 3) {
    st.x = strtof(end, &end);
    st.y = strtof(end, &end);
    st.z = strtof(end, &end);
    st.e = strtof(end, &end);
    st.f = strtof(end, &end);
    st.hotT = strtof(end, &end);
    st.bedT = strtof(end, &end);
    st.fan = strtol(end, &end, 10);
    st.absXYZ = strtol(end, &end, 10) != 0;
    st.relE = strtol(end, &end, 10) != 0;
    st.speedPct = strtol(end, &end, 10);
    st.flowPct = strtol(end, &end, 10);
    haveState = true;
  }
  while (*end == ' ') end++;
  file = end;
  file.trim();
  return file.length() > 0;
}

// Value of parameter `key` in a gcode line ("G1 X12.5 Y3"), searched after the command word.
bool gparam(const char *l, char key, float &v) {
  const char *p = l;
  while (*p && *p != ' ') p++;
  while (*p) {
    while (*p == ' ') p++;
    if (*p == key) {
      char *end = nullptr;
      float x = strtof(p + 1, &end);
      if (end != p + 1) {
        v = x;
        return true;
      }
    }
    while (*p && *p != ' ') p++;
  }
  return false;
}

bool gword(const char *l, char key) {  // parameter present at all (G28 X, with no number)
  const char *p = l;
  while (*p && *p != ' ') p++;
  while (*p) {
    while (*p == ' ') p++;
    if (*p == key) return true;
    while (*p && *p != ' ') p++;
  }
  return false;
}

void trackLine(ResumeState &s, const char *l) {
  char c = l[0];
  if (c != 'G' && c != 'M') return;
  char *end = nullptr;
  long code = strtol(l + 1, &end, 10);
  if (end == l + 1 || (*end != ' ' && *end != 0)) return;
  float v;
  if (c == 'G') {
    switch (code) {
      case 0: case 1: case 2: case 3:
        if (gparam(l, 'X', v)) s.x = s.absXYZ ? v : s.x + v;
        if (gparam(l, 'Y', v)) s.y = s.absXYZ ? v : s.y + v;
        if (gparam(l, 'Z', v)) s.z = s.absXYZ ? v : s.z + v;
        if (gparam(l, 'E', v)) s.e = s.relE ? s.e + v : v;
        if (gparam(l, 'F', v)) s.f = v;
        break;
      case 90: s.absXYZ = true; s.relE = false; break;  // Marlin: G90/G91 also switch E
      case 91: s.absXYZ = false; s.relE = true; break;
      case 92: {
        bool any = false;
        if (gparam(l, 'X', v)) { s.x = v; any = true; }
        if (gparam(l, 'Y', v)) { s.y = v; any = true; }
        if (gparam(l, 'Z', v)) { s.z = v; any = true; }
        if (gparam(l, 'E', v)) { s.e = v; any = true; }
        if (!any) s.x = s.y = s.z = s.e = 0;
        break;
      }
      case 28: {
        bool x = gword(l, 'X'), y = gword(l, 'Y'), z = gword(l, 'Z'), all = !x && !y && !z;
        if (all || x) s.x = 0;
        if (all || y) s.y = 0;
        if (all || z) s.z = 0;
        break;
      }
      default: break;
    }
  } else {
    switch (code) {
      case 82: s.relE = false; break;
      case 83: s.relE = true; break;
      case 104: case 109: if (gparam(l, 'S', v) || gparam(l, 'R', v)) s.hotT = v; break;
      case 140: case 190: if (gparam(l, 'S', v) || gparam(l, 'R', v)) s.bedT = v; break;
      case 106: if (!gparam(l, 'P', v) || v == 0) s.fan = gparam(l, 'S', v) ? (int)v : 255; break;
      case 107: s.fan = 0; break;
      case 220: if (gparam(l, 'S', v)) s.speedPct = (int)v; break;
      case 221: if (gparam(l, 'S', v)) s.flowPct = (int)v; break;
      default: break;
    }
  }
}

// Reads `rd` from where it is up to file offset `upTo` (a line boundary), tracking the machine state.
// Returns the offset reached (just past a whole line), or 0 on an error (err set).
uint32_t scanTo(LineReader &rd, uint32_t upTo, ResumeState &st, String &err, bool showProgress) {
  char buf[256];
  uint32_t after = rd.offset, lastShown = 0;
  while (after < upTo) {
    int got = rd.next(buf, sizeof(buf), after);
    if (got == 0) {
      err = "the resume point is past the end of the file";
      return 0;
    }
    if (got == -1) {
      delay(2);
      continue;
    }
    if (got == -2) continue;  // over-long line: not a command we track
    trackLine(st, buf);
    if (showProgress && after - lastShown > 200000) {
      lastShown = after;
      lock();
      gBytesDone = after;
      unlock();
    }
  }
  return after;
}

// ---- printing ----------------------------------------------------------------------------------
// The engine keeps a small WINDOW of numbered lines in flight (default 3) instead of one. Marlin acknowledges every
// command with an "ok", strictly in the order it received them, so the acks are matched against a FIFO of what was
// sent. With one line in flight, the printer's planner ran dry on fine detail (short moves execute faster than a
// round trip); with a few lines queued in the printer it never waits for the next command.
//
// Error recovery ("Resend"): Marlin throws away everything after a damaged line, and may answer each of the lines
// that were already on the wire with its own error. So on the first Error/Resend the engine stops sending, waits
// until the line goes quiet, forgets what was in flight and re-sends from the line Marlin asked for.
enum SlotType : uint8_t { S_PRINT, S_POLL, S_FINISH };

struct Slot {
  SlotType type;
  bool traced;          // the line was logged (its ack is logged too)
  uint16_t len;         // bytes on the wire
  uint32_t lineNo;      // S_PRINT: the numbered line
  uint32_t bytesAfter;  // S_PRINT: file offset just past the line
  int64_t sentUs;
  uint32_t sentMs;
};

volatile int g_window = 3;  // lines allowed in flight; 1 = classic stop-and-wait
const int MAXW = 10;        // ring size (window is limited to 6, plus probes)
const int MAX_INFLIGHT_BYTES = 200;

struct PrintRun {
  LineReader rd;
  static const int HIST = 16;
  struct H {
    uint32_t n = 0;
    uint32_t after = 0;
    char text[200];
    ResumeState st;  // machine state right after this line
  } hist[HIST];
  uint32_t lineNo = 0;  // last numbered line sent
  ResumeState cur;       // machine state after the last line read from the file

  Slot fifo[MAXW];
  int fh = 0, fn = 0;    // ring head and count
  uint32_t inflightBytes = 0;

  bool eof = false, finishSent = false;
  bool pendValid = false;  // a gcode line was read from the file but not sent yet
  char pend[256];
  uint32_t pendAfter = 0;

  bool resync = false;     // an error was reported: waiting for the line to go quiet
  uint32_t resyncUntil = 0;
  uint32_t resendFrom = 0;
  uint32_t lastGood = 0;   // "Last Line: N" from Marlin's error message
  int throttle = 8;        // window 1 for this many lines (start of the print, after every resync)

  uint32_t lastRx = 0, nextPoll = 0, lastProbeMs = 0;
  int said = 0;            // unexpected lines from the printer logged so far (capped)
  uint32_t busyN = 0;      // "echo:busy" keepalives since the last ack
  uint32_t resends = 0;

  // Throughput statistics (read-only bookkeeping). One report window ~10 s.
  int64_t roomUs = 0;      // when an ack freed a window slot (0 = none waiting)
  uint32_t wLines = 0, wRttSum = 0, wRttMax = 0, wGapSum = 0, wGapN = 0, wGapMax = 0, wTx = 0, wRx = 0, wStartMs = 0;
  uint32_t wDepthSum = 0, wDepthN = 0;
};

// Last finished statistics window, for /printer/status (written by the engine, read under stMtx).
float gLps = 0;
uint32_t gRttAvgUs = 0, gRttMaxUs = 0, gGapAvgUs = 0, gGapMaxUs = 0, gTxBps = 0, gRxBps = 0, gResendsTotal = 0;
float gDepth = 0;

Slot &fifoAt(PrintRun &r, int i) { return r.fifo[(r.fh + i) % MAXW]; }
void fifoPush(PrintRun &r, const Slot &s) {
  r.fifo[(r.fh + r.fn) % MAXW] = s;
  r.fn++;
  if (s.type == S_PRINT) r.inflightBytes += s.len;
}
void fifoPop(PrintRun &r) {
  Slot &s = r.fifo[r.fh];
  if (s.type == S_PRINT) r.inflightBytes -= s.len;
  r.fh = (r.fh + 1) % MAXW;
  r.fn--;
}
void fifoClear(PrintRun &r) {
  r.fh = r.fn = 0;
  r.inflightBytes = 0;
}
int firstPoll(PrintRun &r) {
  for (int i = 0; i < r.fn; i++)
    if (fifoAt(r, i).type == S_POLL) return i;
  return -1;
}

int effWindow(const PrintRun &r) {
  int w = g_window;
  if (w < 1) w = 1;
  if (w > 6) w = 6;
  return r.throttle > 0 ? 1 : w;
}

// Something went out on the wire.
void noteSend(PrintRun &r, size_t bytes) {
  int64_t now = esp_timer_get_time();
  if (r.roomUs) {  // the host's own delay between "a slot became free" and "the next line went out"
    uint32_t gap = (uint32_t)(now - r.roomUs);
    r.wGapSum += gap;
    r.wGapN++;
    if (gap > r.wGapMax) r.wGapMax = gap;
    r.roomUs = 0;
  }
  r.wTx += bytes;
  r.wDepthSum += r.fn;
  r.wDepthN++;
}

// A print line was acknowledged.
void noteAck(PrintRun &r, const Slot &s) {
  int64_t now = esp_timer_get_time();
  uint32_t rtt = (uint32_t)(now - s.sentUs);  // send -> ok: printer queue time plus the USB round trip
  r.wLines++;
  r.wRttSum += rtt;
  if (rtt > r.wRttMax) r.wRttMax = rtt;
  if (r.roomUs == 0) r.roomUs = now;
}

void reportStats(PrintRun &r) {
  uint32_t now = millis();
  if (r.wStartMs == 0) {
    r.wStartMs = now;
    return;
  }
  uint32_t dt = now - r.wStartMs;
  if (dt < 10000) return;
  float lps = r.wLines * 1000.0f / dt;
  uint32_t rttAvg = r.wLines ? r.wRttSum / r.wLines : 0;
  uint32_t gapAvg = r.wGapN ? r.wGapSum / r.wGapN : 0;
  uint32_t txBps = (uint32_t)((uint64_t)r.wTx * 1000 / dt), rxBps = (uint32_t)((uint64_t)r.wRx * 1000 / dt);
  float depth = r.wDepthN ? (float)r.wDepthSum / r.wDepthN : 0;
  lock();
  gLps = lps;
  gRttAvgUs = rttAvg;
  gRttMaxUs = r.wRttMax;
  gGapAvgUs = gapAvg;
  gGapMaxUs = r.wGapMax;
  gTxBps = txBps;
  gRxBps = rxBps;
  gResendsTotal = r.resends;
  gDepth = depth;
  unlock();
  logEvent("stats: L%u %.0f l/s ack %u/%u gap %u/%u tx %u rx %u rs %u q%.1f", (unsigned)r.lineNo, lps, (unsigned)(rttAvg / 1000),
           (unsigned)(r.wRttMax / 1000), (unsigned)(gapAvg / 1000), (unsigned)(r.wGapMax / 1000), (unsigned)txBps, (unsigned)rxBps,
           (unsigned)r.resends, depth);
  r.wLines = r.wRttSum = r.wRttMax = r.wGapSum = r.wGapN = r.wGapMax = r.wTx = r.wRx = r.wDepthSum = r.wDepthN = 0;
  r.wStartMs = now;
}

void finishPrint(const char *why) {
  lock();
  gEndMs = millis();
  unlock();
  logEvent("printer: print %s", why);
}

// Turns heaters and fan off. Best effort: never throws, never blocks long.
void safeShutdown() {
  if (link && link->isOpen()) {
    sendRaw("M104 S0\nM140 S0\nM107\n");
    delay(150);
    link->flushInput();
  }
  lock();
  gHotT = 0;
  gBedT = 0;
  unlock();
}

void answerPrintCmd(Cmd *c) {
  portENTER_CRITICAL(&cmdMux);
  bool ab = c->abandoned;
  portEXIT_CRITICAL(&cmdMux);
  if (ab) delete c;
  else xSemaphoreGive(c->done);
}

// True for the commands that heat something (skipped in a rehearsal).
bool isHeatCmd(const char *s) {
  return !strncmp(s, "M104", 4) || !strncmp(s, "M109", 4) || !strncmp(s, "M140", 4) || !strncmp(s, "M190", 4) || !strncmp(s, "M141", 4) ||
         !strncmp(s, "M191", 4);
}

bool isMoveCmd(const char *s) { return s[0] == 'G' && s[1] >= '0' && s[1] <= '3' && (s[2] == ' ' || s[2] == 0); }

// An ok arrived for the head of the FIFO. Returns true when that completes the print.
bool ackHead(PrintRun &r, bool viaProbe) {
  Slot s = fifoAt(r, 0);
  fifoPop(r);
  if (s.type == S_FINISH) {
    lock();
    gBytesDone = gBytesTotal;
    gFinished++;
    unlock();
    return true;
  }
  if (s.type == S_PRINT) {
    if (s.traced) {
      logEvent("RX N%u %s after %u ms, %u busy", (unsigned)s.lineNo, viaProbe ? "ok+T (probe)" : "ok",
               (unsigned)((esp_timer_get_time() - s.sentUs) / 1000), (unsigned)r.busyN);
      r.busyN = 0;
    }
    const PrintRun::H &h = r.hist[s.lineNo % PrintRun::HIST];
    lock();
    gBytesDone = s.bytesAfter;
    gLines++;
    if (h.n == s.lineNo) gAckState = h.st;
    unlock();
    noteAck(r, s);
    if (r.throttle > 0) r.throttle--;
  }
  return false;
}

// Sends one numbered line (or a re-send). Returns false if the write failed.
// corrupt: send this one transmission with a deliberately wrong checksum (resend-recovery testing
// only - see buildNumbered). Only ever passed true for a fresh send, never for the resend itself,
// so the line always lands correctly on the retry.
bool sendLine(PrintRun &r, uint32_t no, const char *text, uint32_t after, bool fresh, bool corrupt = false) {
  char out[256];
  buildNumbered(no, text, out, sizeof(out), corrupt);
  if (!sendRaw(out)) return false;
  Slot s;
  s.type = S_PRINT;
  s.len = (uint16_t)strlen(out);
  s.lineNo = no;
  s.bytesAfter = after;
  s.sentUs = esp_timer_get_time();
  s.sentMs = millis();
  // Log every command that is not a plain move (M*, G28, G29, G92...) and the first 40 lines, with its ack.
  s.traced = no <= 40 || !isMoveCmd(text);
  if (s.traced) logEvent("TX N%u %s%s%s", (unsigned)no, text, fresh ? "" : " (resend)", corrupt ? " (CORRUPTED for test)" : "");
  fifoPush(r, s);
  noteSend(r, s.len);
  return true;
}

bool sendPoll(PrintRun &r) {
  if (!sendRaw("M105\n")) return false;
  Slot s;
  s.type = S_POLL;
  s.traced = false;
  s.len = 5;
  s.lineNo = 0;
  s.bytesAfter = 0;
  s.sentUs = esp_timer_get_time();
  s.sentMs = millis();
  fifoPush(r, s);
  return true;
}

// ---- resuming an interrupted print ---------------------------------------------------------------
struct ResumeSpec {
  uint32_t offset;  // continue with the line that starts here (just past the last acknowledged one)
  bool cold;        // the printer restarted since: home X/Y and take Z from zNow
  float zNow;       // cold only: where the nozzle is now (the parked height)
  const ResumeState *known;  // the state at `offset` from the journal: jump straight there instead of reading the file
};

// While the resume preamble waits (heating can take minutes), Stop must still work. Other commands are refused.
bool stopArrived() {
  Cmd *c;
  bool stop = false;
  while (xQueueReceive(cmdQ, &c, 0) == pdTRUE) {
    if (c->type == C_STOP) {
      stop = true;
      c->ok = true;
    } else {
      c->ok = false;
      c->err = "the printer is busy resuming a print";
    }
    answerPrintCmd(c);
  }
  return stop;
}

// execGcode, but gives up as soon as a Stop arrives. Returns false with err set (err = "stopped" on Stop).
bool execAbortable(const String &cmd, uint32_t timeoutMs, String &err) {
  link->flushInput();
  String line = cmd + "\n";
  if (!sendRaw(line.c_str())) {
    err = "write to printer failed";
    return false;
  }
  logEvent("TX %s (resume)", cmd.c_str());
  uint32_t t0 = millis();
  char buf[300];
  while (millis() - t0 < timeoutMs) {
    if (stopArrived()) {
      err = "stopped";
      return false;
    }
    int n = link->readLine(buf, sizeof(buf), 100);
    if (n < 0) continue;
    parseTemps(buf);
    if (strncmp(buf, "ok", 2) == 0) return true;
    if (isFatalLine(buf)) {
      err = String("printer reported: ") + buf;
      return false;
    }
  }
  err = "timed out waiting for the printer";
  return false;
}

// Brings the printer back to where the file was at `rs.offset` (the reader is already there, `st` is the state):
// heat, lift, (cold: home X/Y), move over the spot, lower, restore extruder mode/position, fan, speed and flow.
bool resumePreamble(const ResumeSpec &rs, const ResumeState &st, String &err) {
  char b[80];
  std::vector<std::pair<String, uint32_t>> cmds;  // command, timeout
  auto add = [&](const char *fmt, float a, uint32_t t = 30000) {
    snprintf(b, sizeof(b), fmt, a);
    cmds.push_back({String(b), t});
  };
  float lift = st.z + PARK_LIFT_MM;
  if (st.bedT > 0) add("M140 S%.0f", st.bedT);
  if (st.hotT > 0) add("M104 S%.0f", st.hotT);
  cmds.push_back({"G90", 30000});
  if (rs.cold) {
    // Marlin restarted: X/Y are unknown, Z is where the user says the nozzle is (the parked height). Homing only X and Y
    // at that height moves the nozzle above the part, never through it; homing Z would probe into the part.
    cmds.push_back({"G28 X Y", 120000});
    add("G92 Z%.3f", rs.zNow);
    cmds.push_back({"M420 S1", 30000});  // the saved bed mesh
  }
  add("G1 Z%.3f F600", lift);
  if (st.bedT > 0) add("M190 S%.0f", st.bedT, 1800000);
  if (st.hotT > 0) add("M109 S%.0f", st.hotT, 1800000);
  cmds.push_back({"M83", 30000});
  cmds.push_back({"G1 E3 F150", 60000});  // prime what oozed out while waiting, above the part
  snprintf(b, sizeof(b), "G1 X%.3f Y%.3f F3000", st.x, st.y);
  cmds.push_back({String(b), 60000});
  add("G1 Z%.3f F600", st.z);
  cmds.push_back({"M400", 120000});
  if (st.relE) {
    cmds.push_back({"M83", 30000});
  } else {
    cmds.push_back({"M82", 30000});
    add("G92 E%.5f", st.e);
  }
  if (!st.absXYZ) cmds.push_back({"G91", 30000});  // G91 also makes E relative in Marlin, as the file had it
  if (st.fan > 0) add("M106 S%.0f", (float)st.fan);
  else cmds.push_back({"M107", 30000});
  if (st.speedPct > 0) add("M220 S%.0f", (float)st.speedPct);
  if (st.flowPct > 0) add("M221 S%.0f", (float)st.flowPct);
  add("G1 F%.0f", st.f);
  for (auto &c : cmds) {
    String e;
    if (!execAbortable(c.first, c.second, e)) {
      err = e == "stopped" ? e : "'" + c.first + "' failed: " + e;
      return false;
    }
  }
  return true;
}

void runPrint(const String &path, uint32_t dryLines, uint32_t skipLines, uint32_t badEvery, const ResumeSpec *rs = nullptr) {
  static PrintRun *rp = nullptr;
  if (!rp) rp = new PrintRun();
  PrintRun &r = *rp;
  // Reset in place: a temporary PrintRun on this task's stack (several KB) would overflow it.
  r.~PrintRun();
  new (&r) PrintRun();

  if (xSemaphoreTake(sdMutex, pdMS_TO_TICKS(3000)) != pdTRUE) {
    setError("SD card busy");
    return;
  }
  r.rd.f = SD_MMC.open(path, FILE_READ);
  xSemaphoreGive(sdMutex);
  if (!r.rd.f) {
    setError("cannot open " + path);
    return;
  }
  lock();
  gBytesTotal = r.rd.f.size();
  gBytesDone = 0;
  gLines = 0;
  gStartMs = millis();
  gPausedTotalMs = 0;
  gEndMs = 0;
  gErr = "";
  unlock();
  logEvent("printer: print start '%s' (%u bytes), window %d", path.c_str(), (unsigned)r.rd.f.size(), (int)g_window);

  // Real prints keep the journal (a rehearsal is never resumed). Starting one replaces any interrupted print.
  const bool journal = dryLines == 0;
  String jobName = path.substring(path.lastIndexOf('/') + 1);
  uint32_t lastJournalMs = 0, lastJournalBytes = 0xFFFFFFFF;
  if (journal) {
    if (!rs) {
      gJobResumes = 0;
      lock();
      gAckState = ResumeState();
      unlock();
    }
    lock();
    gInt = Interrupted();
    unlock();
    journalWrite(true, jobName, rs ? rs->offset : 0, gBytesTotal);
  }

  char rx[300];
  bool finished = false;
  // Counts every line READ from the file, in both the skip scan below and the main loop further
  // down - i.e. always the same file-line coordinate `dryLines` and `skipLines` are both given
  // in, unlike r.lineNo (Marlin's own N-numbering, which only counts lines actually SENT and
  // restarts at 1 after a skip - conflating the two once cost a rehearsal running ~200000 lines
  // longer than intended, because "dry" was compared against r.lineNo instead of this).
  uint32_t fileLineNo = 0;

  if (dryLines > 0 && skipLines > 0 && dryLines <= skipLines) {
    setError("dry must be greater than skip - nothing would be sent");
    goto done;
  }

  // Rehearsal-only: jump straight to file line `skipLines` instead of reading/discarding the
  // whole run-up in real time (a dry run otherwise runs at real Marlin ack speed - reaching line
  // 200000+ this way can take the better part of an hour). Every line before the target is read
  // and thrown away WITHOUT sending it, except the first G28 found, which is sent for real and
  // waited on synchronously (execGcode) - so the machine has an actual homed reference position
  // before the big positioning jump that follows. If no G28 turns up before the target, refuse:
  // jumping into arbitrary coordinates from an unknown physical position is not safe to guess at.
  if (skipLines > 0) {
    logEvent("printer: rehearsal skip - scanning to file line %u", (unsigned)skipLines);
    char scan[256];
    uint32_t after = 0;
    bool homed = false;
    while (fileLineNo < skipLines) {
      int got = r.rd.next(scan, sizeof(scan), after);
      if (got == 0) break;             // file shorter than the skip target
      if (got == -1) { delay(5); continue; }  // SD busy, retry
      if (got == -2) { fileLineNo++; continue; }  // line too long to buffer - ignore during scan
      fileLineNo++;
      if (!homed && !strncmp(scan, "G28", 3)) {
        String reply, gerr;
        logEvent("printer: rehearsal skip - homing ('%s')", scan);
        if (!execGcode(String(scan), 30000, reply, gerr)) {
          setError("rehearsal skip: homing failed - " + gerr);
          goto done;
        }
        homed = true;
      }
    }
    lock();
    gBytesDone = after;
    unlock();
    if (!homed) {
      setError("rehearsal skip: no G28 found before line " + String(skipLines) + " - refusing to jump unhomed");
      goto done;
    }
    logEvent("printer: rehearsal skip done at file line %u (%u bytes)", (unsigned)fileLineNo, (unsigned)after);
  }

  if (rs) {  // resume: read up to the resume point (tracking the machine state), then bring the printer there
    ResumeState st;
    String err;
    logEvent("printer: resume '%s' from byte %u (%s)", jobName.c_str(), (unsigned)rs->offset, rs->cold ? "printer restarted: home X/Y" : "printer kept its position");
    uint32_t at = 0;
    if (rs->known) {  // the journal has the state: seek, no need to read 20 MB first
      st = *rs->known;
      bool ok = false;
      if (xSemaphoreTake(sdMutex, pdMS_TO_TICKS(3000)) == pdTRUE) {
        ok = r.rd.f.seek(rs->offset);
        xSemaphoreGive(sdMutex);
      }
      if (ok) {
        r.rd.offset = at = rs->offset;
        r.rd.pos = r.rd.len = 0;
      }
    }
    if (!at) {
      st = ResumeState();
      at = scanTo(r.rd, rs->offset, st, err, true);
    }
    if (!at) {
      setError("resume: " + err);
      goto done;
    }
    r.cur = st;
    lock();
    gAckState = st;
    unlock();
    logEvent("printer: resume point %u: X%.2f Y%.2f Z%.2f E%.4f F%.0f %s%s hot %.0f bed %.0f fan %d", (unsigned)at, st.x, st.y, st.z, st.e, st.f,
             st.absXYZ ? "G90" : "G91", st.relE ? " M83" : " M82", st.hotT, st.bedT, st.fan);
    lock();
    gBytesDone = at;
    unlock();
    if (!resumePreamble(*rs, st, err)) {
      if (err == "stopped") {
        safeShutdown();
        setState(PS_IDLE);
        finishPrint("stopped");
      } else {
        safeShutdown();
        setError("resume: " + err);
      }
      goto done;
    }
    lock();
    gStartMs = millis();
    unlock();
    logEvent("printer: resume preamble done - continuing the file");
  }

  link->flushInput();
  r.lastRx = millis();
  r.nextPoll = millis() + 2000;

  // Reset the printer's line counter so numbering starts from a known place (sent alone, acked before anything else).
  if (!sendLine(r, 0, "M110", 0, true)) {
    setError("write to printer failed");
    goto done;
  }

  for (;;) {
    // ---- commands from the network
    Cmd *c;
    while (xQueueReceive(cmdQ, &c, 0) == pdTRUE) {
      if (c->type == C_PAUSE) {
        lock();
        if (gState == PS_PRINTING) {
          gState = PS_PAUSED;
          gPausedAtMs = millis();
        }
        unlock();
        c->ok = true;
      } else if (c->type == C_RESUME) {
        lock();
        if (gState == PS_PAUSED) {
          gState = PS_PRINTING;
          gPausedTotalMs += millis() - gPausedAtMs;
        }
        unlock();
        c->ok = true;
      } else if (c->type == C_STOP) {
        c->ok = true;
        answerPrintCmd(c);
        safeShutdown();
        setState(PS_IDLE);
        finishPrint("stopped");
        goto done;
      } else {
        c->ok = false;
        c->err = "printer is busy printing";
      }
      answerPrintCmd(c);
    }

    reportStats(r);

    beat();
    if (journal && millis() - lastJournalMs >= 1000) {
      lastJournalMs = millis();
      lock();
      uint32_t b = gBytesDone;
      unlock();
      if (b != lastJournalBytes) {
        lastJournalBytes = b;
        journalWrite(true, jobName, b, gBytesTotal);
      }
    }

    if (!link->isOpen()) {
      setError("printer disconnected (USB link lost)");
      goto done;
    }

    PrinterState st;
    lock();
    st = gState;
    unlock();

    // ---- what could be sent right now (decides how long we may wait for the printer)
    bool pollDue = (int32_t)(millis() - r.nextPoll) >= 0;
    bool roomToSend = !r.resync && r.fn < effWindow(r) && (st == PS_PRINTING || r.resendFrom || pollDue);

    // ---- receive
    int n = link->readLine(rx, sizeof(rx), roomToSend ? 1 : (r.fn ? 15 : 3));
    if (n >= 0) {
      uint32_t now = millis();
      r.lastRx = now;
      r.wRx += (uint32_t)n + 1;
      if (r.resync) r.resyncUntil = now + 250;  // still noisy: keep waiting
      parseTemps(rx);
      if (strncmp(rx, "ok", 2) == 0) {
        if (r.resync) {
          // an ok belonging to a line that was thrown away: ignore
        } else if (r.fn == 0) {
          // stray ok (the tail of an older exchange): nothing is waiting for it
        } else if (strstr(rx, "T:")) {
          // Reply to an M105 (our poll or a probe). If it is not the oldest command in flight, the oks of everything
          // before it were lost: the printer is idle and those commands are done.
          int p = firstPoll(r);
          if (p >= 0) {
            if (p > 0) logEvent("printer: %d ok(s) never arrived - printer answered M105, treating them as done", p);
            for (int i = 0; i <= p; i++) {
              if (ackHead(r, i < p)) {
                finished = true;
                break;
              }
            }
            if (finished) {
              finishPrint("finished");
              setState(PS_IDLE);
              goto done;
            }
          }
        } else if (ackHead(r, false)) {
          finishPrint("finished");
          setState(PS_IDLE);
          goto done;
        }
      } else if (strstr(rx, "Resend:") || strstr(rx, "Error:checksum") || strstr(rx, "Error:Line Number") || strstr(rx, "Error:No Checksum")) {
        const char *p = strstr(rx, "Resend:");
        if (p) {
          uint32_t nn = (uint32_t)strtoul(p + 7, nullptr, 10);
          if (nn > 0) r.resendFrom = nn;
        }
        p = strstr(rx, "Last Line:");
        if (p) r.lastGood = (uint32_t)strtoul(p + 10, nullptr, 10);
        if (!r.resync) {
          r.resync = true;
          r.resends++;
          logEvent("printer: %s - pausing to resynchronise (%d in flight)", rx, r.fn);
        }
        r.resyncUntil = now + 250;
      } else if (strncmp(rx, "start", 5) == 0 && rx[5] == 0) {
        safeShutdown();
        setError("printer restarted during the print");
        goto done;
      } else if (strncmp(rx, "Error:", 6) == 0 || strncmp(rx, "!!", 2) == 0) {
        logEvent("printer says: %s", rx);
        if (isFatalLine(rx) || strncmp(rx, "!!", 2) == 0) {
          setError(String("printer reported: ") + rx);
          goto done;
        }
      } else if (strstr(rx, "echo:busy")) {
        r.busyN++;
      } else if (!strstr(rx, "T:") && r.said < 100) {
        r.said++;
        logEvent("printer says: %s", rx);  // anything that is neither ok nor a temperature report
      }
    }

    // ---- the line has gone quiet after an error: forget what was in flight and start again from the requested line
    if (r.resync && (int32_t)(millis() - r.resyncUntil) >= 0) {
      uint32_t from = r.resendFrom;
      if (!from && r.lastGood) from = r.lastGood + 1;
      if (!from) {  // no number given: restart from the oldest print line that was still unacknowledged
        for (int i = 0; i < r.fn; i++)
          if (fifoAt(r, i).type == S_PRINT) {
            from = fifoAt(r, i).lineNo;
            break;
          }
      }
      // If the M400 end-of-file marker was in flight, fifoClear() just wiped the only thing that
      // would ever have matched its "ok" - without this, a resync landing right at the end of a
      // print (rare, but real: reproduced with badEvery testing) leaves the engine waiting
      // forever for a completion that already happened on the wire. Letting `r.eof && !finishSent`
      // fire again below resends M400 and re-tracks it properly. Sending M400 twice is harmless.
      if (r.finishSent) r.finishSent = false;
      fifoClear(r);
      r.resync = false;
      r.lastGood = 0;
      r.throttle = 8;
      r.roomUs = 0;
      if (from == 0 || from > r.lineNo) {
        r.resendFrom = 0;  // Marlin wants the next new line: nothing to re-send
      } else {
        bool have = false;
        for (int i = 0; i < PrintRun::HIST; i++)
          if (r.hist[i].n == from) have = true;
        if (!have) {
          safeShutdown();
          setError("printer asked to resend a line we no longer have");
          goto done;
        }
        r.resendFrom = from;
        logEvent("printer: resending from line %u (last sent %u)", (unsigned)from, (unsigned)r.lineNo);
      }
    }

    // ---- send: as many lines as the window allows
    while (!r.resync) {
      uint32_t now = millis();
      int win = effWindow(r);
      if (r.fn >= win) break;

      // temperature poll (also while paused)
      if ((int32_t)(now - r.nextPoll) >= 0 && r.fn < win) {
        r.nextPoll = now + 2000;
        if (r.fn == 0 || r.throttle == 0) {
          if (!sendPoll(r)) {
            safeShutdown();
            setError("write to printer failed");
            goto done;
          }
          continue;
        }
      }

      // re-send of lines Marlin asked for (also while paused: the printer is waiting for them)
      if (r.resendFrom) {
        int idx = -1;
        for (int i = 0; i < PrintRun::HIST; i++)
          if (r.hist[i].n == r.resendFrom) idx = i;
        if (idx < 0) {
          safeShutdown();
          setError("printer asked to resend a line we no longer have");
          goto done;
        }
        if (!sendLine(r, r.hist[idx].n, r.hist[idx].text, r.hist[idx].after, false)) {
          safeShutdown();
          setError("write to printer failed");
          goto done;
        }
        r.resendFrom = r.resendFrom < r.lineNo ? r.resendFrom + 1 : 0;
        continue;
      }

      if (st != PS_PRINTING) break;  // paused: nothing new goes out

      // the next gcode line (read once, kept until it can be sent)
      if (!r.pendValid && !r.eof) {
        if (dryLines && fileLineNo >= dryLines) {
          r.eof = true;  // rehearsal length reached
          logEvent("printer: rehearsal - reached file line %u, finishing", (unsigned)fileLineNo);
        } else {
          uint32_t after = 0;
          int got = r.rd.next(r.pend, sizeof(r.pend), after);
          if (got == 1) {
            fileLineNo++;
            if (!dryLines) trackLine(r.cur, r.pend);
            if (dryLines && isHeatCmd(r.pend)) {
              logEvent("rehearsal: skipped %s", r.pend);  // not sent, not numbered
              lock();
              gBytesDone = after;
              unlock();
              continue;
            }
            r.pendValid = true;
            r.pendAfter = after;
          } else if (got == 0) {
            r.eof = true;
          } else if (got == -2) {
            safeShutdown();
            setError("gcode line too long to send");
            goto done;
          } else {
            break;  // -1: the SD card is busy, try again next turn
          }
        }
      }
      if (r.pendValid) {
        // keep the printer's serial buffer from overflowing: bounded bytes in flight (a lone long line always goes)
        if (r.fn > 0 && r.inflightBytes + strlen(r.pend) + 16 > (uint32_t)MAX_INFLIGHT_BYTES) break;
        r.lineNo++;
        PrintRun::H &h = r.hist[r.lineNo % PrintRun::HIST];
        h.n = r.lineNo;
        h.after = r.pendAfter;
        strlcpy(h.text, r.pend, sizeof(h.text));
        h.st = r.cur;
        r.pendValid = false;
        // Resend-recovery test only (dry runs, badEvery>0): deliberately corrupt every Nth fresh
        // line's checksum so real Marlin rejects it and asks for a resend - proves the FIFO resync
        // path against real firmware, not just the simulator.
        bool corruptThis = dryLines && badEvery && (r.lineNo % badEvery == 0);
        if (!sendLine(r, h.n, h.text, h.after, true, corruptThis)) {
          safeShutdown();
          setError("write to printer failed");
          goto done;
        }
        continue;
      }
      if (r.eof && !r.finishSent) {
        // every line is acknowledged: let the printer finish its queued moves
        if (!sendRaw("M400\n")) {
          safeShutdown();
          setError("write to printer failed");
          goto done;
        }
        Slot s;
        s.type = S_FINISH;
        s.traced = true;
        s.len = 5;
        s.lineNo = 0;
        s.bytesAfter = 0;
        s.sentUs = esp_timer_get_time();
        s.sentMs = millis();
        fifoPush(r, s);
        r.finishSent = true;
        logEvent("TX M400 (end of file)");
        continue;
      }
      break;
    }

    // ---- safety nets
    {
      uint32_t now = millis();
      // A command has been waiting for a long time and the printer has been quiet (no busy/temperature reports):
      // ask for a temperature. An answer proves the printer is idle, so an "ok" that got lost is not waited for forever.
      if (r.fn > 0 && !r.resync && firstPoll(r) < 0 && now - fifoAt(r, 0).sentMs > 5000 && now - r.lastRx > 4000 && now - r.lastProbeMs > 5000) {
        r.lastProbeMs = now;
        if (sendPoll(r)) logEvent("printer: %d command(s) silent - probing with M105", r.fn);
      }
      // Marlin reports temperatures every second while it waits to heat, so real silence means it or the link is dead.
      if (r.fn > 0 && now - r.lastRx > 180000) {
        safeShutdown();
        setError("printer stopped responding");
        goto done;
      }
    }
  }

done:
  if (r.rd.f) {
    xSemaphoreTake(sdMutex, pdMS_TO_TICKS(3000));
    r.rd.f.close();
    xSemaphoreGive(sdMutex);
  }
  logEvent("printer: summary - %u lines sent, %u resends, %u s", (unsigned)r.lineNo, (unsigned)r.resends, (unsigned)((millis() - gStartMs) / 1000));
  if (journal) {
    lock();
    bool failed = gState == PS_ERROR;
    uint32_t b = gBytesDone, total = gBytesTotal;
    String why = gErr;
    gLastEnd = failed ? "error: " + why : (gFinished && gBytesDone == gBytesTotal ? "finished" : "stopped");
    if (failed) {  // cut off by an error: resumable from the last acknowledged line
      gInt = Interrupted();
      gInt.valid = true;
      gInt.file = jobName;
      gInt.bytes = b;
      gInt.total = total;
      gInt.reason = why;
      gInt.checked = true;
      gInt.printerKept = link && link->isOpen();
      gInt.st = gAckState;
      gInt.haveState = gAckState.hotT > 0 || gAckState.z > 0;
    }
    unlock();
    if (failed) journalWrite(true, jobName, b, total);  // stays "active": a reboot still finds it
    else journalWrite(false, jobName, b, total);
  }
  lock();
  gLps = 0;
  gRttAvgUs = gRttMaxUs = gGapAvgUs = gGapMaxUs = gTxBps = gRxBps = 0;
  gDepth = 0;
  unlock();
}

// ---- idle tick ---------------------------------------------------------------------------------
// ---- automatic connection ------------------------------------------------------------------------------
// The printer appears on USB whenever it is powered (smart plug, its own switch, a replugged cable): open the link
// then, without anyone pressing Connect. Opening only sets the serial speed - no gcode, DTR/RTS untouched - exactly what
// the Connect button does. After the user disconnects on purpose it stays disconnected until Connect is used again.
bool userDisconnected = false;
bool connectFailed = false;  // the last ERROR came from a failed Connect (printer not on yet), not from a print
void autoConnectTick() {
  static uint32_t next = 0;
  static bool failLogged = false;
  if (!link || strcmp(link->name(), "usb") != 0) return;
  if ((int32_t)(millis() - next) < 0) return;
  next = millis() + 2000;
  PrinterState st;
  lock();
  st = gState;
  unlock();
  if (st == PS_IDLE && !link->isOpen()) {  // the printer was switched off (or unplugged) while connected
    link->close();
    lock();
    gState = PS_DISCONNECTED;
    if (gInt.valid) gInt.printerKept = false;  // it lost its position and targets: a resume must home X/Y
    unlock();
    logEvent("printer: USB device gone - disconnected");
    return;
  }
  if (userDisconnected) return;
  if (st != PS_DISCONNECTED && !(st == PS_ERROR && connectFailed)) return;
  if (!link->devicePresent()) {
    failLogged = false;
    return;
  }
  String err;
  if (link->open(err)) {
    lock();
    gErr = "";
    gState = PS_IDLE;
    unlock();
    failLogged = false;
    connectFailed = false;
    logEvent("printer: connected via usb automatically (printer appeared on USB)");
  } else if (!failLogged) {
    failLogged = true;  // a device is there but will not open (yet): say so once, keep trying quietly
    logEvent("printer: auto-connect not yet: %s", err.c_str());
  }
}

void idleTick() {
  static uint32_t nextPoll = 0;
  if (!link || !link->isOpen()) return;
  PrinterState st;
  lock();
  st = gState;
  unlock();
  if (st != PS_IDLE || !g_idlePoll) return;
  if ((int32_t)(millis() - nextPoll) < 0) return;
  nextPoll = millis() + 2000;
  String reply, err;
  execGcode("M105", 2500, reply, err);  // updates temperatures via parseTemps
}

// ---- after an interrupted print ------------------------------------------------------------------
// 1. Work out where the print was (position, temperatures) from the file, for the dashboard and the resume.
// 2. After a board restart, look at the printer once it is connected. If Marlin kept running (its heater targets are
//    still set) it is still sitting on the part with a hot nozzle - on 2026-09-29 that glued the nozzle into the part.
//    Lift the nozzle and switch the hotend off; keep the bed warm for a while so the part stays stuck for a resume.
// 3. Switch the bed off too if nobody resumed within PARK_BED_KEEP_MS.
void interruptedTick() {
  lock();
  Interrupted in = gInt;
  PrinterState st = gState;
  unlock();
  if (!in.valid) return;

  // The park below comes first after a restart (a hot nozzle is sitting on the part); the file scan takes seconds.
  if (!in.scanned && (!in.atBoot || in.checked || millis() > 30000)) {
    LineReader *lr = new (std::nothrow) LineReader();
    if (!lr) return;
    ResumeState rs;
    String err;
    String path = String(GCODE_DIR) + "/" + in.file;
    bool ok = false;
    if (xSemaphoreTake(sdMutex, pdMS_TO_TICKS(3000)) == pdTRUE) {
      lr->f = SD_MMC.open(path, FILE_READ);
      xSemaphoreGive(sdMutex);
      ok = lr->f && scanTo(*lr, in.bytes, rs, err, false) != 0;
      if (lr->f) {
        xSemaphoreTake(sdMutex, pdMS_TO_TICKS(3000));
        lr->f.close();
        xSemaphoreGive(sdMutex);
      }
    }
    delete lr;
    lock();
    gInt.scanned = true;
    if (ok) {
      gInt.st = rs;
      gInt.haveState = true;
      gInt.x = rs.x;
      gInt.y = rs.y;
      gInt.z = rs.z;
      gInt.hotT = rs.hotT;
      gInt.bedT = rs.bedT;
    }
    unlock();
    if (ok) logEvent("printer: interrupted print '%s' at %u/%u bytes: Z%.2f, hotend %.0f, bed %.0f", in.file.c_str(), (unsigned)in.bytes, (unsigned)in.total, rs.z, rs.hotT, rs.bedT);
    else logEvent("printer: interrupted print '%s': cannot read the file (%s)", in.file.c_str(), err.c_str());
    return;
  }

  if (in.atBoot && !in.checked && st == PS_IDLE && link && link->isOpen()) {
    // Anything half-sent before the restart is still in the printer's input: end that line first.
    sendRaw("\n");
    delay(200);
    String reply, err;
    execGcode("M105", 3000, reply, err);
    bool kept;
    lock();
    kept = gHotT > 0 || gBedT > 0;
    gInt.checked = true;
    gInt.printerKept = kept;
    unlock();
    if (!kept) {
      logEvent("printer: after the restart the printer has no heater targets - it restarted too (a resume will home X/Y)");
      return;
    }
    bool ok = execGcode("G91", 5000, reply, err) && execGcode(String("G1 Z") + String(PARK_LIFT_MM, 1) + " F600", 10000, reply, err) &&
              execGcode("G90", 5000, reply, err);
    // If the board is going to continue by itself the hotend stays hot: reheating would add a minute to the pause.
    bool willAuto = ok && !in.crashLoop && in.resumes < AUTO_RESUME_MAX && (!in.haveState || in.st.hotT > 0);
    if (!willAuto) execGcode("M104 S0", 5000, reply, err);
    if (in.crashLoop) execGcode("M140 S0", 5000, reply, err);  // restarting again and again: leave nothing heating
    execGcode("M107", 5000, reply, err);
    lock();
    if (!willAuto) gHotT = 0;
    if (in.crashLoop) {
      gBedT = 0;
      gInt.bedOffDone = true;
    }
    gInt.parked = ok;
    gInt.parkedAtMs = millis();
    unlock();
    logEvent("printer: interrupted print - nozzle %s, %s", ok ? "lifted 5 mm" : "NOT lifted (move failed)",
             willAuto ? "hotend kept hot - resuming by itself" : (in.crashLoop ? "board restarts too often: ALL heaters off, not resuming" : "hotend off, bed kept warm for 30 min"));
    return;
  }

  // Continue by itself: only after a board restart (not a power loss: Marlin must still know where it is, which is
  // what printerKept + a successful park prove), once the file position is known, and at most AUTO_RESUME_MAX times.
  if (in.atBoot && in.parked && in.printerKept && in.scanned && !in.autoTried && st == PS_IDLE && link && link->isOpen()) {
    lock();
    gInt.autoTried = true;
    unlock();
    if (in.crashLoop || in.resumes >= AUTO_RESUME_MAX || in.hotT <= 0) {
      String reply, err;
      execGcode("M104 S0", 5000, reply, err);  // the park kept it hot for a resume that is not coming
      lock();
      gHotT = 0;
      unlock();
      logEvent("printer: NOT resuming by itself (%s) - waiting for the user",
               in.crashLoop ? "the board restarts too often" : (in.resumes >= AUTO_RESUME_MAX ? "already resumed too often" : "no hotend temperature in the file"));
      return;
    }
    String path = String(GCODE_DIR) + "/" + in.file;
    logEvent("printer: resuming the interrupted print by itself (resume #%u)", (unsigned)(in.resumes + 1));
    lock();
    gFile = in.file;
    gState = PS_PRINTING;
    gDry = false;
    unlock();
    gJobResumes = in.resumes + 1;
    ResumeSpec rs = {in.bytes, false, 0, in.haveState ? &in.st : nullptr};
    runPrint(path, 0, 0, 0, &rs);
    return;
  }

  if (in.parked && !in.bedOffDone && st == PS_IDLE && millis() - in.parkedAtMs > PARK_BED_KEEP_MS && link && link->isOpen()) {
    String reply, err;
    execGcode("M140 S0", 5000, reply, err);
    lock();
    gInt.bedOffDone = true;
    gBedT = 0;
    unlock();
    logEvent("printer: interrupted print not resumed within %u min - bed off", (unsigned)(PARK_BED_KEEP_MS / 60000));
  }
}

// ---- command handling --------------------------------------------------------------------------
void finishCmd(Cmd *c) {
  portENTER_CRITICAL(&cmdMux);
  bool ab = c->abandoned;
  portEXIT_CRITICAL(&cmdMux);
  if (ab) delete c;
  else xSemaphoreGive(c->done);
}

void applyLinkChoice(const String &kind, String &err) {
  if (link) {
    link->close();
    delete link;
    link = nullptr;
  }
  if (kind == "sim") link = makeSimLink();
  else if (kind == "usb") link = makeUsbLink();
  else if (kind != "none") {
    err = "unknown link kind (use sim, usb or none)";
    return;
  }
  lock();
  gLinkName = link ? link->name() : "none";
  gState = link ? PS_DISCONNECTED : PS_NO_LINK;
  gErr = "";
  gFw = "";
  gHot = gHotT = gBed = gBedT = 0;
  unlock();
  logEvent("printer: link set to %s", gLinkName.c_str());
}

void handleCmd(Cmd *c) {
  PrinterState st;
  lock();
  st = gState;
  unlock();
  switch (c->type) {
    case C_SELECT: {
      if (st == PS_PRINTING || st == PS_PAUSED) {
        c->err = "cannot change the link while printing";
        break;
      }
      applyLinkChoice(c->arg, c->err);
      c->ok = c->err.length() == 0;
      userDisconnected = false;
      break;
    }
    case C_CONNECT: {
      if (!link) {
        c->err = "no link selected";
        break;
      }
      if (st == PS_PRINTING || st == PS_PAUSED) {
        c->ok = true;  // already connected and busy
        break;
      }
      userDisconnected = false;
      if (!link->isOpen() && !link->open(c->err)) {
        setError(c->err);
        connectFailed = true;  // auto-connect keeps trying: the printer may simply not be powered yet
        break;
      }
      connectFailed = false;
      lock();
      gErr = "";
      gState = PS_IDLE;
      unlock();
      logEvent("printer: connected via %s", link->name());
      c->ok = true;
      break;
    }
    case C_DISCONNECT: {
      if (st == PS_PRINTING || st == PS_PAUSED) {
        c->err = "printer is printing; stop the print first";
        break;
      }
      // Only a disconnect of a live link is the user's choice; a client tidying up after the link was already lost
      // must not switch off the automatic reconnect.
      if (link && link->isOpen() && st == PS_IDLE) userDisconnected = true;
      if (link) link->close();
      lock();
      gState = link ? PS_DISCONNECTED : PS_NO_LINK;
      unlock();
      c->ok = true;
      break;
    }
    case C_GCODE: {
      if (st == PS_PRINTING || st == PS_PAUSED) {
        c->err = "printer is busy printing";
        break;
      }
      if (!gcodeAllowed(c->arg)) {
        c->err = "that command is blocked for safety";
        break;
      }
      c->ok = execGcode(c->arg, c->timeoutMs, c->reply, c->err);
      if (c->ok && c->arg.startsWith("M115")) {
        int i = c->reply.indexOf("FIRMWARE_NAME");
        if (i >= 0) {
          int e = c->reply.indexOf('\n', i);
          lock();
          gFw = c->reply.substring(i, e < 0 ? c->reply.length() : e);
          unlock();
        }
      }
      break;
    }
    case C_START: {
      if (st != PS_IDLE || !link || !link->isOpen()) {
        c->err = st == PS_PRINTING || st == PS_PAUSED ? "already printing" : "printer not connected";
        break;
      }
      String path = String(GCODE_DIR) + "/" + c->arg;
      bool exists = false;
      if (xSemaphoreTake(sdMutex, pdMS_TO_TICKS(3000)) == pdTRUE) {
        exists = SD_MMC.exists(path);
        xSemaphoreGive(sdMutex);
      }
      if (!exists) {
        c->err = "no such file on the SD card";
        break;
      }
      lock();
      gFile = c->arg;
      gState = PS_PRINTING;
      gDry = c->timeoutMs > 0;
      unlock();
      c->ok = true;
      uint32_t dry = c->timeoutMs;  // rehearsal length, read before the command is released
      uint32_t skip = c->arg2;      // rehearsal skip target, same
      uint32_t badEvery = c->arg3;  // rehearsal resend-recovery test, same
      finishCmd(c);  // answer the HTTP request now; the print itself runs below
      runPrint(path, dry, skip, badEvery);
      return;
    }
    case C_RECOVER: {
      if (st != PS_IDLE || !link || !link->isOpen()) {
        c->err = st == PS_PRINTING || st == PS_PAUSED ? "already printing" : "printer not connected";
        break;
      }
      Interrupted in;
      lock();
      in = gInt;
      unlock();
      if (!in.valid) {
        c->err = "there is no interrupted print to resume";
        break;
      }
      uint32_t offset = c->arg2 ? c->arg2 : in.bytes;
      bool cold = c->arg3 == 2 ? true : (c->arg3 == 1 ? false : !in.printerKept);
      float zNow = c->argF;
      if (cold && zNow <= 0) {
        if (in.parked && in.scanned) {
          zNow = in.z + PARK_LIFT_MM;  // lifted by the board before the printer was switched off
        } else {
          c->err = "the printer restarted since the print stopped, so its Z position is unknown: raise the nozzle clear of the part "
                   "without homing and give its height as zNow (mm)";
          break;
        }
      }
      String path = String(GCODE_DIR) + "/" + in.file;
      bool exists = false;
      if (xSemaphoreTake(sdMutex, pdMS_TO_TICKS(3000)) == pdTRUE) {
        exists = SD_MMC.exists(path);
        xSemaphoreGive(sdMutex);
      }
      if (!exists) {
        c->err = "the interrupted file is no longer on the SD card";
        break;
      }
      lock();
      gFile = in.file;
      gState = PS_PRINTING;
      gDry = false;
      unlock();
      c->ok = true;
      finishCmd(c);
      gJobResumes = in.resumes + 1;
      ResumeSpec rs = {offset, cold, zNow, in.haveState && offset == in.bytes ? &in.st : nullptr};
      runPrint(path, 0, 0, 0, &rs);
      return;
    }
    case C_DISCARD: {
      if (st == PS_PRINTING || st == PS_PAUSED) {
        c->err = "printer is busy printing";
        break;
      }
      Interrupted in;
      lock();
      in = gInt;
      gInt = Interrupted();
      unlock();
      if (in.valid) {
        journalWrite(false, in.file, in.bytes, in.total);
        if (in.parked && !in.bedOffDone && link && link->isOpen()) {
          String reply, err;
          execGcode("M140 S0", 5000, reply, err);
        }
        logEvent("printer: interrupted print '%s' discarded", in.file.c_str());
      }
      c->ok = true;
      break;
    }
    default:
      c->err = "no print in progress";
      break;
  }
  finishCmd(c);
}

const char *resetWhy() {
  switch (esp_reset_reason()) {
    case ESP_RST_PANIC: return "crash";
    case ESP_RST_POWERON: return "power loss";
    case ESP_RST_BROWNOUT: return "supply voltage dropped";
    case ESP_RST_INT_WDT: case ESP_RST_TASK_WDT: case ESP_RST_WDT: return "watchdog";
    case ESP_RST_SW: return "software restart";
    default: return "restart";
  }
}

void engineTask(void *) {
  {
    String err;
    applyLinkChoice("usb", err);  // the printer is always on USB: be ready to connect as soon as it appears
  }
  {
    String f;
    uint32_t b = 0, t = 0, n = 0;
    ResumeState js;
    bool haveState = false;
    if (journalRead(f, b, t, n, js, haveState)) {
      lock();
      gInt = Interrupted();
      gInt.valid = true;
      gInt.atBoot = true;
      gInt.file = f;
      gInt.bytes = b;
      gInt.total = t;
      gInt.resumes = n;
      gInt.crashLoop = gPrevUptimeSec < CRASH_LOOP_SEC && esp_reset_reason() != ESP_RST_POWERON && esp_reset_reason() != ESP_RST_BROWNOUT;
      if (haveState) {  // no file scan needed: position and temperatures are in the journal
        gInt.st = js;
        gInt.haveState = true;
        gInt.scanned = true;
        gInt.x = js.x;
        gInt.y = js.y;
        gInt.z = js.z;
        gInt.hotT = js.hotT;
        gInt.bedT = js.bedT;
      }
      gInt.reason = String("the board restarted during the print (") + resetWhy() + ")";
      unlock();
      logEvent("printer: INTERRUPTED print found: '%s' at %u of %u bytes (%s)", f.c_str(), (unsigned)b, (unsigned)t, resetWhy());
      if (gInt.crashLoop)
        logEvent("printer: the board had been up only %us before it went down - no automatic resume, heaters will be switched off", (unsigned)gPrevUptimeSec);
    }
  }
  for (;;) {
    Cmd *c;
    if (xQueueReceive(cmdQ, &c, pdMS_TO_TICKS(100)) == pdTRUE) handleCmd(c);
    beat();
    if (link) link->tick();
    idleTick();
    autoConnectTick();
    interruptedTick();
  }
}

// Posts a command and waits for the engine's answer.
bool post(CmdType type, const String &arg, uint32_t timeoutMs, String *reply, String &err, uint32_t waitMs, uint32_t arg2 = 0,
          uint32_t arg3 = 0, float argF = 0) {
  Cmd *c = new Cmd();
  c->type = type;
  c->arg = arg;
  c->timeoutMs = timeoutMs;
  c->arg2 = arg2;
  c->arg3 = arg3;
  c->argF = argF;
  c->done = xSemaphoreCreateBinary();
  if (xQueueSend(cmdQ, &c, pdMS_TO_TICKS(1000)) != pdTRUE) {
    vSemaphoreDelete(c->done);
    delete c;
    err = "engine queue full";
    return false;
  }
  if (xSemaphoreTake(c->done, pdMS_TO_TICKS(waitMs)) != pdTRUE) {
    portENTER_CRITICAL(&cmdMux);
    c->abandoned = true;
    portEXIT_CRITICAL(&cmdMux);
    err = "engine did not answer in time";
    return false;
  }
  bool ok = c->ok;
  err = c->err;
  if (reply) *reply = c->reply;
  vSemaphoreDelete(c->done);
  delete c;
  return ok;
}

}  // namespace

// ------------------------------------------------------------------------------------------------
// Public API
// ------------------------------------------------------------------------------------------------
void printerBegin() {
  {
    esp_reset_reason_t rr = esp_reset_reason();
    bool valid = rtcBeat.magic == 0x50484254 && rr != ESP_RST_POWERON && rr != ESP_RST_BROWNOUT;
    gPrevUptimeSec = valid ? rtcBeat.uptimeSec : 0;
    rtcBeat.magic = 0x50484254;
    rtcBeat.uptimeSec = 0;
  }
  snprintf(gBootId, sizeof(gBootId), "%08x", (unsigned)esp_random());
  stMtx = xSemaphoreCreateMutex();
  cmdQ = xQueueCreate(8, sizeof(Cmd *));
  xTaskCreatePinnedToCore(engineTask, "printer", 14336, nullptr, 6, nullptr, 1);  // stack: the print loop keeps several KB of buffers
}

bool printerSelectLink(const String &kind, String &err) { return post(C_SELECT, kind, 0, nullptr, err, 5000); }
bool printerConnect(String &err) { return post(C_CONNECT, "", 0, nullptr, err, 20000); }
bool printerDisconnect(String &err) { return post(C_DISCONNECT, "", 0, nullptr, err, 5000); }
bool printerStartPrint(const String &file, String &err, uint32_t dryLines, uint32_t skipLines, uint32_t badEvery) {
  if (!fileNameOk(file)) {
    err = "bad file name";
    return false;
  }
  return post(C_START, file, dryLines, nullptr, err, 8000, skipLines, badEvery);
}
bool printerPause(String &err) { return post(C_PAUSE, "", 0, nullptr, err, 5000); }
bool printerResume(String &err) { return post(C_RESUME, "", 0, nullptr, err, 5000); }
bool printerStop(String &err) { return post(C_STOP, "", 0, nullptr, err, 8000); }
bool printerRecover(uint32_t offset, int mode, float zNow, String &err) { return post(C_RECOVER, "", 0, nullptr, err, 8000, offset, (uint32_t)mode, zNow); }
bool printerDiscardInterrupted(String &err) { return post(C_DISCARD, "", 0, nullptr, err, 8000); }
const char *printerBootId() { return gBootId; }
bool printerGcode(const String &cmd, uint32_t timeoutMs, String &reply, String &err) {
  if (cmd.length() == 0 || cmd.length() > 200) {
    err = "bad command length";
    return false;
  }
  if (timeoutMs < 500) timeoutMs = 500;
  if (timeoutMs > 600000) timeoutMs = 600000;
  return post(C_GCODE, cmd, timeoutMs, &reply, err, timeoutMs + 8000);
}

bool printerFileInUse(const String &name) {
  lock();
  bool busy = ((gState == PS_PRINTING || gState == PS_PAUSED) && gFile.equalsIgnoreCase(name)) || (gInt.valid && gInt.file.equalsIgnoreCase(name));
  unlock();
  return busy;
}

String printerStatusJson() {
  lock();
  const char *stName = "NO_LINK";
  switch (gState) {
    case PS_DISCONNECTED: stName = "DISCONNECTED"; break;
    case PS_IDLE: stName = "IDLE"; break;
    case PS_PRINTING: stName = "PRINTING"; break;
    case PS_PAUSED: stName = "PAUSED"; break;
    case PS_ERROR: stName = "ERROR"; break;
    default: break;
  }
  uint32_t now = millis();
  uint32_t elapsed = 0;
  if (gStartMs) {
    uint32_t end = gState == PS_PRINTING ? now : (gState == PS_PAUSED ? gPausedAtMs : (gEndMs ? gEndMs : now));
    elapsed = end - gStartMs - gPausedTotalMs;
  }
  String fw = gFw, file = gFile, err = gErr, ln = gLinkName;
  fw.replace("\"", "'");
  err.replace("\"", "'");
  file.replace("\"", "'");
  char head[96];
  snprintf(head, sizeof(head), "{\"link\":\"%s\",\"state\":\"%s\",", ln.c_str(), stName);
  String out = head;
  out += "\"file\":\"" + file + "\",\"bytesDone\":" + String(gBytesDone) + ",\"bytesTotal\":" + String(gBytesTotal) +
         ",\"lines\":" + String(gLines) + ",\"elapsedMs\":" + String(elapsed) + ",\"finished\":" + String(gFinished);
  char temps[140];
  snprintf(temps, sizeof(temps), ",\"hotend\":%.1f,\"hotendTarget\":%.1f,\"bed\":%.1f,\"bedTarget\":%.1f", gHot, gHotT, gBed, gBedT);
  out += temps;
  char st[256];
  snprintf(st, sizeof(st), ",\"window\":%d,\"depth\":%.1f,\"lps\":%.1f,\"ackAvgMs\":%.1f,\"ackMaxMs\":%.1f,\"gapAvgMs\":%.1f,\"gapMaxMs\":%.1f,\"txBps\":%u,\"rxBps\":%u,\"resends\":%u",
           (int)g_window, gDepth, gLps, gRttAvgUs / 1000.0f, gRttMaxUs / 1000.0f, gGapAvgUs / 1000.0f, gGapMaxUs / 1000.0f, (unsigned)gTxBps, (unsigned)gRxBps,
           (unsigned)gResendsTotal);
  out += st;
  String lastEnd = gLastEnd;
  lastEnd.replace("\"", "'");
  out += ",\"fw\":\"" + fw + "\",\"error\":\"" + err + "\",\"dry\":" + (gDry ? "true" : "false") + ",\"bootId\":\"" + gBootId + "\",\"lastEnd\":\"" +
         lastEnd + "\"";
  if (gInt.valid) {
    String f = gInt.file, why = gInt.reason;
    f.replace("\"", "'");
    why.replace("\"", "'");
    char nums[300];
    snprintf(nums, sizeof(nums),
             "\"bytes\":%u,\"total\":%u,\"atBoot\":%s,\"scanned\":%s,\"x\":%.2f,\"y\":%.2f,\"z\":%.2f,\"hotendTarget\":%.0f,\"bedTarget\":%.0f,"
             "\"checked\":%s,\"printerKept\":%s,\"parked\":%s,\"bedOff\":%s,\"resumes\":%u,\"autoResumeTried\":%s,\"crashLoop\":%s",
             (unsigned)gInt.bytes, (unsigned)gInt.total, gInt.atBoot ? "true" : "false", gInt.scanned ? "true" : "false", gInt.x, gInt.y, gInt.z,
             gInt.hotT, gInt.bedT, gInt.checked ? "true" : "false", gInt.printerKept ? "true" : "false", gInt.parked ? "true" : "false",
             gInt.bedOffDone ? "true" : "false", (unsigned)gInt.resumes, gInt.autoTried ? "true" : "false", gInt.crashLoop ? "true" : "false");
    out += ",\"interrupted\":{\"file\":\"" + f + "\",\"reason\":\"" + why + "\"," + nums + "}";
  } else {
    out += ",\"interrupted\":null";
  }
  out += ",\"crash\":\"" + crashReport() + "\"}";
  unlock();
  return out;
}

void printerSetWindow(int n) { g_window = n < 1 ? 1 : (n > 6 ? 6 : n); }
int printerGetWindow() { return g_window; }

void printerSnapshot(PrinterSnap &s) {
  lock();
  s.state = (uint8_t)gState;
  s.line = gLines;
  s.bytesDone = gBytesDone;
  s.bytesTotal = gBytesTotal;
  s.lps = gLps;
  s.ackAvgUs = gRttAvgUs;
  s.ackMaxUs = gRttMaxUs;
  s.gapAvgUs = gGapAvgUs;
  s.gapMaxUs = gGapMaxUs;
  s.txBps = gTxBps;
  s.rxBps = gRxBps;
  s.resends = gResendsTotal;
  s.hot = gHot;
  s.hotT = gHotT;
  s.bed = gBed;
  s.bedT = gBedT;
  unlock();
}
