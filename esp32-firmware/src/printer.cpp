#include "printer.h"

#include <SD_MMC.h>
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
int traceBudget = 0;  // >0: log the next N lines exchanged (armed by a Resend, for post-mortems)
PrinterLink *link = nullptr;      // only touched by the engine task (created via SELECT)
String pendingLinkKind;           // set by printerSelectLink, consumed by the engine

// Snapshot fields (written by the engine, read by HTTP handlers under stMtx).
PrinterState gState = PS_NO_LINK;
String gLinkName = "none", gFile, gErr, gFw;
uint32_t gBytesDone = 0, gBytesTotal = 0, gLines = 0, gFinished = 0;
uint32_t gStartMs = 0, gPausedAtMs = 0, gPausedTotalMs = 0, gEndMs = 0;
float gHot = 0, gHotT = 0, gBed = 0, gBedT = 0;

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
  if (traceBudget > 0) {
    traceBudget--;
    String t = s;
    t.trim();
    logEvent("TX %s", t.c_str());
  }
  return link && link->writeBytes((const uint8_t *)s, strlen(s));
}

// Marlin's line format: "N<number> <command>*<xor of everything before the '*'>"
void buildNumbered(uint32_t n, const char *text, char *out, size_t cap) {
  int len = snprintf(out, cap, "N%u %s", (unsigned)n, text);
  uint8_t cs = 0;
  for (int i = 0; i < len; i++) cs ^= (uint8_t)out[i];
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
// Exactly ONE command is ever in flight (a numbered gcode line, an M105 poll, or the final M400), so an
// "ok" always belongs to the command we just sent - there is no bookkeeping that can get out of step.
enum Kind { K_NONE, K_PRINT, K_POLL, K_FINISH };

struct PrintRun {
  LineReader rd;
  static const int HIST = 8;
  struct H {
    uint32_t n = 0;
    char text[200];
  } hist[HIST];
  uint32_t lineNo = 0;         // last numbered line sent
  Kind kind = K_NONE;          // what is currently waiting for its ok
  uint32_t inflightBytes = 0;  // file offset after the in-flight print line
  bool eof = false;
  uint32_t resendFrom = 0;     // 0 = none
  uint32_t swallowOkUntil = 0;
  uint32_t lastRx = 0;
  uint32_t nextPoll = 0;
};

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

bool sendHist(PrintRun &r, uint32_t n) {
  for (int i = 0; i < PrintRun::HIST; i++) {
    if (r.hist[i].n == n) {
      char out[256];
      buildNumbered(n, r.hist[i].text, out, sizeof(out));
      return sendRaw(out);
    }
  }
  return false;
}

void answerPrintCmd(Cmd *c) {
  portENTER_CRITICAL(&cmdMux);
  bool ab = c->abandoned;
  portEXIT_CRITICAL(&cmdMux);
  if (ab) delete c;
  else xSemaphoreGive(c->done);
}

// Runs one print to completion, stop or error. Returns when the engine is free again.
void runPrint(const String &path) {
  static PrintRun *rp = nullptr;
  if (!rp) rp = new PrintRun();
  PrintRun &r = *rp;
  r = PrintRun();  // reset

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
  logEvent("printer: print start '%s' (%u bytes)", path.c_str(), (unsigned)r.rd.f.size());

  char linebuf[256];
  char rx[300];
  link->flushInput();

  // Reset the printer's line counter so numbering starts from a known place.
  {
    char out[64];
    buildNumbered(0, "M110", out, sizeof(out));
    r.lastRx = millis();
    if (!sendRaw(out)) {
      setError("write to printer failed");
      goto done;
    }
    r.kind = K_PRINT;
    r.inflightBytes = 0;
    r.nextPoll = millis() + 2000;
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

    // ---- receive
    if (!link->isOpen()) {
      setError("printer disconnected (USB link lost)");
      goto done;
    }
    int n = link->readLine(rx, sizeof(rx), r.kind != K_NONE ? 15 : 3);
    if (n >= 0) {
      if (traceBudget > 0) {
        traceBudget--;
        logEvent("RX %s", rx);
      }
      r.lastRx = millis();
      parseTemps(rx);
      if (strncmp(rx, "ok", 2) == 0) {
        if (r.kind == K_NONE) {
          // the "ok" that trails a Resend, or a stray one: nothing is waiting for it
        } else if (r.kind == K_FINISH) {
          lock();
          gBytesDone = gBytesTotal;
          gFinished++;
          unlock();
          finishPrint("finished");
          setState(PS_IDLE);
          goto done;
        } else if (r.kind == K_PRINT) {
          lock();
          gBytesDone = r.inflightBytes;
          gLines++;
          unlock();
          r.kind = K_NONE;
        } else {
          r.kind = K_NONE;  // poll answered
        }
      } else if (strstr(rx, "Resend:")) {
        const char *p = strstr(rx, "Resend:");
        uint32_t nn = (uint32_t)strtoul(p + 7, nullptr, 10);
        if (nn > 0 && r.kind == K_PRINT) {
          logEvent("printer: resend requested from line %u (sent up to %u)", (unsigned)nn, (unsigned)r.lineNo);
          traceBudget = 6;
          r.resendFrom = nn;
          r.kind = K_NONE;
          r.swallowOkUntil = millis() + 250;  // some firmware follows Resend with an "ok", some don't
        }
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
      }
    }

    // A trailing "ok" after a Resend must not be mistaken for the answer to the line we resend next:
    // hold the resend until that window has passed.
    bool swallowing = (int32_t)(millis() - r.swallowOkUntil) < 0;

    PrinterState st;
    lock();
    st = gState;
    unlock();

    if (r.kind == K_NONE && !swallowing) {
      // ---- temperature poll first (also while paused); it takes one round trip
      if ((int32_t)(millis() - r.nextPoll) >= 0) {
        r.nextPoll = millis() + 2000;
        if (sendRaw("M105\n")) {
          r.kind = K_POLL;
          r.lastRx = millis();
        }
      } else if (st == PS_PRINTING) {
        // ---- feed: a re-send, the next gcode line, or the end of the file
        if (r.resendFrom) {
          if (!sendHist(r, r.resendFrom)) {
            safeShutdown();
            setError("printer asked to resend a line we no longer have");
            goto done;
          }
          r.kind = K_PRINT;
          r.lastRx = millis();
          r.resendFrom = r.resendFrom < r.lineNo ? r.resendFrom + 1 : 0;
        } else if (!r.eof) {
          uint32_t after = 0;
          int got = r.rd.next(linebuf, sizeof(linebuf), after);
          if (got == 1) {
            r.lineNo++;
            PrintRun::H &h = r.hist[r.lineNo % PrintRun::HIST];
            h.n = r.lineNo;
            strlcpy(h.text, linebuf, sizeof(h.text));
            char out[256];
            buildNumbered(r.lineNo, h.text, out, sizeof(out));
            if (!sendRaw(out)) {
              safeShutdown();
              setError("write to printer failed");
              goto done;
            }
            r.kind = K_PRINT;
            r.inflightBytes = after;
            r.lastRx = millis();
          } else if (got == 0) {
            r.eof = true;
          } else if (got == -2) {
            safeShutdown();
            setError("gcode line too long to send");
            goto done;
          }
          // got == -1: SD busy, try again next turn
        } else if (sendRaw("M400\n")) {
          // every line is acknowledged: let the printer finish its queued moves
          r.kind = K_FINISH;
          r.lastRx = millis();
        }
      }
    }

    // ---- silence watchdog (Marlin reports temperatures every second while it waits to heat, so real
    // silence means the printer or the link is dead)
    if (r.kind != K_NONE && millis() - r.lastRx > 180000) {
      safeShutdown();
      setError("printer stopped responding");
      goto done;
    }
  }

done:
  if (r.rd.f) {
    xSemaphoreTake(sdMutex, pdMS_TO_TICKS(3000));
    r.rd.f.close();
    xSemaphoreGive(sdMutex);
  }
}

// ---- idle tick ---------------------------------------------------------------------------------
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
      if (!link->isOpen() && !link->open(c->err)) {
        setError(c->err);
        break;
      }
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
      unlock();
      c->ok = true;
      finishCmd(c);  // answer the HTTP request now; the print itself runs below
      runPrint(path);
      return;
    }
    default:
      c->err = "no print in progress";
      break;
  }
  finishCmd(c);
}

