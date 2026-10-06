package dev.oleksandr.printhost.panel;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.oleksandr.printhost.PrinterService;
import dev.oleksandr.printhost.PrinterState;

/**
 * The real printer: values come from PrinterService's state (the same one the web dashboard shows), actions go through
 * the service's manual-control methods, which check everything again and talk to the board. Board vitals and logs are
 * read from the board directly. Every network call runs off the UI thread; a failed action shows its reason as a toast.
 */
final class Live extends Printer {
    private static final int K_NOZZLE = 0, K_BED = 1, K_FAN = 2, K_SPEED = 3, K_FLOW = 4, K_Z = 5;

    private final Context ctx;
    private final Runnable changed;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newCachedThreadPool();

    // A value just set here is shown as set until the service has had time to report it back.
    private final long[] holdUntil = new long[6];
    private final Object setLock = new Object();
    private final Double[] want = new Double[6];
    private boolean sending = false;

    private final Object moveLock = new Object();
    private char pendAxis;
    private double pendMm;

    private long opHoldUntil, loadDoneUntil, lastBoardPoll, lastMeshTry;
    private int loadTemp, feedTemp;
    private boolean motorsReleased = false, meshLoading = false;
    private int meshTries = 0;
    private volatile boolean boardPolling = false, boardSeesPrinter = false;

    Live(Context ctx, Runnable changed) {
        this.ctx = ctx;
        this.changed = changed;
        connected = false;
    }

    private PrinterService svc() {
        return PrinterService.get();
    }

    private void toast(final String text) {
        ui.post(() -> Toast.makeText(ctx, text == null || text.isEmpty() ? "Failed" : text, Toast.LENGTH_LONG).show());
    }

    /** Runs a service action in the background; a refusal or an error is shown, then the screen is refreshed. */
    private void act(final Callable<PrinterService.UploadOutcome> action) {
        pool.execute(() -> {
            try {
                PrinterService.UploadOutcome o = action.call();
                if (o != null && !o.success && !"Cancelled".equals(o.message)) toast(o.message);
            } catch (Exception e) {
                toast(String.valueOf(e.getMessage()));
            }
            ui.post(changed);
        });
    }

