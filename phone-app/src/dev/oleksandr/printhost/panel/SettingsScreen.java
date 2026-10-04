package dev.oleksandr.printhost.panel;

import dev.oleksandr.printhost.R;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** Settings: the printer, its connection, the board's logs, the machine values (folded away) and the power. */
final class SettingsScreen extends Screen {
    private Switch link;
    private TextView power, printerName, firmware, dashboard;
    private LinearLayout infoRows;
    private String infoShown = "";

    SettingsScreen(Context c, Printer m, Runnable changed) {
        super(c, m, changed);
    }

    private LinearLayout line(String name, View right) {
        LinearLayout r = Ui.row(c);
        r.setMinimumHeight(Ui.dp(c, 48));
        r.addView(Ui.text(c, name, 15, Ui.TEXT, false), Ui.lp(0, Ui.WRAP, 1));
        if (right != null) r.addView(right);
        return r;
    }

    private View divider() {
        View v = new View(c);
        v.setBackgroundColor(Ui.LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(Ui.FILL, Ui.dp(c, 1)));
        return v;
    }

    private LinearLayout listCard() {
        LinearLayout l = Ui.card(c);
        l.setPadding(Ui.dp(c, 14), Ui.dp(c, 2), Ui.dp(c, 14), Ui.dp(c, 2));
        return l;
    }

    @Override
    View build() {
        ScrollView sv = new ScrollView(c);
        LinearLayout b = body(sv);
        b.addView(title("Settings"));

        LinearLayout info = listCard();
        printerName = Ui.text(c, "", 13, Ui.DIM, false);
        firmware = Ui.text(c, "", 13, Ui.DIM, false);
        dashboard = Ui.text(c, "", 13, Ui.DIM, false);
        info.addView(line("Printer", printerName));
        info.addView(divider());
        info.addView(line("Firmware", firmware));
        info.addView(divider());
        info.addView(line("Dashboard", dashboard));
        info.addView(divider());
        link = new Switch(c);
        int[][] states = {{android.R.attr.state_checked}, {}};
        link.setThumbTintList(new ColorStateList(states, new int[]{0xFFFFFFFF, 0xFFB9BDC4}));
        link.setTrackTintList(new ColorStateList(states, new int[]{Ui.GREEN, Ui.TRACK}));
        link.setOnClickListener(v -> {
            m.setConnected(link.isChecked());
            changed.run();
        });
        info.addView(line("Printer connection", link));
        b.addView(info, block());

        b.addView(dropdown("Board logs", logsBody()), block());

        infoRows = Ui.col(c);
        b.addView(dropdown("Information", infoRows), block());

        b.addView(spring());
        power = Ui.button(c, "", Ui.RED_BG, Ui.RED_T);
        power.setOnClickListener(v -> {
            if (!m.power) {
                m.setPower(true);
                changed.run();
                return;
            }
            confirm("Power off?", () -> {
                if (!m.printing) {
                    m.setPower(false);
                    changed.run();
                } else {
                    confirm("A print is running. Power off anyway?", () -> {
                        m.setPower(false);
                        changed.run();
                    });
                }
            });
        });
        b.addView(power, block(48));
        return sv;
    }

    private void confirm(String question, final Runnable yes) {
        new AlertDialog.Builder(c, android.R.style.Theme_Material_Dialog_Alert).setMessage(question)
                .setPositiveButton("Power off", (d, w) -> yes.run()).setNegativeButton("Cancel", null).show();
    }

    /** A card that opens in place: the arrow points right while closed and down while open. */
    private Runnable loadEvents;

    private LinearLayout dropdown(final String name, final View bodyView) {
        LinearLayout card = listCard();
        final ImageView chevron = Ui.icon(c, R.drawable.ic_chev_right, Ui.DIM, 20);
        LinearLayout head = line(name, chevron);
        card.addView(head);
        bodyView.setVisibility(View.GONE);
        card.addView(bodyView);
        head.setOnClickListener(v -> {
            boolean open = bodyView.getVisibility() != View.VISIBLE;
            if (open && "Board logs".equals(name) && loadEvents != null) loadEvents.run();
            bodyView.setVisibility(open ? View.VISIBLE : View.GONE);
            chevron.setRotation(open ? 90 : 0);
        });
        return card;
    }

    private TextView logText(String s) {
        TextView t = Ui.text(c, s, 11, Ui.DIM, false);
        t.setTypeface(Typeface.MONOSPACE);
        t.setLineSpacing(Ui.dp(c, 3), 1f);
        t.setTextIsSelectable(true);
        t.setPadding(Ui.dp(c, 10), Ui.dp(c, 10), Ui.dp(c, 10), Ui.dp(c, 10));
        return t;
    }

    /** A log in its own scrolling box (it scrolls by itself inside the scrolling screen). */
    private ScrollView logBox(TextView text) {
        ScrollView box = new ScrollView(c);
        box.setBackground(Ui.shape(c, 0xFF0C0D0F, 10));
        box.addView(text);
        box.setOnTouchListener((v, e) -> {
            v.getParent().requestDisallowInterceptTouchEvent(true);
            return false;
        });
        return box;
    }

