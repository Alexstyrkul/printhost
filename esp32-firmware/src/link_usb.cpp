// The real wire: the CH340 USB-to-serial chip on the printer's mainboard, driven from the ESP32-S3's
// native USB port acting as a USB host.
//
// Safety notes (deliberate):
//  * Opening the link only performs USB control transfers (serial speed + frame format). No bytes are
//    written to the printer's serial line until the engine sends a command.
//  * The modem-control lines (DTR/RTS) are never touched. On some printer boards toggling them resets
//    the mainboard, which would ruin a print in progress.
extern "C" {
#include "usb/cdc_acm_host.h"
#include "usb/usb_host.h"
}
#include <Arduino.h>
#include <freertos/stream_buffer.h>

#include "common.h"
#include "printer.h"


namespace {

const uint16_t CH34X_VID = 0x1A86;
const uint16_t CH34X_PIDS[] = {0x7523, 0x7522, 0x5523};

// CH34x vendor requests (see the Linux ch341 driver / Espressif usb_host_ch34x_vcp).
const uint8_t REQ_TYPE_VENDOR_OUT = 0x40;
const uint8_t CH34X_CMD_WRITE = 0x9A;
const uint8_t LCR_ENABLE_RX = 0x80, LCR_ENABLE_TX = 0x40, LCR_CS8 = 0x03;

bool hostInstalled = false;
bool cdcInstalled = false;
cdc_acm_dev_hdl_t dev = nullptr;
// Guards `dev`: it's read/written from both our own task (open/close) and the USB host
// library's own event context (onEvent, on a real disconnect) with no other synchronization.
// Without this, a real unplug racing our own close() could call cdc_acm_host_close() on the
// same handle twice - the second call hits an already-released interface deep inside the
// vendored cdc_acm driver, which ESP_ERROR_CHECKs and panics the whole board.
portMUX_TYPE devMux = portMUX_INITIALIZER_UNLOCKED;
StreamBufferHandle_t rxBuf = nullptr;
volatile bool devGone = false;

// Atomically takes ownership of the current handle (clearing `dev`) so at most one caller ever
// gets a non-null result for a given open device - callers only call cdc_acm_host_close() if
// they get one back.
cdc_acm_dev_hdl_t claimDevForClose() {
  portENTER_CRITICAL(&devMux);
  cdc_acm_dev_hdl_t d = dev;
  dev = nullptr;
  portEXIT_CRITICAL(&devMux);
  return d;
}

void usbLibTask(void *) {
  for (;;) {
    uint32_t flags;
    usb_host_lib_handle_events(portMAX_DELAY, &flags);
    if (flags & USB_HOST_LIB_EVENT_FLAGS_NO_CLIENTS) usb_host_device_free_all();
  }
}

bool onData(const uint8_t *data, size_t len, void *) {
  if (rxBuf) xStreamBufferSend(rxBuf, data, len, 0);
  return true;
}

void onEvent(const cdc_acm_host_dev_event_data_t *ev, void *) {
  if (ev->type == CDC_ACM_HOST_DEVICE_DISCONNECTED) {
    devGone = true;
    logEvent("usb: printer device disconnected");
    cdc_acm_dev_hdl_t d = claimDevForClose();
    if (d) cdc_acm_host_close(d);
  } else if (ev->type == CDC_ACM_HOST_ERROR) {
    logEvent("usb: CDC error %d", ev->data.error);
  }
}

// Same divisor math as Espressif's CH34x driver (115200 baud -> factor/divisor registers).
bool baudRegs(unsigned baud, uint8_t &factor, uint8_t &divisor) {
  uint8_t a, b;
  unsigned long c;
  if (baud > 6000000 / 255) {
    b = 3;
    c = 6000000;
  } else if (baud > 750000 / 255) {
    b = 2;
    c = 750000;
  } else if (baud > 93750 / 255) {
    b = 1;
    c = 93750;
  } else {
    b = 0;
    c = 11719;
  }
  a = (uint8_t)(c / baud);
  if (a == 0 || a == 0xFF) return false;
  int d0 = c / a - baud, d1 = baud - c / (a + 1);
  if (d0 > d1) a++;
  a = 256 - a;
  factor = a;
  divisor = b;
  return true;
}

class UsbLink : public PrinterLink {
 public:
  const char *name() override { return "usb"; }

  // Starts the USB host stack once (idempotent). Needed before a device can even be seen.
  bool ensureHost(String &err) {
    if (!hostInstalled) {
      usb_host_config_t cfg = {};
      cfg.skip_phy_setup = false;
      cfg.intr_flags = ESP_INTR_FLAG_LEVEL1;
      esp_err_t e = usb_host_install(&cfg);
      if (e != ESP_OK) {
        err = String("USB host start failed: ") + esp_err_to_name(e);
        return false;
      }
      xTaskCreatePinnedToCore(usbLibTask, "usb_lib", 4096, nullptr, 10, nullptr, 0);
      hostInstalled = true;
    }
    if (!cdcInstalled) {
      esp_err_t e = cdc_acm_host_install(nullptr);
      if (e != ESP_OK) {
        err = String("USB CDC driver start failed: ") + esp_err_to_name(e);
        return false;
      }
      cdcInstalled = true;
    }
    return true;
  }

