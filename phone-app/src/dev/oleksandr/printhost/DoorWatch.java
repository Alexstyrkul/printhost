package dev.oleksandr.printhost;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.util.Collections;

/**
 * Watches the front camera to notice the cabinet door opening (or someone stepping in front of it), so the screen can
 * be switched on without a touch. About one small frame a second is reduced to a 16x12 grid of brightness values and
 * compared with the frame before: "change" is the average difference per cell, "light" the average brightness (0-255).
 *
 * Mode "log" only records these numbers (GET /debug/door shows them) - used to find out what an opening door looks like
 * in this cabinet before anything acts on it. Mode "on" also calls onMotion when the change stays above the threshold.
 */
final class DoorWatch {
    private static final String TAG = "PrintHostDoor";
    private static final int GRID_W = 16, GRID_H = 12, KEEP = 600;
    private static final long SAMPLE_EVERY_MS = 1000;

    interface Listener {
        void onMotion(double change);
    }

    private final Context ctx;
    private final Listener listener;
    private HandlerThread thread;
    private Handler handler;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;

    private final float[] prev = new float[GRID_W * GRID_H];
    private boolean havePrev = false;
    private long lastSampleAt = 0;
    private int above = 0, seen = 0;

    // samples for /debug/door: time, light, change (ring buffer)
    private final long[] sT = new long[KEEP];
    private final float[] sLight = new float[KEEP], sChange = new float[KEEP];
    private int sCount = 0, sNext = 0;

    private volatile boolean running = false;
    private volatile String error = "";
    volatile double threshold = 20;
    volatile boolean act = false;  // false = only record ("log"), true = call the listener ("on")

    DoorWatch(Context ctx, Listener listener) {
        this.ctx = ctx;
        this.listener = listener;
    }

    boolean isRunning() {
        return running;
    }

    synchronized void start() {
        if (running) return;
        if (ctx.checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            error = "camera permission not granted";
            return;
        }
        error = "";
        running = true;
        havePrev = false;
        above = 0;
        seen = 0;
        thread = new HandlerThread("PrintHostDoor");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(this::open);
    }

    synchronized void stop() {
        if (!running) return;
        running = false;
        final HandlerThread t = thread;
        handler.post(() -> {
            closeCamera();
            t.quitSafely();
        });
    }

    private void closeCamera() {
        try {
            if (session != null) session.close();
        } catch (Exception ignored) {
        }
        try {
            if (camera != null) camera.close();
        } catch (Exception ignored) {
        }
        try {
            if (reader != null) reader.close();
        } catch (Exception ignored) {
        }
        session = null;
        camera = null;
        reader = null;
    }

    private void fail(String what, Throwable e) {
        error = what + (e == null ? "" : ": " + e.getMessage());
        Log.w(TAG, error, e);
        closeCamera();
        running = false;
    }

    private void open() {
        try {
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            String front = null;
            for (String id : cm.getCameraIdList()) {
                Integer facing = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    front = id;
                    break;
                }
            }
            if (front == null) {
                fail("no front camera", null);
                return;
            }
            reader = ImageReader.newInstance(320, 240, ImageFormat.YUV_420_888, 2);
            reader.setOnImageAvailableListener(this::onImage, handler);
            cm.openCamera(front, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice device) {
                    camera = device;
                    startSession();
                }

                @Override
                public void onDisconnected(CameraDevice device) {
                    fail("camera disconnected", null);
                }

                @Override
                public void onError(CameraDevice device, int code) {
                    fail("camera error " + code, null);
                }
            }, handler);
        } catch (SecurityException | android.hardware.camera2.CameraAccessException | IllegalArgumentException e) {
            fail("cannot open the camera", e);
        }
    }

    private void startSession() {
        try {
            final CaptureRequest.Builder req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            req.addTarget(reader.getSurface());
            camera.createCaptureSession(Collections.singletonList(reader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession s) {
                    session = s;
                    try {
                        s.setRepeatingRequest(req.build(), null, handler);
                        Log.i(TAG, "watching (" + (act ? "on" : "log") + ")");
                    } catch (Exception e) {
                        fail("cannot start the camera stream", e);
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession s) {
                    fail("camera session failed", null);
                }
            }, handler);
        } catch (Exception e) {
            fail("cannot start the camera session", e);
        }
    }

    private void onImage(ImageReader r) {
        Image img = null;
        try {
            img = r.acquireLatestImage();
            if (img == null) return;
            long now = System.currentTimeMillis();
            if (now - lastSampleAt < SAMPLE_EVERY_MS) return;
            lastSampleAt = now;

            Image.Plane y = img.getPlanes()[0];
            ByteBuffer buf = y.getBuffer();
            int w = img.getWidth(), h = img.getHeight(), stride = y.getRowStride();
            float[] grid = new float[GRID_W * GRID_H];
            int cw = w / GRID_W, ch = h / GRID_H;
            double total = 0;
            for (int gy = 0; gy < GRID_H; gy++) {
                for (int gx = 0; gx < GRID_W; gx++) {
                    long sum = 0;
                    int n = 0;
                    for (int py = gy * ch; py < (gy + 1) * ch; py += 4) {
                        int rowStart = py * stride;
                        for (int px = gx * cw; px < (gx + 1) * cw; px += 4) {
                            sum += buf.get(rowStart + px) & 0xFF;
                            n++;
                        }
                    }
                    float v = n == 0 ? 0 : (float) sum / n;
                    grid[gy * GRID_W + gx] = v;
                    total += v;
                }
            }
            float light = (float) (total / grid.length), change = 0;
            if (havePrev) {
                double d = 0;
                for (int i = 0; i < grid.length; i++) d += Math.abs(grid[i] - prev[i]);
                change = (float) (d / grid.length);
            }
            System.arraycopy(grid, 0, prev, 0, grid.length);
            havePrev = true;
            synchronized (sT) {
                sT[sNext] = now;
                sLight[sNext] = light;
                sChange[sNext] = change;
                sNext = (sNext + 1) % KEEP;
                if (sCount < KEEP) sCount++;
            }
            // Measured in the cabinet (2026-10-04, lamp on): a closed door gives 0.1-0.2, an opening or closing door one
            // sample of 45-50, sometimes with smaller ones (5-16) around it. So: one sample above the threshold, or two
            // in a row above a quarter of it. The first samples after the camera starts are skipped (it is still
            // settling its exposure: one such frame measured 118).
            seen++;
            above = change > threshold / 4 ? above + 1 : 0;
            if (act && seen > 3 && (change > threshold || above >= 2)) {
                above = 0;
                listener.onMotion(change);
            }
        } catch (Exception e) {
            Log.w(TAG, "frame failed", e);
        } finally {
            if (img != null) img.close();
        }
    }

    /** The recorded samples, oldest first: [[seconds ago, light, change], ...]. */
    JSONObject report(String mode) {
        JSONObject o = new JSONObject();
        try {
            o.put("mode", mode);
            o.put("running", running);
            o.put("error", error);
            o.put("threshold", threshold);
            JSONArray a = new JSONArray();
            long now = System.currentTimeMillis();
            synchronized (sT) {
                for (int i = 0; i < sCount; i++) {
                    int k = (sNext - sCount + i + KEEP) % KEEP;
                    a.put(new JSONArray().put(Math.round((now - sT[k]) / 100.0) / 10.0).put(Math.round(sLight[k] * 10) / 10.0)
                            .put(Math.round(sChange[k] * 10) / 10.0));
                }
            }
            o.put("samples", a);
        } catch (Exception ignored) {
        }
        return o;
    }
}
