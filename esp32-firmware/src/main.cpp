// PrintHost ESP32-S3 bring-up firmware: camera test UI + MJPEG stream + Wi-Fi provisioning.
// Port 80: test page, /capture, /control, /status, /wifi.  Port 81: /stream (MJPEG).
#include <Arduino.h>
#include <ArduinoOTA.h>
#include <ESPmDNS.h>
#include <Preferences.h>
#include <SD_MMC.h>
#include <WiFi.h>
#include "common.h"
#include "dht11.h"
#include "esp_camera.h"
#include "esp_http_server.h"
#include <driver/temp_sensor.h>
#include <esp_crc.h>
#include <esp_heap_caps.h>
#include <esp_log.h>
#include <esp_system.h>
#include <esp_task_wdt.h>
#include <esp_timer.h>
#include <stdarg.h>
#include <esp_wifi.h>
#include <lwip/sockets.h>
#include <netinet/tcp.h>
#include "ping/ping_sock.h"
#include "printer.h"
#include "test_ui.h"
#if __has_include("secrets.h")
#include "secrets.h"
#endif
#ifndef OTA_PASS
#define OTA_PASS "change-me"
#endif

// Pin map found by the first bring-up self-test (matches ESP32-S3-EYE / Freenove layout).
#define CAM_XCLK 15
#define CAM_SIOD 4
#define CAM_SIOC 5
#define CAM_Y2 11
#define CAM_Y3 9
#define CAM_Y4 8
#define CAM_Y5 10
#define CAM_Y6 12
#define CAM_Y7 18
#define CAM_Y8 17
#define CAM_Y9 16
#define CAM_VSYNC 6
#define CAM_HREF 7
#define CAM_PCLK 13

static const char *AP_SSID = "PrintHost-CAM";
static const char *HOSTNAME = "printhost-cam";
static const uint32_t STA_TIMEOUT_MS = 15000;

static Preferences prefs;
static httpd_handle_t mainServer = nullptr;
static String sdStatus = "not tested";
static bool staMode = false;
static String staSsid, staPass;     // saved router credentials (empty: setup network only)
static bool staServicesUp = false;  // mDNS + OTA started (once, on the first router connection)

static volatile uint32_t framesServed = 0;
static volatile size_t lastFrameBytes = 0;
static float currentFps = 0;
static uint32_t fpsWindowStart = 0;
static uint32_t fpsWindowFrames = 0;
// Camera master clock: 20 MHz. 24 MHz was tried (its harmonics avoid Wi-Fi channel 1, where the 20 MHz one at
// 2420 MHz lands) but the OV3660 then delivered corrupted frames (colour bands, half-garbage pictures) - the capture
// cannot keep up. 10 MHz halves the frame rate. /control?var=xclk&val=<MHz> to experiment.
static uint32_t camXclkHz = 20000000;
static volatile int streamTos = 0;          // 1: stream packets use the Wi-Fi "video" queue (IP precedence 4 -> AC_VI)
static volatile int streamFpsCap = 0;       // >0: send at most this many frames per second

static bool initCameraWith(uint32_t xclk, framesize_t initSize, int quality, int fbCount, camera_grab_mode_t mode) {
  camera_config_t c = {};
  c.ledc_channel = LEDC_CHANNEL_0;
  c.ledc_timer = LEDC_TIMER_0;
  c.pin_d0 = CAM_Y2;
  c.pin_d1 = CAM_Y3;
  c.pin_d2 = CAM_Y4;
  c.pin_d3 = CAM_Y5;
  c.pin_d4 = CAM_Y6;
  c.pin_d5 = CAM_Y7;
  c.pin_d6 = CAM_Y8;
  c.pin_d7 = CAM_Y9;
  c.pin_xclk = CAM_XCLK;
  c.pin_pclk = CAM_PCLK;
  c.pin_vsync = CAM_VSYNC;
  c.pin_href = CAM_HREF;
  c.pin_sccb_sda = CAM_SIOD;
  c.pin_sccb_scl = CAM_SIOC;
  c.pin_pwdn = -1;
  c.pin_reset = -1;
  c.xclk_freq_hz = xclk;
  c.pixel_format = PIXFORMAT_JPEG;
  // Buffers are sized for the initial frame size, so start at the largest and shrink after init.
  c.frame_size = initSize;
  c.jpeg_quality = quality;
  c.fb_count = fbCount;
  c.fb_location = CAMERA_FB_IN_PSRAM;
  c.grab_mode = mode;
  return esp_camera_init(&c) == ESP_OK;
}

void logEvent(const char *fmt, ...);

struct Profile {
  const char *name;
  framesize_t size;
  int quality;  // esp32-camera: lower number = better picture, bigger frame
  int targetKB; // frame size the stream aims for: what the Wi-Fi link can carry at a smooth frame rate
};
static const Profile PROFILES[] = {
    {"fast", FRAMESIZE_VGA, 10, 35},
    {"balanced", FRAMESIZE_SVGA, 8, 45},
    {"sharp", FRAMESIZE_HD, 8, 60},       // 1280x720, 16:9: the top and bottom of the sensor are cropped
    {"xga", FRAMESIZE_XGA, 8, 55},        // 1024x768, 4:3: the sensor's full field of view
    {"sxga", FRAMESIZE_SXGA, 8, 70},      // 1280x960, 4:3: full field at the same horizontal sharpness as "sharp"
};

static SemaphoreHandle_t camMutex = nullptr;
static int curProfile = 0;  // "fast" (VGA 640x480) unless the saved choice says otherwise (loadProfileChoice)
// Physical mounting: set these once to match how the camera is installed (0/1).
#define CAM_VFLIP 1    // camera is mounted upside down: vflip + hmirror = 180 degree rotation
#define CAM_HMIRROR 1
static int flipV = CAM_VFLIP, mirrorH = CAM_HMIRROR;
static float chipTempC = 0;
static uint8_t cpuLoad[2] = {0, 0};  // percent per core
static volatile uint32_t lastLatencyMs = 0;
static volatile uint32_t avgGetMs = 0, avgSendMs = 0;
static volatile bool streamActive = false;
static volatile bool camEnabled = false;  // powered on at boot; the dashboard can switch it off and on at any time
static int curQuality = 8;                // live JPEG quality; the stream nudges it to hold the frame-size budget
static float emaFrameBytes = 0;
SemaphoreHandle_t sdMutex = nullptr;  // one user of the SD card at a time (log writer vs. file upload)

static void applySensorTuning() {
  sensor_t *s = esp_camera_sensor_get();
  if (!s) return;
  s->set_vflip(s, flipV);
  s->set_hmirror(s, mirrorH);
  s->set_whitebal(s, 1);
  s->set_awb_gain(s, 1);
  s->set_exposure_ctrl(s, 1);
  s->set_gain_ctrl(s, 1);
  s->set_lenc(s, 1);   // lens shading correction
  s->set_bpc(s, 1);
  s->set_wpc(s, 1);
}

// Changing resolution on a running sensor mangled the JPEG frames, so re-init the camera instead.
static bool applyProfile(int idx) {
  xSemaphoreTake(camMutex, portMAX_DELAY);
  esp_camera_deinit();
  const Profile &p = PROFILES[idx];
  bool ok = initCameraWith(camXclkHz, p.size, p.quality, 2, CAMERA_GRAB_LATEST);
  if (ok) {
    curProfile = idx;
    curQuality = p.quality;
    emaFrameBytes = 0;
    applySensorTuning();
  }
  logEvent("camera: profile %s xclk %u MHz %s", p.name, (unsigned)(camXclkHz / 1000000), ok ? "ok" : "FAILED");
  xSemaphoreGive(camMutex);
  return ok;
}

static bool initCamera() { return applyProfile(curProfile); }

// The chosen quality profile survives a reboot (NVS "cam"/"profile"); VGA ("fast") by default - the user's pick:
// on this congested 2.4 GHz link it gave ~1.5x the frame rate of XGA with fewer dips.
static void saveProfileChoice(int idx) {
  Preferences p;
  p.begin("cam", false);
  p.putString("profile", PROFILES[idx].name);
  p.end();
}

static void loadProfileChoice() {
  Preferences p;
  p.begin("cam", true);
  String name = p.getString("profile", "fast");
  p.end();
  for (int i = 0; i < (int)(sizeof(PROFILES) / sizeof(PROFILES[0])); i++)
    if (name == PROFILES[i].name) curProfile = i;
}

// Powers the camera up or down (sensor clock, DMA, JPEG). The board itself keeps running.
static void setCameraEnabled(bool on) {
  if (on == camEnabled) return;
  camEnabled = on;  // set first: a running stream sees it and lets go of the camera
  if (on) {
    initCamera();
    logEvent("camera: powered on");
  } else {
    xSemaphoreTake(camMutex, portMAX_DELAY);
    esp_camera_deinit();
    xSemaphoreGive(camMutex);
    logEvent("camera: powered off");
  }
}

// JPEG size follows the scene (a dim, noisy picture can be twice as big), and the Wi-Fi link carries a
// varying number of bytes per second. What matters for smoothness is how long a frame takes to send, so
// steer on that: trade a little sharpness to keep each frame's send time near ~15 fps, and give the
// sharpness back when the link is fast. The profile's size budget stays an upper bound on quality.
static void adaptQuality() {
  const Profile &p = PROFILES[curProfile];
  int maxQ = p.quality + 24;
  int q = curQuality;
  uint32_t send = avgSendMs;
  if (send > 90 || emaFrameBytes > p.targetKB * 1024 * 1.6f) q += 3;
  else if (send > 60 || emaFrameBytes > p.targetKB * 1024 * 1.15f) q += 1;
  else if (send < 35 && emaFrameBytes < p.targetKB * 1024 * 0.95f) q -= 1;
  if (q > maxQ) q = maxQ;
  if (q < p.quality) q = p.quality;
  if (q == curQuality) return;
  xSemaphoreTake(camMutex, portMAX_DELAY);
  sensor_t *s = esp_camera_sensor_get();
  if (s && camEnabled) {
    s->set_quality(s, q);
    curQuality = q;
  }
  xSemaphoreGive(camMutex);
}

static void probeSd() {
  SD_MMC.setPins(39, 38, 40);
  if (!SD_MMC.begin("/sdcard", true)) {
    sdStatus = "not detected";
    return;
  }
  sdStatus = String((unsigned long)(SD_MMC.cardSize() / (1024 * 1024))) + " MB";
}


// ---- Runtime diagnostics: RAM ring buffers, persisted to the SD card, served at /log ----
struct Sample {
  uint32_t t;
  float fps;
  int8_t rssi;
  uint16_t getMs, sendMs, delayMs;
  uint32_t frameB, heap, psram;
  uint8_t prof;
  int8_t tempC;
  uint8_t cpu0, cpu1;
  uint8_t pst;             // printer state (PrinterState)
  uint16_t lps10;          // lines/s x10 (last ~10 s window)
  uint16_t ackMaxMs, gapMaxMs, resends;
  int16_t hot10, bed10;    // temperatures x10
};
static const char *PRN_STATE[] = {"NO_LINK", "DISCONNECTED", "IDLE", "PRINTING", "PAUSED", "ERROR"};
static const int SAMPLE_N = 300;  // 5 minutes at 1 Hz
static const int EVENT_N = 64;
static Sample samples[SAMPLE_N];
static uint32_t sampleCount = 0;
static char events[EVENT_N][120];
static uint32_t eventCount = 0, eventsFlushed = 0;
static uint32_t wifiDrops = 0;
static volatile bool gatewayPingRestartDue = false;
// Last time anything proved that packets reach us: a gateway ping reply, a new incoming TCP connection, or a
// stream frame the viewer acknowledged. The router may drop pings to itself on a busy channel, so the ping
// alone is not enough to call the link deaf.
static volatile uint32_t lastRxProofMs = 0;
static volatile uint32_t wdPingOnlyUntilMs = 0;  // /debug/wifi?test=deaf: only the (black-holed) ping counts
static inline void rxProof() {
  if ((int32_t)(millis() - wdPingOnlyUntilMs) >= 0) lastRxProofMs = millis();
}
// Gateway ping statistics per SD log line (the watchdog ping doubles as a link-quality probe).
static volatile uint32_t gwOkWin = 0, gwLostWin = 0, gwRttSumWin = 0, gwRttMaxWin = 0;
static String resetReasonText = "?";
static portMUX_TYPE logMux = portMUX_INITIALIZER_UNLOCKED;

