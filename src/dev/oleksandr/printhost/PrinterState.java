package dev.oleksandr.printhost;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Snapshot of everything the dashboard needs to render. One instance lives in
 * PrinterService and is mutated under its own lock, then serialized to JSON on demand.
 */
public class PrinterState {

    public enum Phase {
        DISCONNECTED, CONNECTING, IDLE, UPLOADING, UPLOAD_VERIFIED, PRINTING, PAUSED, ERROR
    }

    public volatile Phase phase = Phase.DISCONNECTED;
    public volatile String lastError = "";

    public volatile double hotendTemp = 0;
    public volatile double hotendTarget = 0;
    public volatile double bedTemp = 0;
    public volatile double bedTarget = 0;

    public volatile double zOffset = 0;
    public volatile String firmwareInfo = "";

    public volatile String currentFile = ""; // SD-safe 8.3 name - what M23/M24 actually use
    public volatile String currentFileDisplay = ""; // original filename, for the UI only
    public volatile long uploadedBytes = 0;
    public volatile long expectedBytes = 0;

    public volatile int printProgressPercent = 0;
    public volatile long elapsedSeconds = 0;
    public volatile Integer fanSpeed = null; // null = unknown, not parsed yet

    public volatile boolean cameraOn = false;
    public volatile boolean torchOn = false;

    public volatile int batteryPercent = -1; // -1 = unknown
    public volatile boolean batteryCharging = false;
    public volatile boolean screenOn = false;
    public volatile Boolean plugOn = null; // null = unknown, not polled yet
    public volatile boolean unloadingFilament = false;
    public volatile boolean levelingBed = false;

    public synchronized JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("phase", phase.name());
            o.put("lastError", lastError);
            o.put("hotendTemp", hotendTemp);
            o.put("hotendTarget", hotendTarget);
            o.put("bedTemp", bedTemp);
            o.put("bedTarget", bedTarget);
            o.put("zOffset", zOffset);
            o.put("firmwareInfo", firmwareInfo);
            o.put("currentFile", currentFile);
            o.put("currentFileDisplay", currentFileDisplay);
            o.put("uploadedBytes", uploadedBytes);
            o.put("expectedBytes", expectedBytes);
            o.put("printProgressPercent", printProgressPercent);
            o.put("elapsedSeconds", elapsedSeconds);
            o.put("fanSpeed", fanSpeed == null ? JSONObject.NULL : fanSpeed);
            o.put("cameraOn", cameraOn);
            o.put("torchOn", torchOn);
            o.put("batteryPercent", batteryPercent);
            o.put("batteryCharging", batteryCharging);
            o.put("screenOn", screenOn);
            o.put("plugOn", plugOn == null ? JSONObject.NULL : plugOn);
            o.put("unloadingFilament", unloadingFilament);
            o.put("levelingBed", levelingBed);

            long remaining = 0;
            if (printProgressPercent > 0 && printProgressPercent < 100) {
                remaining = elapsedSeconds * (100 - printProgressPercent) / printProgressPercent;
            }
            o.put("estimatedRemainingSeconds", remaining);
        } catch (JSONException e) {
            // JSONObject.put only throws on NaN/Infinite double values or null keys; none apply here.
        }
        return o;
    }
}
