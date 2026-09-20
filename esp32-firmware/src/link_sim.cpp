// A pretend Marlin on the other end of the wire, so the print engine can be exercised end to end (real
// gcode file, numbering, checksums, ok/resend, temperatures) without a printer attached.
// It enforces the same rules Marlin does: line numbers must be consecutive and checksums must match.
#include <Arduino.h>

#include "common.h"
#include "printer.h"

static int simMotionPerSec = 600;   // how many G0/G1 lines it "executes" per second
static int simResendEvery = 0;      // inject a checksum error every N numbered lines (0 = never)

bool printerSimTune(int linesPerSec, int resendEvery, String &err) {
  if (linesPerSec < 10 || linesPerSec > 50000 || resendEvery < 0) {
    err = "bad values";
    return false;
  }
  simMotionPerSec = linesPerSec;
  simResendEvery = resendEvery;
  return true;
}

namespace {

class SimLink : public PrinterLink {
 public:
  const char *name() override { return "sim"; }
  bool open(String &) override {
    opened = true;
    inlen = 0;
    qh = qt = 0;
    lastN = 0;
    lastAt = millis();
    return true;
  }
  void close() override { opened = false; }
  bool isOpen() override { return opened; }
  void flushInput() override { qh = qt = 0; }

  bool writeBytes(const uint8_t *d, size_t n) override {
    if (!opened) return false;
    for (size_t i = 0; i < n; i++) {
      char c = (char)d[i];
      if (c == '\n') {
        inbuf[inlen] = 0;
        processLine(inbuf);
        inlen = 0;
      } else if (c != '\r' && inlen + 1 < sizeof(inbuf)) {
        inbuf[inlen++] = c;
      }
    }
    return true;
  }

  int readLine(char *buf, size_t cap, uint32_t timeoutMs) override {
    uint32_t t0 = millis();
    for (;;) {
      if (qh != qt && (int32_t)(millis() - q[qh].at) >= 0) {
        strlcpy(buf, q[qh].text, cap);
        qh = (qh + 1) % QN;
        return strlen(buf);
      }
      if (millis() - t0 >= timeoutMs) return -1;
      delay(1);
    }
  }

 private:
  static const int QN = 64;
  struct Resp {
    char text[160];
    uint32_t at;
  };
  bool opened = false;
  char inbuf[300];
  size_t inlen = 0;
  Resp q[QN];
  int qh = 0, qt = 0;
  uint32_t lastAt = 0;
  uint32_t lastN = 0, numbered = 0;
  float hot = 24, bed = 24, hotT = 0, bedT = 0;
  uint32_t lastStep = 0;

  void push(const char *text, uint32_t delayMs = 0) {
    uint32_t now = millis();
    uint32_t at = (int32_t)(lastAt - now) > 0 ? lastAt : now;
    at += delayMs;
    lastAt = at;
    int next = (qt + 1) % QN;
    if (next == qh) return;  // full: drop (the engine drains far faster than this fills)
    strlcpy(q[qt].text, text, sizeof(q[qt].text));
    q[qt].at = at;
    qt = next;
  }

  void step() {
    uint32_t now = millis();
    float dt = lastStep ? (now - lastStep) / 1000.0f : 0;
    lastStep = now;
    auto go = [&](float cur, float tgt, float rate, float cool) {
      float want = tgt > 0 ? tgt : 24;
      float r = (tgt > 0 ? rate : cool) * dt;
      if (fabsf(want - cur) <= r) return want;
      return cur + (want > cur ? r : -r);
    };
    hot = go(hot, hotT, 10.0f, 1.5f);
    bed = go(bed, bedT, 3.0f, 0.5f);
  }

  void tempLine(char *out, size_t n, bool withOk) {
    snprintf(out, n, "%sT:%.1f /%.1f B:%.1f /%.1f @:0 B@:0", withOk ? "ok " : "", hot, hotT, bed, bedT);
  }

