package dev.oleksandr.printhost;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

/**
 * Whitelisted printer operations (see PRINTHOST_PLAN.md's command table): connection, SD file
 * management, print control and the maintenance macros (filament unload, bed leveling). No
 * arbitrary G-code is ever accepted from callers.
 *
 * The printer is always driven by the ESP32 bridge board over HTTP - see EspPrinterConnection,
 * the only concrete implementation. This class exists so PrinterService and its callers have a
 * single type to hold and don't need to know about the ESP-specific transport underneath.
 */
public abstract class PrinterConnection {

    public abstract boolean isOpen();

    public abstract boolean connect() throws IOException;

    public abstract void disconnect();

    // ---- info queries -------------------------------------------------------------------------

    public abstract String queryFirmwareInfo() throws IOException, TimeoutException;

    public abstract String queryZOffset() throws IOException, TimeoutException;

    public abstract String pollTemperatures() throws IOException, TimeoutException;

    /** listener sees every line while this M105 sits queued behind a blocking M109/M190 from
     *  the print file - lets the caller update live temps from auto-reports before the eventual
     *  'ok' arrives, instead of only once the (possibly minutes-long) heat finishes. */
    public abstract String pollTemperatures(LineListener listener) throws IOException, TimeoutException;

    public abstract String pollProgress() throws IOException, TimeoutException;

    /** Reports "Current file: NAME" for whatever's selected/printing right now. Used to recover
     *  the filename after reconnecting to a printer that's already mid print (this app's own
     *  process restarting doesn't stop or reselect the job). */
    public abstract String queryCurrentFilename() throws IOException, TimeoutException;

    public abstract String pollPrintTime() throws IOException, TimeoutException;

    public abstract String listSdFiles() throws IOException, TimeoutException;

    // ---- print control ------------------------------------------------------------------------

    public abstract String startPrint(String filename) throws IOException, TimeoutException;

    /** Full stop (can't be resumed after this). */
    public abstract String stopPrint() throws IOException, TimeoutException;

    /** Stops a job that's already PAUSED. */
    public abstract String stopPausedPrint() throws IOException, TimeoutException;

    public abstract void requestAbort();

    /** Pause only - the job stays selected, so resumePrint() can continue it from where it left off. */
    public abstract String pausePrint() throws IOException, TimeoutException;

    /** Resumes a paused SD print. */
    public abstract String resumePrint() throws IOException, TimeoutException;

    // ---- maintenance sequences ----------------------------------------------------------------

    /** Manual filament unload macro: heat, push, retract, cool to a warm standby temperature.
     *  listener sees temperature lines during the heat-up so the caller can show live progress. */
    public abstract void unloadFilament(LineListener listener) throws IOException, TimeoutException;

    /** Auto bed leveling: probe, enable the mesh, persist it. */
    public abstract void levelBed(long levelTimeoutMs) throws IOException, TimeoutException;

    /** Raw per-point mesh values from the printer's own currently-loaded calibration. */
    public abstract String queryLevelingGrid() throws IOException, TimeoutException;

    // ---- file upload ---------------------------------------------------------------------------

    public interface UploadProgressListener {
        void onProgress(long sentBytes, long totalBytes);

        /** Polled once per content line, not just at start - a cancel needs to land within one
         *  line's round-trip (milliseconds), not wait for the whole remaining file. */
        boolean isCancelled();
    }

    /** Thrown mid-transfer when the listener reports cancellation. uploadFile()'s own finally
     *  block still runs first, so printerFilename here is exactly what's left sitting incomplete -
     *  the caller's job is to decide whether to delete it. */
    public static class UploadCancelledException extends IOException {
        public final String printerFilename;
        public final long bytesSent;
        UploadCancelledException(String printerFilename, long bytesSent) {
            super("Upload cancelled after " + bytesSent + " bytes");
            this.printerFilename = printerFilename;
            this.bytesSent = bytesSent;
        }
    }

    public static class UploadResult {
        public final String printerFilename;
        public final long bytesSent;
        UploadResult(String printerFilename, long bytesSent) {
            this.printerFilename = printerFilename;
            this.bytesSent = bytesSent;
        }
    }

    public abstract UploadResult uploadFile(String filename, java.io.InputStream gcodeContent,
                                             UploadProgressListener listener)
            throws IOException, TimeoutException;

    public abstract long selectFile(String filename) throws IOException, TimeoutException;

    public abstract boolean verifyUpload(String printerFilename, long expectedBytes)
            throws IOException, TimeoutException;

    /** Deletes a file from the SD card. Returns the raw response for the caller to inspect. */
    public abstract String deleteFile(String filename) throws IOException, TimeoutException;

    /** Callback for every raw line received while waiting for an 'ok' - lets a caller (see
     *  pollTemperatures()) pick up spontaneous temperature auto-reports that arrive before the
     *  'ok' itself, e.g. while queued behind a blocking M109/M190 from the print file. */
    public interface LineListener {
        void onLine(String line);
    }
}
