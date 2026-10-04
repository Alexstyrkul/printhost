package dev.oleksandr.printhost.panel;

import android.content.Context;

import java.io.IOException;
import java.io.InputStream;

/**
 * Pretend printer: every value the panel shows, moved along by tick(). Nothing here talks to a printer or a board.
 * Started with "adb shell am start -n dev.oleksandr.printhost/.MainActivity --ez mock true" (the emulator has no printer).
 */
final class Mock extends Printer {
    private final Context ctx;
    private final int totalSec = 102 * 60;
    private long homingUntil, opUntil, busyUntil;
    private double opRestoreTarget;
    private static final String EVENTS = "[2s] BOOT: POWERON, build Oct  4 2026 17:38:58\n[5s] usb: CH34x 1a86:7523 opened\n"
            + "[5s] printer: connected via usb automatically\n[9s] wifi: connected, rssi -54, ch 1\n[412s] printer: print start, window 3";

    Mock(Context ctx) {
        this.ctx = ctx;
        printing = true;
        file = "Botten_PLA_1h42m.gcode";
        progress = 37;
        lastFile = "Botten_PLA_4h40m.gcode";
        lastDuration = "4h 45m";
        nozzle = nozzleTarget = 215;
        bed = bedTarget = 60;
        fan = 100;
        zOffset = -1.28;
        canMeasure = true;
        mesh = new double[][]{{-0.50, -0.25, -0.23, -0.42}, {-0.34, -0.12, -0.08, -0.17}, {-0.26, -0.05, 0.04, -0.01}, {-0.24, -0.02, 0.10, 0.11}};
        boardState = BOARD_ONLINE;
        String[] b = {"52°", "31%", "−54 dBm", "118 KB", "0", "24°", "41%"};
        System.arraycopy(b, 0, board, 0, b.length);
        printerName = "Ender-3 V3 SE";
        firmware = "Marlin 1.0.6";
        dashboardUrl = "http://192.168.50.37:8899";
        info = new String[][]{{"Steps per mm", "X80 Y80 Z400 E424.9"}, {"Max speed, mm/s", "X250 Y250 Z5 E40"}, {"Acceleration", "P500 R500 T2500"},
                {"Nozzle PID", "P17.10 I1.39 D52.79"}};
        modelKey = "asset";
        files = new String[][]{{"Botten_PLA_1h42m.gcode", "Botten_PLA_1h42m.gcode", "3.7 MB"}, {"Botten_PLA_4h40m.gcode", "Botten_PLA_4h40m.gcode", "13.1 MB"}};
    }

    @Override
    InputStream openModel() throws IOException {
        return ctx.getAssets().open("model.gcode");
    }

    @Override
    int leftSec() {
        return (int) (totalSec * (100 - progress) / 100);
    }

    @Override
    void tick() {
        long now = System.currentTimeMillis();
        nozzle = approach(nozzle, power && nozzleTarget > 0 ? nozzleTarget : 24, 4, 1.5);
        bed = approach(bed, power && bedTarget > 0 ? bedTarget : 23, 1.5, 0.5);
        if (printing && !paused) {
            progress += 0.02;
            if (progress >= 100) finishPrint();
        }
        if (preheatMat >= 0 && (nozzleTarget != matNozzle[preheatMat] || nozzle >= nozzleTarget - 2)) preheatMat = -1;
        if (cooling && (nozzleTarget > 0 || nozzle <= 50)) cooling = false;
        if (homing && now >= homingUntil) {
            homing = false;
            homed = true;
            motorsOn = true;
            x = 110;
            y = 110;
            z = 10;
        }
        if ((leveling || measuring) && now >= busyUntil) {
            if (leveling) {
                for (double[] r : mesh) for (int i = 0; i < r.length; i++) r[i] = Math.round((r[i] + (Math.random() - 0.5) * 0.04) * 100) / 100.0;
            } else {
                zOffset = Math.round((zOffset + (Math.random() - 0.5) * 0.06) * 100) / 100.0;
            }
            leveling = measuring = false;
        }
        if (op != OP_NONE) {
            if (opPhase == 0 && nozzle >= opTemp - 3) {
                opPhase = 1;
                opUntil = now + (op == OP_FEED ? feedMm * 200L : 5000);
            } else if (opPhase == 1 && now >= opUntil) {
                if (op == OP_LOAD) {
                    opPhase = 2;
                    opUntil = now + 1500;
                } else {
                    endOp();
                }
            } else if (opPhase == 2 && now >= opUntil) {
                endOp();
            }
        }
    }

    private static double approach(double cur, double want, double up, double down) {
        if (Math.abs(want - cur) <= (want > cur ? up : down)) return want;
        return cur + (want > cur ? up : -down);
    }

    private void endOp() {
        nozzleTarget = opRestoreTarget;
        op = OP_NONE;
    }

    /** Unlike the real panel, the manual actions also work while the pretend print runs, so every screen can be tried. */
    @Override
    boolean manualOk() {
        return power && connected && !busy();
    }