void logEvent(const char *fmt, ...) {
  char msg[100], line[120];
  va_list ap;
  va_start(ap, fmt);
  vsnprintf(msg, sizeof(msg), fmt, ap);
  va_end(ap);
  snprintf(line, sizeof(line), "[%lus] %s", (unsigned long)(millis() / 1000), msg);
  Serial.printf("LOG %s\n", line);
  portENTER_CRITICAL(&logMux);
  strlcpy(events[eventCount % EVENT_N], line, sizeof(events[0]));
  eventCount++;
  portEXIT_CRITICAL(&logMux);
}

static const char *resetReasonName(esp_reset_reason_t r) {
  switch (r) {
    case ESP_RST_POWERON: return "POWERON (normal power-up)";
    case ESP_RST_SW: return "SW (software restart)";
    case ESP_RST_PANIC: return "PANIC (crash)";
    case ESP_RST_INT_WDT: return "INT_WDT (interrupt watchdog)";
    case ESP_RST_TASK_WDT: return "TASK_WDT (task hung)";
    case ESP_RST_WDT: return "WDT (other watchdog)";
    case ESP_RST_BROWNOUT: return "BROWNOUT (supply voltage dropped!)";
    case ESP_RST_DEEPSLEEP: return "DEEPSLEEP";
    default: return "UNKNOWN";
  }
}

static void wifiEvent(arduino_event_id_t ev, arduino_event_info_t info) {
  switch (ev) {
    case ARDUINO_EVENT_WIFI_STA_CONNECTED: logEvent("wifi: associated ch%d", info.wifi_sta_connected.channel); break;
    case ARDUINO_EVENT_WIFI_STA_GOT_IP:
      logEvent("wifi: got IP %s", WiFi.localIP().toString().c_str());
      gatewayPingRestartDue = true;  // loop() (re)starts the watchdog ping towards the (new) gateway
      break;
    case ARDUINO_EVENT_WIFI_STA_DISCONNECTED:
      wifiDrops++;
      logEvent("wifi: DISCONNECTED reason %d (drop #%lu)", info.wifi_sta_disconnected.reason, (unsigned long)wifiDrops);
      break;
    default: break;
  }
}

// Logs live in their own folder, apart from /gcode, so they never show up in the dashboard file list.
static const char *LOG_DIR = "/logs";
// Logs: one continuous log written into numbered files (log-0001.txt, log-0002.txt ...). A file grows to 10 MB, then the
// next one starts; only the newest 5 are kept. Nothing is ever trimmed or copied, and old files are deleted only while
// no print is running, so the log never gets in the way of a print.
static const size_t LOG_FILE_MAX = 10UL * 1024 * 1024;
static const int LOG_KEEP = 5;
static int logSeq = 1;              // number of the file being written
static bool logCleanupDue = false;  // more than LOG_KEEP files exist: delete the oldest when the printer is idle

static String logPathFor(int n) {
  char b[40];
  snprintf(b, sizeof(b), "/logs/log-%04d.txt", n);
  return String(b);
}
static bool logNameOk(const String &n) {
  if (n.length() != 12 || !n.startsWith("log-") || !n.endsWith(".txt")) return false;
  for (int i = 4; i < 8; i++)
    if (n[i] < '0' || n[i] > '9') return false;
  return true;
}
static String baseName(String n) {
  int i = n.lastIndexOf('/');
  return i >= 0 ? n.substring(i + 1) : n;
}
// Highest log number on the card (0 = none) and, optionally, the lowest and the number of files. Card mutex held by the caller.
static int logScan(int *count, int *lowest) {
  int hi = 0, lo = 100000, c = 0;
  File dir = SD_MMC.open(LOG_DIR);
  if (dir && dir.isDirectory()) {
    for (File e = dir.openNextFile(); e; e = dir.openNextFile()) {
      String n = baseName(e.name());
      if (!logNameOk(n)) continue;
      c++;
      int v = n.substring(4, 8).toInt();
      if (v > hi) hi = v;
      if (v < lo) lo = v;
    }
    dir.close();
  }
  if (count) *count = c;
  if (lowest) *lowest = lo;
  return hi;
}
// Delete the oldest files beyond LOG_KEEP. Card mutex held by the caller, printer idle.
static void logCleanup() {
  for (int guard = 0; guard < 20; guard++) {
    int count = 0, lo = 0;
    logScan(&count, &lo);
    if (count <= LOG_KEEP) return;
    SD_MMC.remove(logPathFor(lo));
    logEvent("log: deleted the oldest file log-%04d.txt", lo);
  }
}


// Once a second: chip temperature (deg C) and CPU load per core (%).
static void sampleVitals() {
  static bool tsOn = false;
  if (!tsOn) {
    temp_sensor_config_t cfg = TSENS_CONFIG_DEFAULT();
    temp_sensor_set_config(cfg);
    temp_sensor_start();
    tsOn = true;
  }
  float c = 0;
  if (temp_sensor_read_celsius(&c) == ESP_OK) chipTempC = c;

  static uint32_t lastTotal = 0, lastIdle[2] = {0, 0};
  static TaskStatus_t st[48];
  uint32_t total = 0;
  UBaseType_t n = uxTaskGetSystemState(st, 48, &total);
  uint32_t idle[2] = {0, 0};
  for (UBaseType_t i = 0; i < n; i++) {
    if (!strcmp(st[i].pcTaskName, "IDLE0")) idle[0] = st[i].ulRunTimeCounter;
    else if (!strcmp(st[i].pcTaskName, "IDLE1")) idle[1] = st[i].ulRunTimeCounter;
  }
  uint32_t dTotal = total - lastTotal;
  if (lastTotal != 0 && dTotal > 0) {
    for (int core = 0; core < 2; core++) {
      uint32_t dIdle = idle[core] - lastIdle[core];
      int busy = 100 - (int)((uint64_t)dIdle * 100 / dTotal);
      cpuLoad[core] = busy < 0 ? 0 : (busy > 100 ? 100 : busy);
    }
  }
  lastTotal = total;
  lastIdle[0] = idle[0];
  lastIdle[1] = idle[1];
}

// Once a second: push a sample into the ring. Once every 10 s (and on new events): append to the SD card.
static void logTick(uint32_t nowMs) {
  Sample &s = samples[sampleCount % SAMPLE_N];
  s.t = nowMs / 1000;
  s.fps = currentFps;
  s.rssi = staMode ? WiFi.RSSI() : 0;
  s.getMs = avgGetMs;
  s.sendMs = avgSendMs;
  s.delayMs = lastLatencyMs;
  s.frameB = lastFrameBytes;
  s.heap = ESP.getFreeHeap();
  s.psram = ESP.getFreePsram();
  s.prof = curProfile;
  s.tempC = (int8_t)chipTempC;
  s.cpu0 = cpuLoad[0];
  s.cpu1 = cpuLoad[1];
  PrinterSnap ps;
  printerSnapshot(ps);
  s.pst = ps.state;
  s.lps10 = (uint16_t)(ps.lps * 10);
  s.ackMaxMs = ps.ackMaxUs / 1000 > 65535 ? 65535 : ps.ackMaxUs / 1000;
  s.gapMaxMs = ps.gapMaxUs / 1000 > 65535 ? 65535 : ps.gapMaxUs / 1000;
  s.resends = ps.resends > 65535 ? 65535 : ps.resends;
  s.hot10 = (int16_t)(ps.hot * 10);
  s.bed10 = (int16_t)(ps.bed * 10);
  sampleCount++;

  static uint32_t lastMemWarn = 0;  // running low on internal RAM: leave a trace (at most every 30 s)
  if (s.heap < 30 * 1024 && nowMs - lastMemWarn > 30000) {
    lastMemWarn = nowMs;
    logEvent("memory: only %u KB free (lowest so far %u KB)", (unsigned)(s.heap / 1024), (unsigned)(ESP.getMinFreeHeap() / 1024));
  }

  if (xSemaphoreTake(sdMutex, 0) != pdTRUE) return;  // a file upload is using the card right now
  struct Give {
    ~Give() { xSemaphoreGive(sdMutex); }
  } give;  // released on every return path below
  static uint32_t lastSd = 0, sdRetryAt = 0;
  if (sdStatus == "not detected" || sdStatus == "not tested") return;
  if (nowMs < sdRetryAt) return;
  bool newEvents = eventsFlushed < eventCount;
  bool printing = ps.state == PS_PRINTING || ps.state == PS_PAUSED;
  uint32_t sdEvery = printing ? 10000 : 60000;  // a measurement line every 10 s while printing, every minute otherwise
  if (!newEvents && nowMs - lastSd < sdEvery) return;
  lastSd = nowMs;
  static bool logDirReady = false;
  if (!logDirReady) {
    if (!SD_MMC.exists(LOG_DIR)) SD_MMC.mkdir(LOG_DIR);
    logDirReady = true;
  }
  if (logCleanupDue && !printing) {  // old files go only while no print is running
    logCleanup();
    logCleanupDue = false;
  }
  File f = SD_MMC.open(logPathFor(logSeq), FILE_APPEND);
  if (!f) {
    sdRetryAt = nowMs + 60000;  // don't hammer a missing/failed card every second
    sdStatus = "log write failed";
    return;
  }
  if (f.size() >= LOG_FILE_MAX) {  // this file is full: the next one starts (nothing is copied or trimmed)
    f.close();
    logSeq++;
    logCleanupDue = true;
    f = SD_MMC.open(logPathFor(logSeq), FILE_APPEND);
    if (!f) return;
    logEvent("log: continuing in log-%04d.txt", logSeq);
  }
  while (eventsFlushed < eventCount) {
    if (eventCount - eventsFlushed > EVENT_N) eventsFlushed = eventCount - EVENT_N;
    f.println(events[eventsFlushed % EVENT_N]);
    eventsFlushed++;
  }
  f.printf("[%lus] fps=%.1f rssi=%d temp=%dC cpu=%u/%u%% get=%u send=%u delay=%u frame=%uB heap=%u minheap=%u psram=%u prof=%s", (unsigned long)s.t, s.fps,
           s.rssi, s.tempC, s.cpu0, s.cpu1, s.getMs, s.sendMs, s.delayMs, (unsigned)s.frameB, (unsigned)s.heap, (unsigned)ESP.getMinFreeHeap(),
           (unsigned)s.psram, PROFILES[s.prof].name);
  {  // gateway ping since the previous line: replies/lost, round trip avg/max
    uint32_t ok = gwOkWin, lost = gwLostWin, sum = gwRttSumWin, mx = gwRttMaxWin;
    gwOkWin = gwLostWin = gwRttSumWin = gwRttMaxWin = 0;
    f.printf(" gw=%u/%u rtt=%u/%ums", (unsigned)ok, (unsigned)lost, (unsigned)(ok ? sum / ok : 0), (unsigned)mx);
  }
  if (ps.state >= PS_IDLE)
    f.printf(" prn=%s line=%u/%uB lps=%.1f ack=%u/%ums gap=%u/%ums tx=%uB/s rx=%uB/s resends=%u hot=%.1f/%.1f bed=%.1f/%.1f", PRN_STATE[ps.state],
             (unsigned)ps.line, (unsigned)ps.bytesDone, ps.lps, (unsigned)(ps.ackAvgUs / 1000), (unsigned)(ps.ackMaxUs / 1000),
             (unsigned)(ps.gapAvgUs / 1000), (unsigned)(ps.gapMaxUs / 1000), (unsigned)ps.txBps, (unsigned)ps.rxBps, (unsigned)ps.resends,
             ps.hot, ps.hotT, ps.bed, ps.bedT);
  f.println();
  f.close();
}

static void countFrame(size_t bytes) {
  framesServed++;
  lastFrameBytes = bytes;
  fpsWindowFrames++;
  uint32_t now = millis();
  if (now - fpsWindowStart >= 2000) {
    currentFps = fpsWindowFrames * 1000.0f / (now - fpsWindowStart);
    fpsWindowStart = now;
    fpsWindowFrames = 0;
  }
}

static esp_err_t indexHandler(httpd_req_t *req) {
  httpd_resp_set_type(req, "text/html");
  return httpd_resp_send(req, TEST_UI_HTML, HTTPD_RESP_USE_STRLEN);
}

