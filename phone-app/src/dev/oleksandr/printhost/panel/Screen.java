package dev.oleksandr.printhost.panel;

import dev.oleksandr.printhost.R;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** One tab of the panel. refresh() is called twice a second and after every action. */
abstract class Screen {
    final Context c;
    final Printer m;
    final Runnable changed;  // call after changing the mock so the visible screen redraws at once
    private View root;

    Screen(Context c, Printer m, Runnable changed) {
        this.c = c;
        this.m = m;
        this.changed = changed;
    }

    final View view() {
        if (root == null) root = build();
        return root;
    }

    abstract View build();

    abstract void refresh();

    /** The scrolling body every screen sits in: the mockup's 16 dp side padding, blocks 12 dp apart. */
    LinearLayout body(ScrollView into) {
        LinearLayout b = Ui.col(c);
        b.setPadding(Ui.dp(c, 16), Ui.dp(c, 18), Ui.dp(c, 16), Ui.dp(c, 14));
        into.setFillViewport(true);
        into.setVerticalScrollBarEnabled(false);
        into.setOverScrollMode(View.OVER_SCROLL_NEVER);
        into.addView(b, new ScrollView.LayoutParams(Ui.FILL, Ui.WRAP));
        return b;
    }

    /** A block with the standard 12 dp gap above it. */
    LinearLayout.LayoutParams block() {
        return Ui.lp(c, Ui.FILL, Ui.WRAP, 0, 0, 12, 0, 0);
    }

    LinearLayout.LayoutParams block(float heightDp) {
        return Ui.lp(c, Ui.FILL, Ui.dp(c, heightDp), 0, 0, 12, 0, 0);
    }

    /** Pushes whatever follows to the bottom of the screen. */
    View spring() {
        View v = new View(c);
        v.setLayoutParams(Ui.lp(1, 0, 1));
        return v;
    }

    TextView title(String s) {
        TextView t = Ui.text(c, s, 20, Ui.TEXT, true);
        t.setMinHeight(Ui.dp(c, 32));
        t.setGravity(android.view.Gravity.CENTER_VERTICAL);
        return t;
    }

    /** Thin progress bar: a rounded track with a filled part. */
    static final class Bar extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float fraction;
        private final int track, fill;

        Bar(Context c, int track, int fill) {
            super(c);
            this.track = track;
            this.fill = fill;
        }

        void set(double f) {
            float v = (float) Math.max(0, Math.min(1, f));
            if (v == fraction) return;
            fraction = v;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float h = getHeight(), w = getWidth(), r = h / 2;
            p.setColor(track);
            canvas.drawRoundRect(0, 0, w, h, r, r, p);
            if (fraction > 0) {
                p.setColor(fill);
                canvas.drawRoundRect(0, 0, Math.max(h, w * fraction), h, r, r, p);
            }
        }
    }
}
