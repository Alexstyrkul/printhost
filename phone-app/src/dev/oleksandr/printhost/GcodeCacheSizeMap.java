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
 * Remembers the SD-card size (post comment-stripping/CRLF-normalization - see
 * PrinterConnection.uploadFile's own comment on why that's not the same as the original upload's
 * byte count) each gcode_cache entry was verified against at upload time, keyed by its SD short
 * name. selectSdFile() compares this against what M23 reports for the same name later, rather
 * than the cached file's own on-disk length (the *original*, pre-normalization bytes it was
 * cached with) - comparing those two directly would almost never match even for a perfectly
 * valid cache entry, since they measure different things by design (confirmed the hard way: this
 * mismatch made the size check reject every cache hit, defeating it entirely, not just the stale
 * entries it was meant to catch). Persisted so this survives an app restart, same as the cache
 * files themselves and SdFilenameMap.
 */
public class GcodeCacheSizeMap {

    private final File file;
    private final Map<String, Long> shortToSize = new HashMap<>();

    public GcodeCacheSizeMap(File file) {
        this.file = file;
        load();
    }

    public synchronized void put(String shortName, long sdSize) {
        shortToSize.put(shortName, sdSize);
        save();
    }

    /** -1 if nothing's recorded for this name (never cached, or the entry was removed). */
    public synchronized long sdSizeFor(String shortName) {
        Long size = shortToSize.get(shortName);
        return size != null ? size : -1;
    }

    public synchronized void remove(String shortName) {
        if (shortToSize.remove(shortName) != null) save();
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
                shortToSize.put(key, obj.getLong(key));
            }
        } catch (Exception e) {
            // Corrupt or unreadable map file - start fresh rather than failing uploads over it.
        }
    }

    private void save() {
        try (OutputStream out = new FileOutputStream(file)) {
            JSONObject obj = new JSONObject(shortToSize);
            out.write(obj.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // Best-effort persistence - losing this only degrades the cache-hit check later,
            // it doesn't affect the upload/select/print flow itself.
        }
    }
}
