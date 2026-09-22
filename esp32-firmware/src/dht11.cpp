// Room temperature/humidity via a DHT11 wired to a free GPIO. Reads through the RMT peripheral
// instead of bit-banging the pin (the first attempt used digitalRead() in a busy-wait loop, then
// Adafruit's DHT library, which on ESP32 also just busy-waits on digitalRead() under the hood -
// both failed the last couple of bits of every single read on this board's Arduino-as-ESP-IDF
// build: reliably capturing microsecond-wide pulses by polling a GPIO register from C code isn't
// guaranteed on a chip that's also servicing Wi-Fi/USB/flash-cache traffic, regardless of how
// that polling loop is written. RMT is a dedicated hardware block that samples the line on its
// own clock into a hardware buffer - the CPU only reads the result afterwards, so it can't be
// stalled out of an in-progress bit. A low-priority task polls it every 10 s (falling back to a
// faster 1.5 s retry after a failed read - DHT11 itself must not be read faster than ~1 Hz) and
// publishes the last good reading; dht11Snapshot() just copies it out, no blocking.
#include "dht11.h"

#include <Arduino.h>
#include <driver/rmt.h>

#include "common.h"

namespace {

// ESP32-S3's 8 RMT channels are split TX-only (0-3) / RX-capable (4-7); channel 4 has no other
// claimant in this firmware.
const rmt_channel_t CHANNEL = RMT_CHANNEL_4;
gpio_num_t gPin;
RingbufHandle_t gRingBuf = nullptr;
float gTempC = 0, gHum = 0;
bool gOk = false;
portMUX_TYPE gMux = portMUX_INITIALIZER_UNLOCKED;

// One ACK pulse pair plus 40 data-bit pulse pairs, each pair packed into one rmt_item32_t.
const int EXPECTED_ITEMS = 41;

bool readOnce(float &tempC, float &humidityPct) {
  // Start signal: pull the line low >=18ms (DHT11 datasheet), then release it - the sensor
  // answers by pulling low itself, which RMT below is already armed to see.
  gpio_set_direction(gPin, GPIO_MODE_OUTPUT);
  gpio_set_level(gPin, 0);
  delay(20);
  gpio_set_direction(gPin, GPIO_MODE_INPUT);

  rmt_rx_start(CHANNEL, true);
  size_t len = 0;
  auto *items = (rmt_item32_t *)xRingbufferReceive(gRingBuf, &len, pdMS_TO_TICKS(50));
  rmt_rx_stop(CHANNEL);
  if (!items) return false;  // sensor never answered - not wired, or dead

  int n = (int)(len / sizeof(rmt_item32_t));
  if (n < EXPECTED_ITEMS) {
    vRingbufferReturnItem(gRingBuf, items);
    return false;  // sensor stopped partway through the frame
  }

  uint8_t data[5] = {0, 0, 0, 0, 0};
  for (int i = 0; i < 40; i++) {
    rmt_item32_t it = items[i + 1];  // items[0] is the sensor's ACK pulse, not a data bit
    uint32_t lowUs = it.level0 == 0 ? it.duration0 : it.duration1;
    uint32_t highUs = it.level0 == 1 ? it.duration0 : it.duration1;
    data[i / 8] <<= 1;
    if (highUs > lowUs) data[i / 8] |= 1;  // ~70us high = 1, ~26us high = 0 (50us low is the reference)
  }
  vRingbufferReturnItem(gRingBuf, items);

  if (data[4] != (uint8_t)(data[0] + data[1] + data[2] + data[3])) return false;  // checksum mismatch
  humidityPct = data[0] + data[1] * 0.1f;
  tempC = data[2] + (data[3] & 0x0f) * 0.1f;
  if (data[3] & 0x80) tempC = -tempC;
  return true;
}

void taskFn(void *) {
  for (;;) {
    float t, h;
    bool ok = readOnce(t, h);
    portENTER_CRITICAL(&gMux);
    gOk = ok;
    if (ok) { gTempC = t; gHum = h; }
    portEXIT_CRITICAL(&gMux);
    if (!ok) logEvent("dht11: read failed (no sensor wired, or a bad reading)");
    vTaskDelay(pdMS_TO_TICKS(ok ? 10000 : 1500));
  }
}

}  // namespace

void dht11Begin(int gpio) {
  gPin = (gpio_num_t)gpio;
  gpio_set_pull_mode(gPin, GPIO_PULLUP_ONLY);

  rmt_config_t cfg = {};
  cfg.rmt_mode = RMT_MODE_RX;
  cfg.channel = CHANNEL;
  cfg.gpio_num = gPin;
  cfg.clk_div = 80;  // 80MHz APB / 80 = 1MHz -> 1 tick = 1us, matches DHT11's pulse widths directly
  cfg.mem_block_num = 1;
  cfg.rx_config.idle_threshold = 250;  // end capture 250us after the last edge (longest real pulse is ~80us)
  cfg.rx_config.filter_en = true;
  cfg.rx_config.filter_ticks_thresh = 30;  // drop glitches shorter than ~30us; shortest real pulse is ~26us
  ESP_ERROR_CHECK(rmt_config(&cfg));
  ESP_ERROR_CHECK(rmt_driver_install(CHANNEL, 1000, 0));
  ESP_ERROR_CHECK(rmt_get_ringbuf_handle(CHANNEL, &gRingBuf));

  // Core 0: only Wi-Fi/BT and the (usually idle) USB-host task live there, versus core 1's
  // loop()/HTTP/camera plus the printer engine task, which is the one that gets busy while
  // printing. A 10s room-sensor poll doesn't belong competing with that.
  xTaskCreatePinnedToCore(taskFn, "dht11", 4096, nullptr, 1, nullptr, 0);
}

void dht11Snapshot(float &tempC, float &humidityPct, bool &ok) {
  portENTER_CRITICAL(&gMux);
  tempC = gTempC;
  humidityPct = gHum;
  ok = gOk;
  portEXIT_CRITICAL(&gMux);
}