    @Override
    void tick() {
        PrinterService s = svc();
        long now = System.currentTimeMillis();
        if (s == null) {
            connected = false;
            return;
        }
        PrinterState st = s.getState();
        power = st.plugOn == null || st.plugOn;
        simulator = st.simulator;
        PrinterState.Phase ph = st.phase;
        // The board's own view of the printer comes first: it notices within a couple of seconds when the printer is
        // switched off, long before the phone's connection gives up.
        connected = power && boardSeesPrinter && s.isPrinterOpen() && ph != PrinterState.Phase.CONNECTING && ph != PrinterState.Phase.DISCONNECTED;
        printing = connected && (ph == PrinterState.Phase.PRINTING || ph == PrinterState.Phase.PAUSED);
        paused = ph == PrinterState.Phase.PAUSED;
        fileReady = !printing && st.fileReady;
        selectedFile = fileReady && st.currentFile != null ? st.currentFile : "";
        if (!printing && boardState != BOARD_OFFLINE && !filesLoading && now - lastFilesAt > 8000) {
            lastFilesAt = now;
            loadFiles();
        }
        progress = st.printProgressPercent;
        layer = st.currentLayer == null ? -1 : st.currentLayer;
        layers = st.totalLayers == null ? -1 : st.totalLayers;
        String shown = st.currentFileDisplay == null ? "" : st.currentFileDisplay;
        if (printing) {
            file = shown;
        } else {
            lastFile = shown;
            lastDuration = st.elapsedSeconds > 0 ? duration((int) st.elapsedSeconds) : "";
        }
        elapsedSec = st.elapsedSeconds;

        nozzle = st.hotendTemp;
        bed = st.bedTemp;
        if (now > holdUntil[K_NOZZLE]) nozzleTarget = st.hotendTarget;
        if (now > holdUntil[K_BED]) bedTarget = st.bedTarget;
        Integer f = st.manualFan != null ? st.manualFan : st.fanSpeed;
        if (now > holdUntil[K_FAN]) fan = f == null ? 0 : Math.round(f * 100f / 255f);
        if (now > holdUntil[K_SPEED]) speed = st.feedPercent == null ? 100 : st.feedPercent;
        if (now > holdUntil[K_FLOW]) flow = st.flowPercent == null ? 100 : st.flowPercent;
        if (now > holdUntil[K_Z]) zOffset = st.zOffset;
        zOffsetKnown = st.zOffsetKnown;

        homed = st.homed;
        x = st.posX == null ? 0 : st.posX;
        y = st.posY == null ? 0 : st.posY;
        z = st.posZ == null ? 0 : st.posZ;
        String busy = st.manualBusy == null ? "" : st.manualBusy;
        homing = "Homing".equals(busy);
        // The motors count as on unless we released them ourselves and nothing has moved since: that is the safe guess
        // (after a print, a move or a homing they are on, and "Motors off" on already free motors does no harm).
        if (homed || printing || leveling || jogging) motorsReleased = false;
        if (!connected) motorsReleased = false;
        motorsOn = !motorsReleased;

        if (now > opHoldUntil) {
            if ("Loading filament".equals(busy)) {
                setOp(OP_LOAD, loadTemp);
                if (st.loadWaiting) opPhase = 3;
            }
            else if (st.unloadingFilament) setOp(OP_UNLOAD, 240);
            else if ("Extruding".equals(busy) || "Retracting".equals(busy)) setOp(OP_FEED, feedTemp);
            else if (now < loadDoneUntil) {
                op = OP_LOAD;
                opPhase = 2;
            } else op = OP_NONE;
        }
        leveling = st.levelingBed;
        manualBusy = !busy.isEmpty() && !busy.startsWith("Moving ");
        if (preheatMat >= 0 && (nozzleTarget != matNozzle[preheatMat] || nozzle >= nozzleTarget - 2)) preheatMat = -1;
        if (cooling && (nozzleTarget > 0 || nozzle <= 50)) cooling = false;

        JSONArray presets = st.presets;
        if (presets != null && presets.length() >= 2) {
            for (int i = 0; i < 2; i++) {
                JSONObject p = presets.optJSONObject(i);
                if (p == null) continue;
                matName[i] = p.optString("name", matName[i]);
                matNozzle[i] = p.optInt("hotend", matNozzle[i]);
                matBed[i] = p.optInt("bed", matBed[i]);
            }
        }
        String fw = st.firmwareInfo == null ? "" : st.firmwareInfo;
        Matcher mt = MACHINE.matcher(fw);
        if (mt.find()) printerName = mt.group(1).trim();
        Matcher fm = FIRMWARE.matcher(fw);
        if (fm.find()) firmware = fm.group(1).trim();
        info = st.machineInfo;

        File model = s.getUploadedFile();
        if (model == null && st.currentFile != null && !st.currentFile.isEmpty()) model = s.getCachedGcodeFile(st.currentFile);
        modelFile = model != null && model.exists() ? model : null;
        modelKey = modelFile == null ? "" : modelFile.getPath() + ":" + modelFile.length();

        if (now - lastBoardPoll > 2000 && !boardPolling) {
            lastBoardPoll = now;
            boardPolling = true;
            pool.execute(this::pollBoard);
        }
        if (mesh == null && connected && !printing && !busy() && !meshLoading && meshTries < 3 && now - lastMeshTry > 30000) {
            lastMeshTry = now;
            meshTries++;  // a printer that reports no mesh is not asked forever
            loadMesh();
        }
    }

    private static final Pattern MACHINE = Pattern.compile("MACHINE_TYPE:(.*?)(?: EXTRUDER_COUNT|$)");
    private static final Pattern FIRMWARE = Pattern.compile("FIRMWARE_NAME:(.*?)(?: \\(| SOURCE_CODE_URL|$)");

    private long elapsedSec, lastFilesAt;
    private volatile boolean filesLoading = false;

    /** The list of files on the board's card (asked from the board, no printer command). */
    private void loadFiles() {
        filesLoading = true;
        pool.execute(() -> {
            try {
                JSONArray list = svc().listSdFiles();
                String[][] out = new String[list.length()][];
                for (int i = 0; i < out.length; i++) {
                    JSONObject f = list.getJSONObject(i);
                    out[i] = new String[]{f.getString("name"), f.optString("displayName", f.getString("name")),
                            String.format(Locale.US, "%.1f MB", f.optLong("size") / 1048576.0)};
                }
                files = out;
            } catch (Exception ignored) {
                // the board did not answer: keep the list we have
            }
            filesLoading = false;
            ui.post(changed);
        });
    }

    @Override
    void selectFile(final String name, final String shownName) {
        if (printing) return;
        act(() -> svc().selectSdFile(name, shownName));
    }

    @Override
    void startPrint() {
        pool.execute(() -> {
            PrinterService s = svc();
            if (s != null && !s.startPrint()) toast(s.getState().lastError);
            ui.post(changed);
        });
    }
    private boolean manualBusy = false;
    private File modelFile;

