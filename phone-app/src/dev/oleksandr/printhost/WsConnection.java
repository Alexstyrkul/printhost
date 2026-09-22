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

    WsConnection(Socket socket) throws IOException {
        this.socket = socket;
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
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

    /** Safe to call from any thread (the broadcaster) concurrently with this connection's own
     *  read loop - writeLock keeps two frames from interleaving on the wire. Silently drops the
     *  send if the connection is already closed instead of throwing, matching the "best effort
     *  broadcast" nature of WsHub.broadcast(). */
    void send(String text) {
        if (closed) return;
        try {
            sendRaw(OP_TEXT, text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.d(TAG, "send failed, closing: " + e.getMessage());
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
