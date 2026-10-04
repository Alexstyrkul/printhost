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
    /** A file on the ESP32's SD card is chosen and verified, ready to print once the printer is connected. */
    public volatile boolean fileReady = false;

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
    public volatile Integer currentLayer = null; // null = unknown / file has no layer markers
    public volatile Integer totalLayers = null;

    public volatile int batteryPercent = -1; // -1 = unknown
    public volatile boolean batteryCharging = false;
    public volatile boolean screenOn = false;
    public volatile Boolean plugOn = null; // null = unknown, not polled yet
    public volatile boolean unloadingFilament = false;
    public volatile boolean levelingBed = false;

    // ---- manual control (what the printer's own screen offers) ----
    /** The phone talks to the board's built-in pretend printer instead of the real one (for testing). */
    public volatile boolean simulator = false;
    /** What a manual action is doing right now ("Homing", "Loading filament"...), "" = nothing. */
    public volatile String manualBusy = "";
    /** Homed through this app since the motors were last released: only then may the axes be moved. */
    public volatile boolean homed = false;
    public volatile Double posX = null, posY = null, posZ = null; // null = unknown
    public volatile Integer feedPercent = null; // print speed (M220), null = unknown
    public volatile Integer flowPercent = null; // extrusion (M221), null = unknown
    /** Fan value set by hand (0-255); shown instead of the file's value until the file changes the fan itself. */
    public volatile Integer manualFan = null;
    public volatile Integer manualFanOverFile = null;
    /** Z offset moved during a print (babysteps), not yet stored in the printer's memory. */
    public volatile boolean zOffsetUnsaved = false;
    /** The printer's own stored settings for the panel's Information block (from M503): {label, value} pairs. */
    public volatile String[][] machineInfo = new String[0][];
    /** The printer's preheat presets (M145): [{name, hotend, bed}]. */
    public volatile org.json.JSONArray presets = defaultPresets();

    static org.json.JSONArray defaultPresets() {
        org.json.JSONArray a = new org.json.JSONArray();
        try {
            a.put(new JSONObject().put("name", "PLA").put("hotend", 200).put("bed", 60));
            a.put(new JSONObject().put("name", "TPU").put("hotend", 230).put("bed", 70));
        } catch (JSONException ignored) {
        }
        return a;
    }

    public volatile String scheduledFile = null; // SD short name, null = nothing scheduled
    public volatile String scheduledFileDisplay = null;
    public volatile long scheduledAtMillis = 0;
    public volatile String scheduledStatus = null; // "PENDING" | "FAILED", null = no active schedule
    public volatile boolean scheduledOnPhone = false; // the file is still on the phone, not yet on the board
    public volatile long scheduledSendAtMillis = 0;   // when the phone powers the plug and sends it to the board

    public volatile boolean autoShutoffEnabled = false; // power off the plug once cooled after a print

    /** The board's "interrupted" object (a print cut off by a board restart or an engine error), null = none. */
    public volatile JSONObject interrupted = null;
    /** The board's own description of its last crash ("" = none since it was powered up). */
    public volatile String boardCrash = "";
    /** Last alert raised (print interrupted, heaters left on...), shown as a banner; "" = none. */
    public volatile String alert = "";
    public volatile long alertAtMillis = 0;

    public synchronized JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("phase", phase.name());
            o.put("lastError", lastError);
            o.put("fileReady", fileReady);
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
            Integer fan = manualFan != null ? manualFan : fanSpeed;
            o.put("fanSpeed", fan == null ? JSONObject.NULL : fan);
            o.put("simulator", simulator);
            o.put("manualBusy", manualBusy);
            o.put("homed", homed);
            o.put("posX", posX == null ? JSONObject.NULL : posX);
            o.put("posY", posY == null ? JSONObject.NULL : posY);
            o.put("posZ", posZ == null ? JSONObject.NULL : posZ);
            o.put("feedPercent", feedPercent == null ? JSONObject.NULL : feedPercent);
            o.put("flowPercent", flowPercent == null ? JSONObject.NULL : flowPercent);
            o.put("zOffsetUnsaved", zOffsetUnsaved);
            o.put("presets", presets);
            o.put("currentLayer", currentLayer == null ? JSONObject.NULL : currentLayer);
            o.put("totalLayers", totalLayers == null ? JSONObject.NULL : totalLayers);
            o.put("batteryPercent", batteryPercent);
            o.put("batteryCharging", batteryCharging);
            o.put("screenOn", screenOn);
            o.put("plugOn", plugOn == null ? JSONObject.NULL : plugOn);
            o.put("unloadingFilament", unloadingFilament);
            o.put("levelingBed", levelingBed);
            o.put("scheduledFile", scheduledFile == null ? JSONObject.NULL : scheduledFile);
            o.put("scheduledFileDisplay", scheduledFileDisplay == null ? JSONObject.NULL : scheduledFileDisplay);
            o.put("scheduledAtMillis", scheduledAtMillis);
            o.put("scheduledStatus", scheduledStatus == null ? JSONObject.NULL : scheduledStatus);
            o.put("scheduledOnPhone", scheduledOnPhone);
            o.put("scheduledSendAtMillis", scheduledSendAtMillis);
            o.put("autoShutoffEnabled", autoShutoffEnabled);
            o.put("interrupted", interrupted == null ? JSONObject.NULL : interrupted);
            o.put("boardCrash", boardCrash);
            o.put("alert", alert);
            o.put("alertAtMillis", alertAtMillis);

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