    private void setOp(int kind, int temp) {
        op = kind;
        opTemp = temp > 0 ? temp : matNozzle[material];
        opPhase = nozzle >= opTemp - 3 ? 1 : 0;
    }

    @Override
    boolean busy() {
        return super.busy() || manualBusy;
    }

    @Override
    boolean manualOk() {
        return power && connected && !printing && !busy();
    }

    @Override
    InputStream openModel() throws IOException {
        File f = modelFile;
        if (f == null) throw new IOException("no model");
        return new FileInputStream(f);
    }

    @Override
    int leftSec() {
        return progress > 0 && progress < 100 ? (int) (elapsedSec * (100 - progress) / progress) : 0;
    }

    // ---- values: the latest wish per value is sent, one at a time ----

    private void set(int kind, double v) {
        holdUntil[kind] = System.currentTimeMillis() + 4000;
        synchronized (setLock) {
            want[kind] = kind == K_Z && want[kind] != null ? want[kind] + v : v;
            if (sending) return;
            sending = true;
        }
        pool.execute(this::drain);
    }

    private void drain() {
        for (;;) {
            int kind = -1;
            double v = 0;
            synchronized (setLock) {
                for (int i = 0; i < want.length && kind < 0; i++) {
                    if (want[i] != null) {
                        kind = i;
                        v = want[i];
                        want[i] = null;
                    }
                }
                if (kind < 0) {
                    sending = false;
                    break;
                }
            }
            PrinterService s = svc();
            if (s == null) continue;
            try {
                PrinterService.UploadOutcome o;
                switch (kind) {
                    case K_NOZZLE: o = s.manualSetTemps((int) Math.round(v), null); break;
                    case K_BED: o = s.manualSetTemps(null, (int) Math.round(v)); break;
                    case K_FAN: o = s.manualFan((int) Math.round(v)); break;
                    case K_SPEED: o = s.manualFeed((int) Math.round(v)); break;
                    case K_FLOW: o = s.manualFlow((int) Math.round(v)); break;
                    default: {
                        // several taps add up; the service takes at most 0.1 mm per call
                        double step = clamp(v, -0.1, 0.1), rest = Math.round((v - step) * 100) / 100.0;
                        if (Math.abs(rest) >= 0.005) {
                            synchronized (setLock) {
                                want[K_Z] = want[K_Z] == null ? rest : want[K_Z] + rest;
                            }
                        }
                        o = Math.abs(step) < 0.005 ? null : s.manualZOffset(step);
                    }
                }
                if (o != null && !o.success) {
                    toast(o.message);
                    holdUntil[kind] = 0;  // show what the printer really has
                } else {
                    holdUntil[kind] = System.currentTimeMillis() + 1500;
                }
            } catch (Exception e) {
                toast(String.valueOf(e.getMessage()));
                holdUntil[kind] = 0;
            }
        }
        ui.post(changed);
    }

    @Override
    void setNozzleTarget(double v) {
        nozzleTarget = v;
        set(K_NOZZLE, v);
    }

    @Override
    void setBedTarget(double v) {
        bedTarget = v;
        set(K_BED, v);
    }

    @Override
    void setFan(int percent) {
        fan = percent;
        set(K_FAN, percent);
    }

    @Override
    void setSpeed(int percent) {
        speed = percent;
        set(K_SPEED, percent);
    }

    @Override
    void setFlow(int percent) {
        flow = percent;
        set(K_FLOW, percent);
    }

    @Override
    void nudgeZOffset(int dir) {
        if (!zOffsetKnown) return;
        double next = clamp(Math.round((zOffset + 0.01 * dir) * 100) / 100.0, -5, 5);
        if (next == zOffset) return;
        zOffset = next;
        set(K_Z, 0.01 * dir);
    }

    // ---- filament ----

    @Override
    void preheat(final int mat) {
        if (!manualOk()) return;
        material = mat;
        cooling = false;
        nozzleTarget = matNozzle[mat];
        bedTarget = matBed[mat];
        holdUntil[K_NOZZLE] = holdUntil[K_BED] = System.currentTimeMillis() + 4000;
        preheatMat = nozzle < nozzleTarget - 2 ? mat : -1;
        act(() -> svc().manualPreheat(mat));
    }

    @Override
    void cancelPreheat() {
        preheatMat = -1;
        nozzleTarget = 0;
        bedTarget = 0;
        holdUntil[K_NOZZLE] = holdUntil[K_BED] = System.currentTimeMillis() + 4000;
        act(() -> svc().manualSetTemps(0, 0));
    }

