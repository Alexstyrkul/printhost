package dev.oleksandr.printhost;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** One RFC 6455 WebSocket connection. Server->client frames are never masked (per spec, only
 *  client->server frames are); read side must unmask. No continuation-frame/fragmentation
 *  support - every message this server ever sends is a single small state JSON blob, well under
 *  one frame, and nothing meaningful is expected back from the client (this is a push-only
 *  channel - see WsHub's own comment). */
class WsConnection {

    private static final String TAG = "WsConnection";

    private static final int OP_TEXT = 0x1;
    private static final int OP_CLOSE = 0x8;
    private static final int OP_PING = 0x9;
    private static final int OP_PONG = 0xA;

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final Object writeLock = new Object();
    private volatile boolean closed = false;

    // Messages go out on this connection's own writer thread, never on the broadcaster's: a client that stopped taking
    // data (a phone gone to sleep, a dropped Tailscale link) blocks a socket write until TCP gives up, minutes later,
    // and with the write on the broadcaster every dashboard froze meanwhile. Only the newest message waits: each one is
    // a whole state, so older ones are worth nothing. Guarded by `this`.
    private static final long WRITE_STALL_MS = 10_000;
    private String pending = null;
    private long writingSince = 0;  // when the write in progress started, 0 = none

    WsConnection(Socket socket) throws IOException {
        this.socket = socket;
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
        Thread writer = new Thread("PrintHostWsWriter") {
            @Override
            public void run() {
                writeLoop();
            }
        };
        writer.setDaemon(true);
        writer.start();
    }

    private void writeLoop() {
        try {
            while (true) {
                String text;
                synchronized (this) {
                    while (pending == null && !closed) wait();
                    if (closed) return;
                    text = pending;
                    pending = null;
                    writingSince = System.currentTimeMillis();
                }
                sendRaw(OP_TEXT, text.getBytes(StandardCharsets.UTF_8));
                synchronized (this) {
                    writingSince = 0;
                }
            }
        } catch (InterruptedException e) {
            // service shutting down
        } catch (IOException e) {
            Log.d(TAG, "send failed, closing: " + e.getMessage());
        } finally {
            close();
        }
    }

    /** Blocks until the client closes, sends a close frame, or the connection errors out -
     *  responds to ping/close itself; any text/binary frame from the client is read and
     *  discarded (nothing the client could send changes anything server-side today). */
    void runReadLoop() {
        try {
            while (!closed) {
                Frame frame = readFrame();
                if (frame == null) break; // EOF
                switch (frame.opcode) {
                    case OP_CLOSE:
                        sendRaw(OP_CLOSE, new byte[0]);
                        return;
                    case OP_PING:
                        sendRaw(OP_PONG, frame.payload);
                        break;
                    case OP_PONG:
                    default:
                        // text/binary/pong from the client - nothing to do with it
                        break;
                }
            }
        } catch (IOException e) {
            Log.d(TAG, "read loop ended: " + e.getMessage());
        } finally {
            close();
        }
    }

    /** Never blocks: hands the message to this connection's writer thread (replacing one still waiting). A write stuck
     *  for WRITE_STALL_MS means the client is gone: the socket is closed, which also frees the stuck writer; the
     *  dashboard reconnects by itself. Silently drops the send if the connection is already closed. */
    void send(String text) {
        if (closed) return;
        boolean stalled;
        synchronized (this) {
            stalled = writingSince != 0 && System.currentTimeMillis() - writingSince > WRITE_STALL_MS;
            pending = text;
            notifyAll();
        }
        if (stalled) {
            Log.d(TAG, "client stopped taking data for " + WRITE_STALL_MS / 1000 + " s, closing");
            close();
        }
    }

    boolean isClosed() {
        return closed;
    }

    void close() {
        if (closed) return;
        closed = true;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
        synchronized (this) {
            notifyAll();  // the writer thread ends
        }
    }

    private void sendRaw(int opcode, byte[] payload) throws IOException {
        synchronized (writeLock) {
            out.write(0x80 | opcode); // FIN=1, RSV=0
            int len = payload.length;
            if (len < 126) {
                out.write(len);
            } else if (len <= 0xFFFF) {
                out.write(126);
                out.write((len >>> 8) & 0xFF);
                out.write(len & 0xFF);
            } else {
                out.write(127);
                for (int shift = 56; shift >= 0; shift -= 8) {
                    out.write((int) ((long) len >>> shift) & 0xFF);
                }
            }
            out.write(payload);
            out.flush();
        }
    }

    private static class Frame {
        int opcode;
        byte[] payload;
    }

    private Frame readFrame() throws IOException {
        int b0 = in.read();
        if (b0 == -1) return null;
        int b1 = readByteOrThrow();
        int opcode = b0 & 0x0F;
        boolean masked = (b1 & 0x80) != 0;
        long len = b1 & 0x7F;
        if (len == 126) {
            len = (readByteOrThrow() << 8) | readByteOrThrow();
        } else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i++) {
                len = (len << 8) | readByteOrThrow();
            }
        }
        byte[] maskKey = null;
        if (masked) {
            maskKey = new byte[4];
            readFully(maskKey);
        }
        // A client frame this large would only ever be a bug or a hostile peer - this channel
        // never expects meaningful client payloads at all (see the class javadoc).
        if (len > 1_000_000) {
            throw new IOException("WS frame too large: " + len);
        }
        byte[] payload = new byte[(int) len];
        readFully(payload);
        if (masked) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (payload[i] ^ maskKey[i % 4]);
            }
        }
        Frame f = new Frame();
        f.opcode = opcode;
        f.payload = payload;
        return f;
    }

    private int readByteOrThrow() throws IOException {
        int b = in.read();
        if (b == -1) throw new IOException("Unexpected EOF mid-frame");
        return b;
    }

    private void readFully(byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n == -1) throw new IOException("Unexpected EOF mid-frame");
            off += n;
        }
    }
}
