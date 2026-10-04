package dev.oleksandr.printhost;

import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Maps HTTP routes to PrinterService's whitelisted operations. Every method here writes a
 *  complete response and returns - the ConnectionHandler that called us owns closing the socket. */
public class DashboardRouter implements RequestRouter {

    private static final String TAG = "DashboardRouter";
    private static final String MJPEG_BOUNDARY = "printhostboundary";

    private final PrinterService service;
    private byte[] cachedDashboardHtml;

    /** One connection to the board's camera, shared by every viewer (see EspCameraRelay). */
    private final EspCameraRelay relay;

    public DashboardRouter(PrinterService service) {
        this.service = service;
        this.relay = new EspCameraRelay(new EspCameraRelay.HostSource() {
            @Override
            public String host() {
                return DashboardRouter.this.service.getEspHost();
            }
        });
    }

    /** The board pages the dashboard may read through this phone (so it also works away from the home Wi-Fi). */
    private static String espProxyTarget(String path) {
        if (path.equals("/esp/status")) return "/status";
        if (path.equals("/esp/log")) return "/log";
        if (path.equals("/esp/log/sd")) return "/log/sd";
        if (path.equals("/esp/printer/status")) return "/printer/status";
        if (path.equals("/esp/logs")) return "/logs";
        return null;
    }

    @Override
    public void handle(HttpRequest req, OutputStream out) throws IOException {
        try {
            route(req, out);
        } catch (Exception e) {
            Log.e(TAG, "route " + req.method + " " + req.path + " threw", e);
            writeJson(out, 500, errorJson(String.valueOf(e.getMessage())));
        }
    }

