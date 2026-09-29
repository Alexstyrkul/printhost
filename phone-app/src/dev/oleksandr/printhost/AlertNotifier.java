package dev.oleksandr.printhost;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Tells the user that something went wrong with a print (the board restarted mid-print, heaters left on...), on every
 * channel there is: a high-priority notification on this phone, the Mac listener (MacNotifier), and a push through
 * ntfy.sh to the ntfy app on the user's own phone when a topic is configured (POST /alerts/config?ntfy=<topic>).
 * ntfy needs no account and no password: whoever knows the topic name gets the pushes, so the topic is a long random
 * string.
 */
public class AlertNotifier {

    private static final String TAG = "AlertNotifier";
    private static final String CHANNEL_ID = "printhost_alerts";
    private static final int NOTIFICATION_ID = 2;

    private final Context context;
    private final MacNotifier macNotifier;
    private final SharedPreferences prefs;

    public AlertNotifier(Context context, MacNotifier macNotifier) {
        this.context = context.getApplicationContext();
        this.macNotifier = macNotifier;
        this.prefs = this.context.getSharedPreferences("printhost", Context.MODE_PRIVATE);
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "PrintHost alerts", NotificationManager.IMPORTANCE_HIGH);
        this.context.getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    public String ntfyTopic() {
        return prefs.getString("ntfy_topic", "");
    }

    public void setNtfyTopic(String topic) {
        prefs.edit().putString("ntfy_topic", topic == null ? "" : topic.trim()).apply();
    }

    public void send(final String message) {
        Log.w(TAG, "ALERT: " + message);
        try {
            Notification n = new Notification.Builder(context, CHANNEL_ID)
                    .setContentTitle("PrintHost: attention")
                    .setContentText(message)
                    .setStyle(new Notification.BigTextStyle().bigText(message))
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setAutoCancel(true)
                    .build();
            context.getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, n);
        } catch (Exception e) {
            Log.w(TAG, "local notification failed", e);
        }
        macNotifier.notifyAsync("error");
        final String topic = ntfyTopic();
        if (topic.isEmpty()) return;
        new Thread("PrintHostNtfy") {
            @Override
            public void run() {
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL("https://ntfy.sh/" + URLEncoder.encode(topic, "UTF-8")).openConnection();
                    c.setRequestMethod("POST");
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(10000);
                    c.setDoOutput(true);
                    c.setRequestProperty("Title", "PrintHost");
                    c.setRequestProperty("Priority", "high");
                    c.setRequestProperty("Tags", "warning");
                    try (OutputStream out = c.getOutputStream()) {
                        out.write(message.getBytes(StandardCharsets.UTF_8));
                    }
                    Log.i(TAG, "ntfy push -> HTTP " + c.getResponseCode());
                } catch (Exception e) {
                    Log.w(TAG, "ntfy push failed", e);
                } finally {
                    if (c != null) c.disconnect();
                }
            }
        }.start();
    }
}