static esp_err_t captureHandler(httpd_req_t *req) {
  if (!camEnabled) return httpd_resp_send_err(req, HTTPD_500_INTERNAL_SERVER_ERROR, "camera is off");
  xSemaphoreTake(camMutex, portMAX_DELAY);
  camera_fb_t *fb = esp_camera_fb_get();
  if (!fb) {
    xSemaphoreGive(camMutex);
    return httpd_resp_send_500(req);
  }
  httpd_resp_set_type(req, "image/jpeg");
  httpd_resp_set_hdr(req, "Content-Disposition", "inline; filename=capture.jpg");
  esp_err_t r = httpd_resp_send(req, (const char *)fb->buf, fb->len);
  esp_camera_fb_return(fb);
  xSemaphoreGive(camMutex);
  return r;
}

// Address of the client that sent this request (for the log).
static String peerIp(httpd_req_t *req) {
  struct sockaddr_storage a;
  socklen_t l = sizeof(a);
  if (getpeername(httpd_req_to_sockfd(req), (struct sockaddr *)&a, &l) != 0) return "?";
  char b[48] = "?";
  struct in_addr v4;
  if (a.ss_family == AF_INET6) {  // the HTTP server listens dual-stack: an IPv4 client shows up as ::ffff:a.b.c.d
    memcpy(&v4, (uint8_t *)&((struct sockaddr_in6 *)&a)->sin6_addr + 12, 4);
  } else {
    v4 = ((struct sockaddr_in *)&a)->sin_addr;
  }
  inet_ntop(AF_INET, &v4, b, sizeof(b));
  return String(b);
}

// ---- MJPEG stream on port 81: one viewer at a time, served by its own small task ----------------
// Not esp_http_server: its handler never returns while streaming, so a dead or stale viewer (a
// forgotten browser tab, the phone's relay after a network drop) held the only slot until the send
// timed out, and a new viewer could not even be accepted. Here a new viewer simply replaces the old
// one, TCP keepalive notices a vanished peer within ~15 s, and a stalled send gives up after 5 s.
static const int STREAM_PORT = 81;
static const uint32_t STREAM_HEAP_LOW = 20 * 1024;   // pause sending below this much free internal RAM...
static const uint32_t STREAM_HEAP_OK = 32 * 1024;    // ...and resume above this (Wi-Fi needs internal RAM to live)
static volatile uint32_t streamHeapPauses = 0;

static void ipToText(const struct sockaddr_storage &a, char *out, size_t n) {
  struct in_addr v4;
  if (a.ss_family == AF_INET6) memcpy(&v4, (uint8_t *)&((struct sockaddr_in6 *)&a)->sin6_addr + 12, 4);
  else v4 = ((struct sockaddr_in *)&a)->sin_addr;
  inet_ntop(AF_INET, &v4, out, n);
}

// Reads the request head (up to the blank line, 2 s max). True for "GET /stream".
static bool readStreamRequest(int s) {
  char buf[512];
  size_t len = 0;
  uint32_t t0 = millis();
  while (len < sizeof(buf) - 1 && millis() - t0 < 2000) {
    int n = recv(s, buf + len, sizeof(buf) - 1 - len, 0);
    if (n <= 0) {
      if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) continue;
      return false;
    }
    len += n;
    buf[len] = 0;
    if (strstr(buf, "\r\n\r\n")) break;
  }
  buf[len] = 0;
  return strncmp(buf, "GET /stream", 11) == 0;
}

static bool sendAll(int s, const void *data, size_t n) {
  const uint8_t *p = (const uint8_t *)data;
  while (n > 0) {
    int w = send(s, p, n, 0);
    if (w <= 0) return false;  // includes the 5 s SO_SNDTIMEO: the viewer stopped taking data
    p += w;
    n -= w;
  }
  return true;
}

static void configureViewer(int s) {
  int one = 1;
  setsockopt(s, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
  setsockopt(s, SOL_SOCKET, SO_KEEPALIVE, &one, sizeof(one));
  int idle = 5, intvl = 2, cnt = 5;  // a silent peer is declared dead after ~15 s
  setsockopt(s, IPPROTO_TCP, TCP_KEEPIDLE, &idle, sizeof(idle));
  setsockopt(s, IPPROTO_TCP, TCP_KEEPINTVL, &intvl, sizeof(intvl));
  setsockopt(s, IPPROTO_TCP, TCP_KEEPCNT, &cnt, sizeof(cnt));
  struct timeval rt = {0, 200000}, st = {5, 0};
  setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, &rt, sizeof(rt));
  setsockopt(s, SOL_SOCKET, SO_SNDTIMEO, &st, sizeof(st));
}

