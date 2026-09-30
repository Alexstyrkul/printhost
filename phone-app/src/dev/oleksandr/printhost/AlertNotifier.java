package dev.oleksandr.printhost;

import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.text.format.Formatter;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Tells the user that something went wrong with a print (the board restarted mid-print, heaters left on...), on every
 * channel there is: the Mac listener (MacNotifier), and a push through
 * ntfy.sh to the ntfy app on the user's own phone when a topic is configured (POST /alerts/config?ntfy=<topic>).
 * ntfy needs no account and no password: whoever knows the topic name gets the pushes, so the topic is a long random
 * string.
 * Telegram: a bot of the user's own (token from @BotFather) sends to one chat. The chat id does not have to be typed in:
 * the user writes anything to the bot, then POST /alerts/telegram/link picks the chat up from the bot's getUpdates.
 */
public class AlertNotifier {

    private static final String TAG = "AlertNotifier";

    private final Context context;
    private final MacNotifier macNotifier;
    private final SharedPreferences prefs;

    public AlertNotifier(Context context, MacNotifier macNotifier) {
        this.context = context.getApplicationContext();
        this.macNotifier = macNotifier;
        this.prefs = this.context.getSharedPreferences("printhost", Context.MODE_PRIVATE);
        // No notification on this phone any more (it lies in a cupboard as a server): drop the old alerts channel.
        this.context.getSystemService(NotificationManager.class).deleteNotificationChannel("printhost_alerts");
    }

    public String ntfyTopic() {
        return prefs.getString("ntfy_topic", "");
    }

    public void setNtfyTopic(String topic) {
        prefs.edit().putString("ntfy_topic", topic == null ? "" : topic.trim()).apply();
    }

    /**
     * Link added to every push so a tap opens the dashboard. The pushes matter most away from home, so: dash_url when
     * set, else this phone's Tailscale address (opens from anywhere on a device with Tailscale on, at home too), else
     * this phone on the home Wi-Fi.
     */
    public String dashboardUrl() {
        String url = prefs.getString("dash_url", "").trim();
        if (!url.isEmpty()) return url;
        String ts = tailscaleAddress();
        if (ts != null) return "http://" + ts + ":" + PrinterService.HTTP_PORT + "/";
        try {
            WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            int ip = wifi == null ? 0 : wifi.getConnectionInfo().getIpAddress();
            if (ip != 0) return "http://" + Formatter.formatIpAddress(ip) + ":" + PrinterService.HTTP_PORT + "/";
        } catch (Exception e) {
            Log.w(TAG, "no Wi-Fi address for the dashboard link", e);
        }
        return "";
    }