void engineTask(void *) {
  for (;;) {
    Cmd *c;
    if (xQueueReceive(cmdQ, &c, pdMS_TO_TICKS(100)) == pdTRUE) handleCmd(c);
    if (link) link->tick();
    idleTick();
  }
}

// Posts a command and waits for the engine's answer.
bool post(CmdType type, const String &arg, uint32_t timeoutMs, String *reply, String &err, uint32_t waitMs) {
  Cmd *c = new Cmd();
  c->type = type;
  c->arg = arg;
  c->timeoutMs = timeoutMs;
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
  xTaskCreatePinnedToCore(engineTask, "printer", 10240, nullptr, 6, nullptr, 1);
}

bool printerSelectLink(const String &kind, String &err) { return post(C_SELECT, kind, 0, nullptr, err, 5000); }
bool printerConnect(String &err) { return post(C_CONNECT, "", 0, nullptr, err, 20000); }
bool printerDisconnect(String &err) { return post(C_DISCONNECT, "", 0, nullptr, err, 5000); }
bool printerStartPrint(const String &file, String &err) {
  if (!fileNameOk(file)) {
    err = "bad file name";
    return false;
  }
  return post(C_START, file, 0, nullptr, err, 8000);
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
  out += ",\"fw\":\"" + fw + "\",\"error\":\"" + err + "\"}";
  unlock();
  return out;
}

extern volatile bool g_usbLookIn, g_usbGate;
void printerUsbTune(int idlePoll, int lookIn, int gate) {
  if (idlePoll >= 0) g_idlePoll = idlePoll != 0;
  if (lookIn >= 0) g_usbLookIn = lookIn != 0;
  if (gate >= 0) g_usbGate = gate != 0;
}
