package dev.oleksandr.printhost;

import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
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

    public DashboardRouter(PrinterService service) {
        this.service = service;
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
        } else if (p.equals("/camera/start") && req.method.equals("POST")) {
            boolean ok = service.cameraStart();
            writeJson(out, ok ? 200 : 502, resultJson(ok, service.getStateJson()));
        } else if (p.equals("/camera/stop") && req.method.equals("POST")) {
            service.cameraStop();
            writeJson(out, 200, resultJson(true, service.getStateJson()));
        } else if (p.equals("/camera/stream") && req.method.equals("GET")) {
            streamMjpeg(out);
        } else if (p.equals("/torch/on") && req.method.equals("POST")) {
            boolean ok = service.torch(true);
            writeJson(out, ok ? 200 : 502, resultJson(ok, service.getStateJson()));
        } else if (p.equals("/torch/off") && req.method.equals("POST")) {
            boolean ok = service.torch(false);
            writeJson(out, ok ? 200 : 502, resultJson(ok, service.getStateJson()));
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

    private void streamMjpeg(OutputStream out) throws IOException {
        String head = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: multipart/x-mixed-replace; boundary=" + MJPEG_BOUNDARY + "\r\n"
                + "Connection: close\r\n"
                + "Cache-Control: no-cache\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.flush();

        int noFrameWaitMs = 0;
        while (noFrameWaitMs < 8000) {
            byte[] frame = service.cameraLatestFrame();
            if (frame == null) {
                noFrameWaitMs += 150;
                sleepQuiet(150);
                continue;
            }
            noFrameWaitMs = 0;
            String partHeader = "--" + MJPEG_BOUNDARY + "\r\n"
                    + "Content-Type: image/jpeg\r\n"
                    + "Content-Length: " + frame.length + "\r\n\r\n";
            out.write(partHeader.getBytes(StandardCharsets.US_ASCII));
            out.write(frame);
            out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            sleepQuiet(150); // ~6-7 fps, plenty for monitoring a print
        }
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void writeDashboard(OutputStream out) throws IOException {
        if (cachedDashboardHtml == null) {
            cachedDashboardHtml = readAsset("dashboard.html");
        }
        writeBytes(out, 200, "text/html; charset=utf-8", cachedDashboardHtml);
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
}