    /** This phone's Tailscale IPv4 (100.64.0.0/10, on the VPN interface), or null when Tailscale is not up. */
    private static String tailscaleAddress() {
        try {
            for (java.net.NetworkInterface ni : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp()) continue;
                for (java.net.InetAddress a : java.util.Collections.list(ni.getInetAddresses())) {
                    byte[] b = a.getAddress();
                    if (b.length == 4 && (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64) return a.getHostAddress();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "cannot list network interfaces", e);
        }
        return null;
    }

    public void setDashboardUrl(String url) {
        prefs.edit().putString("dash_url", url == null ? "" : url.trim()).apply();
    }

    public String telegramToken() {
        return prefs.getString("tg_token", "");
    }

    public String telegramChat() {
        return prefs.getString("tg_chat", "");
    }

    /** null leaves a value as it is; "" clears it. A new token forgets the old chat (it belonged to another bot). */
    public void setTelegram(String token, String chat) {
        SharedPreferences.Editor e = prefs.edit();
        if (token != null) {
            token = token.trim();
            if (!token.equals(telegramToken())) e.putString("tg_chat", "");
            e.putString("tg_token", token);
        }
        if (chat != null) e.putString("tg_chat", chat.trim());
        e.apply();
    }

    /** Which channels are set up (never the token itself). */
    public JSONObject config() {
        JSONObject o = new JSONObject();
        try {
            o.put("ntfy", !ntfyTopic().isEmpty());
            o.put("telegramToken", !telegramToken().isEmpty());
            o.put("telegramChat", telegramChat());
            o.put("dashboardUrl", dashboardUrl());
        } catch (Exception ignored) {
        }
        return o;
    }

    /**
     * Finds the chat to send to: the newest private message the bot received (the user wrote to it). Blocking, call
     * from a request thread. Returns a line for the dashboard / curl.
     */
    public String linkTelegramChat() {
        String token = telegramToken();
        if (token.isEmpty()) return "No bot token set: POST /alerts/config?tgToken=<token from @BotFather>";
        try {
            JSONObject r = new JSONObject(telegramCall(token, "getUpdates", null));
            if (!r.optBoolean("ok")) return "Telegram refused: " + r.optString("description");
            JSONArray updates = r.optJSONArray("result");
            String chat = "", who = "";
            for (int i = 0; updates != null && i < updates.length(); i++) {
                JSONObject u = updates.getJSONObject(i);
                JSONObject m = u.optJSONObject("message");
                if (m == null) m = u.optJSONObject("my_chat_member");
                JSONObject c = m == null ? null : m.optJSONObject("chat");
                if (c == null || !"private".equals(c.optString("type"))) continue;
                chat = String.valueOf(c.optLong("id"));
                who = c.optString("username", c.optString("first_name"));
            }
            if (chat.isEmpty()) return "The bot has no messages yet: open it in Telegram, press Start (or write anything), then try again";
            prefs.edit().putString("tg_chat", chat).apply();
            telegramSendAsync("PrintHost is linked to this chat. Alerts will come here.");
            return "Linked to chat " + chat + (who.isEmpty() ? "" : " (" + who + ")");
        } catch (Exception e) {
            Log.w(TAG, "telegram link failed", e);
            return "Telegram link failed: " + e.getMessage();
        }
    }

    /** Info message (e.g. print finished): Telegram and ntfy only, no high-priority notification on this phone. */
    public void push(String message) {
        telegramSendAsync(message);
        ntfyAsync(message, "default", "white_check_mark");
    }

    private void telegramSendAsync(final String message) {
        final String token = telegramToken(), chat = telegramChat();
        if (token.isEmpty() || chat.isEmpty()) return;
        String url = dashboardUrl();
        final String text = url.isEmpty() ? message : message + "\n" + url;
        new Thread("PrintHostTelegram") {
            @Override
            public void run() {
                // Telegram may be briefly unreachable (Wi-Fi gap, Tailscale reconnecting): a few tries, then give up.
                for (int attempt = 1; attempt <= 3; attempt++) {
                    try {
                        String body = "chat_id=" + URLEncoder.encode(chat, "UTF-8")
                                + "&text=" + URLEncoder.encode(text, "UTF-8")
                                + "&disable_web_page_preview=true";
                        JSONObject r = new JSONObject(telegramCall(token, "sendMessage", body));
                        if (r.optBoolean("ok")) {
                            Log.i(TAG, "telegram push sent");
                            return;
                        }
                        Log.w(TAG, "telegram push refused: " + r.optString("description"));
                        return;  // a refusal (bad token, blocked bot) does not get better by retrying
                    } catch (Exception e) {
                        Log.w(TAG, "telegram push failed (try " + attempt + ")", e);
                    }
                    try {
                        Thread.sleep(5000L * attempt);
                    } catch (InterruptedException ie) {
                        return;
                    }
                }
            }
        }.start();
    }

    /** One Bot API call; body = form-encoded POST, or null for GET. Returns the JSON text (also for HTTP errors). */
    private static String telegramCall(String token, String method, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.telegram.org/bot" + token + "/" + method).openConnection();
        try {
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            if (body != null) {
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");
                try (OutputStream out = c.getOutputStream()) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) throw new java.io.IOException("HTTP " + code);
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            in.close();
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }

    public void send(final String message) {
        Log.w(TAG, "ALERT: " + message);
        macNotifier.notifyAsync("error");
        telegramSendAsync(message);
        ntfyAsync(message, "high", "warning");
    }

    private void ntfyAsync(final String message, final String priority, final String tags) {
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
                    String url = dashboardUrl();
                    if (!url.isEmpty()) c.setRequestProperty("Click", url);
                    c.setRequestProperty("Priority", priority);
                    c.setRequestProperty("Tags", tags);
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
