package dev.oleksandr.printhost;

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

    // volatile and read without the lock: the panel asks from the UI thread, which must never wait behind a long
    // synchronized call here (an unload holds this object for minutes; a poll of an unreachable board for seconds)
    private volatile boolean open = false;
    private JSONObject lastStatus = null;
    private long lastStatusAt = 0;
    private long finishedSeen = 0;
    // The board picks a new random id at every boot: if it changes under a running print, the board restarted mid-print.
    private String bootIdSeen = "";
    // Set while this phone is stopping the print itself, so the board going idle is not reported as an interruption.
    private volatile boolean stopping = false;
    // This phone saw the board printing (or started a print) since the last end: only then can a print be "interrupted".
    private boolean tracking = false;
    private String cachedFw = "";
    private String cachedZOffset = "echo:Z-0.000";

    public EspPrinterConnection(String espBase, String linkKind) {
        super(espBase);
        this.linkKind = linkKind;
    }

    // ---- connection ---------------------------------------------------------------------------

    /** The board's own printer state (NO_LINK, DISCONNECTED, IDLE, PRINTING, PAUSED, ERROR), or "" when it cannot be reached. */
    public synchronized String boardState() {
        try {
            return statusFresh().optString("state");
        } catch (IOException e) {
            return "";
        }
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    /** Selecting the link and connecting send no gcode; a USB connect only sets the serial speed. */
    @Override
    public synchronized boolean connect() throws IOException {
        JSONObject st = statusFresh();
        String state = st.optString("state");
        boolean busy = "PRINTING".equals(state) || "PAUSED".equals(state);
        // The board connects to the printer by itself when it appears on USB: then there is nothing to (re)open.
        boolean alreadyUp = "IDLE".equals(state) && linkKind.equals(st.optString("link"));
        if (!busy && !alreadyUp) {
            call("/printer/link?kind=" + linkKind, 8000);
            call("/printer/connect", CONNECT_TIMEOUT_MS);
        }
        JSONObject now = statusFresh();
        finishedSeen = now.optLong("finished");
        bootIdSeen = now.optString("bootId");
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
        // A rehearsal (dry run, e.g. started by curl for testing) drives the board's engine and
        // "finished" counter exactly like a real print, but nothing was actually printed - never
        // let this phone start tracking one as if it were the job it uploaded. Without this, a
        // reconnect landing mid-rehearsal (resumeActiveSdPrintIfAny()) could pick it up, and its
        // end would then look like a real print just finished: 100% stuck on the dashboard next
        // to a Start button, and auto-shutoff wrongly armed for a run that never heated anything.
        if (st.optBoolean("dry")) {
            return "Not SD printing\nok";
        }
        // The board restarted under the print (2026-09-29: a crash at 30%). Its counters start from zero again, which
        // used to look like "finished" here - the dashboard said done and the plug auto-shutoff got armed.
        String boot = st.optString("bootId");
        if (!boot.isEmpty() && !bootIdSeen.isEmpty() && !boot.equals(bootIdSeen)) {
            bootIdSeen = boot;
            finishedSeen = st.optLong("finished");
            if (!tracking) return "Not SD printing\nok";
            tracking = false;
            throw new IOException("Printer bridge error: print interrupted - the board restarted during the print");
        }
        if (bootIdSeen.isEmpty()) bootIdSeen = boot;
        long finished = st.optLong("finished");
        if (finished != finishedSeen) {
            finishedSeen = finished;
            tracking = false;
            return "Done printing file\nok";
        }
        if ("PRINTING".equals(state) || "PAUSED".equals(state)) {
            tracking = true;
            return "SD printing byte " + st.optLong("bytesDone") + "/" + st.optLong("bytesTotal") + "\nok";
        }
        // Not printing, and no finish was counted: the print did not end by itself. Only a Stop from here is normal.
        if (tracking && !stopping && !"stopped".equals(st.optString("lastEnd"))) {
            tracking = false;
            String why = st.optString("error");
            throw new IOException("Printer bridge error: print interrupted - the board is " + state + (why.isEmpty() ? "" : " (" + why + ")"));
        }
        return "Not SD printing\nok";
    }

    /** The board's last /printer/status (fresh within a moment), or null when it cannot be reached. */
    public synchronized JSONObject boardStatus() {
        try {
            return statusFresh();
        } catch (IOException e) {
            return null;
        }
    }

    /** Resumes the board's interrupted print. mode: auto | kept | restarted; zNow only for "restarted". */
    public synchronized void recoverPrint(long offset, String mode, double zNow) throws IOException {
        stopping = false;
        tracking = true;
        StringBuilder q = new StringBuilder("/printer/recover?mode=").append(URLEncoder.encode(mode, "UTF-8"));
        if (offset > 0) q.append("&offset=").append(offset);
        if (zNow > 0) q.append("&zNow=").append(String.format(Locale.US, "%.2f", zNow));
        call(q.toString(), 15000);
        JSONObject now = statusFresh();
        finishedSeen = now.optLong("finished");
        bootIdSeen = now.optString("bootId");
    }

    /** Makes the board forget its recorded crash (best effort). */
    public void clearCrash() {
        tryCall("/crash/clear", 5000);
        lastStatus = null;
    }

    public synchronized void discardInterrupted() throws IOException {
        call("/printer/recover/discard", 10000);
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
        stopping = false;
        tracking = true;
        call("/printer/print?file=" + URLEncoder.encode(filename, "UTF-8"), 15000);
        return "ok";
    }

    @Override
    public synchronized String stopPrint() throws IOException {
        stopping = true;
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

    private volatile boolean unloadCancelled = false;

    /** Stops an unload that is still heating the nozzle (once the filament moves it runs to the end). */
    public void cancelUnload() {
        unloadCancelled = true;
    }

    @Override
    public synchronized void unloadFilament(LineListener listener) throws IOException {
        unloadCancelled = false;
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
            if (unloadCancelled) {
                gcode("M104 S0", 5000);
                throw new IOException("Cancelled");
            }
            sleepQuietly(500);
        }
        gcode("G91", 5000);
        gcode("G1 E15.0 F240", 60000);
        gcode("G1 E-90.0 F120", 120000);
        gcode("G90", 5000);
        gcode("M104 S" + UNLOAD_COOL_TEMP_C, 5000);
    }

    @Override
    public synchronized void levelBed(long levelTimeoutMs) throws IOException {
        // Home first: without it this printer answers G29 with "ok" at once, probes nothing and leaves an empty (all
        // zero) mesh behind - and saving that wiped the stored mesh (2026-10-04). So: home, probe, and save only if the
        // probing really ran and the mesh it left is not empty.
        gcode("G28", 120000);
        long t0 = System.currentTimeMillis();
        gcode("G29", Math.max(levelTimeoutMs, 60000));
        if (System.currentTimeMillis() - t0 < 4000) {
            throw new IOException("The printer did not run the leveling (it answered at once). Nothing was saved.");
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[+-]\\d+\\.\\d+").matcher(firstGrid(gcode("M420 V", 8000)));
        boolean any = false, nonZero = false;
        while (m.find()) {
            any = true;
            if (Math.abs(Double.parseDouble(m.group())) > 0.0005) nonZero = true;
        }
        if (any && !nonZero) throw new IOException("The printer reports an empty bed mesh after leveling. Nothing was saved.");
        gcode("M420 S1", 5000);
        gcode("M500", 5000);
    }

    /** The first grid of an "M420 V" report: the mesh itself. This firmware prints a second, 16x16 interpolated one after
     *  the line "Subdivided with ...", which is not what was probed. */
    static String firstGrid(String m420) {
        int i = m420.indexOf("Subdivided");
        return i < 0 ? m420 : m420.substring(0, i);
    }

    @Override
    public synchronized String queryLevelingGrid() throws IOException {
        return firstGrid(gcode("M420 V", 8000));
    }

    // ---- manual control ------------------------------------------------------------------------

    /** One command for the manual controls. Not synchronized: homing takes many seconds and must not hold up the
     *  temperature polls. While a print runs the board accepts only its short list of tune commands. */
    public String manualGcode(String cmd, long timeoutMs) throws IOException {
        return gcode(cmd, timeoutMs);
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

    /** Commands that can take longer than this are sent without waiting for them (see gcode()). */
    private static final long LONG_COMMAND_MS = 6000;

    /**
     * One command to the printer through the board. A quick one is a single request. One that may take long (homing,
     * leveling, waiting for a move) is queued on the board and its result is asked for until it is done: the board
     * handles one HTTP request at a time, so a request that waited for the printer made the board look dead to
     * everything else (the dashboard showed "Camera is off", the panel "Printer is not connected").
     */
    private String gcode(String cmd, long timeoutMs) throws IOException {
        String q = "/printer/gcode?cmd=" + URLEncoder.encode(cmd, "UTF-8") + "&timeout=" + timeoutMs;
        if (timeoutMs <= LONG_COMMAND_MS) return call(q, timeoutMs + 10000).optString("reply");
        JSONObject started = call(q + "&async=1", 10000);
        if (!started.has("job")) return started.optString("reply");  // a board that does not know "async" answered the old way
        long job = started.optLong("job");
        long deadline = System.currentTimeMillis() + timeoutMs + 10000, wait = 80;
        for (;;) {
            sleepQuietly(wait);
            wait = Math.min(wait * 2, 400);
            JSONObject j = null;
            try {
                j = new JSONObject(httpGet("/printer/job?id=" + job));
            } catch (org.json.JSONException e) {
                // unreadable answer: ask again
            } catch (IOException e) {
                // a lost request does not stop the command on the board: keep asking until the deadline
            }
            if (j != null) {
                if (!j.optBoolean("ok")) throw new IOException("Printer bridge: the board lost the command (it restarted?)");
                if (j.optBoolean("done")) {
                    lastStatus = null;
                    if (!j.optBoolean("success")) throw new IOException("Printer bridge: " + j.optString("error", "command failed"));
                    return j.optString("reply");
                }
            }
            if (System.currentTimeMillis() > deadline) throw new IOException("Printer bridge: no answer from the board in time");
        }
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
