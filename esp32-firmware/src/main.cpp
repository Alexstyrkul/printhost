// PrintHost ESP32-S3 bring-up firmware: camera test UI + MJPEG stream + Wi-Fi provisioning.
// Port 80: test page, /capture, /control, /status, /wifi.  Port 81: /stream (MJPEG).
#include <Arduino.h>
#include <ArduinoOTA.h>
#include <ESPmDNS.h>
#include <Preferences.h>
#include <SD_MMC.h>
#include <WiFi.h>
#include "common.h"
#include "esp_camera.h"
#include "esp_http_server.h"
#include <driver/temp_sensor.h>
#include <esp_crc.h>
#include <esp_log.h>
#include <esp_system.h>
#include <esp_task_wdt.h>
#include <esp_timer.h>
#include <stdarg.h>
#include <esp_wifi.h>
#include <lwip/sockets.h>
#include <netinet/tcp.h>
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
static httpd_handle_t streamServer = nullptr;
static String sdStatus = "not tested";
static bool staMode = false;

static volatile uint32_t framesServed = 0;
static volatile size_t lastFrameBytes = 0;
static float currentFps = 0;
static uint32_t fpsWindowStart = 0;
static uint32_t fpsWindowFrames = 0;

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
static int curProfile = 3;  // xga (1024x768, 4:3): full field of view and the best fps of the tested profiles
// Physical mounting: set these once to match how the camera is installed (0/1).
#define CAM_VFLIP 1    // camera is mounted upside down: vflip + hmirror = 180 degree rotation
#define CAM_HMIRROR 1
static int flipV = CAM_VFLIP, mirrorH = CAM_HMIRROR;
static float chipTempC = 0;
static uint8_t cpuLoad[2] = {0, 0};  // percent per core
static volatile uint32_t lastLatencyMs = 0;
static volatile uint32_t avgGetMs = 0, avgSendMs = 0;
static volatile bool streamActive = false;
static volatile bool camEnabled = false;  // the camera is only powered while the phone says the printer plug is on
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
  bool ok = initCameraWith(20000000, p.size, p.quality, 2, CAMERA_GRAB_LATEST);
  if (ok) {
    curProfile = idx;
    curQuality = p.quality;
    emaFrameBytes = 0;
    applySensorTuning();
  }
  logEvent("camera: profile %s %s", p.name, ok ? "ok" : "FAILED");
  xSemaphoreGive(camMutex);
  return ok;
}

static bool initCamera() { return applyProfile(curProfile); }

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
};
static const int SAMPLE_N = 300;  // 5 minutes at 1 Hz
static const int EVENT_N = 64;
static Sample samples[SAMPLE_N];
static uint32_t sampleCount = 0;
static char events[EVENT_N][120];
static uint32_t eventCount = 0, eventsFlushed = 0;
static uint32_t wifiDrops = 0;
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
    case ARDUINO_EVENT_WIFI_STA_GOT_IP: logEvent("wifi: got IP %s", WiFi.localIP().toString().c_str()); break;
    case ARDUINO_EVENT_WIFI_STA_DISCONNECTED:
      wifiDrops++;
      logEvent("wifi: DISCONNECTED reason %d (drop #%lu)", info.wifi_sta_disconnected.reason, (unsigned long)wifiDrops);
      break;
    default: break;
  }
}

// Logs live in their own folder, apart from /gcode, so they never show up in the dashboard file list.
static const char *LOG_DIR = "/logs";
static const char *LOG_PATH = "/logs/printhost.log";
static const char *LOG_TMP = "/logs/logtmp.txt";

