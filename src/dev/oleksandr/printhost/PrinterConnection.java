package dev.oleksandr.printhost;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.util.Log;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * All hardware access to the printer goes through here, and only through the whitelisted
 * methods below (see PRINTHOST_PLAN.md's command table). No arbitrary G-code is ever accepted
 * from callers. Every method that touches the port is `synchronized` so only one command is
 * in flight on the wire at a time.
 *
 * Baud rate: 115200 (typical stock Marlin default for Creality boards). Change BAUD_RATE below
 * if the connected board was reconfigured to 250000.
 */
public class PrinterConnection {

    private static final String TAG = "PrinterConnection";
    private static final int BAUD_RATE = 115200;
    private static final long DEFAULT_TIMEOUT_MS = 5000;
    private static final long UPLOAD_LINE_TIMEOUT_MS = 8000;

    private final UsbManager usbManager;
    private UsbDeviceConnection usbConnection;
    private UsbSerialPort port;
    private final byte[] readBuf = new byte[4096];
    private final ByteArrayOutputStream leftover = new ByteArrayOutputStream();

    public PrinterConnection(UsbManager usbManager) {
        this.usbManager = usbManager;
    }

    public synchronized boolean isOpen() {
        return port != null;
    }

    /** Finds the first probeable serial device and opens it. Returns false if none found/permitted. */
    public synchronized boolean connect(UsbDevice preferredDevice) throws IOException {
        if (port != null) {
            return true;
        }
        List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
        if (drivers.isEmpty()) {
            throw new IOException("No supported serial device found");
        }
        UsbSerialDriver driver = drivers.get(0);
        if (preferredDevice != null) {
            for (UsbSerialDriver d : drivers) {
                if (d.getDevice().equals(preferredDevice)) {
                    driver = d;
                    break;
                }
            }
        }
        if (driver.getPorts().isEmpty()) {
            throw new IOException("Driver exposes no ports");
        }
        UsbSerialPort candidatePort = driver.getPorts().get(0);
        UsbDeviceConnection connection = usbManager.openDevice(driver.getDevice());
        if (connection == null) {
            throw new IOException("openDevice() failed - no permission or device gone");
        }
        try {
            candidatePort.open(connection);
            candidatePort.setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
        } catch (IOException e) {
            closeQuietly(candidatePort);
            connection.close();
            throw e;
        }
        this.usbConnection = connection;
        this.port = candidatePort;
        leftover.reset();
        drainBootNoise(candidatePort);
        return true;
    }

    /**
     * Marlin's serial line-numbering state (used for the N/checksum transport - see
     * uploadFile()) is a property of the connection. Reset before every upload (not just once at
     * connect) - required because "M110 N0" itself contains the letter 'N', so it must always go
     * through the same wrapper as content lines, and because a failed M28 (e.g. "open failed" on
     * an invalid filename - confirmed on real hardware) leaves our own nextLineNumber counter
     * ahead of where the firmware's last_N actually is, since the firmware never advances last_N
     * for lines it rejected. Resetting at the start of every upload makes each attempt
     * self-contained instead of accumulating drift across failed retries. Must run BEFORE M28
     * opens the file - this firmware writes every line it receives during M28 recording straight
     * into the file except M29, so an M110 sent mid-upload would end up as a stray line in it.
     */
    private int nextLineNumber = 1;

    private void resetLineNumbering() throws IOException {
        try {
            sendNumberedLineWithResend(0, "M110 N0");
            nextLineNumber = 1;
        } catch (TimeoutException e) {
            throw new IOException("M110 line-number reset failed", e);
        }
    }

    /**
     * CH340/Arduino-clone boards reset on DTR toggle when the port opens, then print a boot
     * banner before Marlin is ready. Sending M115 immediately can catch that banner mid-stream
     * (observed on real hardware: garbage bytes prepended to the M115 response). Give the board
     * time to finish resetting, then discard whatever it sent before we issue our first command.
     */
    private static void drainBootNoise(UsbSerialPort p) {
        try {
            Thread.sleep(1800);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        byte[] scratch = new byte[256];
        try {
            while (p.read(scratch, 200) > 0) {
                // discard
            }
        } catch (IOException e) {
            // best-effort drain; a failure here just means the first real command might retry
        }
    }

    public synchronized void disconnect() {
        closeQuietly(port);
        port = null;
        if (usbConnection != null) {
            usbConnection.close();
            usbConnection = null;
        }
        leftover.reset();
    }

    private static void closeQuietly(UsbSerialPort p) {
        if (p == null) return;
        try {
            p.close();
        } catch (IOException e) {
            Log.w(TAG, "port.close() failed (ignored, device likely already unplugged)", e);
        }
    }

    // ---- whitelisted high-level commands -------------------------------------------------

    public synchronized String queryFirmwareInfo() throws IOException, TimeoutException {
        return sendAndWaitForOk("M115", DEFAULT_TIMEOUT_MS);
    }

    public synchronized String queryZOffset() throws IOException, TimeoutException {
        return sendAndWaitForOk("M851", DEFAULT_TIMEOUT_MS);
    }

    public synchronized String pollTemperatures() throws IOException, TimeoutException {
        return pollTemperatures(null);
    }

    /** listener sees every line while this M105 sits queued behind a blocking M109/M190 from
     *  the print file - lets the caller update live temps from auto-reports before the eventual
     *  'ok' arrives, instead of only once the (possibly minutes-long) heat finishes. */
    public synchronized String pollTemperatures(LineListener listener) throws IOException, TimeoutException {
        return sendAndWaitForOk("M105", DEFAULT_TIMEOUT_MS, listener);
    }

    public synchronized String pollProgress() throws IOException, TimeoutException {
        return sendAndWaitForOk("M27", DEFAULT_TIMEOUT_MS);
    }

    /** "M27 C" - confirmed in the real firmware source (gcode/sd/M27.cpp) - reports "Current
     *  file: NAME" for whatever's selected/printing right now. Used to recover the filename
     *  after reconnecting to a printer that's already mid SD-print (this app's own process
     *  restarting doesn't stop or reselect the printer's job). */
    public synchronized String queryCurrentFilename() throws IOException, TimeoutException {
        return sendAndWaitForOk("M27 C", DEFAULT_TIMEOUT_MS);
    }

    public synchronized String pollPrintTime() throws IOException, TimeoutException {
        return sendAndWaitForOk("M31", DEFAULT_TIMEOUT_MS);
    }

    /**
     * "L" asks this firmware to include each entry's long filename (LFN) alongside its 8.3
     * short name, when the FAT32 directory entry actually has one - which only happens for
     * files that were written by something that speaks LFN (a card reader on a computer), never
     * for files this app wrote itself via M28 (see SdFilenameMap's javadoc for why). Confirmed
     * on real hardware: for an 8.3-only entry, "M20 L" returns byte-identical output to plain
     * "M20" - there's simply nothing extra to add - so requesting L is always safe.
     */
    public synchronized String listSdFiles() throws IOException, TimeoutException {
        return sendAndWaitForOk("M20 L", DEFAULT_TIMEOUT_MS);
    }

    public synchronized String startPrint(String filename) throws IOException, TimeoutException {
        sendAndWaitForOk("M23 " + filename, DEFAULT_TIMEOUT_MS);
        return sendAndWaitForOk("M24", DEFAULT_TIMEOUT_MS);
    }

    /**
     * Full stop (can't be resumed after this) - confirmed from this Creality-modified Marlin
     * fork's own source (CrealityOfficial/Ender-3V3-SE):
     *
     * - Must NOT be preceded by M25. Marlin/src/gcode/sd/M524.cpp only calls the real abort
     *   routine (card.abortFilePrintSoon()) when IS_SD_PRINTING() is true; otherwise it silently
     *   falls back to just closing the file handle. M25 flips IS_SD_PRINTING() to false (it
     *   pauses via M125's park-on-pause path on this firmware), so an M25-then-M524 sequence -
     *   what this method used to send - defeats M524's own abort entirely: the job looks stopped
     *   on our side (M524 still returns 'ok'), but the printer just stays paused. Confirmed on
     *   real hardware: the printer's own screen kept showing "Paused" and never presented the
     *   finished print, until the user pressed Stop physically on the touchscreen.
     * - The touchscreen's own Stop handler (Marlin/src/lcd/dwin/e3v2/dwin.cpp) also kills heat
     *   SYNCHRONOUSLY - thermalManager.disable_all_heaters() plus target 0 - before calling
     *   card.abortFilePrintSoon(). M524 alone only sets a flag for Marlin's main loop to notice
     *   and reset heat later, which is asynchronous and can lag behind a blocking M109/M190 still
     *   in flight (confirmed on real hardware: ~35s once, while the file's heat-up was still
     *   running). M104 S0 / M140 S0 up front close that gap - they're pure heater-target commands,
     *   independent of card.flag.sdprinting, so sending them before M524 doesn't affect it.
     *
     * See PRINTHOST_STATUS.md #6 for the DWIN screen caveat this doesn't (and structurally can't
     * fully) fix: no gcode command reaches the touchscreen's own checkkey/Popup_Window_Home UI
     * state, only card.abortFilePrintSoon() and the thermal/timer state that command touches.
     */
    public synchronized String stopPrint() throws IOException, TimeoutException {
        sendAndWaitForOk("M104 S0", DEFAULT_TIMEOUT_MS);
        sendAndWaitForOk("M140 S0", DEFAULT_TIMEOUT_MS);
        return sendAndWaitForOk("M524", DEFAULT_TIMEOUT_MS);
    }

    /**
     * Stops a job that's already PAUSED. M524 alone would silently no-op here too (see
     * stopPrint()'s javadoc) - a paused job already has IS_SD_PRINTING() == false. Briefly
     * resuming (M24) flips it true again just long enough for the immediately-following M524 to
     * take the real abort path instead of M524.cpp's "just close the file" fallback. Heaters are
     * killed first (same as stopPrint()), so nothing re-heats during that brief resume, and the
     * abort itself is what stops any resumed motion almost immediately after.
     */
    public synchronized String stopPausedPrint() throws IOException, TimeoutException {
        sendAndWaitForOk("M104 S0", DEFAULT_TIMEOUT_MS);
        sendAndWaitForOk("M140 S0", DEFAULT_TIMEOUT_MS);
        sendAndWaitForOk("M24", DEFAULT_TIMEOUT_MS);
        return sendAndWaitForOk("M524", DEFAULT_TIMEOUT_MS);
    }

    /** Pause only - the job stays selected, so resumePrint() can continue it from where it left off. */
    public synchronized String pausePrint() throws IOException, TimeoutException {
        return sendAndWaitForOk("M25", DEFAULT_TIMEOUT_MS);
    }

    /** Resumes a paused SD print. Deliberately does NOT re-send M23 (that would reset the SD
     *  read position back to byte 0) - M24 alone resumes Marlin's own paused position. */
    public synchronized String resumePrint() throws IOException, TimeoutException {
        return sendAndWaitForOk("M24", DEFAULT_TIMEOUT_MS);
    }

    /**
     * Manual filament unload macro. This firmware doesn't have M701/M702 compiled in
     * (FILAMENT_LOAD_UNLOAD_GCODES is commented out in Configuration_adv.h, confirmed against
     * the real firmware source) - this replicates what the printer's own touchscreen does
     * instead (Auto_in_out_feedstock(false) in the DWIN screen firmware), using that same
     * function's real constants from dwin.h, not generic guesses: heat to 240C, push 15mm at
     * 4mm/s (primes/melts the tip before pulling so it doesn't snap), retract 90mm at 2mm/s,
     * then cool to 140C afterward - the same "warm standby" temp the stock macro leaves it at
     * (below EXTRUDE_MINTEMP=180C, so it can't ooze, but warm enough to reheat quickly for a
     * reload). listener sees temperature lines during the M109 heat-up so the caller can show
     * live progress instead of a frozen dashboard for the ~1-2 minutes heating can take.
     */
    private static final int UNLOAD_HEAT_TEMP_C = 240;
    private static final int UNLOAD_COOL_TEMP_C = 140;
    private static final double UNLOAD_PUSH_MM = 15;
    private static final double UNLOAD_PUSH_FEEDRATE_MM_S = 4;
    private static final double UNLOAD_RETRACT_MM = 90;
    private static final double UNLOAD_RETRACT_FEEDRATE_MM_S = 2;

    public synchronized void unloadFilament(LineListener listener) throws IOException, TimeoutException {
        sendAndWaitForOk("M104 S" + UNLOAD_HEAT_TEMP_C, DEFAULT_TIMEOUT_MS);
        sendAndWaitForOk("M109 S" + UNLOAD_HEAT_TEMP_C, DEFAULT_TIMEOUT_MS, listener);
        sendAndWaitForOk("G91", DEFAULT_TIMEOUT_MS);
        sendAndWaitForOk(extrudeMove(UNLOAD_PUSH_MM, UNLOAD_PUSH_FEEDRATE_MM_S), DEFAULT_TIMEOUT_MS);
        sendAndWaitForOk(extrudeMove(-UNLOAD_RETRACT_MM, UNLOAD_RETRACT_FEEDRATE_MM_S), DEFAULT_TIMEOUT_MS);
        sendAndWaitForOk("G90", DEFAULT_TIMEOUT_MS);
        sendAndWaitForOk("M104 S" + UNLOAD_COOL_TEMP_C, DEFAULT_TIMEOUT_MS);
    }

    private static String extrudeMove(double eDistanceMm, double feedrateMmPerSec) {
        return String.format(java.util.Locale.US, "G1 E%.1f F%.0f", eDistanceMm, feedrateMmPerSec * 60);
    }

    /**
     * Auto bed leveling, matching exactly what this printer's own touchscreen does when you
     * press Confirm after a leveling pass (HMI_Leveling()'s confirm branch in the DWIN screen
     * firmware: gcode.process_subcommands_now_P("M420 S1"); refresh_bed_level(); settings.save()
     * - and settings.save() is the same real function M500 calls). G29 probes the BLTouch grid
     * (4x4 = 16 points, GRID_MAX_POINTS_X=4, confirmed in Configuration.h for the active
     * AUTO_BED_LEVELING_BILINEAR block) and builds the mesh; no heating needed first - it's a
     * mechanical probe, not a melt-touch one. M420 S1 enables the mesh, M500 persists it to
     * EEPROM so it survives this printer's frequent power-cycles through the Tapo plug.
     *
     * A full 16-point probe pass is silent on the wire until it's done (no keepalive lines like
     * M109 gets), so the sliding per-line timeout that keeps a long M109 alive can't help here -
     * levelTimeoutMs is passed straight through as the deadline for the G29 call specifically.
     */
    public synchronized void levelBed(long levelTimeoutMs) throws IOException, TimeoutException {
        sendAndWaitForOk("G29", levelTimeoutMs);
        sendAndWaitForOk("M420 S1", DEFAULT_TIMEOUT_MS);
        sendAndWaitForOk("M500", DEFAULT_TIMEOUT_MS);
    }

    /** Raw per-point mesh values, straight from the printer's own currently-loaded (and possibly
     *  EEPROM-restored, not just freshly-probed) calibration - "M420 V" is what
     *  print_bilinear_leveling_grid() (Marlin/src/feature/bedlevel/abl/abl.cpp) sends in response,
     *  confirmed against the real source. PrinterService parses the returned text into a grid. */
    public synchronized String queryLevelingGrid() throws IOException, TimeoutException {
        return sendAndWaitForOk("M420 V", DEFAULT_TIMEOUT_MS);
    }

    public interface UploadProgressListener {
        void onProgress(long sentBytes, long totalBytes);

        /** Polled once per content line, not just at start - a cancel needs to land within one
         *  line's round-trip (milliseconds), not wait for the whole remaining file. */
        boolean isCancelled();
    }

    /** Thrown mid-transfer when the listener reports cancellation. uploadFile()'s own finally
     *  block still runs first (M29, closing whatever got written), so printerFilename here is
     *  exactly what's left sitting incomplete on the SD card - the caller's job is to decide
     *  whether to delete it. */
    public static class UploadCancelledException extends IOException {
        public final String printerFilename;
        public final long bytesSent;
        UploadCancelledException(String printerFilename, long bytesSent) {
            super("Upload cancelled after " + bytesSent + " bytes");
            this.printerFilename = printerFilename;
            this.bytesSent = bytesSent;
        }
    }

    /**
     * M28 upload using Marlin's standard line-numbered + checksummed transport (N<n> ... *<sum>).
     * Confirmed necessary on real hardware: this Ender-3 V3 SE's Creality-modified firmware
     * rejects un-numbered content lines while recording to SD with
     * "Error:No Checksum with line number" + "Resend:", and silently drops comment-only lines
     * that lack it (0 bytes ever landed on the card without this). M28/M29 themselves don't need
     * numbering - only content lines do. Returns the filename Marlin actually stored (may be
     * 8.3-truncated) and the byte count of the *original* content (what verifyUpload() compares
     * against - Marlin strips the N/checksum wrapper before writing to the file).
     */
    private static final int MAX_RESEND_RETRIES = 3;

    public static class UploadResult {
        public final String printerFilename;
        public final long bytesSent;
        UploadResult(String printerFilename, long bytesSent) {
            this.printerFilename = printerFilename;
            this.bytesSent = bytesSent;
        }
    }

    public synchronized UploadResult uploadFile(String filename, InputStream gcodeContent,
                                                 UploadProgressListener listener)
            throws IOException, TimeoutException {
        resetLineNumbering();
        String openResponse = sendAndWaitForOk("M28 " + filename, UPLOAD_LINE_TIMEOUT_MS);
        if (openResponse.toLowerCase(java.util.Locale.US).contains("open failed")) {
            throw new IOException("Printer rejected filename \"" + filename + "\": " + openResponse.trim());
        }
        String printerFilename = parseWritingToFile(openResponse, filename);

        long totalSent = 0;
        java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(gcodeContent, StandardCharsets.US_ASCII));
        String line;
        try {
            while ((line = reader.readLine()) != null) {
                if (listener != null && listener.isCancelled()) {
                    throw new UploadCancelledException(printerFilename, totalSent);
                }
                // This firmware discards everything from ';' onward character-by-character as
                // it RECEIVES a line - confirmed on real hardware - so a checksum placed after a
                // comment is silently lost and the line gets rejected. Stripping the comment
                // ourselves changes nothing about what actually gets recorded (the firmware would
                // have dropped that text anyway); the G/M command and all its parameters are
                // untouched. A comment-only line strips down to nothing and is skipped entirely.
                String content = stripComment(line).trim();
                if (content.isEmpty()) continue;
                sendNumberedLineWithResend(nextLineNumber++, content);
                // +2 for \r\n: confirmed on real hardware that this firmware normalizes each
                // recorded line to CRLF on the SD card regardless of what terminator we send.
                totalSent += content.getBytes(StandardCharsets.US_ASCII).length + 2;
                if (listener != null) listener.onProgress(totalSent, -1);
            }
        } finally {
            // Always try to close the SD file, even if a line send failed, so the printer
            // isn't left in "recording" state with a half-written file.
            try {
                sendAndWaitForOk("M29", DEFAULT_TIMEOUT_MS);
            } catch (IOException | TimeoutException closeFailure) {
                Log.w(TAG, "M29 close failed after upload", closeFailure);
            }
        }
        return new UploadResult(printerFilename, totalSent);
    }

    private static String stripComment(String line) {
        int idx = line.indexOf(';');
        return idx < 0 ? line : line.substring(0, idx);
    }

    private void sendNumberedLineWithResend(int lineNumber, String content)
            throws IOException, TimeoutException {
        String withN = "N" + lineNumber + " " + content;
        byte[] wire = (withN + "*" + marlinChecksum(withN) + "\n").getBytes(StandardCharsets.US_ASCII);
        for (int attempt = 0; ; attempt++) {
            writeRawBytes(wire);
            String response = waitForOk(UPLOAD_LINE_TIMEOUT_MS);
            if (!response.toLowerCase(java.util.Locale.US).contains("resend")) {
                return;
            }
            if (attempt >= MAX_RESEND_RETRIES) {
                throw new IOException("Printer kept requesting resend of N" + lineNumber + ": " + response);
            }
            Log.w(TAG, "Resend requested for N" + lineNumber + ", retrying (" + (attempt + 1) + ")");
        }
    }

    private static int marlinChecksum(String s) {
        int cs = 0;
        for (int i = 0; i < s.length(); i++) {
            cs ^= (s.charAt(i) & 0xff);
        }
        return cs & 0xff;
    }

    private static final Pattern M23_FILE_SIZE = Pattern.compile("Size:\\s*(\\d+)");

    /**
     * Selects a file already on the SD card, for a fresh upload's own verification or to start
     * printing a file staged earlier without re-uploading it. Uses M23's own response ("File
     * opened: X Size: N") rather than M20's directory listing: on this Ender-3 V3 SE's firmware,
     * M20 always reports 0 bytes for a file that was just written via M28/M29 (confirmed on real
     * hardware - looks like the directory-entry size isn't refreshed after a host-write close),
     * while M23 does a real stat of the file when selecting it. M23 alone never starts motion or
     * heating - only a following M24 does that (see startPrint()) - and M27 right after M23 (but
     * before M24) just reports "Not SD printing", so the size has to come from M23's own
     * response, not M27. Returns -1 if the response didn't carry a parseable size.
     */
    public synchronized long selectFile(String filename) throws IOException, TimeoutException {
        String selectResponse = sendAndWaitForOk("M23 " + filename, DEFAULT_TIMEOUT_MS);
        if (selectResponse.toLowerCase(java.util.Locale.US).contains("open failed")) {
            throw new IOException("Printer rejected filename \"" + filename + "\": " + selectResponse.trim());
        }
        Matcher m = M23_FILE_SIZE.matcher(selectResponse);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    public synchronized boolean verifyUpload(String printerFilename, long expectedBytes)
            throws IOException, TimeoutException {
        return selectFile(printerFilename) == expectedBytes;
    }

    /** Deletes a file from the SD card. Returns the raw response for the caller to inspect -
     *  unlike M23/M28's "open failed" wording (confirmed on real hardware), this firmware's exact
     *  M30 failure phrasing for a missing/undeletable file hasn't been confirmed yet. */
    public synchronized String deleteFile(String filename) throws IOException, TimeoutException {
        return sendAndWaitForOk("M30 " + filename, DEFAULT_TIMEOUT_MS);
    }

    private static String parseWritingToFile(String response, String fallback) {
        for (String line : response.split("\n")) {
            int idx = line.indexOf("Writing to file:");
            if (idx >= 0) {
                return line.substring(idx + "Writing to file:".length()).trim();
            }
        }
        return fallback;
    }

    // ---- low-level line protocol ----------------------------------------------------------

    /** Callback for every raw line received while waiting for an 'ok' - lets a caller (see
     *  pollTemperatures()) pick up spontaneous temperature auto-reports that arrive before the
     *  'ok' itself, e.g. while queued behind a blocking M109/M190 from the print file. */
    public interface LineListener {
        void onLine(String line);
    }

    private String sendAndWaitForOk(String command, long timeoutMs) throws IOException, TimeoutException {
        return sendAndWaitForOk(command, timeoutMs, null);
    }

    private String sendAndWaitForOk(String command, long timeoutMs, LineListener listener)
            throws IOException, TimeoutException {
        writeRaw(command);
        return waitForOk(timeoutMs, listener);
    }

    private void writeRaw(String command) throws IOException {
        writeRawBytes((command + "\n").getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * A failed write/read means the USB link itself is gone (device unplugged, or the mystery
     * self-reconnects seen on real hardware - see PRINTHOST_STATUS.md #3): isOpen() otherwise
     * keeps reporting true forever after that, since it only checks `port != null`, and the
     * dashboard's Connect button then silently no-ops instead of letting the user recover. So on
     * any real I/O failure here, close and null out the port ourselves before propagating the
     * exception - isOpen() immediately reflects reality, and connectPrinter() (see PrinterService)
     * can reconnect from a clean slate on the next explicit Connect click.
     */
    private void writeRawBytes(byte[] bytes) throws IOException {
        if (port == null) throw new IOException("Not connected");
        Log.d(TAG, ">> " + new String(bytes, StandardCharsets.US_ASCII).trim());
        try {
            port.write(bytes, DEFAULT_TIMEOUT_MS_INT);
        } catch (IOException e) {
            disconnect();
            throw e;
        }
    }

    private static final int DEFAULT_TIMEOUT_MS_INT = 3000;

    /**
     * A blocking M109/M190 (heat-and-wait) *from the print file itself* can sit ahead of our own
     * command in Marlin's single queue for minutes - confirmed on real hardware: no 'ok' arrives
     * until it finishes, but the firmware keeps sending "echo:busy: processing" keepalives and
     * spontaneous temperature auto-reports the whole time. A flat timeout here used to time out
     * mid-heat and knock the app into ERROR even though the print itself was fine. HARD_TIMEOUT_MS
     * is the real "connection is dead" ceiling; timeoutMs instead governs the gap between
     * successive lines, reset on every line received (including keepalives), so a slow-but-alive
     * printer never trips it.
     */
    private static final long HARD_TIMEOUT_MS = 180_000; // 3 minutes - covers a full heat-and-wait

    private String waitForOk(long timeoutMs) throws IOException, TimeoutException {
        return waitForOk(timeoutMs, null);
    }

    /** Reads lines until one that is exactly "ok" (or starts with "ok") arrives, or times out. */
    private String waitForOk(long timeoutMs, LineListener listener) throws IOException, TimeoutException {
        if (port == null) throw new IOException("Not connected");
        StringBuilder collected = new StringBuilder();
        long start = System.currentTimeMillis();
        long hardDeadline = start + Math.max(timeoutMs, HARD_TIMEOUT_MS);
        long slidingDeadline = start + timeoutMs;
        while (true) {
            long now = System.currentTimeMillis();
            long deadline = Math.min(slidingDeadline, hardDeadline);
            long remaining = deadline - now;
            if (remaining <= 0) {
                throw new TimeoutException("Timed out waiting for 'ok': " + collected);
            }
            String line = readLine((int) Math.min(remaining, Integer.MAX_VALUE));
            if (line == null) {
                continue; // no full line yet, keep looping until deadline
            }
            Log.d(TAG, "<< " + line);
            // Any line at all proves the connection is alive - reset the sliding window so a
            // stream of keepalives/auto-reports during a long heat doesn't trip a false timeout.
            slidingDeadline = System.currentTimeMillis() + timeoutMs;
            if (listener != null) listener.onLine(line);
            if (line.equals("ok")) {
                return collected.toString();
            }
            if (line.startsWith("ok ")) {
                // Some responses (confirmed on real hardware: M105) pack their data onto the
                // SAME line as "ok" instead of a preceding line - e.g. "ok T:26.1 /0.0 B:26.0
                // /0.0" - so that trailing data must be kept, not discarded as if it were a bare "ok".
                String extra = line.substring(3).trim();
                if (!extra.isEmpty()) collected.append(extra).append("\n");
                return collected.toString();
            }
            collected.append(line).append("\n");
        }
    }

    /** Returns one line (without terminator) if a full line is already buffered/read, else null. */
    private String readLine(int timeoutMs) throws IOException {
        // Look for an existing newline in leftover first.
        byte[] have = leftover.toByteArray();
        int nl = indexOf(have, (byte) '\n');
        if (nl >= 0) {
            String line = new String(have, 0, nl, StandardCharsets.US_ASCII);
            leftover.reset();
            leftover.write(have, nl + 1, have.length - nl - 1);
            return stripCr(line);
        }
        int n;
        try {
            n = port.read(readBuf, Math.min(timeoutMs, 1000));
        } catch (IOException e) {
            disconnect();
            throw e;
        }
        if (n <= 0) {
            return null;
        }
        leftover.write(readBuf, 0, n);
        have = leftover.toByteArray();
        nl = indexOf(have, (byte) '\n');
        if (nl < 0) {
            return null;
        }
        String line = new String(have, 0, nl, StandardCharsets.US_ASCII);
        leftover.reset();
        leftover.write(have, nl + 1, have.length - nl - 1);
        return stripCr(line);
    }

    private static String stripCr(String s) {
        return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
    }

    private static int indexOf(byte[] arr, byte target) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] == target) return i;
        }
        return -1;
    }
}
