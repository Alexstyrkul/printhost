package dev.oleksandr.printhost.panel;

import dev.oleksandr.printhost.R;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Colours and the few view builders every screen uses (the look of the "PrintHost Panel" mockup). */
final class Ui {
    static final int BG = 0xFF141518, CARD = 0xFF1E2024, BTN = 0xFF2A2D32, LINE = 0xFF2A2D32, TRACK = 0xFF3A3E45;
    static final int TEXT = 0xFFECEEF1, DIM = 0xFF9CA1A9, FAINT = 0xFF5D626A;
    static final int GREEN = 0xFF22C063, GREEN_T = 0xFF4FD58A, GREEN_BG = 0xFF12301F, ON_GREEN = 0xFF06210F;
    static final int HOT = 0xFFFF9A57, COLD = 0xFF6FB2FF, RED_BG = 0xFF3A1A1C, RED_T = 0xFFFF9C9C, YELLOW = 0xFFF0B90B, RED = 0xFFFF6B6B;
    static final int WRAP = LinearLayout.LayoutParams.WRAP_CONTENT, FILL = LinearLayout.LayoutParams.MATCH_PARENT;

    private Ui() {
    }

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    static GradientDrawable shape(Context c, int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    static Drawable pressable(Context c, int color, float radiusDp) {
        return new RippleDrawable(ColorStateList.valueOf(0x30FFFFFF), shape(c, color, radiusDp), shape(c, 0xFFFFFFFF, radiusDp));
    }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        t.setFontFeatureSettings("tnum");
        t.setIncludeFontPadding(false);
        return t;
    }

    static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout card(Context c) {
        LinearLayout l = col(c);
        l.setBackground(shape(c, CARD, 16));
        int p = dp(c, 14);
        l.setPadding(p, p, p, p);
        return l;
    }

    /** A button: a centred label on a rounded block. */
    static TextView button(Context c, String label, int bg, int fg) {
        TextView t = text(c, label, 15, fg, true);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(c, 48));
        t.setBackground(pressable(c, bg, 12));
        t.setClickable(true);
        return t;
    }

    static TextView ghost(Context c, String label) {
        TextView t = button(c, label, 0x00000000, TEXT);
        GradientDrawable d = shape(c, 0x00000000, 12);
        d.setStroke(dp(c, 1), TRACK);
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x30FFFFFF), d, shape(c, 0xFFFFFFFF, 12)));
        return t;
    }

    static ImageView icon(Context c, int res, int color, float sizeDp) {
        ImageView v = new ImageView(c);
        v.setImageResource(res);
        v.setColorFilter(color);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return v;
    }

    static LinearLayout.LayoutParams lp(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    /** Layout params with margins given in dp. */
    static LinearLayout.LayoutParams lp(Context c, int w, int h, float weight, float l, float t, float r, float b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h, weight);
        p.setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
        return p;
    }

    static View space(Context c, float hDp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(c, hDp)));
        return v;
    }

    static void enabled(View v, boolean on) {
        v.setEnabled(on);
        v.setAlpha(on ? 1f : 0.38f);
    }

    interface Held {
        /** held: how many repeats so far (0 = the press itself). */
        void step(int held);
    }

    /** Runs the action on touch down and keeps repeating it while the finger stays down; onRelease when it lifts. */
    static void repeatWhileHeld(final View v, final Held action, final Runnable onRelease) {
        final Handler h = new Handler(Looper.getMainLooper());
        final Runnable[] loop = new Runnable[1];
        final int[] held = {0};
        loop[0] = () -> {
            if (!v.isPressed() || !v.isEnabled()) return;
            action.step(++held[0]);
            h.postDelayed(loop[0], 80);
        };
        v.setOnTouchListener((view, e) -> {
            if (!v.isEnabled()) return true;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    held[0] = 0;
                    action.step(0);
                    h.postDelayed(loop[0], 400);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    h.removeCallbacks(loop[0]);
                    onRelease.run();
                    return true;
                default:
                    return true;
            }
        });
    }
}
