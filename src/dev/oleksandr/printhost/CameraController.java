package dev.oleksandr.printhost;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
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
import android.util.Size;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Camera2-based MJPEG source + torch control. Deliberately never opens the camera unless a
 * client actually asked for the stream (battery/thermal cost - see PRINTHOST_PLAN.md, Alfred
 * Camera precedent). Every open resource (ImageReader, CameraDevice, CaptureSession) is closed
 * in stop(), and every acquired Image is closed the moment we're done reading its bytes.
 */
public class CameraController {

    private static final String TAG = "CameraController";
    private static final Size STREAM_SIZE = new Size(640, 480);

    private final Context appContext;
    private final CameraManager cameraManager;
    private String backCameraId;
    private int sensorOrientation = 90;

    private HandlerThread bgThread;
    private Handler bgHandler;

    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private CaptureRequest.Builder repeatingRequestBuilder;

    private volatile byte[] latestFrame;
    private volatile boolean torchRequested = false;

    public CameraController(Context context) {
        this.appContext = context.getApplicationContext();
        this.cameraManager = (CameraManager) appContext.getSystemService(Context.CAMERA_SERVICE);
    }

    public synchronized boolean isStreaming() {
        return cameraDevice != null;
    }

    public synchronized boolean start() {
        if (cameraDevice != null) return true;
        if (appContext.checkSelfPermission(android.Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "CAMERA permission not granted");
            return false;
        }
        try {
            String id = findBackCameraId();
            if (id == null) {
                Log.e(TAG, "No back camera found");
                return false;
            }
            backCameraId = id;
            bgThread = new HandlerThread("PrintHostCamera");
            bgThread.start();
            bgHandler = new Handler(bgThread.getLooper());

            imageReader = ImageReader.newInstance(STREAM_SIZE.getWidth(), STREAM_SIZE.getHeight(),
                    ImageFormat.JPEG, 2);
            imageReader.setOnImageAvailableListener(new FrameListener(), bgHandler);

            cameraManager.openCamera(backCameraId, new DeviceStateCallback(), bgHandler);
            return true;
        } catch (CameraAccessException | SecurityException e) {
            Log.e(TAG, "start() failed", e);
            teardown();
            return false;
        }
    }

    public synchronized void stop() {
        teardown();
    }

    private void teardown() {
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (bgThread != null) {
            bgThread.quitSafely();
            bgThread = null;
            bgHandler = null;
        }
        latestFrame = null;
    }

    public byte[] getLatestFrame() {
        return latestFrame;
    }

    /** Torch works whether or not the preview session is open: if the session is open we drive
     *  FLASH_MODE_TORCH through the repeating request (CameraManager.setTorchMode would fail with
     *  CAMERA_IN_USE while we hold the device open); otherwise we use the standalone torch API. */
    public synchronized boolean setTorch(boolean on) {
        torchRequested = on;
        try {
            if (cameraDevice != null && captureSession != null && repeatingRequestBuilder != null) {
                applyTorchToRequest(repeatingRequestBuilder, on);
                captureSession.setRepeatingRequest(repeatingRequestBuilder.build(), null, bgHandler);
                return true;
            }
            String id = backCameraId != null ? backCameraId : findBackCameraId();
            if (id == null) return false;
            cameraManager.setTorchMode(id, on);
            return true;
        } catch (CameraAccessException e) {
            Log.e(TAG, "setTorch(" + on + ") failed", e);
            return false;
        }
    }

    private void applyTorchToRequest(CaptureRequest.Builder b, boolean on) {
        if (on) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            b.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);
        } else {
            b.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
        }
    }

    private String findBackCameraId() throws CameraAccessException {
        for (String id : cameraManager.getCameraIdList()) {
            CameraCharacteristics chars = cameraManager.getCameraCharacteristics(id);
            Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                Integer orientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
                if (orientation != null) sensorOrientation = orientation;
                return id;
            }
        }
        return null;
    }

    class DeviceStateCallback extends CameraDevice.StateCallback {
        @Override
        public void onOpened(CameraDevice device) {
            synchronized (CameraController.this) {
                cameraDevice = device;
                try {
                    List<android.view.Surface> targets = new ArrayList<android.view.Surface>();
                    targets.add(imageReader.getSurface());
                    repeatingRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                    repeatingRequestBuilder.addTarget(imageReader.getSurface());
                    // The service has no Activity/Display to read the phone's current rotation
                    // from, so this assumes the phone sits in its natural portrait orientation
                    // (typical for a printer-side camera mount) - confirmed on real hardware that
                    // the stream came out sideways without this.
                    repeatingRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation);
                    applyTorchToRequest(repeatingRequestBuilder, torchRequested);
                    device.createCaptureSession(targets, new SessionStateCallback(), bgHandler);
                } catch (CameraAccessException e) {
                    Log.e(TAG, "createCaptureSession failed", e);
                    teardown();
                }
            }
        }

        @Override
        public void onDisconnected(CameraDevice device) {
            synchronized (CameraController.this) {
                teardown();
            }
        }

        @Override
        public void onError(CameraDevice device, int error) {
            Log.e(TAG, "camera onError: " + error);
            synchronized (CameraController.this) {
                teardown();
            }
        }
    }

    class SessionStateCallback extends CameraCaptureSession.StateCallback {
        @Override
        public void onConfigured(CameraCaptureSession session) {
            synchronized (CameraController.this) {
                if (cameraDevice == null) return; // torn down while we were configuring
                captureSession = session;
                try {
                    session.setRepeatingRequest(repeatingRequestBuilder.build(), null, bgHandler);
                } catch (CameraAccessException e) {
                    Log.e(TAG, "setRepeatingRequest failed", e);
                    teardown();
                }
            }
        }

        @Override
        public void onConfigureFailed(CameraCaptureSession session) {
            Log.e(TAG, "capture session configure failed");
            synchronized (CameraController.this) {
                teardown();
            }
        }
    }

    class FrameListener implements ImageReader.OnImageAvailableListener {
        @Override
        public void onImageAvailable(ImageReader reader) {
            Image image = null;
            try {
                image = reader.acquireLatestImage();
                if (image == null) return;
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                latestFrame = bytes;
            } finally {
                if (image != null) {
                    image.close();
                }
            }
        }
    }
}
