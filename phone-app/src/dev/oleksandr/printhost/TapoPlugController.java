package dev.oleksandr.printhost;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Talks to a TP-Link Tapo smart plug (confirmed against a real P100, firmware 1.4.6) over its
 * local KLAP protocol - reverse-engineered by the python-kasa project, ported here from its
 * kasa/transports/klaptransport.py (protocol) and kasa/protocols/smartprotocol.py (request
 * shape). Even though every request stays entirely on the LAN, the handshake still needs the
 * TP-Link account email/password (not a cloud call - this is just how Tapo's local auth works),
 * loaded from assets/tapo.properties (gitignored - see .gitignore).
 *
 * A fresh handshake runs on every command rather than caching a session: this button is pressed
 * rarely (once to power the printer up, once to power it down), so the simplicity of not having
 * to reason about session expiry is worth more here than saving one extra round trip.
 */
public class TapoPlugController {

    private static final String TAG = "TapoPlugController";
    private static final int TIMEOUT_MS = 5000;

    private final String ip;
    private final String email;
    private final String password;

    public TapoPlugController(Context context) {
        String loadedIp = null, loadedEmail = null, loadedPassword = null;
        try (InputStream in = context.getAssets().open("tapo.properties")) {
            Properties props = new Properties();
            props.load(in);
            loadedIp = props.getProperty("ip");
            loadedEmail = props.getProperty("email");
            loadedPassword = props.getProperty("password");
        } catch (IOException e) {
            Log.w(TAG, "assets/tapo.properties not found - Tapo plug control disabled", e);
        }
        this.ip = loadedIp;
        this.email = loadedEmail;
        this.password = loadedPassword;
    }

    public boolean isConfigured() {
        return ip != null && email != null && password != null;
    }

    public void turnOn() throws IOException {
        setDeviceOn(true);
    }

    public void turnOff() throws IOException {
        setDeviceOn(false);
    }

    /** Current on/off state, straight from the device rather than assumed from our last command -
     *  used to populate the dashboard's plug switch on load and to catch changes made outside
     *  this app (e.g. the physical button on the plug itself, or the Tapo app). */
    public boolean isOn() throws IOException {
        if (!isConfigured()) {
            throw new IOException("Tapo plug not configured (assets/tapo.properties missing)");
        }
        Session session = handshake();
        JSONObject response = session.sendCommand("get_device_info", null);
        int errorCode = response.optInt("error_code", -1);
        if (errorCode != 0) {
            throw new IOException("Tapo plug returned error_code " + errorCode + ": " + response);
        }
        JSONObject result = response.optJSONObject("result");
        if (result == null) {
            throw new IOException("Tapo get_device_info: no result in response: " + response);
        }
        return result.optBoolean("device_on");
    }

    private void setDeviceOn(boolean on) throws IOException {
        if (!isConfigured()) {
            throw new IOException("Tapo plug not configured (assets/tapo.properties missing)");
        }
        Session session = handshake();
        JSONObject params = new JSONObject();
        try {
            params.put("device_on", on);
        } catch (Exception ignored) {
        }
        JSONObject response = session.sendCommand("set_device_info", params);
        int errorCode = response.optInt("error_code", -1);
        if (errorCode != 0) {
            throw new IOException("Tapo plug returned error_code " + errorCode + ": " + response);
        }
    }

    // ---- KLAP handshake -----------------------------------------------------------------------

    private Session handshake() throws IOException {
        byte[] localSeed = randomBytes(16);

        HandshakeResponse hs1 = post(ip + "/app/handshake1", localSeed, null);
        if (hs1.status != 200 || hs1.body.length != 48) {
            throw new IOException("Tapo handshake1 failed: status=" + hs1.status
                    + " bodyLen=" + hs1.body.length);
        }
        byte[] remoteSeed = new byte[16];
        byte[] serverHash = new byte[32];
        System.arraycopy(hs1.body, 0, remoteSeed, 0, 16);
        System.arraycopy(hs1.body, 16, serverHash, 0, 32);

        // Try the newer v2 auth-hash scheme first (SHA1-based, matches recent firmware like this
        // P100's 1.4.6), fall back to the older v1 scheme (MD5-based) for older devices.
        byte[] authHash = sha256(sha1(email.getBytes(StandardCharsets.UTF_8)),
                sha1(password.getBytes(StandardCharsets.UTF_8)));
        byte[] expectedV2 = sha256(localSeed, remoteSeed, authHash);
        boolean useV2;
        if (java.util.Arrays.equals(expectedV2, serverHash)) {
            useV2 = true;
        } else {
            authHash = md5(md5(email.getBytes(StandardCharsets.UTF_8)),
                    md5(password.getBytes(StandardCharsets.UTF_8)));
            byte[] expectedV1 = sha256(concat(localSeed, authHash));
            if (!java.util.Arrays.equals(expectedV1, serverHash)) {
                throw new IOException("Tapo handshake1: server hash didn't match v1 or v2 "
                        + "auth scheme - check email/password in assets/tapo.properties");
            }
            useV2 = false;
        }

        byte[] hs2Payload = useV2
                ? sha256(remoteSeed, localSeed, authHash)
                : sha256(concat(remoteSeed, authHash));
        HandshakeResponse hs2 = post(ip + "/app/handshake2", hs2Payload, hs1.sessionCookie);
        if (hs2.status != 200) {
            throw new IOException("Tapo handshake2 failed: status=" + hs2.status);
        }

        return new Session(localSeed, remoteSeed, authHash, hs1.sessionCookie);
    }

    /** One authenticated KLAP session - derives the AES key/iv/signature material once, then
     *  encrypts/decrypts individual requests. Not reused across calls (see class javadoc). */
    private class Session {
        final byte[] key;
        final byte[] iv; // 12 bytes - last 4 bytes of the request IV come from the sequence number
        final int initialSeq;
        final byte[] sig; // 28 bytes
        final String sessionCookie;

        Session(byte[] localSeed, byte[] remoteSeed, byte[] authHash, String sessionCookie) {
            this.sessionCookie = sessionCookie;
            byte[] fullKey = sha256(concat("lsk".getBytes(StandardCharsets.US_ASCII),
                    localSeed, remoteSeed, authHash));
            this.key = java.util.Arrays.copyOf(fullKey, 16);

            byte[] fullIv = sha256(concat("iv".getBytes(StandardCharsets.US_ASCII),
                    localSeed, remoteSeed, authHash));
            this.iv = java.util.Arrays.copyOf(fullIv, 12);
            this.initialSeq = bytesToIntBigEndianSigned(fullIv, 28);

            byte[] fullSig = sha256(concat("ldk".getBytes(StandardCharsets.US_ASCII),
                    localSeed, remoteSeed, authHash));
            this.sig = java.util.Arrays.copyOf(fullSig, 28);
        }

        JSONObject sendCommand(String method, JSONObject params) throws IOException {
            int seq = initialSeq + 1; // encrypt() increments before use, every session sends exactly one request

            String terminalUuid = Base64.getEncoder().encodeToString(md5(uuidBytes(UUID.randomUUID())));
            JSONObject request = new JSONObject();
            try {
                request.put("method", method);
                request.put("request_time_milis", System.currentTimeMillis());
                request.put("terminal_uuid", terminalUuid);
                if (params != null) request.put("params", params);
            } catch (Exception ignored) {
            }
            byte[] plaintext = request.toString().getBytes(StandardCharsets.UTF_8);

            byte[] ivSeq = concat(iv, intToBytesBigEndianSigned(seq));
            byte[] ciphertext = aesCbcEncrypt(plaintext, key, ivSeq);
            byte[] signature = sha256(concat(sig, intToBytesBigEndianSigned(seq), ciphertext));
            byte[] payload = concat(signature, ciphertext);

            HandshakeResponse resp = post(ip + "/app/request?seq=" + seq, payload, sessionCookie);
            if (resp.status != 200) {
                throw new IOException("Tapo request failed: status=" + resp.status);
            }
            // First 32 bytes of the response are its own signature - the reference
            // implementation this is ported from doesn't verify it either, only decrypts
            // what follows.
            byte[] responseCiphertext = java.util.Arrays.copyOfRange(resp.body, 32, resp.body.length);
            byte[] responsePlaintext = aesCbcDecrypt(responseCiphertext, key, ivSeq);
            String json = new String(responsePlaintext, StandardCharsets.UTF_8);
            try {
                return new JSONObject(json);
            } catch (Exception e) {
                throw new IOException("Tapo response wasn't valid JSON: " + json, e);
            }
        }
    }

    // ---- HTTP -----------------------------------------------------------------------------

    private static class HandshakeResponse {
        int status;
        byte[] body;
        String sessionCookie;
    }

    private HandshakeResponse post(String urlPath, byte[] body, String cookie) throws IOException {
        URL url = new URL("http://" + urlPath);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", "application/octet-stream");
            if (cookie != null) {
                conn.setRequestProperty("Cookie", cookie);
            }
            OutputStream out = conn.getOutputStream();
            out.write(body);
            out.flush();

            HandshakeResponse result = new HandshakeResponse();
            result.status = conn.getResponseCode();
            InputStream in = result.status >= 200 && result.status < 300
                    ? conn.getInputStream() : conn.getErrorStream();
            result.body = readAll(in);

            if (cookie == null) {
                // Only handshake1 (called with cookie=null) sets a fresh session - capture it
                // for handshake2 and the subsequent encrypted request to reuse.
                result.sessionCookie = extractSessionCookie(conn.getHeaderFields());
            } else {
                result.sessionCookie = cookie;
            }
            return result;
        } finally {
            conn.disconnect();
        }
    }

    private static String extractSessionCookie(Map<String, List<String>> headers) {
        List<String> setCookies = headers.get("Set-Cookie");
        if (setCookies == null) return null;
        for (String header : setCookies) {
            String[] parts = header.split(";", 2);
            if (parts[0].trim().startsWith("TP_SESSIONID=")) {
                return parts[0].trim();
            }
        }
        return null;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    // ---- crypto -----------------------------------------------------------------------------

    private static byte[] aesCbcEncrypt(byte[] plaintext, byte[] key, byte[] iv) throws IOException {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(plaintext);
        } catch (Exception e) {
            throw new IOException("AES encrypt failed", e);
        }
    }

    private static byte[] aesCbcDecrypt(byte[] ciphertext, byte[] key, byte[] iv) throws IOException {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            throw new IOException("AES decrypt failed", e);
        }
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return b;
    }

    private static byte[] md5(byte[]... parts) {
        return digest("MD5", parts);
    }

    private static byte[] sha1(byte[]... parts) {
        return digest("SHA-1", parts);
    }

    private static byte[] sha256(byte[]... parts) {
        return digest("SHA-256", parts);
    }

    private static byte[] digest(String algorithm, byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            for (byte[] part : parts) {
                md.update(part);
            }
            return md.digest();
        } catch (Exception e) {
            throw new RuntimeException(algorithm + " unavailable", e);
        }
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] part : parts) total += part.length;
        byte[] result = new byte[total];
        int pos = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, result, pos, part.length);
            pos += part.length;
        }
        return result;
    }

    /** Matches Python's struct.pack(">l", seq) - big-endian, 4-byte, signed. */
    private static byte[] intToBytesBigEndianSigned(int value) {
        return new byte[]{
                (byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value
        };
    }

    private static int bytesToIntBigEndianSigned(byte[] arr, int offset) {
        return ((arr[offset] & 0xff) << 24) | ((arr[offset + 1] & 0xff) << 16)
                | ((arr[offset + 2] & 0xff) << 8) | (arr[offset + 3] & 0xff);
    }

    private static byte[] uuidBytes(UUID uuid) {
        byte[] b = new byte[16];
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();
        for (int i = 0; i < 8; i++) {
            b[i] = (byte) (msb >>> (8 * (7 - i)));
            b[8 + i] = (byte) (lsb >>> (8 * (7 - i)));
        }
        return b;
    }
}
