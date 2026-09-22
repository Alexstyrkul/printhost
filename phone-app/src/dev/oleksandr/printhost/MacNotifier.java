package dev.oleksandr.printhost;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.text.format.Formatter;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Finds and talks to the little listener script running on the Mac (mac-notify-listener.py) -
 * no manual IP/port entry anywhere. Scans the phone's own /24 Wi-Fi subnet in parallel for a
 * host that answers GET /ping with the expected signature, caches the address, and re-verifies
 * the cached address with one quick ping before trusting it again (handles the Mac picking up a
 * new DHCP lease between prints without a full rescan every time).
 */
public class MacNotifier {

    private static final String TAG = "MacNotifier";
    static final int PORT = 8765;
    // Must match PING_SIGNATURE in mac-notify-listener.py exactly.
    static final String PING_SIGNATURE = "printhost-notify-v1";
    private static final int PING_TIMEOUT_MS = 350;
    private static final int SCAN_OVERALL_TIMEOUT_MS = 4000;
    private static final int SCAN_THREADS = 32;

    private final Context appContext;
    private volatile String cachedIp;

    public MacNotifier(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public void notifyAsync(String event) {
        new NotifyThread(event).start();
    }

    class NotifyThread extends Thread {
        private final String event;
        NotifyThread(String event) {
            super("MacNotify-" + event);
            this.event = event;
        }
        @Override
        public void run() {
            String ip = resolveMacIp();
            if (ip == null) {
                Log.w(TAG, "notify " + event + " skipped: no Mac listener found on the network");
                return;
            }
            HttpURLConnection conn = null;
            try {
                URL url = new URL("http://" + ip + ":" + PORT + "/" + event);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(3000);
                conn.setDoOutput(true);
                OutputStream out = conn.getOutputStream();
                try {
                    out.write(0);
                } finally {
                    out.close();
                }
                int code = conn.getResponseCode();
                Log.i(TAG, "notify " + event + " -> " + ip + " HTTP " + code);
            } catch (IOException e) {
                Log.w(TAG, "notify " + event + " failed against " + ip + ", will rescan next time", e);
                cachedIp = null;
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
    }

    /** Fast path: re-verify the cached address first; a full subnet scan only happens on the
     *  first notification ever, or after the cached Mac stops answering. */
    private String resolveMacIp() {
        String cached = cachedIp;
        if (cached != null && ping(cached)) {
            return cached;
        }
        String found = scanSubnet();
        cachedIp = found;
        return found;
    }

    private boolean ping(String ip) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://" + ip + ":" + PORT + "/ping");
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(PING_TIMEOUT_MS);
            conn.setReadTimeout(PING_TIMEOUT_MS);
            if (conn.getResponseCode() != 200) return false;
            InputStream in = conn.getInputStream();
            byte[] buf = new byte[64];
            int n = in.read(buf);
            in.close();
            String body = n > 0 ? new String(buf, 0, n, StandardCharsets.UTF_8) : "";
            return PING_SIGNATURE.equals(body.trim());
        } catch (IOException e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String scanSubnet() {
        String subnetPrefix = getSubnetPrefix();
        if (subnetPrefix == null) {
            Log.w(TAG, "Can't determine the phone's own subnet - not on Wi-Fi?");
            return null;
        }
        ExecutorService pool = Executors.newFixedThreadPool(SCAN_THREADS);
        ExecutorCompletionService<String> completion = new ExecutorCompletionService<String>(pool);
        List<Future<String>> submitted = new ArrayList<Future<String>>(254);
        for (int i = 1; i <= 254; i++) {
            submitted.add(completion.submit(new PingProbeTask(subnetPrefix + i)));
        }
        String result = null;
        try {
            long deadline = System.currentTimeMillis() + SCAN_OVERALL_TIMEOUT_MS;
            for (int i = 0; i < submitted.size(); i++) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;
                Future<String> f = completion.poll(remaining, TimeUnit.MILLISECONDS);
                if (f == null) break;
                try {
                    String ip = f.get();
                    if (ip != null) {
                        result = ip;
                        break;
                    }
                } catch (Exception ignored) {
                    // one probe failing (timeout, refused, etc.) just means "not this IP"
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdownNow();
        }
        if (result != null) {
            Log.i(TAG, "Found Mac listener at " + result);
        } else {
            Log.w(TAG, "No Mac listener found scanning " + subnetPrefix + "0/24");
        }
        return result;
    }

    private String getSubnetPrefix() {
        WifiManager wifi = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
        if (wifi == null) return null;
        int ipInt = wifi.getConnectionInfo().getIpAddress();
        if (ipInt == 0) return null;
        String ip = Formatter.formatIpAddress(ipInt);
        int lastDot = ip.lastIndexOf('.');
        if (lastDot < 0) return null;
        return ip.substring(0, lastDot + 1);
    }

    class PingProbeTask implements Callable<String> {
        private final String ip;
        PingProbeTask(String ip) {
            this.ip = ip;
        }
        public String call() {
            return ping(ip) ? ip : null;
        }
    }
}
