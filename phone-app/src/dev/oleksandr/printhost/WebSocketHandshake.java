package dev.oleksandr.printhost;

import android.util.Base64;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** RFC 6455 opening handshake - detects a WS upgrade request and writes the 101 response.
 *  Deliberately hand-rolled, not a vendored library - see PrintHostHttpServer's own top comment
 *  on why this project avoids hidden framework dependencies; the handshake itself is just one
 *  header check plus a SHA-1/Base64 computation, both already in the minSdk 26 bootclasspath. */
final class WebSocketHandshake {

    private static final String MAGIC_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private WebSocketHandshake() {
    }

    static boolean isUpgradeRequest(HttpRequest req) {
        String upgrade = req.headers.get("upgrade");
        String connection = req.headers.get("connection");
        String key = req.headers.get("sec-websocket-key");
        return "GET".equals(req.method)
                && upgrade != null && upgrade.toLowerCase(java.util.Locale.US).contains("websocket")
                && connection != null && connection.toLowerCase(java.util.Locale.US).contains("upgrade")
                && key != null && !key.isEmpty();
    }

    static void doHandshake(OutputStream out, HttpRequest req) throws IOException {
        String key = req.headers.get("sec-websocket-key");
        String accept = computeAccept(key);
        String response = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
        out.write(response.getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private static String computeAccept(String key) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest((key + MAGIC_GUID).getBytes(StandardCharsets.US_ASCII));
            return Base64.encodeToString(digest, Base64.NO_WRAP);
        } catch (NoSuchAlgorithmException e) {
            // SHA-1 is guaranteed present on every Android runtime - this can't actually happen.
            throw new AssertionError(e);
        }
    }
}