  void resendError(const char *why) {
    char e[120];
    snprintf(e, sizeof(e), "Error:%s, Last Line: %u", why, (unsigned)lastN);
    push(e);
    char r[40];
    snprintf(r, sizeof(r), "Resend: %u", (unsigned)(lastN + 1));
    push(r);
    // Real firmware differs: some send "ok" after a Resend, some don't. Alternate to exercise both.
    if ((++numbered & 1) == 0) push("ok");
  }

  void processLine(char *line) {
    while (*line == ' ') line++;
    if (!*line) return;
    char *cmd = line;
    bool hasN = false;
    uint32_t n = 0;
    if (line[0] == 'N') {
      hasN = true;
      char *e;
      n = strtoul(line + 1, &e, 10);
      char *star = strrchr(line, '*');
      if (!star) {
        resendError("No Checksum with line number");
        return;
      }
      uint8_t cs = 0;
      for (char *p = line; p < star; p++) cs ^= (uint8_t)*p;
      if ((unsigned)atoi(star + 1) != cs) {
        resendError("checksum mismatch");
        return;
      }
      *star = 0;
      cmd = e;
      while (*cmd == ' ') cmd++;
    }
    bool isM110 = strncmp(cmd, "M110", 4) == 0;
    if (hasN && !isM110) {
      if (simResendEvery > 0 && (++injectCount % (uint32_t)simResendEvery) == 0) {
        resendError("checksum mismatch");
        return;
      }
      if (n != lastN + 1) {
        resendError("Line Number is not Last Line Number+1");
        return;
      }
      lastN = n;
    } else if (hasN && isM110) {
      lastN = n;
    }
    execute(cmd);
  }

  uint32_t injectCount = 0;

  void execute(char *cmd) {
    step();
    char up[12] = {0};
    for (int i = 0; i < 11 && cmd[i] && cmd[i] != ' '; i++) up[i] = toupper((unsigned char)cmd[i]);
    char out[160];
    if (!strcmp(up, "M105")) {
      tempLine(out, sizeof(out), true);
      push(out);
    } else if (!strcmp(up, "M115")) {
      push("FIRMWARE_NAME:Marlin SIM (PrintHost ESP32 simulator) SOURCE_CODE_URL:none PROTOCOL_VERSION:1.0 MACHINE_TYPE:Simulator EXTRUDER_COUNT:1");
      push("Cap:EEPROM:1");
      push("ok");
    } else if (!strcmp(up, "M851")) {
      push("echo:Probe Z Offset: Z-0.050");
      push("ok");
    } else if (!strcmp(up, "M104") || !strcmp(up, "M140") || !strcmp(up, "M109") || !strcmp(up, "M190")) {
      const char *s = strchr(cmd, 'S');
      float t = s ? atof(s + 1) : 0;
      // M104/M109 = hotend, M140/M190 = bed
      bool hotend = (!strcmp(up, "M104") || !strcmp(up, "M109"));
      if (hotend) hotT = t;
      else bedT = t;
      if (!strcmp(up, "M109") || !strcmp(up, "M190")) {
        // wait for the target, reporting temperatures each second like Marlin does
        float cur = hotend ? hot : bed, rate = hotend ? 10.0f : 3.0f;
        int secs = t > cur ? (int)((t - cur) / rate) + 1 : 0;
        if (secs > 30) secs = 30;
        for (int i = 1; i <= secs; i++) {
          if (hotend) hot = min(t, cur + rate * i);
          else bed = min(t, cur + rate * i);
          tempLine(out, sizeof(out), false);
          push(out, 1000);
        }
        if (hotend) hot = t;
        else bed = t;
      }
      push("ok");
    } else if (up[0] == 'G' && (up[1] == '0' || up[1] == '1' || up[1] == '2' || up[1] == '3') && up[2] == 0) {
      push("ok", 1000 / simMotionPerSec);
    } else if (!strcmp(up, "G28")) {
      push("ok", 3000);
    } else if (!strcmp(up, "G29")) {
      push("ok", 5000);
    } else {
      push("ok");
    }
  }
};

}  // namespace

PrinterLink *makeSimLink() { return new SimLink(); }
