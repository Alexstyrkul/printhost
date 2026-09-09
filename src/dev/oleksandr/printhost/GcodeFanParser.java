package dev.oleksandr.printhost;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Marlin doesn't report fan speed on request, so we scan the gcode file we already uploaded
 * for the last "M106 Sxxx" (or M107 = off) at-or-before the current byte offset reported by M27.
 */
public class GcodeFanParser {

    private static final Pattern M106 = Pattern.compile("^M106\\s+S(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern M107 = Pattern.compile("^M107\\b", Pattern.CASE_INSENSITIVE);

    // uploadedFile is always the same on-disk path (current_upload.gcode, overwritten per upload
    // - see PrinterService), so identity alone can't detect a new file; length changing is what
    // invalidates tracking here.
    private static File trackedFile;
    private static long trackedFileLength = -1;

    // Incremental scan, resumed across polls instead of re-reading from byte 0 every time -
    // same fix as GcodeLayerParser's for the same reason: re-scanning 0..byteOffset every ~2s
    // poll for the whole length of a print is an O(file)-per-poll cost once the print is far
    // enough along, confirmed live as a PrintHostPoller/GC-daemon CPU storm. reader.skip() jumps
    // straight past what's already been scanned instead of re-parsing it.
    private static long scannedPosition;
    private static Integer scannedValue;

    /** Returns 0-255 fan PWM value, or null if the file can't be read or has no fan commands yet. */
    public static synchronized Integer fanSpeedAtByteOffset(File gcodeFile, long byteOffset) {
        if (gcodeFile == null || !gcodeFile.exists()) return null;
        long length = gcodeFile.length();
        if (!gcodeFile.equals(trackedFile) || length != trackedFileLength) {
            trackedFile = gcodeFile;
            trackedFileLength = length;
            scannedPosition = 0;
            scannedValue = null;
        }
        // Byte offset went backward (e.g. a reprint of the same file) - can't resume from the
        // middle, start over.
        if (byteOffset < scannedPosition) {
            scannedPosition = 0;
            scannedValue = null;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(gcodeFile))) {
            reader.skip(scannedPosition);
            long consumed = scannedPosition;
            String line;
            while (consumed < byteOffset && (line = reader.readLine()) != null) {
                consumed += line.length() + 1;
                String trimmed = line.trim();
                Matcher m106 = M106.matcher(trimmed);
                if (m106.find()) {
                    scannedValue = Integer.parseInt(m106.group(1));
                    continue;
                }
                if (M107.matcher(trimmed).find()) {
                    scannedValue = 0;
                }
            }
            scannedPosition = consumed;
        } catch (IOException e) {
            return null;
        }
        return scannedValue;
    }
}