// One log file with a size cap: when it gets too big, keep only the newest half.
static const size_t LOG_MAX_BYTES = 1024 * 1024;
static void trimLogFile() {
  File in = SD_MMC.open(LOG_PATH, FILE_READ);
  if (!in) return;
  in.seek(in.size() / 2);
  in.readStringUntil('\n');  // skip the partial line we landed in
  File out = SD_MMC.open(LOG_TMP, FILE_WRITE);
  if (!out) {
    in.close();
    return;
  }
  static uint8_t buf[2048];  // static: keeps the stack small whichever task calls this
  int n;
  while ((n = in.read(buf, sizeof(buf))) > 0) out.write(buf, n);
  in.close();
  out.close();
  SD_MMC.remove(LOG_PATH);
  SD_MMC.rename(LOG_TMP, LOG_PATH);
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
  sampleCount++;

  if (xSemaphoreTake(sdMutex, 0) != pdTRUE) return;  // a file upload is using the card right now
  struct Give {
    ~Give() { xSemaphoreGive(sdMutex); }
  } give;  // released on every return path below
  static uint32_t lastSd = 0, sdRetryAt = 0;
  if (sdStatus == "not detected" || sdStatus == "not tested") return;
  if (nowMs < sdRetryAt) return;
  bool newEvents = eventsFlushed < eventCount;
  uint32_t sdEvery = streamActive ? 10000 : 60000;  // log less while nobody is watching
  if (!newEvents && nowMs - lastSd < sdEvery) return;
  lastSd = nowMs;
  static bool logDirReady = false;
  if (!logDirReady) {
    if (!SD_MMC.exists(LOG_DIR)) SD_MMC.mkdir(LOG_DIR);
    logDirReady = true;
  }
  File f = SD_MMC.open(LOG_PATH, FILE_APPEND);
  if (!f) {
    sdRetryAt = nowMs + 60000;  // don't hammer a missing/failed card every second
    sdStatus = "log write failed";
    return;
  }
  if (f.size() > LOG_MAX_BYTES) {
    f.close();
    trimLogFile();
    f = SD_MMC.open(LOG_PATH, FILE_APPEND);
    if (!f) return;
  }
  while (eventsFlushed < eventCount) {
    if (eventCount - eventsFlushed > EVENT_N) eventsFlushed = eventCount - EVENT_N;
    f.println(events[eventsFlushed % EVENT_N]);
    eventsFlushed++;
  }
  f.printf("[%lus] fps=%.1f rssi=%d temp=%dC cpu=%u/%u%% get=%u send=%u delay=%u frame=%uB heap=%u psram=%u prof=%s\n", (unsigned long)s.t, s.fps,
           s.rssi, s.tempC, s.cpu0, s.cpu1, s.getMs, s.sendMs, s.delayMs, (unsigned)s.frameB, (unsigned)s.heap, (unsigned)s.psram, PROFILES[s.prof].name);
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

static esp_err_t streamHandler(httpd_req_t *req) {
  if (!camEnabled) return httpd_resp_send_err(req, HTTPD_500_INTERNAL_SERVER_ERROR, "camera is off");
  int one = 1;
  setsockopt(httpd_req_to_sockfd(req), IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
  httpd_resp_set_type(req, "multipart/x-mixed-replace;boundary=frame");
  httpd_resp_set_hdr(req, "Access-Control-Allow-Origin", "*");
  esp_err_t res = ESP_OK;
  int misses = 0;
  streamActive = true;
  logEvent("stream: viewer connected (%s)", PROFILES[curProfile].name);
  // No task watchdog here: a stalled Wi-Fi client must end its own stream (the HTTP send timeout does that),
  // never reboot the board - a reboot would abort a print in progress.
  int adaptCount = 0;
  while (res == ESP_OK) {
    if (!camEnabled) {
      res = ESP_FAIL;  // the camera was powered down: end the stream cleanly
      break;
    }
    xSemaphoreTake(camMutex, portMAX_DELAY);
    int64_t tGet0 = esp_timer_get_time();
    camera_fb_t *fb = esp_camera_fb_get();
    int64_t tGet1 = esp_timer_get_time();
    if (!fb) {
      xSemaphoreGive(camMutex);
      if (++misses > 20) {
        streamActive = false;
        logEvent("stream: camera returned no frames, closing");
        return ESP_FAIL;
      }
      delay(50);
      continue;
    }
    misses = 0;
    char part[96];
    size_t hlen = snprintf(part, sizeof(part), "\r\n--frame\r\nContent-Type: image/jpeg\r\nContent-Length: %u\r\n\r\n", (unsigned)fb->len);
    res = httpd_resp_send_chunk(req, part, hlen);
    if (res == ESP_OK) res = httpd_resp_send_chunk(req, (const char *)fb->buf, fb->len);
    int64_t tSend1 = esp_timer_get_time();
    size_t len = fb->len;
    int64_t capturedUs = (int64_t)fb->timestamp.tv_sec * 1000000LL + fb->timestamp.tv_usec;
    esp_camera_fb_return(fb);
    xSemaphoreGive(camMutex);
    if (res == ESP_OK) {
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
  streamActive = false;
  logEvent("stream: viewer left (err 0x%x)", res);
  return res;
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
      if (!strcmp(val, PROFILES[i].name)) return applyProfile(i) ? httpd_resp_send(req, "ok", 2) : httpd_resp_send_500(req);
    }
    return httpd_resp_send_404(req);
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




static esp_err_t logHandler(httpd_req_t *req) {
  String out;
  out.reserve(16000);
  out += "boot reason: " + resetReasonText + "\n";
  out += "uptime " + String(millis() / 1000) + " s, wifi drops " + String(wifiDrops) + ", min free heap " +
         String(ESP.getMinFreeHeap() / 1024) + " KB, free psram " + String(ESP.getFreePsram() / 1024) + " KB\n";
  out += "--- events (oldest first)\n";
  portENTER_CRITICAL(&logMux);
  uint32_t n = eventCount, start = n > EVENT_N ? n - EVENT_N : 0;
  portEXIT_CRITICAL(&logMux);
  for (uint32_t i = start; i < n; i++) {
    out += events[i % EVENT_N];
    out += "\n";
  }
  out += "--- samples: t,fps,rssi,getMs,sendMs,delayMs,frameKB,heapKB,psramKB,profile,tempC,cpu0,cpu1\n";
  uint32_t sc = sampleCount, first = sc > SAMPLE_N ? sc - SAMPLE_N : 0;
  for (uint32_t i = first; i < sc; i++) {
    const Sample &s = samples[i % SAMPLE_N];
    char row[128];
    snprintf(row, sizeof(row), "%u,%.1f,%d,%u,%u,%u,%.1f,%u,%u,%s,%d,%u,%u\n", (unsigned)s.t, s.fps, s.rssi, s.getMs, s.sendMs, s.delayMs,
             s.frameB / 1024.0f, (unsigned)(s.heap / 1024), (unsigned)(s.psram / 1024), PROFILES[s.prof].name, s.tempC, s.cpu0, s.cpu1);
    out += row;
  }
  httpd_resp_set_type(req, "text/plain");
  httpd_resp_set_hdr(req, "Access-Control-Allow-Origin", "*");
  return httpd_resp_send(req, out.c_str(), HTTPD_RESP_USE_STRLEN);
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

static esp_err_t printerStatusHandler(httpd_req_t *req) { return sendJsonStatus(req, "200 OK", printerStatusJson()); }

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
  bool ok = printerStartPrint(queryValue(req, "file"), err);
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

static esp_err_t printerUsbTuneHandler(httpd_req_t *req) {
  auto val = [&](const char *k) { String v = queryValue(req, k); return v.length() ? (int)v.toInt() : -1; };
  printerUsbTune(val("idlePoll"), val("lookIn"), val("gate"));
  return printerReply(req, true, "");
}

static esp_err_t printerSimHandler(httpd_req_t *req) {
  String err;
  int speed = queryValue(req, "speed").toInt();
  int every = queryValue(req, "resendEvery").toInt();
  bool ok = printerSimTune(speed ? speed : 600, every, err);
  return printerReply(req, ok, err);
}


// POST /camera?on=1|0 - the phone (which owns the printer's smart plug) switches the camera with the plug.
static esp_err_t cameraPowerHandler(httpd_req_t *req) {
  String v = queryValue(req, "on");
  if (v != "0" && v != "1") return sendJsonStatus(req, "400 Bad Request", "{\"ok\":false,\"error\":\"use on=1 or on=0\"}");
  setCameraEnabled(v == "1");
  return sendJsonStatus(req, "200 OK", String("{\"ok\":true,\"cameraOn\":") + (camEnabled ? "true" : "false") + "}");
}

static esp_err_t statusHandler(httpd_req_t *req) {
  char buf[768];
  snprintf(buf, sizeof(buf),
           "{\"fps\":%.2f,\"frameBytes\":%u,\"mode\":\"%s\",\"ssid\":\"%s\",\"ip\":\"%s\",\"rssi\":%d,"
           "\"freePsram\":%u,\"sd\":\"%s\",\"uptime\":%lu,\"latencyMs\":%u,\"profile\":\"%s\",\"getMs\":%u,\"sendMs\":%u,\"wifiDrops\":%lu,\"reset\":\"%s\",\"tempC\":%.1f,\"cpu0\":%u,\"cpu1\":%u,\"cameraOn\":%s,\"quality\":%d}",
           currentFps, (unsigned)lastFrameBytes, staMode ? "sta" : "ap", WiFi.SSID().c_str(),
           (staMode ? WiFi.localIP() : WiFi.softAPIP()).toString().c_str(), staMode ? WiFi.RSSI() : 0,
           (unsigned)ESP.getFreePsram(), sdStatus.c_str(), millis() / 1000, (unsigned)lastLatencyMs, PROFILES[curProfile].name, (unsigned)avgGetMs, (unsigned)avgSendMs, (unsigned long)wifiDrops, resetReasonText.c_str(), chipTempC, cpuLoad[0], cpuLoad[1], camEnabled ? "true" : "false", curQuality);
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

static void startServers() {
  httpd_config_t cfg = HTTPD_DEFAULT_CONFIG();
  cfg.server_port = 80;
  cfg.max_uri_handlers = 28;
  cfg.stack_size = 8192;  // file upload touches FATFS + Strings
  cfg.recv_wait_timeout = 10;
  cfg.send_wait_timeout = 10;
  cfg.max_open_sockets = 5;
  cfg.lru_purge_enable = true;  // drop the oldest idle browser connection instead of refusing new ones
  httpd_uri_t uris[] = {
      {"/", HTTP_GET, indexHandler, nullptr},
      {"/capture", HTTP_GET, captureHandler, nullptr},
      {"/control", HTTP_GET, controlHandler, nullptr},
      {"/status", HTTP_GET, statusHandler, nullptr},
      {"/log", HTTP_GET, logHandler, nullptr},
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
      {"/printer/usbtune", HTTP_POST, printerUsbTuneHandler, nullptr},
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
  httpd_config_t scfg = HTTPD_DEFAULT_CONFIG();
  scfg.server_port = 81;
  scfg.max_open_sockets = 3;
  scfg.lru_purge_enable = true;
  scfg.ctrl_port += 1;
  httpd_uri_t streamUri = {"/stream", HTTP_GET, streamHandler, nullptr};
  if (httpd_start(&streamServer, &scfg) == ESP_OK) httpd_register_uri_handler(streamServer, &streamUri);
}

static void startNetwork() {
  prefs.begin("wifi", false);
  String ssid = prefs.getString("ssid", "");
  String pass = prefs.getString("pass", "");
#ifdef WIFI_SSID
  if (ssid.length() == 0) {
    ssid = WIFI_SSID;
    pass = WIFI_PASS;
  }
#endif
  WiFi.setHostname(HOSTNAME);
  WiFi.onEvent(wifiEvent);
  WiFi.mode(WIFI_STA);
  int found = WiFi.scanNetworks();
  Serial.printf("WIFI: scan found %d networks:\n", found);
  for (int i = 0; i < found; i++) Serial.printf("  '%s' ch%d %ddBm\n", WiFi.SSID(i).c_str(), WiFi.channel(i), WiFi.RSSI(i));
  if (ssid.length()) {
    Serial.printf("WIFI: connecting to '%s'...\n", ssid.c_str());
    WiFi.mode(WIFI_STA);
    esp_wifi_set_bandwidth(WIFI_IF_STA, WIFI_BW_HT20);
    esp_wifi_set_protocol(WIFI_IF_STA, WIFI_PROTOCOL_11B | WIFI_PROTOCOL_11G | WIFI_PROTOCOL_11N);
    WiFi.begin(ssid.c_str(), pass.c_str());
    uint32_t t0 = millis();
    while (WiFi.status() != WL_CONNECTED && millis() - t0 < STA_TIMEOUT_MS) delay(250);
    if (WiFi.status() == WL_CONNECTED) {
      staMode = true;
      WiFi.setAutoReconnect(true);
      WiFi.setSleep(false);
      esp_wifi_set_ps(WIFI_PS_NONE);
      WiFi.setTxPower(WIFI_POWER_19_5dBm);
      MDNS.begin(HOSTNAME);
      ArduinoOTA.setHostname(HOSTNAME);
      ArduinoOTA.setPassword(OTA_PASS);
      ArduinoOTA.onStart([]() { logEvent("ota: update starting"); });
      ArduinoOTA.onEnd([]() { logEvent("ota: update finished, rebooting"); });
      ArduinoOTA.onError([](ota_error_t e) { logEvent("ota: ERROR %d", (int)e); });
      ArduinoOTA.begin();
      Serial.printf("WIFI: connected, IP %s, open http://%s/ or http://%s.local/\n", WiFi.localIP().toString().c_str(),
                    WiFi.localIP().toString().c_str(), HOSTNAME);
      return;
    }
    Serial.println("WIFI: connect failed, falling back to setup network");
  }
  WiFi.mode(WIFI_AP);
  WiFi.softAP(AP_SSID);
  Serial.printf("WIFI: setup network '%s' (open), page at http://%s/\n", AP_SSID, WiFi.softAPIP().toString().c_str());
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
    if (SD_MMC.exists("/phlog.txt") && !SD_MMC.exists(LOG_PATH)) SD_MMC.rename("/phlog.txt", LOG_PATH);  // move the old root log
    SD_MMC.remove("/phlog.txt");
  }
  logEvent("BOOT: %s, OTA-verified build %s %s, free heap %u KB, SD %s", resetReasonText.c_str(), __DATE__, __TIME__, (unsigned)(ESP.getFreeHeap() / 1024), sdStatus.c_str());
  // The camera stays powered down until the phone reports the printer plug is on (POST /camera?on=1).
  Serial.println("CAM: off until the plug turns on");
  fpsWindowStart = millis();
  printerBegin();  // starts the engine task only; the printer link stays unselected until a client asks
  startNetwork();
  startServers();
}

// Rejoin the router on its own (it may reboot or change channel), and reboot if that keeps failing.
void loop() {
  static uint32_t downSince = 0;
  if (staMode) {
    if (WiFi.status() == WL_CONNECTED) {
      downSince = 0;
    } else {
      if (downSince == 0) downSince = millis();
      uint32_t down = millis() - downSince;
      if (down > 5000 && (down / 5000) != ((down - 1000) / 5000)) {
        Serial.println("WIFI: link down, reconnecting");
        WiFi.reconnect();
      }
      if (down > 120000) {
        logEvent("wifi: down >120s, rebooting");
        ESP.restart();
      }
    }
  }
  static uint32_t lastTick = 0;
  if (millis() - lastTick >= 1000) {
    lastTick = millis();
    sampleVitals();
    logTick(lastTick);
  }
  if (staMode) ArduinoOTA.handle();
  delay(20);
}
