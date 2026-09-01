package dev.oleksandr.printhost;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Just enough UI to confirm the service is running and tell the user where the dashboard
 *  lives on the LAN. All real interaction happens through the web dashboard, not this screen.
 *  Also the target for the USB_DEVICE_ATTACHED intent-filter (see device_filter.xml). */
public class MainActivity extends Activity {

    private static final int PERMISSION_REQUEST_CODE = 100;
    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        requestNeededPermissions();
        startPrinterService();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        startPrinterService();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(48, 48, 48, 48);
        root.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(this);
        title.setText("PrintHost");
        title.setTextSize(28);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        statusView = new TextView(this);
        statusView.setTextSize(16);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(0, 32, 0, 32);
        root.addView(statusView);

        Button openButton = new Button(this);
        openButton.setText("Open dashboard in browser");
        openButton.setOnClickListener(new OpenDashboardClickListener());
        root.addView(openButton);

        setContentView(root);
        updateStatusText();
    }

    class OpenDashboardClickListener implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            String url = dashboardUrl();
            if (url != null) {
                startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
            }
        }
    }

    private void updateStatusText() {
        String url = dashboardUrl();
        statusView.setText(url != null
                ? "Service running.\nDashboard:\n" + url
                : "Service running.\nConnect to Wi-Fi to see the dashboard URL.");
    }

    private String dashboardUrl() {
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wifi == null) return null;
        int ipInt = wifi.getConnectionInfo().getIpAddress();
        if (ipInt == 0) return null;
        String ip = Formatter.formatIpAddress(ipInt);
        return "http://" + ip + ":" + PrinterService.HTTP_PORT + "/";
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
