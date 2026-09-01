package dev.oleksandr.printhost;

import java.io.IOException;
import java.io.InputStream;

/** Wraps an underlying stream (a raw socket stream) and stops after exactly `limit` bytes,
 *  so readers using EOF-terminated loops (BufferedReader.readLine()) work correctly against
 *  a socket that would otherwise just block waiting for more bytes. Never closes the
 *  underlying stream - the socket's lifecycle is owned by the connection handler. */
public class LimitedInputStream extends InputStream {
    private final InputStream in;
    private long remaining;

    public LimitedInputStream(InputStream in, long limit) {
        this.in = in;
        this.remaining = limit;
    }

    @Override
    public int read() throws IOException {
        if (remaining <= 0) return -1;
        int b = in.read();
        if (b >= 0) remaining--;
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (remaining <= 0) return -1;
        int toRead = (int) Math.min(len, remaining);
        int n = in.read(b, off, toRead);
        if (n > 0) remaining -= n;
        return n;
    }

    @Override
    public int available() throws IOException {
        return (int) Math.min(in.available(), remaining);
    }
}