    private void route(HttpRequest req, OutputStream out) throws IOException, java.util.concurrent.TimeoutException {
        String p = req.path;
        if (p.equals("/") && req.method.equals("GET")) {
            writeDashboard(out);
        } else if (p.startsWith("/vendor/") && req.method.equals("GET")) {
            writeVendorAsset(out, p);
        } else if (p.equals("/status") && req.method.equals("GET")) {
            writeJson(out, 200, service.getStateJson());
        } else if (p.equals("/connect") && req.method.equals("POST")) {
            boolean ok = service.connectPrinter();
            writeJson(out, ok ? 200 : 502, resultJson(ok, service.getStateJson()));
        } else if (p.equals("/disconnect") && req.method.equals("POST")) {
            service.disconnectPrinter();
            writeJson(out, 200, resultJson(true, service.getStateJson()));
        } else if (p.equals("/screen/wake") && req.method.equals("POST")) {
            service.wakeScreen();
            writeJson(out, 200, resultJson(true, service.getStateJson()));
        } else if (p.equals("/screen/lock") && req.method.equals("POST")) {
            service.lockScreen();
            writeJson(out, 200, resultJson(true, service.getStateJson()));
        } else if (p.equals("/plug/on") && req.method.equals("POST")) {
            PrinterService.UploadOutcome outcome = service.tapoPlugOn();
            JSONObject json = resultJson(outcome.success, service.getStateJson());
            try {
                json.put("message", outcome.message);
            } catch (Exception ignored) {
            }
            writeJson(out, outcome.success ? 200 : 502, json);
        } else if (p.equals("/plug/off") && req.method.equals("POST")) {
            PrinterService.UploadOutcome outcome = service.tapoPlugOff();
            JSONObject json = resultJson(outcome.success, service.getStateJson());
            try {
                json.put("message", outcome.message);
            } catch (Exception ignored) {
            }
            writeJson(out, outcome.success ? 200 : 502, json);
        } else if (p.equals("/filament/unload") && req.method.equals("POST")) {
            PrinterService.UploadOutcome outcome = service.unloadFilament();
            JSONObject json = resultJson(outcome.success, service.getStateJson());
            try {
                json.put("message", outcome.message);
            } catch (Exception ignored) {
            }
            writeJson(out, outcome.success ? 200 : 422, json);
        } else if ((p.startsWith("/control/") || p.equals("/filament/load")) && req.method.equals("POST")) {
            PrinterService.UploadOutcome outcome = manualControl(p, req);
            JSONObject json = resultJson(outcome.success, service.getStateJson());
            try {
                json.put("message", outcome.message);
            } catch (Exception ignored) {
            }
            writeJson(out, outcome.success ? 200 : 422, json);
        } else if (p.equals("/level") && req.method.equals("POST")) {
            PrinterService.UploadOutcome outcome = service.levelBed();
            JSONObject json = resultJson(outcome.success, service.getStateJson());
            try {
                json.put("message", outcome.message);
            } catch (Exception ignored) {
            }
            writeJson(out, outcome.success ? 200 : 422, json);
        } else if (p.equals("/level/grid") && req.method.equals("GET")) {
            JSONObject body = new JSONObject();
            try {
                body.put("grid", service.getLevelingGrid());
                body.put("success", true);
            } catch (Exception e) {
                try {
                    body.put("success", false);
                    body.put("message", String.valueOf(e.getMessage()));
                } catch (Exception ignored) {
                }
            }
            writeJson(out, 200, body);
        } else if (p.equals("/debug/door") && req.method.equals("GET")) {
            writeJson(out, 200, service.doorReport());
        } else if (p.equals("/debug/door") && req.method.equals("POST")) {
            // mode=off|log|on, threshold=<change that counts as "the door opened">
            double th = 0;
            try {
                th = Double.parseDouble(req.queryParam("threshold"));
            } catch (NumberFormatException ignored) {
            }
            service.setDoorWatch(req.queryParam("mode"), th);
            writeJson(out, 200, service.doorReport());
        } else if (p.equals("/debug/link") && req.method.equals("POST")) {
            // kind=sim: the board's pretend printer (testing without the real one); kind=usb: the real printer
            PrinterService.UploadOutcome outcome = service.setSimulator("sim".equals(req.queryParam("kind")));
            JSONObject json = resultJson(outcome.success, service.getStateJson());
            try {
                json.put("message", outcome.message);
            } catch (Exception ignored) {
            }
            writeJson(out, outcome.success ? 200 : 422, json);
        } else if (p.equals("/debug/sdlist") && req.method.equals("GET")) {
            writeText(out, 200, "text/plain", service.debugListSdFiles());
        } else if (p.equals("/sdfiles") && req.method.equals("GET")) {
            JSONObject body = new JSONObject();
            try {
                body.put("files", service.listSdFiles());
            } catch (Exception ignored) {
            }
            writeJson(out, 200, body);
        } else if (p.equals("/select") && req.method.equals("POST")) {
            String filename = req.queryParam("filename");
            String displayName = req.queryParam("displayName");
            PrinterService.UploadOutcome outcome = service.selectSdFile(filename, displayName);
            JSONObject json = resultJson(outcome.success, service.getStateJson());
            try {
                json.put("message", outcome.message);
            } catch (Exception ignored) {
            }
            writeJson(out, outcome.success ? 200 : 422, json);
        } else if (p.equals("/sdfiles/known") && req.method.equals("GET")) {
            JSONObject body = new JSONObject();
            try {
                body.put("files", service.listKnownFiles());
            } catch (Exception ignored) {
            }
            writeJson(out, 200, body);
        } else if (p.equals("/schedule/set") && req.method.equals("POST")) {
            String filename = req.queryParam("filename");
            String displayName = req.queryParam("displayName");
            String atMillisParam = req.queryParam("atMillis");
            long atMillis;
            try {
                atMillis = Long.parseLong(atMillisParam);
            } catch (NumberFormatException e) {
                writeJson(out, 400, errorJson("atMillis must be a number"));
                return;
            }
            if (atMillis <= System.currentTimeMillis()) {
                writeJson(out, 400, errorJson("Scheduled time must be in the future"));
                return;
            }
            service.setScheduledPrint(filename, displayName, atMillis);
            writeJson(out, 200, resultJson(true, service.getStateJson()));
        } else if (p.equals("/schedule/upload") && req.method.equals("POST")) {
            // body = the gcode file; kept on the phone and sent to the board shortly before the start
            long atMillis;
            try {
                atMillis = Long.parseLong(req.queryParam("atMillis"));
            } catch (NumberFormatException e) {
                writeJson(out, 400, errorJson("atMillis must be a number"));
                return;
            }
            if (atMillis <= System.currentTimeMillis()) {
                writeJson(out, 400, errorJson("Scheduled time must be in the future"));
                return;
            }
            PrinterService.UploadOutcome outcome = service.setScheduledPrintFromPhone(req.queryParam("displayName"), req.body, req.contentLength, atMillis);
            JSONObject json = resultJson(outcome.success, service.getStateJson());
            try {
                json.put("message", outcome.message);
            } catch (Exception ignored) {
            }
            writeJson(out, outcome.success ? 200 : 422, json);
        } else if (p.equals("/schedule/time") && req.method.equals("POST")) {
            long atMillis;
            try {
                atMillis = Long.parseLong(req.queryParam("atMillis"));
            } catch (NumberFormatException e) {
                writeJson(out, 400, errorJson("atMillis must be a number"));
                return;
            }
            if (atMillis <= System.currentTimeMillis()) {
                writeJson(out, 400, errorJson("Scheduled time must be in the future"));
                return;
            }
            boolean ok = service.rescheduleScheduledPrint(atMillis);
            writeJson(out, ok ? 200 : 409, ok ? resultJson(true, service.getStateJson()) : errorJson("Nothing is scheduled"));
        } else if (p.equals("/schedule/cancel") && req.method.equals("POST")) {
            service.cancelScheduledPrint();
            writeJson(out, 200, resultJson(true, service.getStateJson()));
        } else if (p.equals("/print/recover") && req.method.equals("POST")) {
            long offset = 0;
            double zNow = 0;
            try {
                if (req.queryParam("offset") != null) offset = Long.parseLong(req.queryParam("offset"));
                if (req.queryParam("zNow") != null) zNow = Double.parseDouble(req.queryParam("zNow"));
            } catch (NumberFormatException e) {
                writeJson(out, 400, errorJson("offset and zNow must be numbers"));
                return;
            }
            String mode = req.queryParam("mode");
            PrinterService.UploadOutcome outcome = service.recoverPrint(offset, mode == null ? "auto" : mode, zNow);
            JSONObject json = resultJson(outcome.success, service.getStateJson());
            try {
                json.put("message", outcome.message);
            } catch (Exception ignored) {
            }
            writeJson(out, outcome.success ? 200 : 409, json);
        } else if (p.equals("/print/recover/discard") && req.method.equals("POST")) {
            PrinterService.UploadOutcome outcome = service.discardInterrupted();
            JSONObject json = resultJson(outcome.success, service.getStateJson());
            try {
                json.put("message", outcome.message);
            } catch (Exception ignored) {
            }
            writeJson(out, outcome.success ? 200 : 409, json);
        } else if (p.equals("/alert/dismiss") && req.method.equals("POST")) {
            service.dismissAlertByUser();
            writeJson(out, 200, resultJson(true, service.getStateJson()));
        } else if (p.equals("/alerts/config") && req.method.equals("POST")) {
            // Only the keys given change: ntfy=<topic> (push to the ntfy app), tgToken=<bot token from @BotFather>,
            // tgChat=<chat id>, dashUrl=<link put in every push, default: this phone on Wi-Fi>; "" switches one off. No keys: just reports what is set up (never the token).
            if (req.query.containsKey("ntfy")) service.setNtfyTopic(req.queryParam("ntfy"));
            if (req.query.containsKey("dashUrl")) service.setDashboardUrl(req.queryParam("dashUrl"));
            if (req.query.containsKey("tgToken") || req.query.containsKey("tgChat")) {
                service.setTelegram(req.query.containsKey("tgToken") ? req.queryParam("tgToken") : null,
                        req.query.containsKey("tgChat") ? req.queryParam("tgChat") : null);
            }
            JSONObject json = resultJson(true, service.getStateJson());
            try {
                json.put("alerts", service.alertsConfig());
            } catch (Exception ignored) {
            }
            writeJson(out, 200, json);
        } else if (p.equals("/alerts/telegram/link") && req.method.equals("POST")) {
            // After the user wrote to the bot: take the chat id from the bot's updates, send a confirmation there.
            String msg = service.linkTelegramChat();
            JSONObject json = resultJson(service.alertsConfig().optString("telegramChat").length() > 0, service.getStateJson());
            try {
                json.put("message", msg);
                json.put("alerts", service.alertsConfig());
            } catch (Exception ignored) {
            }
            writeJson(out, 200, json);
        } else if (p.equals("/alerts/test") && req.method.equals("POST")) {
            // demo=1: the 25/50/75 % and "successful" print pushes instead of the alert
            if ("1".equals(req.queryParam("demo"))) service.sendDemoPrintPushes();
            else service.raiseAlert("test", "PrintHost test alert - notifications work");
            writeJson(out, 200, resultJson(true, service.getStateJson()));
        } else if (p.equals("/autoshutoff/set") && req.method.equals("POST")) {
            service.setAutoShutoffEnabled("true".equals(req.queryParam("enabled")));
            writeJson(out, 200, resultJson(true, service.getStateJson()));
        } else if (p.equals("/delete") && req.method.equals("POST")) {
            String filename = req.queryParam("filename");
            PrinterService.UploadOutcome outcome = service.deleteSdFile(filename);
            JSONObject json = resultJson(outcome.success, service.getStateJson());
            try {
                json.put("message", outcome.message);
            } catch (Exception ignored) {
            }
            writeJson(out, outcome.success ? 200 : 422, json);
        } else if (p.equals("/upload") && req.method.equals("POST")) {
            handleUpload(req, out);
        } else if (p.equals("/upload/cancel") && req.method.equals("POST")) {
            service.cancelUpload();
            writeJson(out, 200, resultJson(true, service.getStateJson()));
        } else if (p.equals("/start") && req.method.equals("POST")) {
            boolean ok = service.startPrint();
            writeJson(out, ok ? 200 : 409, resultJson(ok, service.getStateJson()));
        } else if (p.equals("/stop") && req.method.equals("POST")) {
            boolean ok = service.stopPrint();
            writeJson(out, ok ? 200 : 502, resultJson(ok, service.getStateJson()));
        } else if (p.equals("/pause") && req.method.equals("POST")) {
            boolean ok = service.pausePrint();
            writeJson(out, ok ? 200 : 502, resultJson(ok, service.getStateJson()));
        } else if (p.equals("/resume") && req.method.equals("POST")) {
            boolean ok = service.resumePrint();
            writeJson(out, ok ? 200 : 409, resultJson(ok, service.getStateJson()));
        } else if (p.equals("/camera/relay") && req.method.equals("GET")) {
            relayMjpeg(out, "1".equals(req.queryParam("raw")));
        } else if (p.equals("/camera/relay/stats") && req.method.equals("GET")) {
            writeText(out, 200, "application/json", relay.statsJson());
        } else if (p.equals("/esp/logs/read") && req.method.equals("GET")) {
            proxyEspLogsRead(out, req);
        } else if (espProxyTarget(p) != null && req.method.equals("GET")) {
            proxyEspGet(out, espProxyTarget(p), req);
        } else if (p.equals("/camera/quality") && req.method.equals("POST")) {
            boolean ok = service.setCameraQuality(req.queryParam("profile"));
            writeJson(out, 200, resultJson(ok, service.getStateJson()));
        } else if ((p.equals("/camera/set") || p.equals("/camera/force")) && req.method.equals("POST")) {
            // /camera/force: the old name, kept so a dashboard still open from before the update works
            boolean ok = service.setCamera("1".equals(req.queryParam("on")));
            writeJson(out, 200, resultJson(ok, service.getStateJson()));
        } else if (p.equals("/gcode/current") && req.method.equals("GET")) {
            writeUploadedGcode(out);
        } else if (p.equals("/gcode/cached") && req.method.equals("GET")) {
            writeCachedGcode(out, req.queryParam("filename"));
        } else {
            writeText(out, 404, "text/plain", "Not found: " + p);
        }
    }

