package dev.oleksandr.printhost;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Remembers the original, human-readable filename a slicer gave each upload, keyed by the short
 * 8.3 name the printer's firmware actually stored it under. Needed because M28 (this firmware's
 * SD-write command) only ever accepts classic 8.3 names - the long name never reaches the
 * printer, so there is no way to recover it later from M20 alone. Confirmed on real hardware:
 * "M20 L" (Marlin's long-filename listing flag) returns output byte-for-byte identical to plain
 * "M20" for files we wrote ourselves, because the FAT32 directory entry never got a
 * long-filename component in the first place - there was nothing to ask M20 to surface.
 * Persisted to disk so the mapping survives an app restart, not just this process's lifetime.
 */
public class SdFilenameMap {

    private final File file;
    private final Map<String, String> shortToDisplay = new HashMap<>();

    public SdFilenameMap(File file) {
        this.file = file;
        load();
    }

    public synchronized void put(String shortName, String displayName) {
        shortToDisplay.put(shortName, displayName);
        save();
    }

    public synchronized String displayNameFor(String shortName) {
        String display = shortToDisplay.get(shortName);
        return display != null ? display : shortName;
    }

    public synchronized void remove(String shortName) {
        if (shortToDisplay.remove(shortName) != null) save();
    }

    /** Every short-name/display-name pair this app has ever uploaded (and not since deleted) -
     *  unlike a live M20 listing, this needs no printer connection at all, so callers that must
     *  work while the printer is powered off (the scheduled-print file picker) can use it. */
    public synchronized Map<String, String> entries() {
        return new HashMap<>(shortToDisplay);
    }

    private void load() {
        if (!file.exists()) return;
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            JSONObject obj = new JSONObject(bos.toString("UTF-8"));
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                shortToDisplay.put(key, obj.getString(key));
            }
        } catch (Exception e) {
            // Corrupt or unreadable map file - start fresh rather than failing uploads over it.
        }
    }

    private void save() {
        try (OutputStream out = new FileOutputStream(file)) {
            JSONObject obj = new JSONObject(shortToDisplay);
            out.write(obj.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // Best-effort persistence - losing this mapping only degrades display names later,
            // it doesn't affect the upload/select/print flow itself.
        }
    }
}
