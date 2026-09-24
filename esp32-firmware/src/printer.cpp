#include "printer.h"

#include <SD_MMC.h>
#include <esp_timer.h>
#include <new>
#include <stdarg.h>

#include "common.h"

// ------------------------------------------------------------------------------------------------
// Engine: one FreeRTOS task owns the link. HTTP handlers post commands to it and wait for the answer,
// so nothing else ever touches the serial line.
// ------------------------------------------------------------------------------------------------
namespace {

enum CmdType { C_CONNECT, C_DISCONNECT, C_START, C_PAUSE, C_RESUME, C_STOP, C_GCODE, C_SELECT };

struct Cmd {
  CmdType type;
  String arg;
  uint32_t timeoutMs = 0;
  uint32_t arg2 = 0;  // C_START only: rehearsal skip target (file line number)
  uint32_t arg3 = 0;  // C_START only: rehearsal resend-recovery test - corrupt every Nth sent line
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

void lock() { xSemaphoreTake(stMtx, portMAX_DELAY); }
void unlock() { xSemaphoreGive(stMtx); }

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
  } hist[HIST];
  uint32_t lineNo = 0;  // last numbered line sent

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
    lock();
    gBytesDone = s.bytesAfter;
    gLines++;
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

void runPrint(const String &path, uint32_t dryLines, uint32_t skipLines, uint32_t badEvery) {
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
    default:
      c->err = "no print in progress";
      break;
  }
  finishCmd(c);
}

void engineTask(void *) {
  {
    String err;
    applyLinkChoice("usb", err);  // the printer is always on USB: be ready to connect as soon as it appears
  }
  for (;;) {
    Cmd *c;
    if (xQueueReceive(cmdQ, &c, pdMS_TO_TICKS(100)) == pdTRUE) handleCmd(c);
    if (link) link->tick();
    idleTick();
    autoConnectTick();
  }
}

// Posts a command and waits for the engine's answer.
bool post(CmdType type, const String &arg, uint32_t timeoutMs, String *reply, String &err, uint32_t waitMs, uint32_t arg2 = 0,
          uint32_t arg3 = 0) {
  Cmd *c = new Cmd();
  c->type = type;
  c->arg = arg;
  c->timeoutMs = timeoutMs;
  c->arg2 = arg2;
  c->arg3 = arg3;
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
  bool busy = (gState == PS_PRINTING || gState == PS_PAUSED) && gFile.equalsIgnoreCase(name);
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
  out += ",\"fw\":\"" + fw + "\",\"error\":\"" + err + "\",\"dry\":" + (gDry ? "true" : "false") + "}";
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
