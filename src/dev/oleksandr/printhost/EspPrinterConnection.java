package dev.oleksandr.printhost;

import android.hardware.usb.UsbDevice;

import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.Locale;

/**
 * The real printer connection when the ESP32 board drives the printer: this phone never opens USB.
 * Every call is translated into an HTTP request to the board's printer engine (/printer/...), and the
 * board answers in the same Marlin-style text PrinterService already parses, so nothing above this
 * class knows the difference.
 *
 * The board streams the gcode to the printer itself, so a print keeps going when this phone is
 * disconnected or restarted (unlike a stream held by the phone).
 *
 * Files live on the board's SD card (see EspStoreConnection).
 */
public class EspPrinterConnection extends EspStoreConnection {

    private static final long STATUS_TTL_MS = 400;
    private static final long CONNECT_TIMEOUT_MS = 40000;

    /** "usb" = the real printer on the board's OTG port, "sim" = the board's built-in fake Marlin. */
    private final String linkKind;

    private boolean open = false;
    private JSONObject lastStatus = null;
    private long lastStatusAt = 0;
    private long finishedSeen = 0;
    private String cachedFw = "";
    private String cachedZOffset = "echo:Z-0.000";

    public EspPrinterConnection(String espBase, String linkKind) {
        super(espBase);
        this.linkKind = linkKind;
    }

    // ---- connection ---------------------------------------------------------------------------

    @Override
    public synchronized boolean isOpen() {
        return open;
    }

    /** Selecting the link and connecting send no gcode; a USB connect only sets the serial speed. */
    @Override
    public synchronized boolean connect(UsbDevice preferredDevice) throws IOException {
        JSONObject st = statusFresh();
        String state = st.optString("state");
        boolean busy = "PRINTING".equals(state) || "PAUSED".equals(state);
        if (!busy) {
            call("/printer/link?kind=" + linkKind, 8000);
            call("/printer/connect", CONNECT_TIMEOUT_MS);
        }
        finishedSeen = statusFresh().optLong("finished");
        open = true;
        return true;
    }

    @Override
    public synchronized void disconnect() {
        // The board keeps printing on its own; only the monitoring link goes away. A refused
        // disconnect (it is mid-print) is fine: this phone just stops watching.
        try {
            HttpCall c = tryCall("/printer/disconnect", 5000);
            if (c == null) return;
        } finally {
            open = false;
        }
    }

    // ---- info queries -------------------------------------------------------------------------

    @Override
    public synchronized String queryFirmwareInfo() throws IOException {
        JSONObject st = statusFresh();
        String fw = st.optString("fw");
        if (fw.isEmpty() && !isBusy(st)) {
            String reply = gcode("M115", 5000);
            int i = reply.indexOf("FIRMWARE_NAME");
            fw = i >= 0 ? reply.substring(i).split("\n")[0] : reply.split("\n")[0];
        }
        if (!fw.isEmpty()) cachedFw = fw;
        return cachedFw + "\nok";
    }

    @Override
    public synchronized String queryZOffset() throws IOException {
        if (!isBusy(statusFresh())) {
            String reply = gcode("M851", 5000);
            if (reply.contains("Z")) cachedZOffset = reply;
        }
        return cachedZOffset;
    }

    @Override
    public synchronized String pollTemperatures() throws IOException {
        return pollTemperatures(null);
    }

    @Override
    public synchronized String pollTemperatures(LineListener listener) throws IOException {
        JSONObject st = statusFresh();
        String line = String.format(Locale.US, "ok T:%.1f /%.1f B:%.1f /%.1f @:0 B@:0",
                st.optDouble("hotend"), st.optDouble("hotendTarget"), st.optDouble("bed"), st.optDouble("bedTarget"));
        if (listener != null) listener.onLine(line);
        return line;
    }

    @Override
    public synchronized String pollProgress() throws IOException {
        JSONObject st = statusFresh();
        String state = st.optString("state");
        if ("ERROR".equals(state) && st.optLong("bytesTotal") > 0 && st.optLong("bytesDone") < st.optLong("bytesTotal")) {
            throw new IOException("Printer bridge error: " + st.optString("error"));
        }
        long finished = st.optLong("finished");
        if (finished != finishedSeen) {
            finishedSeen = finished;
            return "Done printing file\nok";
        }
        if ("PRINTING".equals(state) || "PAUSED".equals(state)) {
            return "SD printing byte " + st.optLong("bytesDone") + "/" + st.optLong("bytesTotal") + "\nok";
        }
        return "Not SD printing\nok";
    }

    @Override
    public synchronized String queryCurrentFilename() throws IOException {
        JSONObject st = statusFresh();
        return isBusy(st) ? "Current file: " + st.optString("file") + "\nok" : "No file\nok";
    }

    @Override
    public synchronized String pollPrintTime() throws IOException {
        long s = statusFresh().optLong("elapsedMs") / 1000;
        return String.format(Locale.US, "echo:Print time: %dh %dm %ds\nok", s / 3600, (s % 3600) / 60, s % 60);
    }

