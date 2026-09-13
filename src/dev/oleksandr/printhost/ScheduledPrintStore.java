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
 * Persists a single pending scheduled print (SD filename + target time) to disk, so it survives
 * an app/service restart between now and whenever it's due to fire. Only one job at a time - a
 * new call to save() replaces whatever was there before, matching the dashboard's one
 * date/time-plus-file picker in the Reserved card.
 */
public class ScheduledPrintStore {

    public static class Job {
        public final String filename;
        public final String displayName;
        public final long atMillis;
        public final String status; // "PENDING" or "FAILED"

        public Job(String filename, String displayName, long atMillis, String status) {
            this.filename = filename;
            this.displayName = displayName;
            this.atMillis = atMillis;
            this.status = status;
        }
    }

    private final File file;
    private volatile Job job;

    public ScheduledPrintStore(File file) {
        this.file = file;
        load();
    }

    public synchronized Job get() {
        return job;
    }

    public synchronized void set(String filename, String displayName, long atMillis) {
        job = new Job(filename, displayName, atMillis, "PENDING");
        save();
    }

    public synchronized void markFailed(String reason) {
        if (job == null) return;
        job = new Job(job.filename, job.displayName, job.atMillis, "FAILED:" + reason);
        save();
    }

    public synchronized void clear() {
        job = null;
        save();
    }

    private void load() {
        if (!file.exists()) return;
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            JSONObject obj = new JSONObject(bos.toString("UTF-8"));
            job = new Job(
                    obj.getString("filename"),
                    obj.getString("displayName"),
                    obj.getLong("atMillis"),
                    obj.getString("status"));
        } catch (Exception e) {
            // Corrupt or unreadable - start with no pending job rather than failing service startup.
        }
    }

    private void save() {
        try (OutputStream out = new FileOutputStream(file)) {
            if (job == null) {
                out.write("{}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            JSONObject obj = new JSONObject();
            obj.put("filename", job.filename);
            obj.put("displayName", job.displayName);
            obj.put("atMillis", job.atMillis);
            obj.put("status", job.status);
            out.write(obj.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException | org.json.JSONException e) {
            // Best-effort persistence - losing this only means the schedule won't survive a
            // restart, it doesn't affect anything already running.
        }
    }
}