static void streamTask(void *) {
  int ls = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
  int one = 1;
  setsockopt(ls, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
  struct sockaddr_in addr = {};
  addr.sin_family = AF_INET;
  addr.sin_port = htons(STREAM_PORT);
  addr.sin_addr.s_addr = htonl(INADDR_ANY);
  if (ls < 0 || bind(ls, (struct sockaddr *)&addr, sizeof(addr)) != 0 || listen(ls, 2) != 0) {
    logEvent("stream: cannot listen on :%d", STREAM_PORT);
    vTaskDelete(nullptr);
    return;
  }
  int viewer = -1;
  char viewerIp[20] = "";
  bool heapPaused = false;
  int adaptCount = 0, misses = 0, tosApplied = -1;
  int64_t lastFrameUs = 0;
  for (;;) {
    // A new viewer? Check without waiting while someone is watching, wait a little while nobody is.
    fd_set rf;
    FD_ZERO(&rf);
    FD_SET(ls, &rf);
    struct timeval tv = {0, viewer < 0 ? 500000 : 0};
    if (select(ls + 1, &rf, nullptr, nullptr, &tv) > 0) {
      struct sockaddr_storage peer;
      socklen_t pl = sizeof(peer);
      int c = accept(ls, (struct sockaddr *)&peer, &pl);
      if (c >= 0) {
        configureViewer(c);
        char ip[20];
        ipToText(peer, ip, sizeof(ip));
        static const char HEAD[] =
            "HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace;boundary=frame\r\n"
            "Access-Control-Allow-Origin: *\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n";
        if (!readStreamRequest(c)) {
          static const char NF[] = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
          sendAll(c, NF, sizeof(NF) - 1);
          close(c);
        } else if (!camEnabled) {
          static const char OFF[] = "HTTP/1.1 503 Service Unavailable\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\ncamera is off";
          sendAll(c, OFF, sizeof(OFF) - 1);
          close(c);
        } else if (!sendAll(c, HEAD, sizeof(HEAD) - 1)) {
          close(c);
        } else {
          if (viewer >= 0) {
            logEvent("stream: viewer %s replaced by %s", viewerIp, ip);
            close(viewer);
          }
          viewer = c;
          tosApplied = -1;
          strlcpy(viewerIp, ip, sizeof(viewerIp));
          streamActive = true;
          misses = 0;
          logEvent("stream: viewer %s connected (%s)", viewerIp, PROFILES[curProfile].name);
        }
      }
    }
    if (viewer < 0) continue;

    auto dropViewer = [&](const char *why) {
      logEvent("stream: viewer %s left (%s)", viewerIp, why);
      close(viewer);
      viewer = -1;
      streamActive = false;
    };
    if (!camEnabled) {
      dropViewer("camera switched off");
      continue;
    }
    // Wi-Fi copies every outgoing packet into internal RAM; when that runs low the whole network stack
    // starves. Let it drain instead of pushing more frames.
    uint32_t freeInt = heap_caps_get_free_size(MALLOC_CAP_INTERNAL);
    if (heapPaused ? freeInt < STREAM_HEAP_OK : freeInt < STREAM_HEAP_LOW) {
      if (!heapPaused) {
        heapPaused = true;
        streamHeapPauses++;
        logEvent("stream: pausing, only %u KB internal RAM free", (unsigned)(freeInt / 1024));
      }
      vTaskDelay(pdMS_TO_TICKS(100));
      continue;
    }
    if (heapPaused) {
      heapPaused = false;
      logEvent("stream: resuming (%u KB free)", (unsigned)(freeInt / 1024));
    }
    if (tosApplied != streamTos) {
      int tos = streamTos ? (4 << 5) : 0;  // IP precedence 4 = the Wi-Fi video queue (AC_VI, still aggregated)
      setsockopt(viewer, IPPROTO_IP, IP_TOS, &tos, sizeof(tos));
      tosApplied = streamTos;
    }
    if (streamFpsCap > 0) {  // pacing: the sensor keeps the latest frame, so waiting simply skips frames
      int64_t due = lastFrameUs + 1000000LL / streamFpsCap, nowUs = esp_timer_get_time();
      if (nowUs < due) {
        vTaskDelay(pdMS_TO_TICKS((due - nowUs) / 1000 + 1));
        continue;
      }
    }

    xSemaphoreTake(camMutex, portMAX_DELAY);
    int64_t tGet0 = esp_timer_get_time();
    camera_fb_t *fb = camEnabled ? esp_camera_fb_get() : nullptr;
    int64_t tGet1 = esp_timer_get_time();
    if (!fb) {
      xSemaphoreGive(camMutex);
      if (++misses > 20) {
        misses = 0;
        dropViewer("camera returned no frames");
      } else {
        vTaskDelay(pdMS_TO_TICKS(50));
      }
      continue;
    }
    misses = 0;
    char part[96];
    size_t hlen = snprintf(part, sizeof(part), "\r\n--frame\r\nContent-Type: image/jpeg\r\nContent-Length: %u\r\n\r\n", (unsigned)fb->len);
    bool ok = sendAll(viewer, part, hlen) && sendAll(viewer, fb->buf, fb->len);
    int64_t tSend1 = esp_timer_get_time();
    size_t len = fb->len;
    int64_t capturedUs = (int64_t)fb->timestamp.tv_sec * 1000000LL + fb->timestamp.tv_usec;
    esp_camera_fb_return(fb);
    xSemaphoreGive(camMutex);
    if (!ok) {
      char why[32];
      snprintf(why, sizeof(why), "send failed, errno %d", errno);
      dropViewer(why);
      continue;
    }
    lastFrameUs = tGet0;
    rxProof();  // a whole frame went out: the viewer's ACKs are arriving
    countFrame(len);
    emaFrameBytes = emaFrameBytes == 0 ? (float)len : emaFrameBytes * 0.9f + len * 0.1f;
    if (++adaptCount >= 10) {
      adaptCount = 0;
      adaptQuality();
    }
    lastLatencyMs = (uint32_t)((esp_timer_get_time() - capturedUs) / 1000);
    avgGetMs = (avgGetMs * 7 + (uint32_t)((tGet1 - tGet0) / 1000)) / 8;
    avgSendMs = (avgSendMs * 7 + (uint32_t)((tSend1 - tGet1) / 1000)) / 8;
  }
}

static bool queryParam(httpd_req_t *req, const char *key, char *out, size_t n) {
  char q[128];
  if (httpd_req_get_url_query_str(req, q, sizeof(q)) != ESP_OK) return false;
  return httpd_query_key_value(q, key, out, n) == ESP_OK;
}

static esp_err_t controlHandler(httpd_req_t *req) {
  char var[24], val[16];
  if (!queryParam(req, "var", var, sizeof(var)) || !queryParam(req, "val", val, sizeof(val))) return httpd_resp_send_404(req);
  int v = atoi(val);
  if (!strcmp(var, "profile")) {
    for (int i = 0; i < (int)(sizeof(PROFILES) / sizeof(PROFILES[0])); i++) {
      if (!strcmp(val, PROFILES[i].name)) {
        saveProfileChoice(i);
        if (!camEnabled) {  // applied the next time the camera powers up
          curProfile = i;
          return httpd_resp_send(req, "ok", 2);
        }
        return applyProfile(i) ? httpd_resp_send(req, "ok", 2) : httpd_resp_send_500(req);
      }
    }
    return httpd_resp_send_404(req);
  }
  if (!strcmp(var, "xclk")) {
    if (v != 8 && v != 10 && v != 16 && v != 20 && v != 24) return httpd_resp_send_err(req, HTTPD_400_BAD_REQUEST, "xclk: 8, 10, 16, 20 or 24 (MHz)");
    camXclkHz = (uint32_t)v * 1000000;
    if (!camEnabled) return httpd_resp_send(req, "ok (camera is off)", HTTPD_RESP_USE_STRLEN);
    return applyProfile(curProfile) ? httpd_resp_send(req, "ok", 2) : httpd_resp_send_500(req);
  }
  if (!strcmp(var, "tos")) {
    streamTos = v ? 1 : 0;
    logEvent("stream: video priority %s", streamTos ? "on" : "off");
    return httpd_resp_send(req, "ok", 2);
  }
  if (!strcmp(var, "fpscap")) {
    streamFpsCap = v < 0 ? 0 : (v > 30 ? 30 : v);
    logEvent("stream: fps cap %d", streamFpsCap);
    return httpd_resp_send(req, "ok", 2);
  }
  int r = -1;
  xSemaphoreTake(camMutex, portMAX_DELAY);
  sensor_t *s = esp_camera_sensor_get();
  if (s && !strcmp(var, "vflip")) { flipV = v; r = s->set_vflip(s, v); }
  else if (s && !strcmp(var, "hmirror")) { mirrorH = v; r = s->set_hmirror(s, v); }
  xSemaphoreGive(camMutex);
  if (r < 0) return httpd_resp_send_500(req);
  return httpd_resp_send(req, "ok", 2);
}




// Bulk replies (log reads) make Wi-Fi copy every outgoing packet into internal RAM. Refuse to start one when
// that RAM is already short, and let the network drain between pieces instead of piling more on.
static const uint32_t BULK_HEAP_MIN = 36 * 1024;
static bool bulkHeapOk() { return heap_caps_get_free_size(MALLOC_CAP_INTERNAL) >= BULK_HEAP_MIN; }
static void bulkHeapWait() {
  for (int i = 0; i < 50 && heap_caps_get_free_size(MALLOC_CAP_INTERNAL) < STREAM_HEAP_OK; i++) vTaskDelay(pdMS_TO_TICKS(20));
}
static esp_err_t sendBusy(httpd_req_t *req) {
  httpd_resp_set_status(req, "503 Service Unavailable");
  httpd_resp_set_hdr(req, "Retry-After", "2");
  httpd_resp_set_hdr(req, "Access-Control-Allow-Origin", "*");
  return httpd_resp_send(req, "board is short of memory right now - try again in a moment", HTTPD_RESP_USE_STRLEN);
}

// GET /log: the RAM ring (events + one sample per second), streamed in small pieces so a request never needs a big buffer.
static esp_err_t logHandler(httpd_req_t *req) {
  httpd_resp_set_type(req, "text/plain");
  httpd_resp_set_hdr(req, "Access-Control-Allow-Origin", "*");
  const size_t CAP = 2048;
  char *b = (char *)heap_caps_malloc(CAP, MALLOC_CAP_SPIRAM);
  if (!b) return httpd_resp_send_500(req);
  size_t len = 0;
  esp_err_t res = ESP_OK;
  auto flush = [&]() {
    if (len && res == ESP_OK) res = httpd_resp_send_chunk(req, b, len);
    len = 0;
  };
  auto add = [&](const char *s, size_t n) {
    if (len + n > CAP) flush();
    if (n > CAP) n = CAP;
    memcpy(b + len, s, n);
    len += n;
  };
  char line[256];
  int n = snprintf(line, sizeof(line), "boot reason: %s\nuptime %lu s, wifi drops %lu, min free heap %u KB, free psram %u KB\n--- events (oldest first)\n",
                   resetReasonText.c_str(), (unsigned long)(millis() / 1000), (unsigned long)wifiDrops, (unsigned)(ESP.getMinFreeHeap() / 1024),
                   (unsigned)(ESP.getFreePsram() / 1024));
  add(line, n);
  portENTER_CRITICAL(&logMux);
  uint32_t ec = eventCount, start = ec > EVENT_N ? ec - EVENT_N : 0;
  portEXIT_CRITICAL(&logMux);
  for (uint32_t i = start; i < ec; i++) {
    n = snprintf(line, sizeof(line), "%s\n", events[i % EVENT_N]);
    add(line, n);
  }
  n = snprintf(line, sizeof(line), "--- samples: t,fps,rssi,getMs,sendMs,delayMs,frameKB,heapKB,psramKB,profile,tempC,cpu0,cpu1,prn,lps,ackMaxMs,gapMaxMs,resends,hotC,bedC\n");
  add(line, n);
  uint32_t sc = sampleCount, first = sc > SAMPLE_N ? sc - SAMPLE_N : 0;
  for (uint32_t i = first; i < sc; i++) {
    const Sample &s = samples[i % SAMPLE_N];
    n = snprintf(line, sizeof(line), "%u,%.1f,%d,%u,%u,%u,%.1f,%u,%u,%s,%d,%u,%u,%s,%.1f,%u,%u,%u,%.1f,%.1f\n", (unsigned)s.t, s.fps, s.rssi, s.getMs,
                 s.sendMs, s.delayMs, s.frameB / 1024.0f, (unsigned)(s.heap / 1024), (unsigned)(s.psram / 1024), PROFILES[s.prof].name, s.tempC, s.cpu0,
                 s.cpu1, PRN_STATE[s.pst], s.lps10 / 10.0f, s.ackMaxMs, s.gapMaxMs, s.resends, s.hot10 / 10.0f, s.bed10 / 10.0f);
    if (n > (int)sizeof(line) - 1) n = sizeof(line) - 1;
    add(line, n);
  }
  flush();
  free(b);
  if (res != ESP_OK) return res;
  return httpd_resp_send_chunk(req, nullptr, 0);
}

static esp_err_t sendJsonStatus(httpd_req_t *req, const char *status, const String &json);

// GET /logs: the numbered log files on the SD card, newest first.
static esp_err_t logsListHandler(httpd_req_t *req) {
  String names[12];
  size_t sizes[12];
  int cnt = 0;
  if (xSemaphoreTake(sdMutex, pdMS_TO_TICKS(1500)) == pdTRUE) {
    File dir = SD_MMC.open(LOG_DIR);
    if (dir && dir.isDirectory()) {
      for (File e = dir.openNextFile(); e && cnt < 12; e = dir.openNextFile()) {
        String nm = baseName(e.name());
        if (!logNameOk(nm)) continue;
        names[cnt] = nm;
        sizes[cnt] = e.size();
        cnt++;
      }
      dir.close();
    }
    xSemaphoreGive(sdMutex);
  }
  for (int i = 1; i < cnt; i++)  // names are zero-padded numbers: newest first
    for (int j = i; j > 0 && names[j] > names[j - 1]; j--) {
      String tn = names[j];
      names[j] = names[j - 1];
      names[j - 1] = tn;
      size_t ts = sizes[j];
      sizes[j] = sizes[j - 1];
      sizes[j - 1] = ts;
    }
  String cur = baseName(logPathFor(logSeq));
  String out = String("{\"ok\":true,\"keep\":") + LOG_KEEP + ",\"maxBytes\":" + String((unsigned long)LOG_FILE_MAX) + ",\"files\":[";
  for (int i = 0; i < cnt; i++) {
    if (i) out += ",";
    out += String("{\"name\":\"") + names[i] + "\",\"size\":" + String((unsigned long)sizes[i]) + ",\"current\":" + (names[i] == cur ? "true" : "false") + "}";
  }
  out += "]}";
  return sendJsonStatus(req, "200 OK", out);
}

// GET /logs/read?name=log-0003.txt&from=<offset>&max=<bytes>: a piece of one log file. Without "from" (or from=-1) it is
// the END of the file, starting on a line boundary; with "from" it is everything after that offset, so a viewer can
// follow a file that is still being written by asking only for what is new. Headers tell the viewer where it is:
// X-Log-Size (file size), X-Log-From (offset of the first byte sent), X-Log-Next (offset to ask for next time).
static char logHdrSize[24], logHdrFrom[24], logHdrNext[24];
static esp_err_t logsReadHandler(httpd_req_t *req) {
  char nm[32] = "", v[16];
  if (!queryParam(req, "name", nm, sizeof(nm)) || !logNameOk(String(nm))) return httpd_resp_send_err(req, HTTPD_400_BAD_REQUEST, "bad log name");
  long from = -1, maxb = 32768;
  if (queryParam(req, "from", v, sizeof(v))) from = atol(v);
  if (queryParam(req, "max", v, sizeof(v))) maxb = atol(v);
  if (maxb < 1) maxb = 1;
  if (maxb > 32768) maxb = 32768;  // bigger pieces starved Wi-Fi of internal RAM (min free fell to 4 KB)
  if (!bulkHeapOk()) return sendBusy(req);
  uint8_t *buf = (uint8_t *)heap_caps_malloc(4096, MALLOC_CAP_SPIRAM);  // PSRAM: internal RAM is what Wi-Fi lives on
  if (!buf) return httpd_resp_send_500(req);
  String path = String(LOG_DIR) + "/" + nm;
  File f;
  size_t size = 0, pos = 0;
  if (xSemaphoreTake(sdMutex, pdMS_TO_TICKS(1500)) == pdTRUE) {
    f = SD_MMC.open(path, FILE_READ);
    if (f) {
      size = f.size();
      bool tail = from < 0 || (size_t)from > size;
      pos = tail ? (size > (size_t)maxb ? size - (size_t)maxb : 0) : (size_t)from;
      if (tail && pos > 0) {  // start on a line boundary
        f.seek(pos);
        int got = f.read(buf, 4096);
        int i = 0;
        while (i < got && buf[i] != '\n') i++;
        pos += (i < got) ? i + 1 : (got > 0 ? got : 0);
      }
      f.seek(pos);
    }
    xSemaphoreGive(sdMutex);
  }
  if (!f) {
    free(buf);
    return httpd_resp_send_err(req, HTTPD_404_NOT_FOUND, "no such log file");
  }
  size_t toSend = size > pos ? size - pos : 0;
  if (toSend > (size_t)maxb) toSend = (size_t)maxb;
  snprintf(logHdrSize, sizeof(logHdrSize), "%u", (unsigned)size);
  snprintf(logHdrFrom, sizeof(logHdrFrom), "%u", (unsigned)pos);
  snprintf(logHdrNext, sizeof(logHdrNext), "%u", (unsigned)(pos + toSend));
  httpd_resp_set_type(req, "text/plain");
  httpd_resp_set_hdr(req, "Access-Control-Allow-Origin", "*");
  httpd_resp_set_hdr(req, "Access-Control-Expose-Headers", "X-Log-Size, X-Log-From, X-Log-Next");
  httpd_resp_set_hdr(req, "X-Log-Size", logHdrSize);
  httpd_resp_set_hdr(req, "X-Log-From", logHdrFrom);
  httpd_resp_set_hdr(req, "X-Log-Next", logHdrNext);
  esp_err_t res = ESP_OK;
  size_t left = toSend;
  while (res == ESP_OK && left > 0) {
    int want = left > 4096 ? 4096 : (int)left, got = 0;
    bulkHeapWait();
    if (xSemaphoreTake(sdMutex, pdMS_TO_TICKS(1500)) != pdTRUE) break;
    got = f.read(buf, want);
    xSemaphoreGive(sdMutex);
    if (got <= 0) break;
    res = httpd_resp_send_chunk(req, (const char *)buf, got);
    left -= (size_t)got;
  }
  xSemaphoreTake(sdMutex, pdMS_TO_TICKS(1500));
  f.close();
  xSemaphoreGive(sdMutex);
  free(buf);
  if (res != ESP_OK) return res;
  return httpd_resp_send_chunk(req, nullptr, 0);
}


// GET /log/sd?tail=<KB>: the end of the persistent log on the SD card (default 64 KB, 0 = whole file).
// Reads 4 KB at a time and holds the card only for each read, never while sending, so a running print is not starved.
static esp_err_t logSdHandler(httpd_req_t *req) {
  char v[12];
  long kb = 64;
  if (queryParam(req, "tail", v, sizeof(v))) kb = atol(v);
  if (!bulkHeapOk()) return sendBusy(req);
  httpd_resp_set_type(req, "text/plain");
  httpd_resp_set_hdr(req, "Access-Control-Allow-Origin", "*");
  uint8_t *buf = (uint8_t *)heap_caps_malloc(4096, MALLOC_CAP_SPIRAM);  // PSRAM: internal RAM is what Wi-Fi lives on
  if (!buf) return httpd_resp_send_500(req);
  File f;
  size_t size = 0, pos = 0;
  if (xSemaphoreTake(sdMutex, pdMS_TO_TICKS(1500)) == pdTRUE) {
    f = SD_MMC.open(logPathFor(logSeq), FILE_READ);
    if (f) {
      size = f.size();
      if (kb > 0 && size > (size_t)kb * 1024) pos = size - (size_t)kb * 1024;
      f.seek(pos);
    }
    xSemaphoreGive(sdMutex);
  }
  if (!f) {
    free(buf);
    return httpd_resp_send(req, "(log file not available)\n", HTTPD_RESP_USE_STRLEN);
  }
  bool skipPartial = pos > 0;  // we start mid-line: drop everything up to the first newline
  esp_err_t res = ESP_OK;
  while (res == ESP_OK) {
    int n = 0;
    bulkHeapWait();
    if (xSemaphoreTake(sdMutex, pdMS_TO_TICKS(1500)) != pdTRUE) break;
    n = f.read(buf, 4096);
    xSemaphoreGive(sdMutex);
    if (n <= 0) break;
    uint8_t *p = buf;
    if (skipPartial) {
      uint8_t *nl = (uint8_t *)memchr(buf, '\n', n);
      if (!nl) continue;
      skipPartial = false;
      n -= (int)(nl + 1 - buf);
      p = nl + 1;
    }
    if (n > 0) res = httpd_resp_send_chunk(req, (const char *)p, n);
  }
  xSemaphoreTake(sdMutex, pdMS_TO_TICKS(1500));
  f.close();
  xSemaphoreGive(sdMutex);
  free(buf);
  if (res != ESP_OK) return res;
  return httpd_resp_send_chunk(req, nullptr, 0);
}

// ---- G-code files on the SD card: upload (streamed, CRC-checked), list, delete -------------------
const char *GCODE_DIR = "/gcode";
bool sdMissing() { return sdStatus == "not detected" || sdStatus == "not tested"; }

static String urlDecode(const char *s) {
  String out;
  for (; *s; s++) {
    if (*s == '+') out += ' ';
    else if (*s == '%' && isxdigit((unsigned char)s[1]) && isxdigit((unsigned char)s[2])) {
      char hex[3] = {s[1], s[2], 0};
      out += (char)strtol(hex, nullptr, 16);
      s += 2;
    } else out += *s;
  }
  return out;
}

bool fileNameOk(const String &n) {
  if (n.length() == 0 || n.length() > 120) return false;
  if (n.indexOf('/') >= 0 || n.indexOf('\\') >= 0 || n.indexOf("..") >= 0 || n.indexOf('"') >= 0) return false;
  for (size_t i = 0; i < n.length(); i++)
    if ((unsigned char)n[i] < 32) return false;
  return true;
}

static bool nameParam(httpd_req_t *req, String &name) {
  char q[400], raw[300];
  if (httpd_req_get_url_query_str(req, q, sizeof(q)) != ESP_OK) return false;
  if (httpd_query_key_value(q, "name", raw, sizeof(raw)) != ESP_OK) return false;
  name = urlDecode(raw);
  return fileNameOk(name);
}

static void corsHeaders(httpd_req_t *req) {
  httpd_resp_set_hdr(req, "Access-Control-Allow-Origin", "*");
  httpd_resp_set_hdr(req, "Access-Control-Allow-Methods", "GET, POST, OPTIONS");
  httpd_resp_set_hdr(req, "Access-Control-Allow-Headers", "Content-Type");
}

static esp_err_t filesOptionsHandler(httpd_req_t *req) {
  corsHeaders(req);
  httpd_resp_set_status(req, "204 No Content");
  return httpd_resp_send(req, nullptr, 0);
}

static esp_err_t sendJsonStatus(httpd_req_t *req, const char *status, const String &json) {
  corsHeaders(req);
  httpd_resp_set_status(req, status);
  httpd_resp_set_type(req, "application/json");
  return httpd_resp_send(req, json.c_str(), HTTPD_RESP_USE_STRLEN);
}

// POST /files?name=<file name>  body = raw file bytes. Writes to /gcode/<name> via a temp file, so a
// dropped connection never leaves a half-written file under the real name.
static esp_err_t filesUploadHandler(httpd_req_t *req) {
  String name;
  if (!nameParam(req, name)) return sendJsonStatus(req, "400 Bad Request", "{\"ok\":false,\"error\":\"bad or missing name\"}");
  size_t total = req->content_len;
  if (total == 0) return sendJsonStatus(req, "400 Bad Request", "{\"ok\":false,\"error\":\"empty body\"}");
  if (sdStatus == "not detected") return sendJsonStatus(req, "503 Service Unavailable", "{\"ok\":false,\"error\":\"no SD card\"}");
  if (xSemaphoreTake(sdMutex, pdMS_TO_TICKS(3000)) != pdTRUE) return sendJsonStatus(req, "409 Conflict", "{\"ok\":false,\"error\":\"card busy\"}");

  // Held only for short stretches (setup, each chunk write, the final rename), so the print feeder can
  // keep reading gcode from the same card while a file is being received.
  struct Give {
    bool held = true;
    void release() {
      if (held) {
        xSemaphoreGive(sdMutex);
        held = false;
      }
    }
    bool take() {
      if (!held && xSemaphoreTake(sdMutex, pdMS_TO_TICKS(5000)) == pdTRUE) held = true;
      return held;
    }
    ~Give() { release(); }
  } give;

  uint64_t freeB = SD_MMC.totalBytes() - SD_MMC.usedBytes();
  if ((uint64_t)total + 1048576ULL > freeB) return sendJsonStatus(req, "507 Insufficient Storage", "{\"ok\":false,\"error\":\"not enough space on the SD card\"}");
  if (!SD_MMC.exists(GCODE_DIR)) SD_MMC.mkdir(GCODE_DIR);
  String tmp = String(GCODE_DIR) + "/upload.tmp", dst = String(GCODE_DIR) + "/" + name;
  File f = SD_MMC.open(tmp, FILE_WRITE);
  if (!f) return sendJsonStatus(req, "500 Internal Server Error", "{\"ok\":false,\"error\":\"cannot create file\"}");

  const size_t CH = 8192;
  uint8_t *buf = (uint8_t *)malloc(CH);
  if (!buf) {
    f.close();
    SD_MMC.remove(tmp);
    return sendJsonStatus(req, "500 Internal Server Error", "{\"ok\":false,\"error\":\"out of memory\"}");
  }
  give.release();  // the setup is done; each chunk below takes the lock again
  size_t got = 0;
  uint32_t crc = 0;
  int stalls = 0;
  const char *fail = nullptr;
  logEvent("files: upload '%s' %u bytes", name.c_str(), (unsigned)total);
  uint32_t t0 = millis();
  while (got < total) {
    int n = httpd_req_recv(req, (char *)buf, min(CH, total - got));
    if (n == HTTPD_SOCK_ERR_TIMEOUT) {
      if (++stalls > 12) { fail = "receive timed out"; break; }
      continue;
    }
    if (n <= 0) { fail = "connection lost"; break; }
    stalls = 0;
    if (!give.take()) { fail = "SD card busy"; break; }
    size_t wrote = f.write(buf, n);
    give.release();
    if (wrote != (size_t)n) { fail = "SD write failed"; break; }
    crc = esp_crc32_le(crc, buf, n);
    got += n;
  }
  free(buf);
  give.take();
  f.close();
  if (fail) {
    SD_MMC.remove(tmp);
    logEvent("files: upload FAILED (%s) after %u of %u bytes", fail, (unsigned)got, (unsigned)total);
    return sendJsonStatus(req, "500 Internal Server Error", String("{\"ok\":false,\"error\":\"") + fail + "\"}");
  }
  SD_MMC.remove(dst);
  if (!SD_MMC.rename(tmp, dst)) {
    SD_MMC.remove(tmp);
    return sendJsonStatus(req, "500 Internal Server Error", "{\"ok\":false,\"error\":\"rename failed\"}");
  }
  uint32_t ms = millis() - t0;
  logEvent("files: upload ok '%s' %u bytes in %.1fs (%.0f KB/s)", name.c_str(), (unsigned)got, ms / 1000.0f, got / 1.024f / (ms ? ms : 1));
  char crcHex[12];
  snprintf(crcHex, sizeof(crcHex), "%08x", (unsigned)crc);
  return sendJsonStatus(req, "200 OK", String("{\"ok\":true,\"name\":\"") + name + "\",\"bytes\":" + String((unsigned long)got) + ",\"crc32\":\"" + crcHex + "\"}");
}

static esp_err_t filesListHandler(httpd_req_t *req) {
  if (xSemaphoreTake(sdMutex, pdMS_TO_TICKS(3000)) != pdTRUE) return sendJsonStatus(req, "409 Conflict", "{\"ok\":false,\"error\":\"card busy\"}");
  struct Give {
    ~Give() { xSemaphoreGive(sdMutex); }
  } give;
  String out = "{\"ok\":true,\"files\":[";
  File dir = SD_MMC.open(GCODE_DIR);
  bool first = true;
  if (dir && dir.isDirectory()) {
    for (File e = dir.openNextFile(); e; e = dir.openNextFile()) {
      if (e.isDirectory()) continue;
      String n = e.name();
      if (n == "upload.tmp" || n == "UPLOAD.TMP") continue;
      if (!first) out += ",";
      first = false;
      out += String("{\"name\":\"") + n + "\",\"size\":" + String((unsigned long)e.size()) + "}";
    }
    dir.close();
  }
  out += String("],\"freeBytes\":") + String((unsigned long)(SD_MMC.totalBytes() - SD_MMC.usedBytes())) +
         ",\"totalBytes\":" + String((unsigned long)SD_MMC.totalBytes()) + "}";
  return sendJsonStatus(req, "200 OK", out);
}

static esp_err_t filesDeleteHandler(httpd_req_t *req) {
  String name;
  if (!nameParam(req, name)) return sendJsonStatus(req, "400 Bad Request", "{\"ok\":false,\"error\":\"bad or missing name\"}");
  if (xSemaphoreTake(sdMutex, pdMS_TO_TICKS(3000)) != pdTRUE) return sendJsonStatus(req, "409 Conflict", "{\"ok\":false,\"error\":\"card busy\"}");
  struct Give {
    ~Give() { xSemaphoreGive(sdMutex); }
  } give;
  if (printerFileInUse(name)) return sendJsonStatus(req, "409 Conflict", "{\"ok\":false,\"error\":\"that file is being printed\"}");
  bool ok = SD_MMC.remove(String(GCODE_DIR) + "/" + name);
  logEvent("files: delete '%s' %s", name.c_str(), ok ? "ok" : "FAILED");
  return sendJsonStatus(req, ok ? "200 OK" : "404 Not Found", ok ? "{\"ok\":true}" : "{\"ok\":false,\"error\":\"no such file\"}");
}


// ---- Printer bridge API (POST unless noted). Answers {"ok":bool,"error":"...","status":{...}} ------------
static String queryValue(httpd_req_t *req, const char *key) {
  char q[512], raw[400];
  if (httpd_req_get_url_query_str(req, q, sizeof(q)) != ESP_OK) return "";
  if (httpd_query_key_value(q, key, raw, sizeof(raw)) != ESP_OK) return "";
  return urlDecode(raw);
}

static esp_err_t printerReply(httpd_req_t *req, bool ok, const String &err, const String &extra = "") {
  String out = String("{\"ok\":") + (ok ? "true" : "false");
  String e = err;
  e.replace("\"", "'");
  e.replace("\n", " ");
  if (!ok) out += ",\"error\":\"" + e + "\"";
  if (extra.length()) out += "," + extra;
  out += ",\"status\":" + printerStatusJson() + "}";
  return sendJsonStatus(req, ok ? "200 OK" : "409 Conflict", out);
}

static void notePhone(httpd_req_t *req);
static esp_err_t printerStatusHandler(httpd_req_t *req) {
  notePhone(req);
  return sendJsonStatus(req, "200 OK", printerStatusJson());
}

static esp_err_t printerLinkHandler(httpd_req_t *req) {
  String err;
  bool ok = printerSelectLink(queryValue(req, "kind"), err);
  return printerReply(req, ok, err);
}
static esp_err_t printerConnectHandler(httpd_req_t *req) {
  String err;
  bool ok = printerConnect(err);
  return printerReply(req, ok, err);
}
static esp_err_t printerDisconnectHandler(httpd_req_t *req) {
  String err;
  bool ok = printerDisconnect(err);
  return printerReply(req, ok, err);
}
static esp_err_t printerPrintHandler(httpd_req_t *req) {
  String err;
  bool ok = printerStartPrint(queryValue(req, "file"), err, (uint32_t)queryValue(req, "dry").toInt(),
                               (uint32_t)queryValue(req, "skip").toInt(), (uint32_t)queryValue(req, "badEvery").toInt());
  return printerReply(req, ok, err);
}
static esp_err_t printerPauseHandler(httpd_req_t *req) {
  String err;
  bool ok = printerPause(err);
  return printerReply(req, ok, err);
}
static esp_err_t printerResumeHandler(httpd_req_t *req) {
  String err;
  bool ok = printerResume(err);
  return printerReply(req, ok, err);
}
static esp_err_t printerStopHandler(httpd_req_t *req) {
  String err;
  bool ok = printerStop(err);
  return printerReply(req, ok, err);
}
static esp_err_t printerGcodeHandler(httpd_req_t *req) {
  String err, reply;
  uint32_t t = (uint32_t)queryValue(req, "timeout").toInt();
  bool ok = printerGcode(queryValue(req, "cmd"), t ? t : 5000, reply, err);
  reply.replace("\\", "\\\\");
  reply.replace("\"", "'");
  reply.replace("\n", "\\n");
  return printerReply(req, ok, err, "\"reply\":\"" + reply + "\"");
}

static esp_err_t printerWindowHandler(httpd_req_t *req) {
  String n = queryValue(req, "n");
  if (n.length()) printerSetWindow((int)n.toInt());
  return printerReply(req, true, "window=" + String(printerGetWindow()));
}

static esp_err_t printerSimHandler(httpd_req_t *req) {
  String err;
  int speed = queryValue(req, "speed").toInt();
  int every = queryValue(req, "resendEvery").toInt();
  bool ok = printerSimTune(speed ? speed : 600, every, (int)queryValue(req, "latency").toInt(), err);
  return printerReply(req, ok, err);
}


// POST /camera?on=1|0 - switch the camera (on by default after boot; the dashboard toggle calls this via the phone).
// The phone's address as last seen by the board (the phone polls /printer/status and switches the camera), so
// GET /app can send a browser to the dashboard even after the phone's IP changed: bookmark
// http://printhost-cam.local/app instead of the phone's IP.
static char phoneIp[20] = "";
static void notePhone(httpd_req_t *req) {
  char ua[24] = "";
  if (httpd_req_get_hdr_value_str(req, "User-Agent", ua, sizeof(ua)) != ESP_OK && ua[0] == 0) return;
  if (strncmp(ua, "Dalvik", 6) != 0) return;  // Android's HttpURLConnection: that is our phone
  String ip = peerIp(req);
  strlcpy(phoneIp, ip.c_str(), sizeof(phoneIp));
}

static esp_err_t appRedirectHandler(httpd_req_t *req) {
  if (!phoneIp[0]) {
    httpd_resp_set_type(req, "text/plain");
    return httpd_resp_send(req, "The phone has not contacted the board yet - try again in a few seconds.", HTTPD_RESP_USE_STRLEN);
  }
  static char loc[64];
  snprintf(loc, sizeof(loc), "http://%s:8899/", phoneIp);
  httpd_resp_set_status(req, "302 Found");
  httpd_resp_set_hdr(req, "Location", loc);
  httpd_resp_set_hdr(req, "Cache-Control", "no-store");
  return httpd_resp_send(req, nullptr, 0);
}

static esp_err_t cameraPowerHandler(httpd_req_t *req) {
  notePhone(req);
  String v = queryValue(req, "on");
  if (v != "0" && v != "1") return sendJsonStatus(req, "400 Bad Request", "{\"ok\":false,\"error\":\"use on=1 or on=0\"}");
  logEvent("camera: %s requested by %s", v == "1" ? "ON" : "OFF", peerIp(req).c_str());
  setCameraEnabled(v == "1");
  return sendJsonStatus(req, "200 OK", String("{\"ok\":true,\"cameraOn\":") + (camEnabled ? "true" : "false") + "}");
}

// One-off diagnostic (not polled by the dashboard): a breakdown of where internal RAM actually
// goes, since freeHeapKB alone doesn't say whether it's genuinely used or just fragmented, or
// which task's stack is the big one.
static esp_err_t debugHeapHandler(httpd_req_t *req) {
  multi_heap_info_t info;
  heap_caps_get_info(&info, MALLOC_CAP_INTERNAL);

  UBaseType_t n = uxTaskGetNumberOfTasks();
  auto *tasks = new TaskStatus_t[n];
  n = uxTaskGetSystemState(tasks, n, nullptr);

  String buf = "{\"internal\":{";
  buf += "\"totalBytes\":" + String((unsigned)info.total_free_bytes + (unsigned)info.total_allocated_bytes);
  buf += ",\"freeBytes\":" + String((unsigned)info.total_free_bytes);
  buf += ",\"largestFreeBlock\":" + String((unsigned)info.largest_free_block);
  buf += ",\"minEverFreeBytes\":" + String((unsigned)info.minimum_free_bytes);
  buf += ",\"allocatedBlocks\":" + String((unsigned)info.allocated_blocks);
  buf += ",\"freeBlocks\":" + String((unsigned)info.free_blocks);
  buf += "},\"tasks\":[";
  for (UBaseType_t i = 0; i < n; i++) {
    if (i) buf += ",";
    // stackHighWaterBytes: how close that task has ever come to overflowing its own stack -
    // low here (a few hundred bytes) means its allocated stack size is basically the right size,
    // not wasted, and isn't where free RAM would come from if trimmed.
    buf += "{\"name\":\"" + String(tasks[i].pcTaskName) + "\",\"stackHighWaterBytes\":" +
           String((unsigned)(tasks[i].usStackHighWaterMark * sizeof(StackType_t))) + "}";
  }
  buf += "]}";
  delete[] tasks;

  httpd_resp_set_type(req, "application/json");
  return httpd_resp_send(req, buf.c_str(), HTTPD_RESP_USE_STRLEN);
}

static esp_err_t statusHandler(httpd_req_t *req) {
  float envTempC = 0, envHum = 0;
  bool envOk = false;
  dht11Snapshot(envTempC, envHum, envOk);
  uint8_t wch = 0;
  wifi_second_chan_t wsec;
  esp_wifi_get_channel(&wch, &wsec);
  char buf[1150];
  snprintf(buf, sizeof(buf),
           "{\"fps\":%.2f,\"frameBytes\":%u,\"mode\":\"%s\",\"ssid\":\"%s\",\"ip\":\"%s\",\"rssi\":%d,"
           "\"freePsram\":%u,\"sd\":\"%s\",\"uptime\":%lu,\"latencyMs\":%u,\"profile\":\"%s\",\"getMs\":%u,\"sendMs\":%u,\"wifiDrops\":%lu,\"reset\":\"%s\",\"tempC\":%.1f,\"cpu0\":%u,\"cpu1\":%u,\"cameraOn\":%s,\"quality\":%d,\"freeHeapKB\":%u,\"minHeapKB\":%u,\"totalHeapKB\":%u,"
           "\"envOk\":%s,\"envTempC\":%.1f,\"envHum\":%.0f,\"channel\":%u}",
           currentFps, (unsigned)lastFrameBytes, staMode ? "sta" : "ap", WiFi.SSID().c_str(),
           (staMode ? WiFi.localIP() : WiFi.softAPIP()).toString().c_str(), staMode ? WiFi.RSSI() : 0,
           (unsigned)ESP.getFreePsram(), sdStatus.c_str(), millis() / 1000, (unsigned)lastLatencyMs, PROFILES[curProfile].name, (unsigned)avgGetMs, (unsigned)avgSendMs, (unsigned long)wifiDrops, resetReasonText.c_str(), chipTempC, cpuLoad[0], cpuLoad[1], camEnabled ? "true" : "false", curQuality,
           (unsigned)(ESP.getFreeHeap() / 1024), (unsigned)(ESP.getMinFreeHeap() / 1024), (unsigned)(ESP.getHeapSize() / 1024),
           envOk ? "true" : "false", envTempC, envHum, (unsigned)wch);
  httpd_resp_set_type(req, "application/json");
  httpd_resp_set_hdr(req, "Access-Control-Allow-Origin", "*");  // the main dashboard reads this from the phone
  return httpd_resp_send(req, buf, HTTPD_RESP_USE_STRLEN);
}

static String formValue(const String &body, const char *key) {
  String k = String(key) + "=";
  int i = body.indexOf(k);
  if (i < 0) return "";
  int e = body.indexOf('&', i);
  String raw = body.substring(i + k.length(), e < 0 ? body.length() : e);
  String out;
  for (size_t j = 0; j < raw.length(); j++) {
    char ch = raw[j];
    if (ch == '+') out += ' ';
    else if (ch == '%' && j + 2 < raw.length()) {
      out += (char)strtol(raw.substring(j + 1, j + 3).c_str(), nullptr, 16);
      j += 2;
    } else out += ch;
  }
  return out;
}

static esp_err_t wifiHandler(httpd_req_t *req) {
  char body[256] = {0};
  int n = httpd_req_recv(req, body, min((size_t)(sizeof(body) - 1), req->content_len));
  if (n <= 0) return httpd_resp_send_500(req);
  String ssid = formValue(String(body), "ssid");
  String pass = formValue(String(body), "pass");
  if (ssid.length() == 0) return httpd_resp_send_err(req, HTTPD_400_BAD_REQUEST, "ssid required");
  prefs.putString("ssid", ssid);
  prefs.putString("pass", pass);
  httpd_resp_send(req, "saved", 5);
  delay(500);
  ESP.restart();
  return ESP_OK;
}

// ---- Channel survey: how busy is each 2.4 GHz channel, and who is talking there -----------------
// GET /debug/survey?ms=400: listens on channels 1-13 in turn (promiscuous) and adds up the air time of
// every frame it can decode (length / PHY rate + preamble). A lower bound - ACKs' gaps, backoff and
// non-Wi-Fi noise are not counted - but good for comparing channels. The board leaves its own channel
// for the whole survey (~13 x ms), so the stream stalls meanwhile; refused while printing.
struct SvTalker {
  uint8_t mac[6];
  uint32_t airUs, frames;
  int8_t rssi;
};
static const int SV_TALKERS = 32;
static SvTalker svTalk[SV_TALKERS];
static volatile int svTalkN = 0;
static volatile uint32_t svFrames = 0, svBytes = 0, svAirUs = 0, svBad = 0;
static volatile bool surveyActive = false;  // wifiKeepAlive() leaves the radio alone meanwhile
static portMUX_TYPE svMux = portMUX_INITIALIZER_UNLOCKED;

static float legacyMbps(unsigned rate) {
  switch (rate) {
    case 0: return 1; case 1: case 5: return 2; case 2: case 6: return 5.5f; case 3: case 7: return 11;
    case 11: return 6; case 15: return 9; case 10: return 12; case 14: return 18;
    case 9: return 24; case 13: return 36; case 8: return 48; case 12: return 54;
    default: return 0;
  }
}

static void svPacket(void *buf, wifi_promiscuous_pkt_type_t type) {
  const wifi_promiscuous_pkt_t *p = (const wifi_promiscuous_pkt_t *)buf;
  const wifi_pkt_rx_ctrl_t &rc = p->rx_ctrl;
  uint32_t len = rc.sig_len;
  float mbps;
  uint32_t preUs;
  if (rc.sig_mode == 0) {
    mbps = legacyMbps(rc.rate);
    preUs = mbps <= 11 ? 192 : 20;  // DSSS long preamble vs OFDM
  } else {
    static const float HT20[8] = {6.5f, 13, 19.5f, 26, 39, 52, 58.5f, 65};
    mbps = HT20[rc.mcs & 7] * (rc.mcs >= 8 ? 2 : 1) * (rc.cwb ? 2.08f : 1) * (rc.sgi ? 1.11f : 1);
    preUs = 36;
  }
  if (mbps <= 0) {
    svBad++;
    return;
  }
  uint32_t air = preUs + (uint32_t)(len * 8 / mbps);
  portENTER_CRITICAL(&svMux);
  svFrames++;
  svBytes += len;
  svAirUs += air;
  if (type != WIFI_PKT_CTRL && len >= 16) {  // management/data: the transmitter is address 2
    const uint8_t *ta = p->payload + 10;
    int i = 0;
    for (; i < svTalkN; i++)
      if (!memcmp(svTalk[i].mac, ta, 6)) break;
    if (i == svTalkN && svTalkN < SV_TALKERS) {
      memcpy(svTalk[i].mac, ta, 6);
      svTalk[i].airUs = svTalk[i].frames = 0;
      svTalkN++;
    }
    if (i < svTalkN) {
      svTalk[i].airUs += air;
      svTalk[i].frames++;
      svTalk[i].rssi = rc.rssi;
    }
  }
  portEXIT_CRITICAL(&svMux);
}

static String surveyResult = "{\"ok\":false,\"error\":\"no survey yet\"}";
static volatile int surveyDwellMs = 400;

static void surveyTask(void *) {
  int dwell = surveyDwellMs;
  uint8_t home = 1;
  wifi_second_chan_t sec = WIFI_SECOND_CHAN_NONE;
  esp_wifi_get_channel(&home, &sec);
  logEvent("survey: start, %d ms per channel (home ch%u)", dwell, (unsigned)home);
  // The driver will not leave the home channel while associated: drop the router for the survey (~13 x dwell)
  // and rejoin afterwards.
  delay(500);  // let the "started" reply leave first
  bool wasConnected = WiFi.status() == WL_CONNECTED;
  if (wasConnected) {
    esp_wifi_disconnect();
    delay(300);
  }
  wifi_promiscuous_filter_t flt = {WIFI_PROMIS_FILTER_MASK_ALL};
  esp_wifi_set_promiscuous_filter(&flt);
  wifi_promiscuous_filter_t cflt = {WIFI_PROMIS_CTRL_FILTER_MASK_ALL};
  esp_wifi_set_promiscuous_ctrl_filter(&cflt);
  esp_wifi_set_promiscuous_rx_cb(svPacket);
  esp_wifi_set_promiscuous(true);
  String out = "{\"ok\":true,\"homeChannel\":" + String(home) + ",\"ms\":" + String(dwell) + ",\"channels\":[";
  for (int ch = 1; ch <= 13; ch++) {
    esp_err_t ce = esp_wifi_set_channel(ch, WIFI_SECOND_CHAN_NONE);
    if (ce != ESP_OK) logEvent("survey: set channel %d failed 0x%x", ch, ce);
    portENTER_CRITICAL(&svMux);
    svFrames = svBytes = svAirUs = svBad = 0;
    svTalkN = 0;
    portEXIT_CRITICAL(&svMux);
    delay(dwell);
    portENTER_CRITICAL(&svMux);
    uint32_t fr = svFrames, by = svBytes, air = svAirUs, bad = svBad;
    int tn = svTalkN;
    SvTalker top[3] = {};
    for (int k = 0; k < 3; k++) {  // three biggest talkers by air time
      int best = -1;
      for (int i = 0; i < tn; i++) {
        bool used = false;
        for (int j = 0; j < k; j++)
          if (!memcmp(top[j].mac, svTalk[i].mac, 6)) used = true;
        if (!used && (best < 0 || svTalk[i].airUs > svTalk[best].airUs)) best = i;
      }
      if (best >= 0) top[k] = svTalk[best];
    }
    portEXIT_CRITICAL(&svMux);
    char row[360];
    int n = snprintf(row, sizeof(row), "%s{\"ch\":%d,\"frames\":%u,\"kB\":%u,\"airPct\":%.1f,\"undecoded\":%u,\"talkers\":%d,\"top\":[", ch > 1 ? "," : "", ch,
                     (unsigned)fr, (unsigned)(by / 1024), air * 100.0f / (dwell * 1000.0f), (unsigned)bad, tn);
    for (int k = 0; k < 3 && top[k].frames; k++)
      n += snprintf(row + n, sizeof(row) - n, "%s{\"mac\":\"%02x:%02x:%02x:%02x:%02x:%02x\",\"airPct\":%.1f,\"frames\":%u,\"rssi\":%d}", k ? "," : "",
                    top[k].mac[0], top[k].mac[1], top[k].mac[2], top[k].mac[3], top[k].mac[4], top[k].mac[5], top[k].airUs * 100.0f / (dwell * 1000.0f),
                    (unsigned)top[k].frames, top[k].rssi);
    snprintf(row + n, sizeof(row) - n, "]}");
    out += row;
    logEvent("survey: ch%d air %.1f%% frames %u talkers %d", ch, air * 100.0f / (dwell * 1000.0f), (unsigned)fr, tn);
  }
  esp_wifi_set_promiscuous(false);
  esp_wifi_set_channel(home, sec);
  surveyActive = false;
  if (wasConnected) WiFi.reconnect();
  out += "]}";
  surveyResult = out;
  logEvent("survey: done, back on ch%u", (unsigned)home);
  vTaskDelete(nullptr);
}

// GET /debug/survey?ms=400 starts a survey in the background (the board drops off the network for ~13 x ms);
// GET /debug/survey?result=1 returns the last result once the board is back.
static esp_err_t debugSurveyHandler(httpd_req_t *req) {
  if (queryValue(req, "result") == "1") return sendJsonStatus(req, "200 OK", surveyActive ? "{\"ok\":false,\"error\":\"running\"}" : surveyResult);
  if (surveyActive) return sendJsonStatus(req, "409 Conflict", "{\"ok\":false,\"error\":\"a survey is already running\"}");
  PrinterSnap ps;
  printerSnapshot(ps);
  if ((ps.state == PS_PRINTING || ps.state == PS_PAUSED) && queryValue(req, "force") != "1")
    return sendJsonStatus(req, "409 Conflict", "{\"ok\":false,\"error\":\"printing - the survey takes the radio off its channel\"}");
  int dwell = queryValue(req, "ms").toInt();
  if (dwell < 100) dwell = 400;
  if (dwell > 2000) dwell = 2000;
  surveyDwellMs = dwell;
  surveyActive = true;  // set here so a second request cannot slip in before the task starts
  sendJsonStatus(req, "202 Accepted", String("{\"ok\":true,\"started\":true,\"seconds\":") + String((13 * dwell) / 1000 + 3) + "}");
  xTaskCreate(surveyTask, "survey", 6144, nullptr, 4, nullptr);
  return ESP_OK;
}

// GET /debug/tx?sec=10: raw upload speed test - the board sends filler bytes for that long (no camera involved),
// the client measures the rate. Blocks the main HTTP server meanwhile, so keep it short.
static esp_err_t debugTxHandler(httpd_req_t *req) {
  int sec = queryValue(req, "sec").toInt();
  if (sec < 1) sec = 10;
  if (sec > 30) sec = 30;
  const size_t CH = 4096;
  char *b = (char *)heap_caps_malloc(CH, MALLOC_CAP_SPIRAM);
  if (!b) return httpd_resp_send_500(req);
  memset(b, 'x', CH);
  httpd_resp_set_type(req, "application/octet-stream");
  uint32_t t0 = millis();
  size_t sent = 0;
  esp_err_t res = ESP_OK;
  while (res == ESP_OK && millis() - t0 < (uint32_t)sec * 1000) {
    if (heap_caps_get_free_size(MALLOC_CAP_INTERNAL) < STREAM_HEAP_LOW) {
      vTaskDelay(pdMS_TO_TICKS(20));
      continue;
    }
    res = httpd_resp_send_chunk(req, b, CH);
    if (res == ESP_OK) sent += CH;
  }
  free(b);
  logEvent("tx test: %u KB in %us = %u KB/s", (unsigned)(sent / 1024), (unsigned)((millis() - t0) / 1000),
           (unsigned)(sent / 1024 * 1000 / (millis() - t0 + 1)));
  if (res != ESP_OK) return res;
  return httpd_resp_send_chunk(req, nullptr, 0);
}

static esp_err_t debugWifiHandler(httpd_req_t *req);

static void startServers() {
  httpd_config_t cfg = HTTPD_DEFAULT_CONFIG();
  cfg.server_port = 80;
  cfg.max_uri_handlers = 40;
  cfg.stack_size = 8192;  // file upload touches FATFS + Strings
  cfg.recv_wait_timeout = 10;
  cfg.send_wait_timeout = 10;
  cfg.max_open_sockets = 5;
  cfg.lru_purge_enable = true;  // drop the oldest idle browser connection instead of refusing new ones
  cfg.open_fn = [](httpd_handle_t, int) -> esp_err_t {
    rxProof();  // an incoming connection: packets reach us
    return ESP_OK;
  };
  httpd_uri_t uris[] = {
      {"/", HTTP_GET, indexHandler, nullptr},
      {"/capture", HTTP_GET, captureHandler, nullptr},
      {"/control", HTTP_GET, controlHandler, nullptr},
      {"/status", HTTP_GET, statusHandler, nullptr},
      {"/app", HTTP_GET, appRedirectHandler, nullptr},
      {"/debug/heap", HTTP_GET, debugHeapHandler, nullptr},
      {"/debug/wifi", HTTP_GET, debugWifiHandler, nullptr},
      {"/debug/wifi", HTTP_POST, debugWifiHandler, nullptr},
      {"/debug/survey", HTTP_GET, debugSurveyHandler, nullptr},
      {"/debug/tx", HTTP_GET, debugTxHandler, nullptr},
      {"/log", HTTP_GET, logHandler, nullptr},
      {"/log/sd", HTTP_GET, logSdHandler, nullptr},
      {"/camera", HTTP_POST, cameraPowerHandler, nullptr},
      {"/printer/status", HTTP_GET, printerStatusHandler, nullptr},
      {"/printer/link", HTTP_POST, printerLinkHandler, nullptr},
      {"/printer/connect", HTTP_POST, printerConnectHandler, nullptr},
      {"/printer/disconnect", HTTP_POST, printerDisconnectHandler, nullptr},
      {"/printer/print", HTTP_POST, printerPrintHandler, nullptr},
      {"/printer/pause", HTTP_POST, printerPauseHandler, nullptr},
      {"/printer/resume", HTTP_POST, printerResumeHandler, nullptr},
      {"/printer/stop", HTTP_POST, printerStopHandler, nullptr},
      {"/printer/gcode", HTTP_POST, printerGcodeHandler, nullptr},
      {"/printer/sim", HTTP_POST, printerSimHandler, nullptr},
      {"/logs", HTTP_GET, logsListHandler, nullptr},
      {"/logs/read", HTTP_GET, logsReadHandler, nullptr},
      {"/printer/window", HTTP_POST, printerWindowHandler, nullptr},
      {"/files", HTTP_POST, filesUploadHandler, nullptr},
      {"/files", HTTP_GET, filesListHandler, nullptr},
      {"/files", HTTP_OPTIONS, filesOptionsHandler, nullptr},
      {"/files/delete", HTTP_POST, filesDeleteHandler, nullptr},
      {"/files/delete", HTTP_OPTIONS, filesOptionsHandler, nullptr},
      {"/wifi", HTTP_POST, wifiHandler, nullptr},
  };
  if (httpd_start(&mainServer, &cfg) == ESP_OK) {
    for (auto &u : uris) httpd_register_uri_handler(mainServer, &u);
  }
  xTaskCreate(streamTask, "stream", 6144, nullptr, 5, nullptr);  // same priority/affinity as the old httpd stream task
}

// ---- Wi-Fi: join the router, keep rejoining forever, never reboot because of the network ----------
// The network only carries telemetry and the camera; a print runs from the SD card over USB and must
// never stop because Wi-Fi or the router went away. So there is no "reboot after N minutes offline".
//
// RX watchdog: twice on 2026-09-24 the board went deaf while still associated (nothing received, ARP
// to it failed, the router listed it as connected with no traffic) - once for an hour until the router
// dropped it with reason 16, once until a power cycle. Pinging the gateway catches that: if it has
// not answered for WD_SILENCE_MS while we believe we are connected, leave and rejoin.
static const uint32_t WD_PING_MS = 5000;
static const uint32_t WD_SILENCE_MS = 45000;
static const uint32_t STA_RETRY_MS = 30000;  // setup-network mode: try the router again this often
static esp_ping_handle_t pingHandle = nullptr;
static volatile uint32_t lastGatewayReplyMs = 0;
static volatile uint32_t gatewayPingOk = 0, gatewayPingLost = 0;
static uint32_t wdRejoins = 0;
static volatile bool setupTestDue = false;  // /debug/wifi?test=setup

static void onPingOk(esp_ping_handle_t h, void *) {
  lastGatewayReplyMs = millis();
  lastRxProofMs = lastGatewayReplyMs;
  gatewayPingOk++;
  uint32_t rtt = 0;
  esp_ping_get_profile(h, ESP_PING_PROF_TIMEGAP, &rtt, sizeof(rtt));
  gwOkWin++;
  gwRttSumWin += rtt;
  if (rtt > gwRttMaxWin) gwRttMaxWin = rtt;
}
static void onPingLost(esp_ping_handle_t, void *) {
  gatewayPingLost++;
  gwLostWin++;
}

// (Re)starts the endless gateway ping. Called when an IP is obtained (the gateway may change).
static void startGatewayPing(IPAddress target) {
  if (pingHandle) {
    esp_ping_stop(pingHandle);
    esp_ping_delete_session(pingHandle);
    pingHandle = nullptr;
  }
  esp_ping_config_t cfg = ESP_PING_DEFAULT_CONFIG();
  cfg.count = ESP_PING_COUNT_INFINITE;
  cfg.interval_ms = WD_PING_MS;
  cfg.timeout_ms = 2000;
  cfg.data_size = 16;
  ip_addr_t a = {};
  IP_ADDR4(&a, target[0], target[1], target[2], target[3]);
  cfg.target_addr = a;
  esp_ping_callbacks_t cbs = {};
  cbs.on_ping_success = onPingOk;
  cbs.on_ping_timeout = onPingLost;
  lastGatewayReplyMs = lastRxProofMs = millis();  // a fresh start gets a full grace period
  if (esp_ping_new_session(&cfg, &cbs, &pingHandle) == ESP_OK) esp_ping_start(pingHandle);
  else logEvent("wifi: watchdog ping could not start");
}

static void applyStaTuning() {
  WiFi.setSleep(false);
  esp_wifi_set_ps(WIFI_PS_NONE);
  WiFi.setTxPower(WIFI_POWER_19_5dBm);
}

// First time the router is reached (at boot or later from setup-network mode): the services that
// only make sense on the home network.
static void onRouterJoined() {
  staMode = true;
  WiFi.setAutoReconnect(false);  // wifiKeepAlive() is the only one that reconnects (two owners fought each other)
  applyStaTuning();
  if (!staServicesUp) {
    staServicesUp = true;
    MDNS.begin(HOSTNAME);
    ArduinoOTA.setHostname(HOSTNAME);
    ArduinoOTA.setPassword(OTA_PASS);
    ArduinoOTA.onStart([]() { logEvent("ota: update starting"); });
    ArduinoOTA.onEnd([]() { logEvent("ota: update finished, rebooting"); });
    ArduinoOTA.onError([](ota_error_t e) { logEvent("ota: ERROR %d", (int)e); });
    ArduinoOTA.begin();
  }
  Serial.printf("WIFI: connected, IP %s, open http://%s/ or http://%s.local/\n", WiFi.localIP().toString().c_str(),
                WiFi.localIP().toString().c_str(), HOSTNAME);
}

static void startSetupNetwork() {
  // AP+STA: the setup page stays reachable, and the router is retried in the background (it may
  // simply have been off when the board booted).
  WiFi.mode(staSsid.length() ? WIFI_AP_STA : WIFI_AP);
  WiFi.softAP(AP_SSID);
  logEvent("wifi: router not reachable - setup network '%s' up, retrying the router every %us", AP_SSID, (unsigned)(STA_RETRY_MS / 1000));
  Serial.printf("WIFI: setup network '%s' (open), page at http://%s/\n", AP_SSID, WiFi.softAPIP().toString().c_str());
}

static void startNetwork() {
  prefs.begin("wifi", false);
  staSsid = prefs.getString("ssid", "");
  staPass = prefs.getString("pass", "");
#ifdef WIFI_SSID
  if (staSsid.length() == 0) {
    staSsid = WIFI_SSID;
    staPass = WIFI_PASS;
  }
#endif
  WiFi.setHostname(HOSTNAME);
  WiFi.setScanMethod(WIFI_ALL_CHANNEL_SCAN);  // always look on every channel: the router's channel may have changed
  WiFi.onEvent(wifiEvent);
  WiFi.mode(WIFI_STA);
  int found = WiFi.scanNetworks();
  Serial.printf("WIFI: scan found %d networks:\n", found);
  for (int i = 0; i < found; i++) {
    Serial.printf("  '%s' ch%d %ddBm\n", WiFi.SSID(i).c_str(), WiFi.channel(i), WiFi.RSSI(i));
    // Persisted (not just Serial) so a channel-congestion regression can be diagnosed after the
    // fact from /logs alone, without needing USB serial plugged in at the time it happens.
    logEvent("wifi scan: '%s' ch%d %ddBm", WiFi.SSID(i).c_str(), WiFi.channel(i), WiFi.RSSI(i));
  }
  WiFi.scanDelete();
  if (staSsid.length()) {
    Serial.printf("WIFI: connecting to '%s'...\n", staSsid.c_str());
    esp_wifi_set_bandwidth(WIFI_IF_STA, WIFI_BW_HT20);
    esp_wifi_set_protocol(WIFI_IF_STA, WIFI_PROTOCOL_11B | WIFI_PROTOCOL_11G | WIFI_PROTOCOL_11N);
    WiFi.begin(staSsid.c_str(), staPass.c_str());
    uint32_t t0 = millis();
    while (WiFi.status() != WL_CONNECTED && millis() - t0 < STA_TIMEOUT_MS) delay(250);
    if (WiFi.status() == WL_CONNECTED) {
      onRouterJoined();
      return;
    }
    Serial.println("WIFI: connect failed, falling back to setup network");
  }
  startSetupNetwork();
}

// Called from loop(): keeps the router connection alive, in every mode.
static void wifiKeepAlive(uint32_t now) {
  static uint32_t downSince = 0, lastRetry = 0, lastRejoin = 0;
  if (!staSsid.length()) return;  // no router configured: setup network only
  if (surveyActive) return;       // a channel survey has the radio
  if (setupTestDue && staMode) {
    setupTestDue = false;
    logEvent("wifi: TEST setup - dropping to the setup network");
    staMode = false;
    WiFi.disconnect(false, false);
    startSetupNetwork();
    lastRetry = now;  // the first retry comes after the full STA_RETRY_MS, as it would after a failed boot
    return;
  }
  if (!staMode) {
    // Setup-network mode: keep trying the router; once it answers, switch to normal operation.
    if (WiFi.status() == WL_CONNECTED) {
      logEvent("wifi: router reachable again - leaving setup network");
      WiFi.softAPdisconnect(true);
      WiFi.mode(WIFI_STA);
      onRouterJoined();
    } else if (now - lastRetry >= STA_RETRY_MS) {
      lastRetry = now;
      WiFi.begin(staSsid.c_str(), staPass.c_str());
    }
    return;
  }
  if (WiFi.status() != WL_CONNECTED) {
    // One owner for reconnecting (Arduino's own auto-reconnect is off, see onRouterJoined): a fresh join with a
    // scan of ALL channels (the router may have moved to another channel), and a started attempt is never cut
    // short - scan + authentication can take several seconds on a busy band. No giving up, no reboot.
    static uint32_t attempt = 0;
    if (downSince == 0) {
      downSince = now;
      attempt = 0;
      lastRetry = now - 9000;  // first attempt ~1 s after the drop
    }
    uint32_t wait = attempt < 1 ? 10000 : (attempt < 3 ? 20000 : 30000);
    if (now - lastRetry >= wait) {
      lastRetry = now;
      attempt++;
      if (attempt <= 5 || attempt % 10 == 0)
        logEvent("wifi: rejoin attempt %u after %us offline (status %d)", (unsigned)attempt, (unsigned)((now - downSince) / 1000), (int)WiFi.status());
      WiFi.disconnect(false, false);
      WiFi.begin(staSsid.c_str(), staPass.c_str());
    }
    return;
  }
  if (downSince) {
    logEvent("wifi: back after %us offline", (unsigned)((now - downSince) / 1000));
    downSince = 0;
  }
  if (gatewayPingRestartDue) {
    gatewayPingRestartDue = false;
    startGatewayPing(WiFi.gatewayIP());
  }
  // Connected as far as the driver knows. Deaf? (see the comment above)
  if (!pingHandle) return;
  uint32_t last = lastRxProofMs;  // written by other tasks: it can be a few ms newer than "now"
  uint32_t silent = (int32_t)(now - last) > 0 ? now - last : 0;
  if (silent > WD_SILENCE_MS && now - lastRejoin > WD_SILENCE_MS) {
    lastRejoin = now;
    wdRejoins++;
    logEvent("wifi: WATCHDOG nothing received for %us (ping ok %u lost %u), rssi %d, heap %u KB - rejoining (#%u)", (unsigned)(silent / 1000),
             (unsigned)gatewayPingOk, (unsigned)gatewayPingLost, WiFi.RSSI(), (unsigned)(ESP.getFreeHeap() / 1024), (unsigned)wdRejoins);
    lastGatewayReplyMs = lastRxProofMs = now;  // grace period for the rejoin itself
    esp_wifi_disconnect();     // the reconnect branch above takes it from here
  }
}

// POST /debug/wifi?test=deaf|setup - exercises the recovery paths on purpose.
//   deaf:  ping an address that never answers, so the watchdog must fire and rejoin (~45 s) - as long as nothing
//          else proves the link meanwhile (no new HTTP connections, no stream viewer).
//   setup: drop to setup-network mode as if the router had been unreachable at boot (~30 s back).
static esp_err_t debugWifiHandler(httpd_req_t *req) {
  String t = queryValue(req, "test");
  if (t == "deaf") {
    wdPingOnlyUntilMs = millis() + 90000;
    startGatewayPing(IPAddress(192, 0, 2, 1));  // TEST-NET-1: nothing ever answers there
    logEvent("wifi: TEST deaf - pinging a black hole, watchdog should rejoin in ~%us", (unsigned)(WD_SILENCE_MS / 1000));
    return sendJsonStatus(req, "200 OK", "{\"ok\":true,\"test\":\"deaf\"}");
  }
  if (t == "setup") {
    setupTestDue = true;  // done by loop(), so it cannot race wifiKeepAlive()
    return sendJsonStatus(req, "200 OK", "{\"ok\":true,\"test\":\"setup\"}");
  }
  char buf[200];
  snprintf(buf, sizeof(buf), "{\"ok\":true,\"sta\":%s,\"gatewaySilentMs\":%u,\"pingOk\":%u,\"pingLost\":%u,\"watchdogRejoins\":%u,\"streamHeapPauses\":%u}",
           staMode ? "true" : "false", (unsigned)(millis() - lastGatewayReplyMs), (unsigned)gatewayPingOk, (unsigned)gatewayPingLost,
           (unsigned)wdRejoins, (unsigned)streamHeapPauses);
  return sendJsonStatus(req, "200 OK", buf);
}

void setup() {
  camMutex = xSemaphoreCreateMutex();
  sdMutex = xSemaphoreCreateMutex();
  Serial.begin(115200);
  esp_log_level_set("cam_hal", ESP_LOG_ERROR);  // FB-OVF warnings are expected while nobody is watching
  resetReasonText = resetReasonName(esp_reset_reason());
  delay(1200);
  Serial.println("\n=== PrintHost ESP32-S3 camera test ===");
  probeSd();
  Serial.printf("SD: %s\n", sdStatus.c_str());
  if (sdStatus != "not detected") {
    SD_MMC.remove("/phlog.old");
    SD_MMC.remove("/sdtest.txt");
    if (!SD_MMC.exists(LOG_DIR)) SD_MMC.mkdir(LOG_DIR);
    SD_MMC.remove("/phlog.txt");
    SD_MMC.remove("/logs/logtmp.txt");
    // the single 1 MB log of the earlier firmware becomes a numbered file
    if (SD_MMC.exists("/logs/printhost.log")) SD_MMC.rename("/logs/printhost.log", logPathFor(logScan(nullptr, nullptr) + 1));
    logSeq = logScan(nullptr, nullptr);
    if (logSeq == 0) logSeq = 1;
    else {
      File last = SD_MMC.open(logPathFor(logSeq), FILE_READ);
      if (last) {
        if (last.size() >= LOG_FILE_MAX) logSeq++;
        last.close();
      }
    }
    logCleanupDue = true;  // no print can be running yet: the first log tick tidies up
  }
  logEvent("BOOT: %s, OTA-verified build %s %s, free heap %u KB, SD %s", resetReasonText.c_str(), __DATE__, __TIME__, (unsigned)(ESP.getFreeHeap() / 1024), sdStatus.c_str());
  fpsWindowStart = millis();
  printerBegin();  // engine task; selects the USB link and connects by itself once the printer appears on USB
  dht11Begin(21);  // room sensor on GPIO 21 (moved from 14) - see docs/HANDOFF_ESP32_BRIDGE.md
  startNetwork();
  startServers();
  loadProfileChoice();
  setCameraEnabled(true);  // on by default; POST /camera?on=0|1 switches it at any time
}

void loop() {
  uint32_t now = millis();
  wifiKeepAlive(now);
  static uint32_t lastTick = 0;
  if (now - lastTick >= 1000) {
    lastTick = now;
    sampleVitals();
    logTick(lastTick);
  }
  if (staServicesUp) ArduinoOTA.handle();
  delay(20);
}
