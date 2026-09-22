package dev.oleksandr.printhost;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;
import java.util.zip.CRC32;

/**
 * The gcode file store on the ESP32 camera board (POST /files, GET /files, POST /files/delete), used as
 * the printer's "SD card" by both the mock printer and the real ESP32 bridge. Never touches USB.
 */
public abstract class EspStoreConnection extends PrinterConnection {

    protected static final int HTTP_CONNECT_MS = 5000;
    protected static final int HTTP_READ_MS = 60000;

    protected final String espBase;
    protected String selectedName = null;

    protected EspStoreConnection(String espBase) {
        this.espBase = espBase;
    }

    // ---- files (stored on the ESP32) ----------------------------------------------------------

    @Override
    public synchronized String listSdFiles() throws IOException {
        StringBuilder sb = new StringBuilder("Begin file list\n");
        try {
            JSONArray files = new JSONObject(httpGet("/files")).getJSONArray("files");
            for (int i = 0; i < files.length(); i++) {
                JSONObject f = files.getJSONObject(i);
                sb.append(f.getString("name")).append(' ').append(f.getLong("size")).append('\n');
            }
        } catch (org.json.JSONException e) {
            throw new IOException("Bad file list from the camera board: " + e.getMessage());
        }
        return sb.append("End file list\nok").toString();
    }

    @Override
    public synchronized long selectFile(String filename) throws IOException {
        long size = fileSize(filename);
        if (size < 0) {
            throw new IOException("Printer rejected filename \"" + filename + "\": open failed");
        }
        selectedName = filename;
        return size;
    }

    @Override
    public synchronized boolean verifyUpload(String printerFilename, long expectedBytes) throws IOException {
        return selectFile(printerFilename) == expectedBytes;
    }

    @Override
    public synchronized String deleteFile(String filename) throws IOException {
        String body = httpPost("/files/delete?name=" + URLEncoder.encode(filename, "UTF-8"), null, -1, null);
        if (filename.equals(selectedName)) selectedName = null;
        return "File deleted: " + filename + "\n" + body + "\nok";
    }

    /**
     * Streams the file to the ESP32 as-is (no comment stripping - there is no serial link to
     * protect), counting bytes and CRC32 on the way and checking both against the board's answer.
     * The stream must know its length up front (the board reads Content-Length), which holds for
     * the FileInputStream PrinterService always passes.
     */
    @Override
    public synchronized UploadResult uploadFile(String filename, InputStream gcodeContent,
                                                 UploadProgressListener listener) throws IOException {
        final long total = gcodeContent.available();
        if (total <= 0) throw new IOException("Empty file");
        CRC32 crc = new CRC32();
        long[] sent = {0};
        InputStream counted = new InputStream() {
            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (listener != null && listener.isCancelled()) {
                    throw new UploadCancelledException(filename, sent[0]);
                }
                int n = gcodeContent.read(b, off, len);
                if (n > 0) {
                    crc.update(b, off, n);
                    sent[0] += n;
                    if (listener != null) listener.onProgress(sent[0], total);
                }
                return n;
            }
        };
        String response = httpPost("/files?name=" + URLEncoder.encode(filename, "UTF-8"), counted, total, listener);
        try {
            JSONObject json = new JSONObject(response);
            if (!json.optBoolean("ok")) {
                throw new IOException("Camera board rejected the file: " + json.optString("error", response));
            }
            long boardBytes = json.getLong("bytes");
            String boardCrc = json.getString("crc32");
            String myCrc = String.format(Locale.US, "%08x", crc.getValue());
            if (boardBytes != total || !boardCrc.equalsIgnoreCase(myCrc)) {
                throw new IOException("Upload check failed: sent " + total + " bytes crc " + myCrc
                        + ", board stored " + boardBytes + " bytes crc " + boardCrc);
            }
        } catch (org.json.JSONException e) {
            throw new IOException("Unreadable answer from the camera board: " + response);
        }
        return new UploadResult(filename, total);
    }

    // ---- HTTP to the ESP32 --------------------------------------------------------------------

    protected long fileSize(String name) throws IOException {
        try {
            JSONArray files = new JSONObject(httpGet("/files")).getJSONArray("files");
            for (int i = 0; i < files.length(); i++) {
                JSONObject f = files.getJSONObject(i);
                if (f.getString("name").equalsIgnoreCase(name)) return f.getLong("size");
            }
        } catch (org.json.JSONException e) {
            throw new IOException("Bad file list from the camera board: " + e.getMessage());
        }
        return -1;
    }

    protected String httpGet(String path) throws IOException {
        HttpURLConnection c = open(path, "GET");
        return readAll(c);
    }

    /** POST with an optional streamed body of known length. */
    protected String httpPost(String path, InputStream body, long length, UploadProgressListener listener)
            throws IOException {
        HttpURLConnection c = open(path, "POST");
        if (body != null) {
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(length);
            c.setRequestProperty("Content-Type", "application/octet-stream");
            try (OutputStream out = c.getOutputStream()) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = body.read(buf)) > 0) out.write(buf, 0, n);
            }
        } else {
            c.setFixedLengthStreamingMode(0);
            c.setDoOutput(true);
            c.getOutputStream().close();
        }
        return readAll(c);
    }

    protected HttpURLConnection open(String path, String method) throws IOException {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(espBase + path).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(HTTP_CONNECT_MS);
            c.setReadTimeout(HTTP_READ_MS);
            return c;
        } catch (java.net.ConnectException | java.net.SocketTimeoutException | java.net.NoRouteToHostException e) {
            throw new IOException("Camera board (file store) not reachable at " + espBase);
        }
    }

    protected String readAll(HttpURLConnection c) throws IOException {
        try {
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            if (in != null) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                in.close();
            }
            String text = bos.toString("UTF-8");
            if (code >= 400) {
                String msg = text;
                try {
                    msg = new JSONObject(text).optString("error", text);
                } catch (org.json.JSONException ignored) {
                }
                throw new IOException("Camera board: " + msg + " (HTTP " + code + ")");
            }
            return text;
        } catch (java.net.ConnectException | java.net.SocketTimeoutException | java.net.NoRouteToHostException e) {
            throw new IOException("Camera board (file store) not reachable at " + espBase);
        } finally {
            c.disconnect();
        }
    }
}