    @Override
    void coolDown() {
        if (!power || !connected) return;
        final PrinterService s = svc();
        if (s == null) return;
        s.manualCancel();
        preheatMat = -1;
        cooling = nozzle > 50;
        coolFrom = nozzle;
        nozzleTarget = 0;
        bedTarget = 0;
        holdUntil[K_NOZZLE] = holdUntil[K_BED] = System.currentTimeMillis() + 4000;
        act(() -> {
            PrinterService.UploadOutcome o = s.manualCooldown();
            if (o.success) s.manualFan(0);
            return o;
        });
    }

    private void showOp(int kind, int temp) {
        op = kind;
        opTemp = temp;
        opPhase = nozzle >= temp - 3 ? 1 : 0;
        opHoldUntil = System.currentTimeMillis() + 2500;
    }

    @Override
    void load() {
        if (!manualOk()) return;
        loadTemp = matNozzle[material];
        showOp(OP_LOAD, loadTemp);
        final int temp = loadTemp;
        act(() -> {
            PrinterService.UploadOutcome o = svc().manualLoadFilament(temp);
            if (o.success) loadDoneUntil = System.currentTimeMillis() + 1500;
            else opHoldUntil = 0;
            return o;
        });
    }

    @Override
    void unload() {
        if (!manualOk()) return;
        showOp(OP_UNLOAD, 240);
        act(() -> {
            PrinterService.UploadOutcome o = svc().unloadFilament();
            if (!o.success) opHoldUntil = 0;
            return o;
        });
    }

    @Override
    void feed(int dir) {
        if (!manualOk()) return;
        feedVerb = dir > 0 ? "Extruding" : "Retracting";
        feedTemp = matNozzle[material];
        showOp(OP_FEED, feedTemp);
        final double mm = dir * feedMm;
        final int temp = feedTemp;
        act(() -> {
            PrinterService.UploadOutcome o = svc().manualExtrude(mm, temp);
            if (!o.success) opHoldUntil = 0;
            return o;
        });
    }

    @Override
    void cancelOp() {
        PrinterService s = svc();
        if (s != null) s.manualCancel();
        opHoldUntil = 0;
    }

    @Override
    void continueLoad() {
        PrinterService s = svc();
        if (s != null) s.manualContinue();
        opPhase = 1;
        opHoldUntil = System.currentTimeMillis() + 1500;
    }

    // ---- motion ----

    @Override
    void home() {
        if (!manualOk()) return;
        homing = true;
        act(() -> svc().manualHome());
    }

    @Override
    void move(char axis, double mm) {
        synchronized (moveLock) {
            if (jogging) {  // one move at a time: remember only the newest request
                pendAxis = axis;
                pendMm = mm;
                return;
            }
            if (!manualOk()) return;
            jogging = true;
        }
        sendMove(axis, mm);
    }

    @Override
    void stopJog() {
        synchronized (moveLock) {
            pendMm = 0;
        }
    }

    private void sendMove(final char axis, final double mm) {
        pool.execute(() -> {
            PrinterService s = svc();
            boolean ok = false;
            if (s != null) {
                PrinterService.UploadOutcome o = s.manualMove(String.valueOf(axis), mm);
                ok = o.success;
                if (!ok) toast(o.message);
            }
            char a;
            double next;
            synchronized (moveLock) {
                a = pendAxis;
                next = ok ? pendMm : 0;
                pendMm = 0;
                if (next == 0) jogging = false;
            }
            ui.post(changed);
            if (next != 0) sendMove(a, next);
        });
    }

    @Override
    void toggleMotors() {
        if (!manualOk()) return;
        final boolean off = !motorsReleased;
        motorsReleased = off;
        act(() -> off ? svc().manualMotorsOff() : svc().manualMotorsOn());
    }

    // ---- calibration ----

    private void loadMesh() {
        meshLoading = true;
        pool.execute(() -> {
            try {
                JSONArray g = svc().getLevelingGrid();
                if (g.length() == 4) {
                    double[][] m = new double[4][4];
                    for (int r = 0; r < 4; r++) for (int k = 0; k < 4; k++) m[r][k] = g.getJSONArray(r).getDouble(k);
                    mesh = m;
                }
            } catch (Exception ignored) {
            }
            meshLoading = false;
            ui.post(changed);
        });
    }

