package dev.oleksandr.printhost.panel;

import dev.oleksandr.printhost.R;

import android.app.Dialog;
import android.content.Context;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/** Bottom sheet for one value: minus, the value, plus. Opened by tapping a tile on the Print screen. */
final class AdjustSheet {
    static final int NOZZLE = 0, BED = 1, FAN = 2, SPEED = 3, FLOW = 4, Z_OFFSET = 5;
    private static final String[] TITLE = {"Nozzle temperature", "Bed temperature", "Fan", "Print speed", "Flow", "Z offset"};

    private AdjustSheet() {
    }

    static String value(Printer m, int kind) {
        switch (kind) {
            case NOZZLE: return m.nozzleTarget > 0 ? Math.round(m.nozzleTarget) + "°" : "Off";
            case BED: return m.bedTarget > 0 ? Math.round(m.bedTarget) + "°" : "Off";
            case FAN: return m.fan > 0 ? m.fan + "%" : "Off";
            case SPEED: return m.speed + "%";
            case FLOW: return m.flow + "%";
            default: return String.format(Locale.US, "%.2f", m.zOffset).replace('-', '−');
        }
    }

    /** One press of minus (dir -1) or plus (dir +1). A heater that is off starts at the chosen material's temperature. */
    static void step(Printer m, int kind, int dir) {
        switch (kind) {
            case NOZZLE: {
                double t = m.nozzleTarget <= 0 ? (dir > 0 ? m.matNozzle[m.material] : 0) : Math.min(260, m.nozzleTarget + 5 * dir);
                m.setNozzleTarget(t < 150 ? 0 : t);
                break;
            }
            case BED: {
                double t = m.bedTarget <= 0 ? (dir > 0 ? m.matBed[m.material] : 0) : Math.min(100, m.bedTarget + dir);
                m.setBedTarget(t < 30 ? 0 : t);
                break;
            }
            case FAN: m.setFan((int) Printer.clamp(m.fan + 5 * dir, 0, 100)); break;
            case SPEED: m.setSpeed((int) Printer.clamp(m.speed + 5 * dir, 10, 300)); break;
            case FLOW: m.setFlow((int) Printer.clamp(m.flow + dir, 50, 200)); break;
            default: m.nudgeZOffset(dir); break;
        }
    }

    interface Value {
        String text();

        /** dir -1 / +1: one press of minus / plus. */
        void step(int dir);
    }

    static void show(Context c, final Printer m, final int kind, final Runnable onChange) {
        show(c, TITLE[kind], new Value() {
            @Override
            public String text() {
                return value(m, kind);
            }

            @Override
            public void step(int dir) {
                AdjustSheet.step(m, kind, dir);
            }
        }, onChange, null);
    }

    /** One point of the bed mesh: 0.01 mm per press; stored in the printer when the sheet closes, if it was changed. */
    static void showMeshPoint(Context c, final Printer m, final int row, final int column, final Runnable onChange) {
        final double before = m.mesh[row][column];
        show(c, "Bed point", new Value() {
            @Override
            public String text() {
                double v = m.mesh[row][column];
                return (v > 0 ? "+" : v < 0 ? "\u2212" : "") + String.format(Locale.US, "%.2f", Math.abs(v));
            }

            @Override
            public void step(int dir) {
                m.mesh[row][column] = Printer.clamp(Math.round((m.mesh[row][column] + 0.01 * dir) * 100) / 100.0, -2, 2);
            }
        }, onChange, () -> {
            if (Math.abs(m.mesh[row][column] - before) > 0.0005) m.saveMeshPoint(row, column);
        });
    }

    private static void show(Context c, String title, final Value value, final Runnable onChange, final Runnable onClose) {
        // A full-screen dialog of our own: the dark area above the sheet closes it on a tap (not on a swipe), the sheet
        // itself swallows touches.
        final Dialog d = new Dialog(c, android.R.style.Theme_Translucent_NoTitleBar);
        FrameLayout root = new FrameLayout(c);
        View scrim = new View(c);
        scrim.setBackgroundColor(0x9E08090A);
        final GestureDetector tap = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                d.dismiss();
                return true;
            }
        });
        scrim.setOnTouchListener((v, e) -> {
            tap.onTouchEvent(e);
            return true;
        });
        root.addView(scrim, new FrameLayout.LayoutParams(Ui.FILL, Ui.FILL));

        LinearLayout sheet = Ui.col(c);
        sheet.setClickable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.CARD);
        float r = Ui.dp(c, 22);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        sheet.setBackground(bg);
        sheet.setPadding(Ui.dp(c, 18), Ui.dp(c, 18), Ui.dp(c, 18), Ui.dp(c, 22));

        sheet.addView(Ui.text(c, title, 18, Ui.TEXT, true), Ui.lp(c, Ui.FILL, Ui.WRAP, 0, 0, 0, 0, 14));

        LinearLayout row = Ui.row(c);
        TextView minus = Ui.button(c, "\u2212", Ui.BTN, Ui.TEXT);
        minus.setTextSize(24);
        TextView plus = Ui.button(c, "+", Ui.BTN, Ui.TEXT);
        plus.setTextSize(24);
        final TextView val = Ui.text(c, value.text(), 44, Ui.TEXT, true);
        val.setGravity(Gravity.CENTER);
        row.addView(minus, new LinearLayout.LayoutParams(Ui.dp(c, 64), Ui.dp(c, 56)));
        row.addView(val, Ui.lp(0, Ui.WRAP, 1));
        row.addView(plus, new LinearLayout.LayoutParams(Ui.dp(c, 64), Ui.dp(c, 56)));
        sheet.addView(row);

        minus.setOnClickListener(v -> {
            value.step(-1);
            val.setText(value.text());
            onChange.run();
        });
        plus.setOnClickListener(v -> {
            value.step(1);
            val.setText(value.text());
            onChange.run();
        });
        if (onClose != null) d.setOnDismissListener(dialog -> {
            onClose.run();
            onChange.run();
        });

        TextView done = Ui.button(c, "Done", Ui.GREEN, Ui.ON_GREEN);
        done.setOnClickListener(v -> d.dismiss());
        sheet.addView(done, Ui.lp(c, Ui.FILL, Ui.dp(c, 48), 0, 0, 16, 0, 0));
        root.addView(sheet, new FrameLayout.LayoutParams(Ui.FILL, Ui.WRAP, Gravity.BOTTOM));

        d.setContentView(root);
        Window w = d.getWindow();
        if (w != null) {
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            w.setNavigationBarColor(Ui.CARD);
        }
        d.show();
    }
}
