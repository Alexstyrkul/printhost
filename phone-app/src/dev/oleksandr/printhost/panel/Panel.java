package dev.oleksandr.printhost.panel;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import dev.oleksandr.printhost.R;

/**
 * The phone on the printer as its control panel (instead of the printer's own screen): five tabs - Print, Control,
 * Filament, Calibrate, Settings. Built into an activity with attach(); the activity forwards resume() and pause().
 */
public final class Panel {
    private static final String[] TAB = {"Print", "Control", "Filament", "Calibrate", "Settings"};
    private static final int[] TAB_ICON = {R.drawable.ic_print, R.drawable.ic_control, R.drawable.ic_filament, R.drawable.ic_calibrate,
            R.drawable.ic_settings};

    private final Activity a;
    private final Printer printer;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Screen[] screens;
    private FrameLayout content;
    private TextView banner;
    private final ImageView[] tabIcon = new ImageView[5];
    private final TextView[] tabText = new TextView[5];
    private int current = -1;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            printer.tick();
            refresh();
            handler.postDelayed(this, 500);
        }
    };

    /** mock: pretend values instead of the real printer (for looking at the screens without one). */
    public Panel(Activity a, boolean mock) {
        this.a = a;
        Runnable changed = this::refresh;
        printer = mock ? new Mock(a) : new Live(a, changed);
        screens = new Screen[]{new PrintScreen(a, printer, changed), new ControlScreen(a, printer, changed), new FilamentScreen(a, printer, changed),
                new CalibrateScreen(a, printer, changed), new SettingsScreen(a, printer, changed)};
    }

    public void attach() {
        a.getWindow().setStatusBarColor(Ui.BG);
        a.getWindow().setNavigationBarColor(0xFF1A1B1F);
        LinearLayout root = Ui.col(a);
        root.setBackgroundColor(Ui.BG);
        // one plate on every screen while the printer cannot be used
        banner = Ui.text(a, "", 14, Ui.RED_T, true);
        banner.setGravity(Gravity.CENTER);
        banner.setBackground(Ui.shape(a, Ui.RED_BG, 12));
        banner.setVisibility(View.GONE);
        root.addView(banner, Ui.lp(a, Ui.FILL, Ui.dp(a, 38), 0, 16, 10, 16, 0));
        content = new FrameLayout(a);
        root.addView(content, Ui.lp(Ui.FILL, 0, 1));
        View line = new View(a);
        line.setBackgroundColor(0xFF26282D);
        root.addView(line, new LinearLayout.LayoutParams(Ui.FILL, Ui.dp(a, 1)));

        LinearLayout tabs = Ui.row(a);
        tabs.setBackgroundColor(0xFF1A1B1F);
        tabs.setPadding(Ui.dp(a, 6), Ui.dp(a, 6), Ui.dp(a, 6), Ui.dp(a, 8));
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            LinearLayout t = Ui.col(a);
            t.setGravity(Gravity.CENTER);
            t.setMinimumHeight(Ui.dp(a, 48));
            tabIcon[i] = Ui.icon(a, TAB_ICON[i], Ui.DIM, 22);
            tabText[i] = Ui.text(a, TAB[i], 11, Ui.DIM, true);
            t.addView(tabIcon[i]);
            t.addView(tabText[i], Ui.lp(a, Ui.WRAP, Ui.WRAP, 0, 0, 3, 0, 0));
            t.setOnClickListener(v -> show(idx));
            tabs.addView(t, Ui.lp(0, Ui.WRAP, 1));
        }
        root.addView(tabs);
        a.setContentView(root);
        show(0);
    }

    private void show(int idx) {
        if (idx == current) return;
        current = idx;
        content.removeAllViews();
        content.addView(screens[idx].view());
        for (int i = 0; i < 5; i++) {
            int col = i == idx ? Ui.GREEN_T : 0xFF8D929A;
            tabIcon[i].setColorFilter(col);
            tabText[i].setTextColor(col);
        }
        refresh();
    }

    private void refresh() {
        if (banner != null) {
            String text = !printer.power ? "Printer is off" : !printer.connected ? "Printer is not connected" : printer.simulator ? "Simulator" : "";
            boolean warn = printer.power && printer.connected;  // the simulator notice is yellow, the problems are red
            banner.setTextColor(warn ? Ui.YELLOW : Ui.RED_T);
            banner.setBackground(Ui.shape(a, warn ? 0xFF3D310A : Ui.RED_BG, 12));
            banner.setText(text);
            banner.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        }
        if (current >= 0) screens[current].refresh();
    }

    public void resume() {
        PreviewView p = ((PrintScreen) screens[0]).preview;
        if (p != null) p.onResume();
        handler.removeCallbacks(tick);
        handler.post(tick);
        touched();
    }

    private static final long KEEP_ON_MS = 5 * 60 * 1000L;
    private final Runnable letScreenSleep = new Runnable() {
        @Override
        public void run() {
            a.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    };

    /** Any touch keeps the screen on for five more minutes; after that the phone's own timeout switches it off.
     *  Only the screen sleeps: the service keeps running. */
    public void touched() {
        a.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        handler.removeCallbacks(letScreenSleep);
        handler.postDelayed(letScreenSleep, KEEP_ON_MS);
    }

    public void pause() {
        handler.removeCallbacks(tick);
        PreviewView p = ((PrintScreen) screens[0]).preview;
        if (p != null) p.onPause();
    }
}
