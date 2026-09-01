package dev.oleksandr.printhost;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/** Bare ServerSocket HTTP server - no framework, so there is no hidden connection pool that
 *  can leak the way 3D Fox apparently did. Every accepted socket is owned by exactly one
 *  ConnectionHandler thread and is always closed via try-with-resources. */
public class PrintHostHttpServer extends Thread {

    private static final String TAG = "PrintHostHttpServer";

    private final int port;
    private final RequestRouter router;
    private volatile ServerSocket serverSocket;
    private volatile boolean running = true;

    public PrintHostHttpServer(int port, RequestRouter router) {
        super("PrintHostHttpServer");
        this.port = port;
        this.router = router;
    }

    @Override
    public void run() {
        try {
            serverSocket = new ServerSocket(port);
        } catch (IOException e) {
            Log.e(TAG, "Failed to bind port " + port, e);
            return;
        }
        Log.i(TAG, "Listening on port " + port);
        while (running) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (IOException e) {
                if (running) {
                    Log.w(TAG, "accept() failed", e);
                }
                break;
            }
            ConnectionHandler handler = new ConnectionHandler(socket, router);
            handler.start();
        }
        closeQuietly(serverSocket);
    }

    public void shutdown() {
        running = false;
        closeQuietly(serverSocket);
    }

    private static void closeQuietly(ServerSocket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (IOException e) {
            Log.w(TAG, "serverSocket.close() failed", e);
        }
    }

    /** One HTTP request per connection - simplest thing that works for a LAN dashboard, and it
     *  means every response path (including MJPEG streaming) has one clear owner for the socket. */
    static class ConnectionHandler extends Thread {
        private final Socket socket;
        private final RequestRouter router;

        ConnectionHandler(Socket socket, RequestRouter router) {
            super("PrintHostConn");
            this.socket = socket;
            this.router = router;
        }

        @Override
        public void run() {
            try {
                socket.setSoTimeout(0); // streaming routes (MJPEG) may run indefinitely
                try {
                    InputStream rawIn = socket.getInputStream();
                    OutputStream rawOut = socket.getOutputStream();
                    HttpRequest request = parseRequest(rawIn);
                    if (request == null) {
                        writeError(rawOut, 400, "Bad Request");
                        return;
                    }
                    router.handle(request, rawOut);
                } finally {
                    socket.close();
                }
            } catch (IOException e) {
                Log.d(TAG, "connection closed: " + e.getMessage());
            }
        }

        private static HttpRequest parseRequest(InputStream in) throws IOException {
            String requestLine = readHeaderLine(in);
            if (requestLine == null || requestLine.isEmpty()) return null;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return null;

            HttpRequest req = new HttpRequest();
            req.method = parts[0];
            String rawPath = parts[1];
            int qIdx = rawPath.indexOf('?');
            if (qIdx >= 0) {
                req.path = rawPath.substring(0, qIdx);
                parseQuery(rawPath.substring(qIdx + 1), req.query);
            } else {
                req.path = rawPath;
            }

            String headerLine;
            while ((headerLine = readHeaderLine(in)) != null && !headerLine.isEmpty()) {
                int colon = headerLine.indexOf(':');
                if (colon > 0) {
                    String name = headerLine.substring(0, colon).trim().toLowerCase(java.util.Locale.US);
                    String value = headerLine.substring(colon + 1).trim();
                    req.headers.put(name, value);
                }
            }

            String cl = req.headers.get("content-length");
            req.contentLength = cl != null ? Long.parseLong(cl) : 0;
            req.body = new LimitedInputStream(in, req.contentLength);
            return req;
        }

        private static void parseQuery(String qs, java.util.Map<String, String> out) {
            for (String pair : qs.split("&")) {
                if (pair.isEmpty()) continue;
                int eq = pair.indexOf('=');
                try {
                    String key = eq >= 0 ? URLDecoder.decode(pair.substring(0, eq), "UTF-8") : pair;
                    String value = eq >= 0 ? URLDecoder.decode(pair.substring(eq + 1), "UTF-8") : "";
                    out.put(key, value);
                } catch (Exception e) {
                    // malformed query fragment - skip it
                }
            }
        }

        /** Reads one CRLF- or LF-terminated header line as bytes (never over-reads into body). */
        private static String readHeaderLine(InputStream in) throws IOException {
            ByteArrayOutputStream buf = new ByteArrayOutputStream(128);
            int b;
            boolean any = false;
            while ((b = in.read()) != -1) {
                any = true;
                if (b == '\n') break;
                if (b != '\r') buf.write(b);
            }
            if (!any && buf.size() == 0) return null;
            return buf.toString("US-ASCII");
        }

        private static void writeError(OutputStream out, int code, String message) throws IOException {
            String body = message;
            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
            String head = "HTTP/1.1 " + code + " " + message + "\r\n"
                    + "Content-Type: text/plain; charset=utf-8\r\n"
                    + "Content-Length: " + bodyBytes.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            out.write(bodyBytes);
            out.flush();
        }
    }
}
