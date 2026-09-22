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

/**
 * Persists whether "power off the plug once the printer cools down after a print" is turned on,
 * so the toggle survives an app/service restart the same way the scheduled-print job does.
 */
public class AutoShutoffStore {

    private final File file;
    private volatile boolean enabled = false;

    public AutoShutoffStore(File file) {
        this.file = file;
        load();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public synchronized void setEnabled(boolean enabled) {
        this.enabled = enabled;
        save();
    }

    private void load() {
        if (!file.exists()) return;
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[256];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            JSONObject obj = new JSONObject(bos.toString("UTF-8"));
            enabled = obj.optBoolean("enabled", false);
        } catch (Exception e) {
            // Corrupt or unreadable - default to off rather than failing service startup.
        }
    }

    private void save() {
        try (OutputStream out = new FileOutputStream(file)) {
            JSONObject obj = new JSONObject();
            obj.put("enabled", enabled);
            out.write(obj.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            // Best-effort persistence - losing this only means the toggle resets to off on the
            // next restart, it doesn't affect anything currently running.
        }
    }
}