    @Override
    void levelBed() {
        if (!manualOk()) return;
        leveling = true;
        act(() -> {
            PrinterService.UploadOutcome o = svc().levelBed();
            meshTries = 0;
            if (o.success) loadMesh();
            return o;
        });
    }

    @Override
    void saveMeshPoint(final int row, final int column) {
        if (mesh == null) return;
        final double z = mesh[row][column];
        act(() -> svc().manualSetMeshPoint(column, row, z));
    }

    @Override
    void measureZOffset() {
    }

    // ---- print, power, connection ----

    @Override
    void togglePause() {
        final boolean resume = paused;
        pool.execute(() -> {
            PrinterService s = svc();
            if (s != null && !(resume ? s.resumePrint() : s.pausePrint())) toast(s.getState().lastError);
            ui.post(changed);
        });
    }

    @Override
    void stopPrint() {
        pool.execute(() -> {
            PrinterService s = svc();
            if (s != null && !s.stopPrint()) toast(s.getState().lastError);
            ui.post(changed);
        });
    }

    @Override
    void setPower(final boolean on) {
        act(() -> on ? svc().tapoPlugOn() : svc().tapoPlugOff());
    }

    @Override
    void setConnected(final boolean on) {
        pool.execute(() -> {
            PrinterService s = svc();
            if (s == null) return;
            if (on) {
                if (!s.connectPrinter()) toast(s.getState().lastError);
            } else {
                s.disconnectPrinter();
            }
            ui.post(changed);
        });
    }

    // ---- the board: vitals, room sensor, logs ----

    private String boardUrl(String path) {
        PrinterService s = svc();
        return "http://" + (s == null ? "192.168.50.190" : s.getEspHost()) + path;
    }

    private static String get(String url, int timeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(Math.min(timeoutMs, 3000));
        c.setReadTimeout(timeoutMs);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private void pollBoard() {
        try {
            JSONObject b = new JSONObject(get(boardUrl("/status"), 2500));
            JSONObject p = new JSONObject(get(boardUrl("/printer/status"), 2500));
            long up = b.optLong("uptime");
            board[0] = Math.round(b.optDouble("tempC")) + "°";
            board[1] = Math.max(b.optInt("cpu0"), b.optInt("cpu1")) + "%";
            board[2] = b.optInt("rssi") + " dBm";
            board[3] = b.optInt("freeHeapKB") + " KB";
            board[4] = String.valueOf(p.optInt("resends"));
            boolean env = b.optBoolean("envOk");
            board[5] = env ? Math.round(b.optDouble("envTempC")) + "°" : "–";
            board[6] = env ? Math.round(b.optDouble("envHum")) + "%" : "–";
            boardState = up < 20 ? BOARD_STARTING : BOARD_ONLINE;
            String ps = p.optString("state");
            boardSeesPrinter = "IDLE".equals(ps) || "PRINTING".equals(ps) || "PAUSED".equals(ps);
        } catch (Exception e) {
            boardState = BOARD_OFFLINE;
            boardSeesPrinter = false;
            for (int i = 0; i < board.length; i++) board[i] = "–";
        }
        WifiManager wifi = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        int ip = wifi == null ? 0 : wifi.getConnectionInfo().getIpAddress();
        dashboardUrl = ip == 0 ? "" : "http://" + Formatter.formatIpAddress(ip) + ":" + PrinterService.HTTP_PORT;
        boardPolling = false;
    }

    private <T> void fetch(final Callable<T> work, final T onError, final Callback<T> cb) {
        pool.execute(() -> {
            T v;
            try {
                v = work.call();
            } catch (Exception e) {
                v = onError;
            }
            final T result = v;
            ui.post(() -> cb.done(result));
        });
    }

    @Override
    void events(Callback<String> cb) {
        fetch(() -> get(boardUrl("/log"), 8000), "The board does not answer", cb);
    }

    @Override
    void logFiles(Callback<String[][]> cb) {
        fetch(() -> {
            JSONArray files = new JSONObject(get(boardUrl("/logs"), 8000)).getJSONArray("files");
            String[][] out = new String[files.length()][];
            for (int i = 0; i < out.length; i++) {
                JSONObject f = files.getJSONObject(i);
                out[i] = new String[]{f.getString("name"), String.format(Locale.US, "%.1f MB", f.optLong("size") / 1048576.0)};
            }
            return out;
        }, new String[0][], cb);
    }

    /** The last 32 KB of the file (the board sends no more than that per request, and slowly). */
    @Override
    void logFile(final String name, Callback<String> cb) {
        fetch(() -> get(boardUrl("/logs/read?name=" + name), 60000), "The board does not answer", cb);
    }
}
