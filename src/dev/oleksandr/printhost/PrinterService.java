package dev.oleksandr.printhost;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PrinterService extends Service {

    private static final String TAG = "PrinterService";
    private static final String CHANNEL_ID = "printhost_service";
    private static final int NOTIFICATION_ID = 1;
    static final int HTTP_PORT = 8899; // not 2525, so it can't be confused with 3D Fox
    private static final String ACTION_USB_PERMISSION = "dev.oleksandr.printhost.USB_PERMISSION";
    private static final int POLL_INTERVAL_MS = 1500;
    private static final int IDLE_TEMP_POLL_INTERVAL_MS = 8000;
    private static final int MAX_CONSECUTIVE_POLL_FAILURES = 5;

    private final PrinterState state = new PrinterState();
    // Serializes hardware-touching operations (connect/upload/start/stop) with each other, but
    // deliberately NOT with getStateJson()/getConfigJson() - a multi-minute upload must never
    // block the dashboard's status polling. PrinterState.toJson() has its own lock for a
    // consistent snapshot of its (volatile) fields, which is all status reads need.
    private final Object commandLock = new Object();
    private PrinterConnection printerConnection;
    private CameraController cameraController;
    private PrintHostHttpServer httpServer;
    private MacNotifier macNotifier;
    private PowerManager.WakeLock wakeLock;
    private UsbManager usbManager;
    private PollerThread pollerThread;
    private TempPollerThread tempPollerThread;
    private File uploadedFile;
    private SdFilenameMap sdFilenameMap;

    @Override
    public void onCreate() {
        super.onCreate();
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        printerConnection = new PrinterConnection(usbManager);
        cameraController = new CameraController(this);
        macNotifier = new MacNotifier(this);
        sdFilenameMap = new SdFilenameMap(new File(getFilesDir(), "sd_filename_map.json"));

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PrintHost:service");
        wakeLock.acquire();

        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundCompat();
        if (httpServer == null) {
            httpServer = new PrintHostHttpServer(HTTP_PORT, new DashboardRouter(this));
            httpServer.start();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopPoller();
        stopTempPoller();
        if (httpServer != null) httpServer.shutdown();
        cameraController.stop();
        printerConnection.disconnect();
        if (wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    private void startForegroundCompat() {
        Notification notification = buildNotification("Idle");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                            | ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "PrintHost", NotificationManager.IMPORTANCE_LOW);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("PrintHost")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIFICATION_ID, buildNotification(text));
    }

    // ---- printer connect / disconnect ------------------------------------------------------

    /** Deliberately lock-free: must stay responsive while a multi-minute upload holds
     *  commandLock, since this is what the dashboard polls for live progress. */
    public JSONObject getStateJson() {
        updateBatteryStatus();
        return state.toJson();
    }

    private void updateBatteryStatus() {
        android.os.BatteryManager bm = (android.os.BatteryManager) getSystemService(BATTERY_SERVICE);
        if (bm == null) return;
        state.batteryPercent = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
        state.batteryCharging = bm.isCharging();
    }

    /** Diagnostic passthrough for M20 - useful while bringing up a new printer/firmware. */
    public String debugListSdFiles() throws IOException, TimeoutException {
        synchronized (commandLock) {
            return printerConnection.listSdFiles();
        }
    }

    /**
     * Root-level print files on the SD card, for the dashboard's "choose an already-uploaded
     * file" list. Filters out subdirectory entries (anything containing '/') - confirmed on real
     * hardware that M20 also reports OS-created paths like "/SPOTLI~1/STORE-V2/.../TM~5.GLO"
     * once a card has ever been mounted on a Mac, and this firmware only ever writes to the
     * root, so nothing we care about lives in a subdirectory.
     *
     * Each entry is "SHORTNAME SIZE [LONGNAME]" - the long name (may itself contain spaces) is
     * present only for files that have a real LFN directory entry, i.e. files copied onto the
     * card from a computer rather than uploaded through this app (see SdFilenameMap). When the
     * firmware doesn't have one, fall back to whatever name we ourselves remember for that short
     * name from an earlier upload in this app, and only then to the bare short name.
     */
    public JSONArray listSdFiles() throws IOException, TimeoutException {
        synchronized (commandLock) {
            String raw = printerConnection.listSdFiles();
            JSONArray files = new JSONArray();
            boolean inList = false;
            for (String line : raw.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.equalsIgnoreCase("Begin file list")) { inList = true; continue; }
                if (trimmed.equalsIgnoreCase("End file list")) { inList = false; continue; }
                if (!inList || trimmed.isEmpty()) continue;
                int sp1 = trimmed.indexOf(' ');
                if (sp1 < 0) continue;
                String name = trimmed.substring(0, sp1);
                if (name.contains("/")) continue;
                String rest = trimmed.substring(sp1 + 1).trim();
                int sp2 = rest.indexOf(' ');
                String sizeStr = sp2 < 0 ? rest : rest.substring(0, sp2);
                String firmwareLongName = sp2 < 0 ? null : rest.substring(sp2 + 1).trim();
                try {
                    long size = Long.parseLong(sizeStr);
                    String displayName = (firmwareLongName != null && !firmwareLongName.isEmpty())
                            ? firmwareLongName : sdFilenameMap.displayNameFor(name);
                    JSONObject entry = new JSONObject();
                    entry.put("name", name);
                    entry.put("displayName", displayName);
                    entry.put("size", size);
                    files.put(entry);
                } catch (Exception ignored) {
                    // a line that doesn't parse as "NAME SIZE [LONGNAME]" is skipped, not fatal
                }
            }
            return files;
        }
    }

    /**
     * Selects a file already on the SD card for printing, without re-uploading it - the
     * counterpart to upload() for jobs staged ahead of time. Mirrors upload()'s post-condition
     * (UPLOAD_VERIFIED with currentFile/uploadedBytes set) so startPrint() works unchanged.
     * uploadedFile is cleared: it backs fan-speed-by-byte-offset lookup against the locally kept
     * copy of a *freshly uploaded* file, and there is no local copy for a file staged earlier.
     *
     * displayName is whatever listSdFiles() resolved for this entry (its own firmware-reported
     * LFN, our own remembered upload name, or the bare short name) - the caller already has it
     * from the file list it rendered, so it's cheaper to pass along than to re-derive here.
     */
    public UploadOutcome selectSdFile(String filename, String displayName) {
        synchronized (commandLock) {
            if (!printerConnection.isOpen()) {
                return new UploadOutcome(false, "Not connected");
            }
            if (filename == null || filename.trim().isEmpty()) {
                return new UploadOutcome(false, "Missing filename");
            }
            if (state.phase == PrinterState.Phase.PRINTING || state.phase == PrinterState.Phase.PAUSED
                    || state.phase == PrinterState.Phase.UPLOADING) {
                return new UploadOutcome(false, "Cannot switch files while " + state.phase);
            }
            try {
                long size = printerConnection.selectFile(filename);
                state.phase = PrinterState.Phase.UPLOAD_VERIFIED;
                state.currentFile = filename;
                state.currentFileDisplay = (displayName != null && !displayName.trim().isEmpty())
                        ? displayName : sdFilenameMap.displayNameFor(filename);
                state.uploadedBytes = size;
                state.expectedBytes = size;
                state.lastError = "";
                uploadedFile = null;
                state.fanSpeed = null;
                updateNotification("Selected: " + filename);
                return new UploadOutcome(true, "Selected " + filename);
            } catch (IOException | TimeoutException e) {
                Log.e(TAG, "selectSdFile failed", e);
                state.phase = PrinterState.Phase.ERROR;
                state.lastError = String.valueOf(e.getMessage());
                return new UploadOutcome(false, state.lastError);
            }
        }
    }

    /**
     * Deletes a file from the SD card and forgets its remembered display name. Blocked while a
     * job is active for the same reason selectSdFile() is - an M30 sent while the printer is mid
     * SD-read is not something this firmware's behavior has been confirmed for, and there is no
     * reason to risk it when nothing needs deleting until the current job is done anyway.
     */
    public UploadOutcome deleteSdFile(String filename) {
        synchronized (commandLock) {
            if (!printerConnection.isOpen()) {
                return new UploadOutcome(false, "Not connected");
            }
            if (filename == null || filename.trim().isEmpty()) {
                return new UploadOutcome(false, "Missing filename");
            }
            if (state.phase == PrinterState.Phase.PRINTING || state.phase == PrinterState.Phase.PAUSED
                    || state.phase == PrinterState.Phase.UPLOADING) {
                return new UploadOutcome(false, "Cannot delete files while " + state.phase);
            }
            try {
                String response = printerConnection.deleteFile(filename);
                sdFilenameMap.remove(filename);
                if (filename.equals(state.currentFile)) {
                    state.currentFile = "";
                    state.currentFileDisplay = "";
                    state.uploadedBytes = 0;
                    state.expectedBytes = 0;
                    if (state.phase == PrinterState.Phase.UPLOAD_VERIFIED) {
                        state.phase = PrinterState.Phase.IDLE;
                    }
                }
                updateNotification("Deleted: " + filename);
                return new UploadOutcome(true, response.trim().isEmpty() ? "Deleted " + filename : response.trim());
            } catch (IOException | TimeoutException e) {
                Log.e(TAG, "deleteSdFile failed", e);
                state.phase = PrinterState.Phase.ERROR;
                state.lastError = String.valueOf(e.getMessage());
                return new UploadOutcome(false, state.lastError);
            }
        }
    }

    /**
     * Always reconnects from a clean slate rather than short-circuiting on isOpen() - the whole
     * point of an explicit Connect click is to recover from a bad state, and isOpen() can lag
     * behind reality (see PRINTHOST_STATUS.md #2/#3: a dead USB link that hasn't hit a write/read
     * yet still reads as "open"). Cheap even in the already-healthy case - the dashboard hides the
     * Connect button whenever the printer is genuinely connected, so this only runs when the user
     * is actually trying to recover from DISCONNECTED or ERROR.
     */
    public boolean connectPrinter() {
        synchronized (commandLock) {
            if (printerConnection.isOpen()) {
                printerConnection.disconnect();
            }
            state.phase = PrinterState.Phase.CONNECTING;
            try {
                UsbDevice target = findCandidateDevice();
                if (target == null) {
                    state.phase = PrinterState.Phase.ERROR;
                    state.lastError = "No supported USB-serial device attached";
                    return false;
                }
                if (!usbManager.hasPermission(target)) {
                    if (!requestUsbPermissionAndWait(target)) {
                        state.phase = PrinterState.Phase.ERROR;
                        state.lastError = "USB permission denied or timed out";
                        return false;
                    }
                }
                printerConnection.connect(target);
                state.firmwareInfo = firstLine(printerConnection.queryFirmwareInfo());
                state.zOffset = parseZOffset(printerConnection.queryZOffset());
                state.phase = PrinterState.Phase.IDLE;
                state.lastError = "";
                updateNotification("Connected");
                startTempPoller();
                return true;
            } catch (IOException | TimeoutException e) {
                Log.e(TAG, "connectPrinter failed", e);
                state.phase = PrinterState.Phase.ERROR;
                state.lastError = String.valueOf(e.getMessage());
                printerConnection.disconnect();
                return false;
            }
        }
    }

    public void disconnectPrinter() {
        synchronized (commandLock) {
            stopPoller();
            stopTempPoller();
            printerConnection.disconnect();
            state.phase = PrinterState.Phase.DISCONNECTED;
            // Everything below is a live reading from the printer we're no longer talking to -
            // leaving it in place would show the dashboard's last-known numbers as if they were
            // still current (confirmed as misleading on real hardware: temps and the selected
            // file stayed visible on the dashboard well after disconnecting).
            state.hotendTemp = 0;
            state.hotendTarget = 0;
            state.bedTemp = 0;
            state.bedTarget = 0;
            state.zOffset = 0;
            state.firmwareInfo = "";
            state.currentFile = "";
            state.currentFileDisplay = "";
            state.uploadedBytes = 0;
            state.expectedBytes = 0;
            state.printProgressPercent = 0;
            state.elapsedSeconds = 0;
            state.fanSpeed = null;
            uploadedFile = null;
            updateNotification("Idle");
        }
    }

    private UsbDevice findCandidateDevice() {
        List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
        if (drivers.isEmpty()) return null;
        return drivers.get(0).getDevice();
    }

    private boolean requestUsbPermissionAndWait(UsbDevice device) {
        UsbPermissionReceiver receiver = new UsbPermissionReceiver();
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
        try {
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? PendingIntent.FLAG_MUTABLE : 0;
            PendingIntent pi = PendingIntent.getBroadcast(
                    this, 0, new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName()), flags);
            usbManager.requestPermission(device, pi);
            try {
                // usbtap's accessibility service auto-taps the system dialog almost immediately;
                // 10s comfortably covers that round trip without hanging the HTTP request forever.
                boolean signalled = receiver.latch.await(10, TimeUnit.SECONDS);
                return signalled && receiver.granted;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        } finally {
            unregisterReceiver(receiver);
        }
    }

    class UsbPermissionReceiver extends BroadcastReceiver {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile boolean granted = false;
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
                granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                latch.countDown();
            }
        }
    }

    private static String firstLine(String s) {
        int markerIdx = s.indexOf("FIRMWARE_NAME:");
        String fromMarker = markerIdx >= 0 ? s.substring(markerIdx) : s;
        int idx = fromMarker.indexOf('\n');
        return idx >= 0 ? fromMarker.substring(0, idx) : fromMarker;
    }

    private static final Pattern Z_OFFSET_PATTERN = Pattern.compile("Z([\\-0-9.]+)");

    private static double parseZOffset(String m851Response) {
        Matcher m = Z_OFFSET_PATTERN.matcher(m851Response);
        if (m.find()) {
            try {
                return Double.parseDouble(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    // ---- upload -----------------------------------------------------------------------------

    public static class UploadOutcome {
        public final boolean success;
        public final String message;
        UploadOutcome(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    /** Uses commandLock, not the service monitor - a multi-minute transfer must never block
     *  getStateJson(), which is what the dashboard polls to show live upload progress. */
    public UploadOutcome upload(String filename, InputStream body, long contentLength) {
      synchronized (commandLock) {
        if (!printerConnection.isOpen()) {
            return new UploadOutcome(false, "Not connected");
        }
        if (filename == null || filename.trim().isEmpty()) {
            return new UploadOutcome(false, "Missing filename");
        }
        String safeFilename = toSafeSdFilename(filename);
        state.phase = PrinterState.Phase.UPLOADING;
        state.currentFile = safeFilename;
        state.currentFileDisplay = filename;
        state.uploadedBytes = 0;
        state.expectedBytes = contentLength;
        updateNotification("Uploading " + filename);

        File localCopy = new File(getFilesDir(), "current_upload.gcode");
        try {
            saveToLocalFile(body, localCopy);
        } catch (IOException e) {
            state.phase = PrinterState.Phase.ERROR;
            state.lastError = "Failed to receive upload: " + e.getMessage();
            return new UploadOutcome(false, state.lastError);
        }

        try (InputStream fileIn = new FileInputStream(localCopy)) {
            PrinterConnection.UploadResult result = printerConnection.uploadFile(
                    safeFilename, fileIn, new ProgressListener());
            boolean verified = printerConnection.verifyUpload(result.printerFilename, result.bytesSent);
            state.uploadedBytes = result.bytesSent;
            state.currentFile = result.printerFilename;
            if (verified) {
                state.phase = PrinterState.Phase.UPLOAD_VERIFIED;
                uploadedFile = localCopy;
                sdFilenameMap.put(result.printerFilename, filename);
                updateNotification("Upload verified: " + result.printerFilename);
                return new UploadOutcome(true, "Uploaded as " + result.printerFilename
                        + " (from " + filename + "), verified " + result.bytesSent + " bytes");
            } else {
                state.phase = PrinterState.Phase.ERROR;
                state.lastError = "Upload verification failed (size mismatch on SD card)";
                updateNotification("Upload FAILED verification");
                return new UploadOutcome(false, state.lastError);
            }
        } catch (IOException | TimeoutException e) {
            Log.e(TAG, "upload failed", e);
            state.phase = PrinterState.Phase.ERROR;
            state.lastError = String.valueOf(e.getMessage());
            return new UploadOutcome(false, state.lastError);
        }
      }
    }

    class ProgressListener implements PrinterConnection.UploadProgressListener {
        @Override
        public void onProgress(long sentBytes, long totalBytes) {
            state.uploadedBytes = sentBytes;
        }
    }

    /**
     * This firmware's M28 (SD write) only creates classic 8.3 short names - confirmed on real
     * hardware: it rejects anything longer with "open failed", even though it can browse/list
     * long names written by other tools (e.g. copying the file onto the card directly via a
     * reader). Slicers name files descriptively (long, with spaces/hyphens), so we shorten
     * automatically here rather than making the user rename every file by hand before upload.
     * Names that already fit 8.3 pass through unchanged.
     */
    private static String toSafeSdFilename(String original) {
        String name = original;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        String base = dot >= 0 ? name.substring(0, dot) : name;
        String ext = dot >= 0 ? name.substring(dot + 1) : "";

        String cleanBase = base.toUpperCase(java.util.Locale.US).replaceAll("[^A-Z0-9_]", "");
        String cleanExt = ext.toUpperCase(java.util.Locale.US).replaceAll("[^A-Z0-9]", "");
        if (cleanExt.isEmpty()) cleanExt = "GCO";
        if (cleanExt.length() > 3) cleanExt = cleanExt.substring(0, 3);

        if (!cleanBase.isEmpty() && cleanBase.length() <= 8) {
            return cleanBase + "." + cleanExt;
        }
        // Doesn't fit - shorten deterministically (same source name always maps to the same
        // short name, so re-uploading the same file overwrites rather than piling up aliases).
        int hash = 0;
        for (int i = 0; i < original.length(); i++) hash = 31 * hash + original.charAt(i);
        String hex = String.format(java.util.Locale.US, "%02X", hash & 0xFF);
        String prefix = cleanBase.isEmpty() ? "FILE" : cleanBase.substring(0, Math.min(5, cleanBase.length()));
        return prefix + "~" + hex + "." + cleanExt;
    }

    private static void saveToLocalFile(InputStream in, File dest) throws IOException {
        try (OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        }
    }

    // ---- print control ------------------------------------------------------------------------

    public boolean startPrint() {
        synchronized (commandLock) {
            if (state.phase != PrinterState.Phase.UPLOAD_VERIFIED) {
                state.lastError = "Cannot start: upload not verified yet";
                return false;
            }
            try {
                printerConnection.startPrint(state.currentFile);
                state.phase = PrinterState.Phase.PRINTING;
                state.printProgressPercent = 0;
                state.elapsedSeconds = 0;
                state.lastError = "";
                updateNotification("Printing " + state.currentFileDisplay);
                startPoller();
                return true;
            } catch (IOException | TimeoutException e) {
                Log.e(TAG, "startPrint failed", e);
                state.phase = PrinterState.Phase.ERROR;
                state.lastError = String.valueOf(e.getMessage());
                return false;
            }
        }
    }

    public boolean stopPrint() {
        synchronized (commandLock) {
            try {
                // A currently-paused job needs the M24-then-M524 variant - see
                // PrinterConnection.stopPausedPrint()'s javadoc for why a bare stopPrint() would
                // silently fail to actually abort it on this firmware.
                if (state.phase == PrinterState.Phase.PAUSED) {
                    printerConnection.stopPausedPrint();
                } else {
                    printerConnection.stopPrint();
                }
                state.phase = PrinterState.Phase.IDLE;
                // The job is gone for good after a Stop (unlike Pause) - nothing is printing
                // any more, so a leftover progress/elapsed reading from before Stop was pressed
                // would otherwise sit there stale (confirmed on real hardware: survives even a
                // reconnect, since nothing else ever touches these fields once printing stops).
                state.printProgressPercent = 0;
                state.elapsedSeconds = 0;
                stopPoller();
                updateNotification("Stopped");
                return true;
            } catch (IOException | TimeoutException e) {
                Log.e(TAG, "stopPrint failed", e);
                state.lastError = String.valueOf(e.getMessage());
                return false;
            }
        }
    }

    public boolean pausePrint() {
        synchronized (commandLock) {
            try {
                printerConnection.pausePrint();
                state.phase = PrinterState.Phase.PAUSED;
                updateNotification("Paused: " + state.currentFileDisplay);
                return true;
            } catch (IOException | TimeoutException e) {
                Log.e(TAG, "pausePrint failed", e);
                state.lastError = String.valueOf(e.getMessage());
                return false;
            }
        }
    }

    public boolean resumePrint() {
        synchronized (commandLock) {
            if (state.phase != PrinterState.Phase.PAUSED) {
                state.lastError = "Cannot resume: not paused";
                return false;
            }
            try {
                printerConnection.resumePrint();
                state.phase = PrinterState.Phase.PRINTING;
                updateNotification("Printing " + state.currentFileDisplay);
                return true;
            } catch (IOException | TimeoutException e) {
                Log.e(TAG, "resumePrint failed", e);
                state.phase = PrinterState.Phase.ERROR;
                state.lastError = String.valueOf(e.getMessage());
                return false;
            }
        }
    }

    // ---- background polling while printing --------------------------------------------------

    private synchronized void startPoller() {
        stopPoller();
        pollerThread = new PollerThread();
        pollerThread.start();
    }

    private synchronized void stopPoller() {
        if (pollerThread != null) {
            pollerThread.requestStop();
            pollerThread = null;
        }
    }

    // ---- lightweight always-on temperature polling (connect to disconnect) ------------------

    private synchronized void startTempPoller() {
        stopTempPoller();
        tempPollerThread = new TempPollerThread();
        tempPollerThread.start();
    }

    private synchronized void stopTempPoller() {
        if (tempPollerThread != null) {
            tempPollerThread.requestStop();
            tempPollerThread = null;
        }
    }

    /** Slower and separate from PollerThread: shows hotend/bed temp any time you're connected,
     *  not just while printing (per user request) - but skips its own poll while PollerThread is
     *  already covering M105 during an active print, so the two never poll temperature at once. */
    class TempPollerThread extends Thread {
        private volatile boolean stopRequested = false;

        TempPollerThread() {
            super("PrintHostTempPoller");
        }

        void requestStop() {
            stopRequested = true;
            interrupt();
        }

        @Override
        public void run() {
            while (!stopRequested) {
                try {
                    Thread.sleep(IDLE_TEMP_POLL_INTERVAL_MS);
                    if (stopRequested) break;
                    if (state.phase == PrinterState.Phase.PRINTING) continue;
                    String temps = printerConnection.pollTemperatures();
                    parseTemperatures(temps, state);
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    Log.w(TAG, "idle temp poll failed (non-fatal)", e);
                }
            }
        }
    }

    private static final Pattern SD_PRINTING_BYTE = Pattern.compile("SD printing byte (\\d+)/(\\d+)");
    private static final Pattern ELAPSED_TIME = Pattern.compile("(\\d+):(\\d+)");

    class PollerThread extends Thread {
        private volatile boolean stopRequested = false;

        PollerThread() {
            super("PrintHostPoller");
        }

        void requestStop() {
            stopRequested = true;
            interrupt();
        }

        @Override
        public void run() {
            int consecutiveFailures = 0;
            while (!stopRequested) {
                try {
                    Thread.sleep(POLL_INTERVAL_MS);
                    if (stopRequested) break;
                    pollOnce();
                    consecutiveFailures = 0;
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    consecutiveFailures++;
                    Log.w(TAG, "poll failed (" + consecutiveFailures + ")", e);
                    if (consecutiveFailures >= MAX_CONSECUTIVE_POLL_FAILURES) {
                        synchronized (PrinterService.this) {
                            state.phase = PrinterState.Phase.ERROR;
                            state.lastError = "Printer stopped responding";
                        }
                        macNotifier.notifyAsync("error");
                        updateNotification("Printer not responding");
                        break;
                    }
                }
            }
        }

        private void pollOnce() throws IOException, TimeoutException {
            // While this M105 sits queued behind a blocking M109/M190 from the print file
            // itself (can take minutes), the firmware streams spontaneous temperature
            // auto-reports and "echo:busy" keepalives ahead of the eventual 'ok' - this listener
            // updates live temps from those as they arrive, instead of only once heating finishes.
            String temps = printerConnection.pollTemperatures(new PrinterConnection.LineListener() {
                @Override
                public void onLine(String line) {
                    synchronized (PrinterService.this) {
                        parseTemperatures(line, state);
                    }
                }
            });
            String progress = printerConnection.pollProgress();
            String printTime = printerConnection.pollPrintTime();

            synchronized (PrinterService.this) {
                parseTemperatures(temps, state);
                boolean done = parseProgress(progress, state);
                state.elapsedSeconds = parseElapsedSeconds(printTime);
                if (uploadedFile != null) {
                    state.fanSpeed = GcodeFanParser.fanSpeedAtByteOffset(uploadedFile, state.uploadedBytes);
                }
                if (done) {
                    state.phase = PrinterState.Phase.IDLE;
                    state.printProgressPercent = 100;
                    updateNotification("Print finished: " + state.currentFileDisplay);
                    macNotifier.notifyAsync("done");
                    stopRequested = true;
                }
            }
        }
    }

    private static final Pattern T_TEMP = Pattern.compile("T:([\\-0-9.]+)\\s*/\\s*([\\-0-9.]+)");
    private static final Pattern B_TEMP = Pattern.compile("B:([\\-0-9.]+)\\s*/\\s*([\\-0-9.]+)");

    private static void parseTemperatures(String m105Response, PrinterState state) {
        Matcher t = T_TEMP.matcher(m105Response);
        if (t.find()) {
            state.hotendTemp = Double.parseDouble(t.group(1));
            state.hotendTarget = Double.parseDouble(t.group(2));
        }
        Matcher b = B_TEMP.matcher(m105Response);
        if (b.find()) {
            state.bedTemp = Double.parseDouble(b.group(1));
            state.bedTarget = Double.parseDouble(b.group(2));
        }
    }

    /** Returns true if the printer reports the SD print is done. */
    private static boolean parseProgress(String m27Response, PrinterState state) {
        if (m27Response.toLowerCase(java.util.Locale.US).contains("done printing")) {
            return true;
        }
        Matcher m = SD_PRINTING_BYTE.matcher(m27Response);
        if (m.find()) {
            long done = Long.parseLong(m.group(1));
            long total = Long.parseLong(m.group(2));
            if (total > 0) {
                state.printProgressPercent = (int) (done * 100 / total);
            }
        }
        return false;
    }

    private static long parseElapsedSeconds(String m31Response) {
        Matcher m = ELAPSED_TIME.matcher(m31Response);
        if (m.find()) {
            long minutes = Long.parseLong(m.group(1));
            long seconds = Long.parseLong(m.group(2));
            return minutes * 60 + seconds;
        }
        return 0;
    }

    // ---- screen wake / lock -------------------------------------------------------------------
    // Manual replacement for trying to auto-detect "the printer just got plugged in" and wake the
    // screen for it - confirmed on real hardware that reliably distinguishing that from a manual
    // power-button lock (in usbtap's accessibility service) turned out to be more trouble than it
    // was worth. Instead: the user physically turns the printer on, then presses this dashboard
    // button themselves to wake the phone before pressing Connect - explicit, not guessed.

    /** A brief, one-shot wake - nothing is held afterward, so it can never fight a manual
     *  power-button lock (see usbtap's UsbAllowService for why that matters). */
    @SuppressWarnings("deprecation") // SCREEN_BRIGHT_WAKE_LOCK has no non-deprecated replacement
    public void wakeScreen() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null) return;
        PowerManager.WakeLock oneShot = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "PrintHost:manualWake");
        oneShot.acquire(1000);
        oneShot.release();
    }

    /**
     * Locking the screen isn't something a regular app is allowed to do on its own - only an
     * AccessibilityService can call performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN). usbtap
     * already has exactly that capability (it uses the same call for its own dialog-handling
     * flow), so this just asks it to do it via a targeted broadcast rather than duplicating an
     * accessibility service inside PrintHost itself.
     */
    public void lockScreen() {
        Intent intent = new Intent("dev.oleksandr.usbtap.ACTION_LOCK_SCREEN");
        intent.setPackage("dev.oleksandr.usbtap");
        sendBroadcast(intent);
    }

    // ---- camera / torch -----------------------------------------------------------------------

    public boolean cameraStart() {
        boolean ok = cameraController.start();
        synchronized (this) {
            state.cameraOn = ok;
        }
        return ok;
    }

    public void cameraStop() {
        cameraController.stop();
        synchronized (this) {
            state.cameraOn = false;
        }
    }

    public byte[] cameraLatestFrame() {
        return cameraController.getLatestFrame();
    }

    public boolean torch(boolean on) {
        boolean ok = cameraController.setTorch(on);
        if (ok) {
            synchronized (this) {
                state.torchOn = on;
            }
        }
        return ok;
    }
}
