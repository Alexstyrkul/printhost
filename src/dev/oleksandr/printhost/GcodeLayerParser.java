package dev.oleksandr.printhost;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

/**
 * Marlin doesn't track or report layer number, so we scan the gcode file we already have a local
 * copy of - same approach as GcodeFanParser, and the same reason: the printer only tells us a
 * byte offset (M27), not anything semantic about what's at that offset.
 *
 * ";LAYER_CHANGE" is OrcaSlicer's real layer-boundary marker - confirmed against the actual
 * OrcaSlicer source (src/libslic3r/GCode/GCodeProcessor.cpp), not guessed. Deliberately not
 * using the "; total layer number: N" header some OrcaSlicer exports carry - confirmed in that
 * same source that it's written "for Bambu printers only", so it's not reliably present for this
 * printer's profile. Counting markers ourselves works regardless of machine profile.
 */
public class GcodeLayerParser {

    private static final String LAYER_CHANGE_TAG = ";LAYER_CHANGE";

    public static class LayerInfo {
        public final int currentLayer;
        public final int totalLayers;
        LayerInfo(int currentLayer, int totalLayers) {
            this.currentLayer = currentLayer;
            this.totalLayers = totalLayers;
        }
    }

    // uploadedFile is always the same on-disk path (current_upload.gcode, overwritten per upload
    // - see PrinterService), so identity alone can't detect a new file; length changing is what
    // invalidates tracking here. totalLayers only needs computing once per file (one full scan).
    private static File trackedFile;
    private static long trackedFileLength = -1;
    private static int trackedTotalLayers;

    // Incremental scan of "current layer" - resumed across polls instead of re-reading from byte
    // 0 every time. Caching totalLayers alone (an earlier version of this fix) wasn't enough:
    // re-scanning 0..byteOffset every ~2s poll for the whole length of a print is itself the same
    // O(file)-per-poll cost once the print is far enough along - confirmed live, the
    // PrintHostPoller/GC-daemon CPU storm came right back as the print progressed even with
    // totalLayers cached. reader.skip() jumps straight past what's already been counted instead of
    // re-parsing it.
    private static long scannedPosition;
    private static int scannedCurrent;

    /** Returns null if the file can't be read or has no layer markers at all (e.g. a file from a
     *  slicer/profile that doesn't emit them). */
    public static synchronized LayerInfo layerInfoAtByteOffset(File gcodeFile, long byteOffset) {
        if (gcodeFile == null || !gcodeFile.exists()) return null;
        long length = gcodeFile.length();
        if (!gcodeFile.equals(trackedFile) || length != trackedFileLength) {
            trackedFile = gcodeFile;
            trackedFileLength = length;
            trackedTotalLayers = countLayers(gcodeFile);
            scannedPosition = 0;
            scannedCurrent = 0;
        }
        if (trackedTotalLayers == 0) return null;

        // Byte offset went backward (e.g. a reprint of the same file) - can't resume from the
        // middle, start over.
        if (byteOffset < scannedPosition) {
            scannedPosition = 0;
            scannedCurrent = 0;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(gcodeFile))) {
            reader.skip(scannedPosition);
            long consumed = scannedPosition;
            String line;
            while (consumed < byteOffset && (line = reader.readLine()) != null) {
                consumed += line.length() + 1;
                if (line.trim().startsWith(LAYER_CHANGE_TAG)) {
                    scannedCurrent++;
                }
            }
            scannedPosition = consumed;
        } catch (IOException e) {
            return null;
        }
        return new LayerInfo(scannedCurrent, trackedTotalLayers);
    }

    private static int countLayers(File gcodeFile) {
        int total = 0;
        try (BufferedReader reader = new BufferedReader(new FileReader(gcodeFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().startsWith(LAYER_CHANGE_TAG)) total++;
            }
        } catch (IOException e) {
            return 0;
        }
        return total;
    }
}
