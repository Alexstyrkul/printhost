package dev.oleksandr.printhost;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

import dev.oleksandr.printhost.panel.Panel;

/**
 * The panel: this phone, mounted on the printer, replaces the printer's own screen (see the panel package).
 * Also starts the service that does the real work (printer, dashboard, alerts).
 * "--ez mock true" in the start intent shows the panel with pretend values and leaves the service alone.
 */
public class MainActivity extends Activity {

    private static final int PERMISSION_REQUEST_CODE = 100;
    private Panel panel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean mock = getIntent().getBooleanExtra("mock", false);
        panel = new Panel(this, mock);
        panel.attach();
        // Waking the phone (double tap, power button) shows the panel straight away, without unlocking.
        setShowWhenLocked(true);
        if (!mock) {
            requestNeededPermissions();
            startPrinterService();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (!intent.getBooleanExtra("mock", false)) startPrinterService();
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent e) {
        panel.touched();
        return super.dispatchTouchEvent(e);
    }

    @Override
    protected void onResume() {
        super.onResume();
        panel.resume();
    }

    @Override
    protected void onPause() {
        panel.pause();
        super.onPause();
    }

    private void requestNeededPermissions() {
        java.util.List<String> needed = new java.util.ArrayList<String>();
        if (checkSelfPermission(android.Manifest.permission.CAMERA)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            needed.add(android.Manifest.permission.CAMERA);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            needed.add(android.Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!needed.isEmpty()) {
            requestPermissions(needed.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        }
    }

    private void startPrinterService() {
        Intent serviceIntent = new Intent(this, PrinterService.class);
        startForegroundService(serviceIntent);
    }
}
