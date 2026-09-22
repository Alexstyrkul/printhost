package dev.oleksandr.printhost;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Re-serves the ESP32 board's MJPEG stream to any number of viewers.
 *
 * The board's HTTP server can feed one stream at a time, and its Wi-Fi carries roughly one stream's worth
 * of data. So this phone (the server) holds ONE connection to the board and hands every viewer the newest
 * frame. Frames are copied as they are - nothing is decoded or re-encoded, so picture quality is exactly
 * what the board produced. A slow viewer only skips frames for itself; it never slows the others or the board.
 *
 * The upstream connection opens with the first viewer and closes a few seconds after the last one leaves.
 */
public class EspCameraRelay {

    private static final String TAG = "EspCameraRelay";
    static final String BOUNDARY = "printhostboundary";
    private static final long LINGER_MS = 8000;

    /** One frame plus its sequence number. */
    public static final class Frame {
        public final byte[] jpeg;
        public final long seq;

        Frame(byte[] jpeg, long seq) {
            this.jpeg = jpeg;
            this.seq = seq;
        }
    }

    public interface HostSource {
        String host();
    }

    private final HostSource hostSource;
    private final Object lock = new Object();
    private Frame latest;
    private long seq = 0;
    private int clients = 0;
    private long lastClientLeftAt = 0;
    private Thread upstream;
    private volatile boolean upstreamConnected = false;

    // Statistics, one window ~5 s (guarded by lock).
    private long winStart = 0, winFrames = 0, winBytes = 0;
    private double fps = 0, kbps = 0;
    private long lastFrameAt = 0;
    private long totalFrames = 0;
    private int reconnects = 0;

    public EspCameraRelay(HostSource hostSource) {
        this.hostSource = hostSource;
    }

    // ---- viewers ------------------------------------------------------------------------------

