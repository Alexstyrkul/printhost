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
        /** Non-null while the file is still only on this phone (scheduled with the board powered off):
         *  it goes to the board shortly before atMillis, then this becomes null. */
        public final String localPath;
        public final long sizeBytes;

        public Job(String filename, String displayName, long atMillis, String status) {
            this(filename, displayName, atMillis, status, null, 0);
        }

        public Job(String filename, String displayName, long atMillis, String status, String localPath, long sizeBytes) {
            this.filename = filename;
            this.displayName = displayName;
            this.atMillis = atMillis;
            this.status = status;
            this.localPath = localPath;
            this.sizeBytes = sizeBytes;
        }

        public boolean needsUpload() {
            return localPath != null;
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

    /** A job whose file is still on this phone (localPath) and goes to the board before the start. */
    public synchronized void setWithLocalFile(String filename, String displayName, long atMillis, String localPath, long sizeBytes) {
        job = new Job(filename, displayName, atMillis, "PENDING", localPath, sizeBytes);
        save();
    }

    /** Moves the job to a new time and makes a failed one pending again; the file (on the board or still on
     *  the phone) stays the same. False if there is no job. */
    public synchronized boolean reschedule(long atMillis) {
        if (job == null) return false;
        job = new Job(job.filename, job.displayName, atMillis, "PENDING", job.localPath, job.sizeBytes);
        save();
        return true;
    }

    /** The phone's copy reached the board under `boardFilename`: from now on it is a normal scheduled job. */
    public synchronized void markUploaded(String boardFilename) {
        if (job == null) return;
        job = new Job(boardFilename, job.displayName, job.atMillis, job.status);
        save();
    }

    public synchronized void markFailed(String reason) {
        if (job == null) return;
        job = new Job(job.filename, job.displayName, job.atMillis, "FAILED:" + reason, job.localPath, job.sizeBytes);
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
                    obj.getString("status"),
                    obj.has("localPath") ? obj.getString("localPath") : null,
                    obj.optLong("sizeBytes", 0));
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
            if (job.localPath != null) {
                obj.put("localPath", job.localPath);
                obj.put("sizeBytes", job.sizeBytes);
            }
            out.write(obj.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException | org.json.JSONException e) {
            // Best-effort persistence - losing this only means the schedule won't survive a
            // restart, it doesn't affect anything already running.
        }
    }
}
