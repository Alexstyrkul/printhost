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

    /** Returns null if the file can't be read or has no layer markers at all (e.g. a file from a
     *  slicer/profile that doesn't emit them). One pass over the whole file - see the class
     *  javadoc for why a single marker count serves both "how many so far" and "how many total". */
    public static LayerInfo layerInfoAtByteOffset(File gcodeFile, long byteOffset) {
        if (gcodeFile == null || !gcodeFile.exists()) return null;
        int current = 0;
        int total = 0;
        long consumed = 0;
        try (BufferedReader reader = new BufferedReader(new FileReader(gcodeFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                consumed += line.length() + 1;
                if (line.trim().startsWith(LAYER_CHANGE_TAG)) {
                    total++;
                    if (consumed <= byteOffset) current = total;
                }
            }
        } catch (IOException e) {
            return null;
        }
        if (total == 0) return null;
        return new LayerInfo(current, total);
    }
}
