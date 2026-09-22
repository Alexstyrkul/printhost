// Room temperature/humidity via a DHT11 wired to a free GPIO (see docs/HANDOFF_ESP32_BRIDGE.md) -
// separate from the chip's own internal temperature sensor already reported in /status.
#pragma once

// Starts the low-priority background task that reads the sensor every 10 s.
void dht11Begin(int gpio);

// Latest reading. ok is false before the first successful read, or if the last read failed
// (no sensor wired, bad checksum, or a timed-out pulse).
void dht11Snapshot(float &tempC, float &humidityPct, bool &ok);