    private void handleUpload(HttpRequest req, OutputStream out) throws IOException {
        String filename = req.queryParam("filename");
        InputStream body = req.body;
        PrinterService.UploadOutcome outcome = service.upload(filename, body, req.contentLength);
        JSONObject json = resultJson(outcome.success, service.getStateJson());
        try {
            json.put("message", outcome.message);
        } catch (Exception ignored) {
        }
        writeJson(out, outcome.success ? 200 : 422, json);
    }

    /** Raw bytes of whatever's currently loaded (PrinterService.uploadedFile), for the
     *  dashboard's 3D preview to parse client-side - it has no other way to reach a file that
     *  only ever lived in the app's private storage. Streamed rather than buffered like
     *  readAsset(): a real gcode file can be tens of MB, dashboard.html itself is a few KB. */
    private void writeUploadedGcode(OutputStream out) throws IOException {
        File file = service.getUploadedFile();
        if (file == null || !file.exists()) {
            writeText(out, 404, "text/plain", "No gcode file uploaded this session");
            return;
        }
        String head = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "Content-Length: " + file.length() + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        InputStream in = new FileInputStream(file);
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        } finally {
            in.close();
        }
        out.flush();
    }

    private void writeCachedGcode(OutputStream out, String filename) throws IOException {
        File file = filename == null ? null : service.getCachedGcodeFile(filename);
        if (file == null || !file.exists()) {
            writeText(out, 404, "text/plain", "No local cached copy of that file");
            return;
        }
        String head = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "Content-Length: " + file.length() + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        InputStream in = new FileInputStream(file);
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        } finally {
            in.close();
        }
        out.flush();
    }

    /** Every viewer gets the newest frame the board sent; a viewer that cannot keep up skips frames on its own. */
    /** raw = the same bytes as application/octet-stream: WebKit (every iPhone browser) will not hand a
     *  multipart/x-mixed-replace response to a page script piece by piece, so the dashboard reader asks for raw. */
    private void relayMjpeg(OutputStream rawOut, boolean raw) throws IOException {
        java.io.BufferedOutputStream out = new java.io.BufferedOutputStream(rawOut, 65536);
        String head = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: " + (raw ? "application/octet-stream" : "multipart/x-mixed-replace; boundary=" + MJPEG_BOUNDARY) + "\r\n"
                + "Connection: close\r\n"
                + "Cache-Control: no-cache\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.flush();
        relay.register();
        try {
            long lastSeq = 0;
            int idleMs = 0;
            while (idleMs < 15000) {  // no frame for 15 s (camera off / board gone): end, the page retries by itself
                EspCameraRelay.Frame f;
                try {
                    f = relay.awaitFrame(lastSeq, 1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (f == null) {
                    idleMs += 1000;
                    continue;
                }
                idleMs = 0;
                lastSeq = f.seq;
                String partHeader = "--" + MJPEG_BOUNDARY + "\r\n"
                        + "Content-Type: image/jpeg\r\n"
                        + "Content-Length: " + f.jpeg.length + "\r\n\r\n";
                out.write(partHeader.getBytes(StandardCharsets.US_ASCII));
                out.write(f.jpeg);
                out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
            }
        } finally {
            relay.unregister();
        }
    }

    /** Fetches one read-only page from the board and passes it on unchanged. */
    private void proxyEspGet(OutputStream out, String espPath, HttpRequest req) throws IOException {
        String query = "";
        String tail = req.queryParam("tail");
        if (tail.length() > 0 && tail.matches("[0-9]{1,5}")) query = "?tail=" + tail;
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL("http://" + service.getEspHost() + espPath + query).openConnection();
            c.setConnectTimeout(2500);
            c.setReadTimeout(8000);
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String type = c.getContentType();
            String head = "HTTP/1.1 " + code + " " + statusText(code) + "\r\n"
                    + "Content-Type: " + (type == null ? "text/plain" : type) + "\r\n"
                    + "Cache-Control: no-cache\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            if (in != null) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                in.close();
            }
            out.flush();
        } catch (IOException e) {
            writeJson(out, 502, errorJson("camera board unreachable"));
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** GET /esp/logs/read?name=&from=&max= -> the board's /logs/read, forwarding those params and
     *  the X-Log-* headers the viewer needs to know where in the file it just read (see the
     *  board's own logsReadHandler for what they mean). */
    private void proxyEspLogsRead(OutputStream out, HttpRequest req) throws IOException {
        StringBuilder query = new StringBuilder();
        appendEspParam(query, req, "name");
        appendEspParam(query, req, "from");
        appendEspParam(query, req, "max");
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(
                    "http://" + service.getEspHost() + "/logs/read" + query).openConnection();
            c.setConnectTimeout(2500);
            c.setReadTimeout(8000);
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String type = c.getContentType();
            StringBuilder head = new StringBuilder("HTTP/1.1 " + code + " " + statusText(code) + "\r\n"
                    + "Content-Type: " + (type == null ? "text/plain" : type) + "\r\n"
                    + "Access-Control-Expose-Headers: X-Log-Size, X-Log-From, X-Log-Next\r\n"
                    + "Cache-Control: no-cache\r\n");
            appendEspHeader(head, c, "X-Log-Size");
            appendEspHeader(head, c, "X-Log-From");
            appendEspHeader(head, c, "X-Log-Next");
            head.append("Connection: close\r\n\r\n");
            out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
            if (in != null) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                in.close();
            }
            out.flush();
        } catch (IOException e) {
            writeJson(out, 502, errorJson("camera board unreachable"));
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static void appendEspParam(StringBuilder query, HttpRequest req, String name) throws IOException {
        String v = req.queryParam(name);
        if (v.length() == 0) return;
        query.append(query.length() == 0 ? '?' : '&').append(name).append('=')
                .append(java.net.URLEncoder.encode(v, "UTF-8"));
    }

    private static void appendEspHeader(StringBuilder head, java.net.HttpURLConnection c, String name) {
        String v = c.getHeaderField(name);
        if (v != null) head.append(name).append(": ").append(v).append("\r\n");
    }

    private void writeDashboard(OutputStream out) throws IOException {
        if (cachedDashboardHtml == null) {
            cachedDashboardHtml = readAsset("dashboard.html");
        }
        writeBytes(out, 200, "text/html; charset=utf-8", cachedDashboardHtml);
    }

    /** Static files bundled under assets/vendor/ - the 3D preview's JS libraries and the print
     *  bed model, none of which existed as servable routes before (writeDashboard() only ever
     *  served the one fixed asset). AssetManager.open() reads out of the APK's own asset
     *  namespace, not the real filesystem, so "..' can't escape to anything outside it - rejected
     *  anyway as a cheap sanity check. */
    private void writeVendorAsset(OutputStream out, String path) throws IOException {
        if (path.contains("..")) {
            writeText(out, 404, "text/plain", "Not found: " + path);
            return;
        }
        byte[] body;
        try {
            body = readAsset(path.substring(1)); // strip leading '/'
        } catch (IOException e) {
            writeText(out, 404, "text/plain", "Not found: " + path);
            return;
        }
        writeBytes(out, 200, vendorContentType(path), body);
    }

    private static String vendorContentType(String path) {
        if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".stl")) return "application/octet-stream";
        return "application/octet-stream";
    }

    private byte[] readAsset(String name) throws IOException {
        InputStream in = service.getAssets().open(name);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            in.close();
        }
    }

    private static JSONObject resultJson(boolean success, JSONObject state) {
        JSONObject o = new JSONObject();
        try {
            o.put("success", success);
            o.put("state", state);
        } catch (Exception ignored) {
        }
        return o;
    }

    private static JSONObject errorJson(String message) {
        JSONObject o = new JSONObject();
        try {
            o.put("success", false);
            o.put("error", message);
        } catch (Exception ignored) {
        }
        return o;
    }

    private static void writeJson(OutputStream out, int code, JSONObject body) throws IOException {
        writeBytes(out, code, "application/json; charset=utf-8",
                body.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void writeText(OutputStream out, int code, String contentType, String body) throws IOException {
        writeBytes(out, code, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeBytes(OutputStream out, int code, String contentType, byte[] body) throws IOException {
        String head = "HTTP/1.1 " + code + " " + statusText(code) + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static String statusText(int code) {
        switch (code) {
            case 200: return "OK";
            case 404: return "Not Found";
            case 409: return "Conflict";
            case 422: return "Unprocessable Entity";
            case 500: return "Internal Server Error";
            case 502: return "Bad Gateway";
            default: return "";
        }
    }

    /** The manual controls (what the printer's own screen offers). Every value is checked again in PrinterService. */
    private PrinterService.UploadOutcome manualControl(String p, HttpRequest req) {
        try {
            switch (p) {
                case "/control/home": return service.manualHome();
                case "/control/cancel":
                    service.manualCancel();
                    return new PrinterService.UploadOutcome(true, "Cancel requested");
                case "/control/motors-on": return service.manualMotorsOn();
                case "/control/mesh": return service.manualSetMeshPoint(Integer.parseInt(req.queryParam("column")),
                        Integer.parseInt(req.queryParam("row")), Double.parseDouble(req.queryParam("z")));
                case "/control/feed": return service.manualExtrude(Double.parseDouble(req.queryParam("mm")), Integer.parseInt(req.queryParam("temp")));
                case "/control/move": return service.manualMove(req.queryParam("axis"), Double.parseDouble(req.queryParam("dist")));
                case "/control/motors-off": return service.manualMotorsOff();
                case "/control/temp": {
                    String h = req.queryParam("hotend"), b = req.queryParam("bed");
                    return service.manualSetTemps(h.isEmpty() ? null : Integer.valueOf(h), b.isEmpty() ? null : Integer.valueOf(b));
                }
                case "/control/preheat": return service.manualPreheat(Integer.parseInt(req.queryParam("preset")));
                case "/control/cooldown": return service.manualCooldown();
                case "/control/fan": return service.manualFan(Integer.parseInt(req.queryParam("percent")));
                case "/control/speed": return service.manualFeed(Integer.parseInt(req.queryParam("percent")));
                case "/control/flow": return service.manualFlow(Integer.parseInt(req.queryParam("percent")));
                case "/control/extrude": return service.manualExtrude(Double.parseDouble(req.queryParam("mm")));
                case "/control/zoffset": return service.manualZOffset(Double.parseDouble(req.queryParam("delta")));
                case "/filament/load": return service.manualLoadFilament(Integer.parseInt(req.queryParam("temp")));
                default: return new PrinterService.UploadOutcome(false, "Unknown control");
            }
        } catch (NumberFormatException e) {
            return new PrinterService.UploadOutcome(false, "Bad value");
        }
    }
}