    // ---- print control ------------------------------------------------------------------------

    @Override
    public synchronized String startPrint(String filename) throws IOException {
        if (fileSize(filename) < 0) {
            throw new IOException("Printer rejected filename \"" + filename + "\": open failed");
        }
        selectedName = filename;
        call("/printer/print?file=" + URLEncoder.encode(filename, "UTF-8"), 15000);
        return "ok";
    }

    @Override
    public synchronized String stopPrint() throws IOException {
        call("/printer/stop", 10000);
        return "ok";
    }

    @Override
    public synchronized String stopPausedPrint() throws IOException {
        return stopPrint();
    }

    @Override
    public void requestAbort() {
        // Stop is a separate, immediate HTTP request handled by the board's engine.
    }

    @Override
    public synchronized String pausePrint() throws IOException {
        call("/printer/pause", 8000);
        return "ok";
    }

    @Override
    public synchronized String resumePrint() throws IOException {
        call("/printer/resume", 8000);
        return "ok";
    }

    // ---- maintenance sequences (same gcode the USB connection sends) --------------------------

    private static final int UNLOAD_HEAT_TEMP_C = 240;
    private static final int UNLOAD_COOL_TEMP_C = 140;

    @Override
    public synchronized void unloadFilament(LineListener listener) throws IOException {
        gcode("M104 S" + UNLOAD_HEAT_TEMP_C, 5000);
        // Wait for the heat by watching temperatures, so the dashboard shows them live.
        long deadline = System.currentTimeMillis() + 10 * 60 * 1000L;
        while (System.currentTimeMillis() < deadline) {
            JSONObject st = statusFresh();
            if (listener != null) {
                listener.onLine(String.format(Locale.US, "T:%.1f /%.1f B:%.1f /%.1f",
                        st.optDouble("hotend"), st.optDouble("hotendTarget"), st.optDouble("bed"), st.optDouble("bedTarget")));
            }
            if (st.optDouble("hotend") >= UNLOAD_HEAT_TEMP_C - 3) break;
            sleepQuietly(1000);
        }
        gcode("G91", 5000);
        gcode("G1 E15.0 F240", 60000);
        gcode("G1 E-90.0 F120", 120000);
        gcode("G90", 5000);
        gcode("M104 S" + UNLOAD_COOL_TEMP_C, 5000);
    }

    @Override
    public synchronized void levelBed(long levelTimeoutMs) throws IOException {
        gcode("G29", Math.max(levelTimeoutMs, 60000));
        gcode("M420 S1", 5000);
        gcode("M500", 5000);
    }

    @Override
    public synchronized String queryLevelingGrid() throws IOException {
        return gcode("M420 V", 8000);
    }

    // ---- HTTP to the board's printer engine ---------------------------------------------------

    private boolean isBusy(JSONObject st) {
        String s = st.optString("state");
        return "PRINTING".equals(s) || "PAUSED".equals(s);
    }

    /** GET /printer/status, cached for a moment: PrinterService asks for several values per poll. */
    private JSONObject statusFresh() throws IOException {
        long now = System.currentTimeMillis();
        if (lastStatus != null && now - lastStatusAt < STATUS_TTL_MS) return lastStatus;
        try {
            lastStatus = new JSONObject(httpGet("/printer/status"));
        } catch (org.json.JSONException e) {
            throw new IOException("Unreadable answer from the printer bridge");
        }
        lastStatusAt = System.currentTimeMillis();
        return lastStatus;
    }

    private String gcode(String cmd, long timeoutMs) throws IOException {
        JSONObject r = call("/printer/gcode?cmd=" + URLEncoder.encode(cmd, "UTF-8") + "&timeout=" + timeoutMs, timeoutMs + 10000);
        return r.optString("reply");
    }

    /** POSTs to the engine; throws with the engine's own message if it refused. Invalidates the status cache. */
    private JSONObject call(String path, long readTimeoutMs) throws IOException {
        HttpCall c = tryCall(path, readTimeoutMs);
        if (c == null) throw new IOException("No answer from the printer bridge");
        lastStatus = null;
        if (!c.json.optBoolean("ok")) {
            throw new IOException("Printer bridge: " + c.json.optString("error", "request refused"));
        }
        return c.json;
    }

    private static class HttpCall {
        JSONObject json;
    }

    /** Returns null only when the board could not be reached at all; a refusal comes back with ok=false. */
    private HttpCall tryCall(String path, long readTimeoutMs) {
        try {
            java.net.HttpURLConnection c = open(path, "POST");
            c.setReadTimeout((int) Math.min(readTimeoutMs, Integer.MAX_VALUE));
            c.setFixedLengthStreamingMode(0);
            c.setDoOutput(true);
            c.getOutputStream().close();
            int code = c.getResponseCode();
            java.io.InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            if (in != null) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                in.close();
            }
            c.disconnect();
            HttpCall h = new HttpCall();
            h.json = new JSONObject(bos.toString("UTF-8"));
            return h;
        } catch (Exception e) {
            return null;
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
