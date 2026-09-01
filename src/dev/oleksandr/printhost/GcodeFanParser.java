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

    /** Returns 0-255 fan PWM value, or null if the file can't be read or has no fan commands yet. */
    public static Integer fanSpeedAtByteOffset(File gcodeFile, long byteOffset) {
        if (gcodeFile == null || !gcodeFile.exists()) return null;
        Integer lastValue = null;
        long consumed = 0;
        try (BufferedReader reader = new BufferedReader(new FileReader(gcodeFile))) {
            String line;
            while (consumed < byteOffset && (line = reader.readLine()) != null) {
                consumed += line.length() + 1;
                String trimmed = line.trim();
                Matcher m106 = M106.matcher(trimmed);
                if (m106.find()) {
                    lastValue = Integer.parseInt(m106.group(1));
                    continue;
                }
                if (M107.matcher(trimmed).find()) {
                    lastValue = 0;
                }
            }
        } catch (IOException e) {
            return null;
        }
        return lastValue;
    }
}