    public void register() {
        synchronized (lock) {
            clients++;
            if (upstream == null || !upstream.isAlive()) {
                upstream = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        upstreamLoop();
                    }
                }, "PrintHostCamRelay");
                upstream.start();
            }
        }
    }

    public void unregister() {
        synchronized (lock) {
            if (clients > 0) clients--;
            lastClientLeftAt = System.currentTimeMillis();
        }
    }

    /** Blocks until a frame newer than lastSeq exists (or timeoutMs passes -> null). */
    public Frame awaitFrame(long lastSeq, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (lock) {
            while (latest == null || latest.seq <= lastSeq) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) return null;
                lock.wait(left);
            }
            return latest;
        }
    }

    // ---- statistics ---------------------------------------------------------------------------

    public String statsJson() {
        synchronized (lock) {
            long age = lastFrameAt == 0 ? -1 : System.currentTimeMillis() - lastFrameAt;
            return "{\"clients\":" + clients + ",\"upstream\":" + upstreamConnected + ",\"fps\":" + String.format(java.util.Locale.US, "%.1f", fps)
                    + ",\"kbps\":" + String.format(java.util.Locale.US, "%.0f", kbps) + ",\"frameAgeMs\":" + age
                    + ",\"frames\":" + totalFrames + ",\"reconnects\":" + reconnects + "}";
        }
    }

    // ---- upstream: the single connection to the board -----------------------------------------

    private void upstreamLoop() {
        Log.i(TAG, "upstream thread started");
        for (;;) {
            synchronized (lock) {
                if (clients == 0 && System.currentTimeMillis() - lastClientLeftAt > LINGER_MS) {
                    upstream = null;
                    upstreamConnected = false;
                    Log.i(TAG, "no viewers - upstream closed");
                    return;
                }
            }
            try {
                readStreamOnce();
            } catch (IOException e) {
                Log.d(TAG, "upstream ended: " + e.getMessage());
            }
            upstreamConnected = false;
            synchronized (lock) {
                reconnects++;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void readStreamOnce() throws IOException {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(hostSource.host(), 81), 3000);
            s.setSoTimeout(6000);
            s.setTcpNoDelay(true);
            OutputStream out = s.getOutputStream();
            out.write(("GET /stream HTTP/1.0\r\nHost: " + hostSource.host() + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream raw = new BufferedInputStream(s.getInputStream(), 65536);
            // The board answers with HTTP chunked encoding (esp_http_server send_chunk); undo it, or the chunk sizes end up inside the JPEGs.
            String statusLine = readLine(raw);
            if (statusLine == null || !statusLine.contains(" 200")) return;  // e.g. 500 "camera is off"
            boolean chunked = false;
            String hl;
            while ((hl = readLine(raw)) != null && !hl.isEmpty()) {
                if (hl.toLowerCase(java.util.Locale.US).startsWith("transfer-encoding:") && hl.toLowerCase(java.util.Locale.US).contains("chunked")) chunked = true;
            }
            InputStream in = chunked ? new ChunkedInputStream(raw) : raw;
            // The board answers "camera is off" (a short plain body, no multipart) when the camera is powered down.
            long contentLength = -1;
            boolean sawHeaderEnd = false;
            long idleSince = System.currentTimeMillis();
            for (;;) {
                synchronized (lock) {
                    if (clients == 0 && System.currentTimeMillis() - lastClientLeftAt > LINGER_MS) return;
                }
                String line = readLine(in);
                if (line == null) return;  // board closed the stream
                if (line.length() >= 15 && line.regionMatches(true, 0, "Content-Length:", 0, 15)) {
                    try {
                        contentLength = Long.parseLong(line.substring(15).trim());
                    } catch (NumberFormatException e) {
                        contentLength = -1;
                    }
                } else if (line.isEmpty()) {
                    if (contentLength > 0 && contentLength < 2_000_000) {
                        byte[] jpeg = new byte[(int) contentLength];
                        readFully(in, jpeg);
                        if (jpeg.length > 3 && (jpeg[0] & 0xff) == 0xFF && (jpeg[1] & 0xff) == 0xD8) {  // never pass on a damaged frame
                            publish(jpeg);
                            upstreamConnected = true;
                        }
                        idleSince = System.currentTimeMillis();
                    }
                    contentLength = -1;
                    sawHeaderEnd = true;
                }
                if (!upstreamConnected && System.currentTimeMillis() - idleSince > 10000) return;
            }
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void publish(byte[] jpeg) {
        long now = System.currentTimeMillis();
        synchronized (lock) {
            seq++;
            latest = new Frame(jpeg, seq);
            lastFrameAt = now;
            totalFrames++;
            if (winStart == 0) winStart = now;
            winFrames++;
            winBytes += jpeg.length;
            long dt = now - winStart;
            if (dt >= 5000) {
                fps = winFrames * 1000.0 / dt;
                kbps = winBytes * 8.0 / dt;  // bits per ms == kbit/s
                winStart = now;
                winFrames = 0;
                winBytes = 0;
            }
            lock.notifyAll();
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(64);
        for (;;) {
            int c = in.read();
            if (c < 0) return sb.length() == 0 ? null : sb.toString();
            if (c == '\n') {
                int n = sb.length();
                if (n > 0 && sb.charAt(n - 1) == '\r') sb.setLength(n - 1);
                return sb.toString();
            }
            if (sb.length() < 512) sb.append((char) c);
        }
    }

    /** Decodes HTTP/1.1 chunked transfer encoding. */
    private static final class ChunkedInputStream extends InputStream {
        private final InputStream in;
        private long remaining = 0;
        private boolean eof = false, first = true;
        private final byte[] one = new byte[1];

        ChunkedInputStream(InputStream in) {
            this.in = in;
        }

        @Override
        public int read() throws IOException {
            int n = read(one, 0, 1);
            return n < 0 ? -1 : (one[0] & 0xff);
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            if (eof) return -1;
            if (remaining == 0) {
                if (!first) readLine(in);  // the CRLF that ends the previous chunk
                first = false;
                String l = readLine(in);
                if (l == null) {
                    eof = true;
                    return -1;
                }
                int semi = l.indexOf(';');
                if (semi >= 0) l = l.substring(0, semi);
                try {
                    remaining = Long.parseLong(l.trim(), 16);
                } catch (NumberFormatException e) {
                    throw new IOException("bad chunk size");
                }
                if (remaining == 0) {
                    eof = true;
                    return -1;
                }
            }
            int n = in.read(buf, off, (int) Math.min(len, remaining));
            if (n < 0) {
                eof = true;
                return -1;
            }
            remaining -= n;
            return n;
        }
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) throw new IOException("stream ended inside a frame");
            off += n;
        }
    }
}