    private void copy(CharSequence text) {
        ((ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("PrintHost log", text));
        Toast.makeText(c, "Copied", Toast.LENGTH_SHORT).show();
    }

    /** The two kinds of board logs, as in the dashboard: the live events and the log files on the board's card. */
    private View logsBody() {
        LinearLayout box = Ui.col(c);
        box.setPadding(0, 0, 0, Ui.dp(c, 12));
        LinearLayout tabs = Ui.row(c);
        final TextView events = Ui.button(c, "Events", Ui.BTN, Ui.TEXT), files = Ui.button(c, "Files", Ui.BTN, Ui.TEXT);
        events.setMinHeight(0);
        files.setMinHeight(0);
        tabs.addView(events, Ui.lp(0, Ui.dp(c, 40), 1));
        tabs.addView(files, Ui.lp(c, 0, Ui.dp(c, 40), 1, 8, 0, 0, 0));
        box.addView(tabs);

        final LinearLayout eventsPane = Ui.col(c);
        final TextView eventsText = logText("");
        eventsPane.addView(logBox(eventsText), Ui.lp(c, Ui.FILL, Ui.dp(c, 200), 0, 0, 10, 0, 0));
        TextView copyEvents = Ui.button(c, "Copy", Ui.BTN, Ui.TEXT);
        copyEvents.setOnClickListener(v -> copy(eventsText.getText()));
        eventsPane.addView(copyEvents, Ui.lp(c, Ui.FILL, Ui.dp(c, 44), 0, 0, 8, 0, 0));
        box.addView(eventsPane);

        final LinearLayout filesPane = Ui.col(c);
        filesPane.setVisibility(View.GONE);
        box.addView(filesPane, Ui.lp(c, Ui.FILL, Ui.WRAP, 0, 0, 4, 0, 0));

        // read from the board each time the tab is opened
        loadEvents = () -> {
            eventsText.setText("Loading…");
            m.events(eventsText::setText);
        };
        final Runnable loadFiles = () -> m.logFiles(list -> {
            filesPane.removeAllViews();
            for (int k = 0; k < list.length; k++) {
                final String name = list[k][0];
                if (k > 0) filesPane.addView(divider());
                LinearLayout r = line(name, Ui.text(c, list[k][1], 13, Ui.DIM, false));
                r.setOnClickListener(v -> showFile(name));
                filesPane.addView(r);
            }
            if (list.length == 0) filesPane.addView(line("The board does not answer", null));
        });

        View.OnClickListener pick = v -> {
            boolean ev = v == events;
            if (ev) loadEvents.run();
            else loadFiles.run();
            eventsPane.setVisibility(ev ? View.VISIBLE : View.GONE);
            filesPane.setVisibility(ev ? View.GONE : View.VISIBLE);
            events.setAlpha(ev ? 1f : 0.5f);
            files.setAlpha(ev ? 0.5f : 1f);
        };
        events.setOnClickListener(pick);
        files.setOnClickListener(pick);
        files.setAlpha(0.5f);
        return box;
    }

    /** One log file on its own screen: its name on top, the text (scrolls, can be selected), Copy and Close. */
    private void showFile(String name) {
        final Dialog d = new Dialog(c, android.R.style.Theme_Material_NoActionBar);
        LinearLayout col = Ui.col(c);
        col.setBackgroundColor(Ui.BG);
        col.setPadding(Ui.dp(c, 16), Ui.dp(c, 18), Ui.dp(c, 16), Ui.dp(c, 14));
        col.addView(title(name));
        final TextView text = logText("Loading…");
        m.logFile(name, text::setText);
        col.addView(logBox(text), Ui.lp(c, Ui.FILL, 0, 1, 0, 12, 0, 12));
        LinearLayout row = Ui.row(c);
        TextView copy = Ui.button(c, "Copy", Ui.BTN, Ui.TEXT), close = Ui.button(c, "Close", Ui.BTN, Ui.TEXT);
        copy.setOnClickListener(v -> copy(text.getText()));
        close.setOnClickListener(v -> d.dismiss());
        row.addView(copy, Ui.lp(0, Ui.dp(c, 48), 1));
        row.addView(close, Ui.lp(c, 0, Ui.dp(c, 48), 1, 10, 0, 0, 0));
        col.addView(row);
        d.setContentView(col);
        Window w = d.getWindow();
        if (w != null) {
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            w.setStatusBarColor(Ui.BG);
            w.setNavigationBarColor(Ui.BG);
        }
        d.show();
    }

    @Override
    void refresh() {
        if (link == null) return;
        printerName.setText(m.printerName);
        firmware.setText(m.firmware);
        dashboard.setText(m.dashboardUrl);
        StringBuilder key = new StringBuilder();
        for (String[] v : m.info) key.append(v[0]).append(v[1]);
        if (!key.toString().equals(infoShown)) {
            infoShown = key.toString();
            infoRows.removeAllViews();
            for (String[] v : m.info) {
                infoRows.addView(divider());
                infoRows.addView(line(v[0], Ui.text(c, v[1], 13, Ui.DIM, false)));
            }
        }
        link.setChecked(m.connected);
        link.setEnabled(m.power);
        power.setText(m.power ? "Power off" : "Power on");
        power.setTextColor(m.power ? Ui.RED_T : Ui.ON_GREEN);
        power.setBackground(Ui.pressable(c, m.power ? Ui.RED_BG : Ui.GREEN, 12));
    }
}
