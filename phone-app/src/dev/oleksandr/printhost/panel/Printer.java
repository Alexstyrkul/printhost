package dev.oleksandr.printhost.panel;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * Everything the panel's screens show and every action they can ask for. The screens read the fields and call the
 * methods; where the values come from is up to the subclass: Live (the real printer, through PrinterService) or Mock
 * (pretend values, for looking at the screens without a printer).
 */
public abstract class Printer {
    static final int OP_NONE = 0, OP_LOAD = 1, OP_UNLOAD = 2, OP_FEED = 3;
    static final int BOARD_OFFLINE = 0, BOARD_STARTING = 1, BOARD_ONLINE = 2;

    interface Callback<T> {
        void done(T value);
    }

    /** The printer's preheat presets. */
    String[] matName = {"PLA", "TPU"};
    int[] matNozzle = {200, 230}, matBed = {60, 70};

    boolean power = true, connected = true;
    /** The board's pretend printer is in use instead of the real one (test mode). */
    boolean simulator = false;
    boolean printing = false, paused = false;
    /** A file is chosen and on the board, waiting to be started (its model is shown before the print). */
    boolean fileReady = false;
    String file = "";
    double progress = 0;
    /** The layer being printed and the file's layer count, when known (-1 = not known: the progress is used instead). */
    int layer = -1, layers = -1;
    String lastFile = "", lastDuration = "";

    double nozzle, nozzleTarget, bed, bedTarget;
    int fan, speed = 100, flow = 100;
    double zOffset;

    boolean homed = false, homing = false, motorsOn = false;
    double x, y, z;

    int material = 0;
    int feedMm = 5;
    /** A filament action in progress. Phase 0 = heating, 1 = moving the filament, 2 = done (load only). */
    int op = OP_NONE, opPhase = 0;
    int opTemp;
    String feedVerb = "Extruding";
    /** PLA / TPU was pressed and the nozzle is still heating up for it (-1 = no). */
    int preheatMat = -1;
    /** Cool down was pressed and the nozzle is still hot. */
    boolean cooling = false;
    double coolFrom;

    boolean leveling = false, measuring = false;
    /** Whether the printer's own "measure the Z offset" routine can be started from here. */
    boolean canMeasure = false;
    /** The bed mesh, back row first; null = not read yet. */
    double[][] mesh;

    int boardState = BOARD_OFFLINE;
    /** Chip, CPU, Wi-Fi, Free RAM, Resends, Room temperature, Room humidity. */
    final String[] board = {"–", "–", "–", "–", "–", "–", "–"};

    String printerName = "", firmware = "", dashboardUrl = "";
    /** The printer's stored settings for the Information block: {label, value}. */
    String[][] info = new String[0][];

    /** Changes whenever a different model should be shown in the preview ("" = none). */
    String modelKey = "";

    /** Called twice a second on the UI thread: bring the fields up to date. */
    abstract void tick();

    abstract InputStream openModel() throws IOException;

    abstract int leftSec();

    boolean busy() {
        return op != OP_NONE || homing || leveling || measuring;
    }

    /** Whether the manual actions (move, filament, calibration) may be used right now. */
    abstract boolean manualOk();

    abstract void setNozzleTarget(double v);

    abstract void setBedTarget(double v);

    abstract void setFan(int percent);

    abstract void setSpeed(int percent);

    abstract void setFlow(int percent);

    /** dir -1 / +1: one 0.01 mm step of the Z offset. */
    abstract void nudgeZOffset(int dir);

    abstract void preheat(int mat);

    /** Stops a PLA / TPU preheat: heaters off. */
    abstract void cancelPreheat();

    abstract void coolDown();

    abstract void load();

    abstract void unload();

    /** dir +1 pushes the filament out, -1 pulls it back. A cold nozzle is heated to the material's temperature first. */
    abstract void feed(int dir);

    abstract void cancelOp();

    abstract void home();

    /** Moves an axis by mm (also before homing: then nothing limits the move). While a move is still on its way the
     *  newest request waits and replaces any older one; stopJog() drops it. */
    abstract void move(char axis, double mm);

    /** The finger is off the jog button: nothing more is sent after the move that is on its way. */
    void stopJog() {
    }

    /** A jog move of ours is on its way (the jog buttons stay usable meanwhile). */
    boolean jogging = false;

    /** How far one jog request moves. held = how many repeats the held button has made so far (0 = the first press). */
    static double jogStep(char axis, int held) {
        if (axis == 'Z') return held < 12 ? 0.5 : 2;
        return held < 12 ? 1 : 5;
    }

    abstract void toggleMotors();

    abstract void levelBed();

    /** Stores mesh[row][column] (already changed in place) in the printer. */
    abstract void saveMeshPoint(int row, int column);

    abstract void measureZOffset();

    /** The gcode files on the board's card: {name, shown name, size as text}. */
    String[][] files = new String[0][];
    /** The file that is chosen and ready to start ("" = none). */
    String selectedFile = "";

    /** Chooses one of the files on the card (its model then shows in the preview). */
    abstract void selectFile(String name, String shownName);

    /** Starts printing the chosen file. */
    abstract void startPrint();

    abstract void togglePause();

    abstract void stopPrint();

    abstract void setPower(boolean on);

    abstract void setConnected(boolean on);

    /** The board's recent events, as text. */
    abstract void events(Callback<String> cb);

    /** The board's log files: {name, size as text}. */
    abstract void logFiles(Callback<String[][]> cb);

    /** The end of one log file. */
    abstract void logFile(String name, Callback<String> cb);

    static String duration(int sec) {
        int h = sec / 3600, m = (sec % 3600) / 60;
        return h > 0 ? String.format(Locale.US, "%dh %02dm", h, m) : m + "m";
    }

    static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
