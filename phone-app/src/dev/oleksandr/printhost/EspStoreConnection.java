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
     * Sends the file to the ESP32, checking its size and CRC32 against the board's answer. When the board can unpack
     * (its /status says "uploadZ":1), the file goes zlib-packed - lossless, gcode text shrinks about 4-5x - and the
     * board keeps it only if the zlib checksum holds and the unpacked size and CRC32 equal the original's; this side
     * checks the board's answer against the original once more. Otherwise it goes as-is, as before.
     * The stream must know its length up front, which holds for the FileInputStream PrinterService always passes.
     */
    @Override
    public synchronized UploadResult uploadFile(String filename, InputStream gcodeContent,
                                                 UploadProgressListener listener) throws IOException {
        final long total = gcodeContent.available();
        if (total <= 0) throw new IOException("Empty file");
        CRC32 crc = new CRC32();
        String path = "/files?name=" + URLEncoder.encode(filename, "UTF-8");
        String response;
        if (boardUnpacks()) {
            java.io.File packed = java.io.File.createTempFile("upload", ".z");
            try {
                // Pack first (a second or two): the original's CRC32 is taken here, before packing.
                java.util.zip.Deflater deflater = new java.util.zip.Deflater(6);
                try (java.util.zip.DeflaterOutputStream z = new java.util.zip.DeflaterOutputStream(
                        new java.io.BufferedOutputStream(new java.io.FileOutputStream(packed), 65536), deflater, 65536)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = gcodeContent.read(buf)) > 0) {
                        if (listener != null && listener.isCancelled()) throw new UploadCancelledException(filename, 0);
                        crc.update(buf, 0, n);
                        z.write(buf, 0, n);
                    }
                } finally {
                    deflater.end();
                }
                final long packedLen = packed.length();
                String myCrc = String.format(Locale.US, "%08x", crc.getValue());
                try (InputStream in = new java.io.FileInputStream(packed)) {
                    // The progress is shown in original-file bytes, scaled from the packed bytes actually sent.
                    response = sendWatched(path + "&z=1&size=" + total + "&crc=" + myCrc, in, packedLen, filename, listener,
                            total, null);
                }
            } finally {
                packed.delete();
            }
        } else {
            response = sendWatched(path, gcodeContent, total, filename, listener, total, crc);
        }
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

    /** Whether the board's firmware unpacks packed uploads. Anything unclear means no: an older board would store
     *  the packed bytes as the file. */
    private boolean boardUnpacks() {
        try {
            return new JSONObject(httpGet("/status")).optInt("uploadZ", 0) == 1;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * POSTs `body` (`length` bytes) with a stall watchdog, reporting progress in `shownTotal` units (the original
     * file's size, also when packed bytes are sent). `crc` (may be null) is fed with every byte sent.
     */
    private String sendWatched(String path, InputStream body, final long length, final String filename,
                               final UploadProgressListener listener, final long shownTotal, final CRC32 crc) throws IOException {
        final long[] sent = {0};
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
                int n = body.read(b, off, len);
                if (n > 0) {
                    if (crc != null) crc.update(b, off, n);
                    sent[0] += n;
                    if (listener != null) listener.onProgress(sent[0] * shownTotal / length, shownTotal);
                }
                return n;
            }
        };
        // Watchdog: if the board takes no data for UPLOAD_STALL_MS, give up instead of waiting forever.
        final boolean[] stalled = {false};
        Thread watchdog = new Thread(new Runnable() {
            @Override
            public void run() {
                long last = -1, since = System.currentTimeMillis();
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (sent[0] != last) {
                        last = sent[0];
                        since = System.currentTimeMillis();
                    } else if (sent[0] < length && System.currentTimeMillis() - since > UPLOAD_STALL_MS) {
                        stalled[0] = true;
                        abortUpload();
                        return;
                    }
                }
            }
        }, "PrintHostUploadWatchdog");
        watchdog.start();
        try {
            return rawPost(path, counted, length);
        } catch (IOException e) {
            if (e instanceof UploadCancelledException) throw e;
            if (listener != null && listener.isCancelled()) throw new UploadCancelledException(filename, sent[0]);
            if (stalled[0]) throw new IOException("The board stopped taking the file for " + UPLOAD_STALL_MS / 1000 + " s at "
                    + sent[0] + " of " + length + " bytes");
            throw e;
        } finally {
            watchdog.interrupt();
        }
    }

    /** A file upload the board stops taking for this long is abandoned (and Cancel closes it at once). The board
     *  sometimes pauses for tens of seconds and then carries on, so this only catches a board that is really gone. */
    private static final long UPLOAD_STALL_MS = 90000;  // longer than the board's own 60 s receive timeout
    /** Small on purpose: the progress counts what went into this buffer, so it has to stay close to what the board
     *  actually took. The default (several MB on Android) made the bar fly to ~60% and then sit still. */
    private static final int UPLOAD_SEND_BUFFER = 64 * 1024;

    private volatile java.net.Socket uploadSocket;

    /** Ends a running upload now (Cancel, or the stall watchdog): the blocked write fails right away. */
    public void abortUpload() {
        java.net.Socket s = uploadSocket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** POST of a streamed body over a plain socket (HttpURLConnection gives no say over the send buffer and cannot be
     *  interrupted mid-write). Returns the response body; HTTP errors become IOExceptions like readAll(). */
    private String rawPost(String path, InputStream body, long length) throws IOException {
        URL u = new URL(espBase + path);
        java.net.Socket s = new java.net.Socket();
        uploadSocket = s;
        try {
            s.setSendBufferSize(UPLOAD_SEND_BUFFER);
            s.setTcpNoDelay(true);
            try {
                s.connect(new java.net.InetSocketAddress(u.getHost(), u.getPort() > 0 ? u.getPort() : 80), HTTP_CONNECT_MS);
            } catch (IOException e) {
                throw new IOException("Camera board (file store) not reachable at " + espBase);
            }
            s.setSoTimeout(HTTP_READ_MS);
            OutputStream out = new java.io.BufferedOutputStream(s.getOutputStream(), 16384);
            out.write(("POST " + u.getFile() + " HTTP/1.1\r\nHost: " + u.getHost() + "\r\nContent-Type: application/octet-stream\r\n"
                    + "Content-Length: " + length + "\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            IOException writeError = null;
            try {
                byte[] buf = new byte[16384];
                int n;
                while ((n = body.read(buf)) > 0) out.write(buf, 0, n);
                out.flush();
            } catch (UploadCancelledException e) {
                throw e;
            } catch (IOException e) {
                writeError = e;  // the board may have answered early (card busy, no space): read that answer if it is there
            }
            InputStream in = new java.io.BufferedInputStream(s.getInputStream());
            String status;
            try {
                status = readHttpLine(in);
            } catch (IOException e) {
                throw writeError != null ? writeError : e;
            }
            if (status == null || !status.startsWith("HTTP/")) {
                if (writeError != null) throw writeError;
                throw new IOException("Unreadable answer from the camera board: " + status);
            }
            int code = Integer.parseInt(status.split(" ")[1]);
            long contentLength = -1;
            String h;
            while ((h = readHttpLine(in)) != null && !h.isEmpty()) {
                if (h.regionMatches(true, 0, "Content-Length:", 0, 15)) contentLength = Long.parseLong(h.substring(15).trim());
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((contentLength < 0 || bos.size() < contentLength) && (n = in.read(buf)) > 0) bos.write(buf, 0, n);
            String text = bos.toString("UTF-8");
            if (code >= 400) {
                String msg = text;
                try {
                    msg = new JSONObject(text).optString("error", text);
                } catch (org.json.JSONException ignored) {
                }
                throw new IOException("Camera board: " + msg + " (HTTP " + code + ")");
            }
            if (writeError != null) throw writeError;
            return text;
        } finally {
            uploadSocket = null;
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String readHttpLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
        }
        return c < 0 && sb.length() == 0 ? null : sb.toString();
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