  bool devicePresent() override {
    String err;
    if (!ensureHost(err)) return false;
    uint8_t addrs[4];
    int n = 0;
    if (usb_host_device_addr_list_fill(sizeof(addrs), addrs, &n) != ESP_OK) return false;
    return n > 0;  // something is enumerated on the OTG port (only the printer is ever plugged in there)
  }

  bool open(String &err) override {
    if (!rxBuf) rxBuf = xStreamBufferCreate(4096, 1);
    if (!ensureHost(err)) return false;
    devGone = false;
    xStreamBufferReset(rxBuf);
    cdc_acm_host_device_config_t dc = {};
    dc.connection_timeout_ms = 2500;
    dc.out_buffer_size = 512;
    // IN transfers are exactly one packet (0 = the endpoint max packet size, 64 B). With a bigger buffer a printer reply
    // whose length is a multiple of 64 bytes never completes (no short packet ends it) and sits in the buffer until
    // the printer sends something else: a G92/M114 reply of exactly 64 B froze the print for 4 s at every layer.
    dc.in_buffer_size = 0;
    dc.event_cb = onEvent;
    dc.data_cb = onData;
    dc.user_arg = nullptr;
    esp_err_t e = ESP_FAIL;
    uint16_t pid = 0;
    for (int attempt = 0; attempt < 2 && dev == nullptr; attempt++) {
      for (uint16_t p : CH34X_PIDS) {
        e = cdc_acm_host_open(CH34X_VID, p, 0, &dc, &dev);
        if (e == ESP_OK) {
          pid = p;
          break;
        }
        dev = nullptr;
      }
    }
    if (e != ESP_OK || !dev) {
      err = "No CH340 serial chip found on the OTG port. Is the printer switched on and the cable in the OTG port?";
      return false;
    }
    logEvent("usb: CH34x %04x:%04x opened", CH34X_VID, pid);

    uint8_t factor, divisor;
    if (!baudRegs(115200, factor, divisor)) {
      err = "cannot compute the baud rate";
      close();
      return false;
    }
    uint16_t baudReg = ((uint16_t)factor << 8 | divisor) | 0x80;
    uint8_t lcr = LCR_ENABLE_RX | LCR_ENABLE_TX | LCR_CS8;  // 8N1
    esp_err_t e1 = cdc_acm_host_send_custom_request(dev, REQ_TYPE_VENDOR_OUT, CH34X_CMD_WRITE, 0x1312, baudReg, 0, nullptr);
    esp_err_t e2 = cdc_acm_host_send_custom_request(dev, REQ_TYPE_VENDOR_OUT, CH34X_CMD_WRITE, 0x2518, lcr, 0, nullptr);
    if (e1 != ESP_OK || e2 != ESP_OK) {
      err = "could not set the serial speed on the CH340";
      close();
      return false;
    }
    pendLen = pendPos = 0;
    lineLen = 0;
    return true;
  }

  void close() override {
    cdc_acm_dev_hdl_t d = claimDevForClose();
    if (d) cdc_acm_host_close(d);
  }

  bool isOpen() override { return dev != nullptr && !devGone; }

  bool writeBytes(const uint8_t *d, size_t n) override {
    while (n > 0) {
      if (!isOpen()) return false;
      size_t chunk = n > 512 ? 512 : n;
      if (cdc_acm_host_data_tx_blocking(dev, d, chunk, 1000) != ESP_OK) return false;
      d += chunk;
      n -= chunk;
    }
    return true;
  }

  int readLine(char *buf, size_t cap, uint32_t timeoutMs) override {
    uint32_t t0 = millis();
    for (;;) {
      while (pendPos < pendLen) {
        char c = (char)pend[pendPos++];
        if (c == '\n') {
          if (lineLen > 0) {
            size_t n = lineLen < cap - 1 ? lineLen : cap - 1;
            memcpy(buf, line, n);
            buf[n] = 0;
            lineLen = 0;

            return (int)n;
          }
        } else if (c != '\r' && lineLen + 1 < sizeof(line)) {
          line[lineLen++] = c;
        }
      }
      uint32_t left = millis() - t0 >= timeoutMs ? 0 : timeoutMs - (millis() - t0);
      uint32_t slice = left < 4 ? left : 4;
      size_t got = xStreamBufferReceive(rxBuf, pend, sizeof(pend), pdMS_TO_TICKS(slice));
      if (got == 0) {
        if (left <= slice) return -1;
        continue;
      }
      pendLen = got;
      pendPos = 0;
    }
  }


  void flushInput() override {
    if (rxBuf) xStreamBufferReset(rxBuf);
    pendLen = pendPos = 0;
    lineLen = 0;
  }

 private:
  // Receiving is always on: the bulk IN transfer is polled continuously (an earlier scheme that paused it to spare the
  // Wi-Fi lost the first bytes of printer lines and swallowed replies - the Wi-Fi trouble it was meant to cure came from
  // a crowded 2.4 GHz channel, not from USB).
  uint8_t pend[128];
  size_t pendLen = 0, pendPos = 0;
  char line[300];
  size_t lineLen = 0;
};

}  // namespace

PrinterLink *makeUsbLink() { return new UsbLink(); }