    @Override
    void setNozzleTarget(double v) {
        nozzleTarget = v;
    }

    @Override
    void setBedTarget(double v) {
        bedTarget = v;
    }

    @Override
    void setFan(int percent) {
        fan = percent;
    }

    @Override
    void setSpeed(int percent) {
        speed = percent;
    }

    @Override
    void setFlow(int percent) {
        flow = percent;
    }

    @Override
    void nudgeZOffset(int dir) {
        zOffset = clamp(Math.round((zOffset + 0.01 * dir) * 100) / 100.0, -5, 5);
    }

    @Override
    void preheat(int mat) {
        if (!power || !connected || busy()) return;
        material = mat;
        nozzleTarget = matNozzle[mat];
        bedTarget = matBed[mat];
        cooling = false;
        preheatMat = nozzle < nozzleTarget - 2 ? mat : -1;
    }

    @Override
    void cancelPreheat() {
        preheatMat = -1;
        nozzleTarget = 0;
        bedTarget = 0;
    }

    private void startOp(int kind, int temp) {
        op = kind;
        opTemp = temp;
        opRestoreTarget = nozzleTarget;
        if (nozzle >= temp - 3 && kind == OP_FEED) {
            opPhase = 1;
            opUntil = System.currentTimeMillis() + feedMm * 200L;
        } else {
            opPhase = 0;
            nozzleTarget = temp;
        }
    }

    @Override
    void load() {
        if (manualOk()) startOp(OP_LOAD, matNozzle[material]);
    }

    @Override
    void unload() {
        if (manualOk()) startOp(OP_UNLOAD, 240);
    }

    @Override
    void feed(int dir) {
        if (!manualOk()) return;
        feedVerb = dir > 0 ? "Extruding" : "Retracting";
        startOp(OP_FEED, matNozzle[material]);
    }

    @Override
    void cancelOp() {
        if (op != OP_NONE) endOp();
    }

    @Override
    void coolDown() {
        if (!power) return;
        cancelOp();
        nozzleTarget = 0;
        bedTarget = 0;
        fan = 0;
        preheatMat = -1;
        cooling = nozzle > 50;
        coolFrom = nozzle;
    }

    @Override
    void home() {
        if (!manualOk()) return;
        homing = true;
        homed = false;
        homingUntil = System.currentTimeMillis() + 2500;
    }

    @Override
    void move(char axis, double mm) {
        if (!manualOk() || !homed) return;
        if (axis == 'X') x = clamp(x + mm, 0, 220);
        else if (axis == 'Y') y = clamp(y + mm, 0, 220);
        else z = clamp(z + mm, 0, 250);
    }

    @Override
    void toggleMotors() {
        if (!manualOk()) return;
        motorsOn = !motorsOn;
        if (!motorsOn) homed = false;
    }

    @Override
    void levelBed() {
        if (!manualOk()) return;
        leveling = true;
        homed = false;
        busyUntil = System.currentTimeMillis() + 5000;
    }

    @Override
    void saveMeshPoint(int row, int column) {
    }

    @Override
    void measureZOffset() {
        if (!manualOk()) return;
        measuring = true;
        homed = false;
        busyUntil = System.currentTimeMillis() + 4000;
    }

    @Override
    void selectFile(String name, String shownName) {
        selectedFile = name;
        fileReady = true;
        lastFile = file = shownName;
    }

    @Override
    void startPrint() {
        if (!power || printing) return;
        fileReady = false;
        selectedFile = "";
        cancelOp();
        printing = true;
        paused = false;
        progress = 37;
        nozzleTarget = 215;
        bedTarget = 60;
        fan = 100;
        homed = false;
    }

    @Override
    void togglePause() {
        paused = !paused;
    }

    @Override
    void stopPrint() {
        if (printing) finishPrint();
    }

    private void finishPrint() {
        printing = false;
        paused = false;
        lastFile = file;
        lastDuration = duration((int) (totalSec * Math.min(100, progress) / 100));
        nozzleTarget = 0;
        bedTarget = 0;
        fan = 0;
    }

    @Override
    void setPower(boolean on) {
        power = on;
        connected = on;
        boardState = on ? BOARD_ONLINE : BOARD_OFFLINE;
        if (!on) {
            printing = false;
            paused = false;
            cancelOp();
            homing = leveling = measuring = false;
            homed = false;
            motorsOn = false;
            preheatMat = -1;
            cooling = false;
            nozzleTarget = 0;
            bedTarget = 0;
            fan = 0;
        }
    }

    @Override
    void setConnected(boolean on) {
        if (power) connected = on;
    }

    @Override
    void events(Callback<String> cb) {
        cb.done(EVENTS);
    }

    @Override
    void logFiles(Callback<String[][]> cb) {
        cb.done(new String[][]{{"log-0003.txt", "2.3 MB"}, {"log-0002.txt", "10.0 MB"}, {"log-0001.txt", "10.0 MB"}});
    }

    @Override
    void logFile(String name, Callback<String> cb) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) sb.append(EVENTS).append('\n');
        cb.done(sb.toString());
    }
}
