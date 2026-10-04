package dev.oleksandr.printhost.panel;

import dev.oleksandr.printhost.R;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;

/**
 * Round pad for X and Y: four wedges (Y+ top, X+ right, Y- bottom, X- left) around a Home button.
 * A tap sends one step; holding repeats single steps and stops the moment the finger lifts.
 */
final class JogPad extends View {
    interface Listener {
        /** held: how many repeats the held wedge has made so far (0 = the press itself). */
        void onStep(char axis, int dir, int held);

        /** The finger is off. */
        void onRelease();

        void onHome();
    }

    private static final String[] LABEL = {"Y+", "X+", "Y−", "X−"};
    private static final char[] AXIS = {'Y', 'X', 'Y', 'X'};
    private static final int[] DIR = {1, 1, -1, -1};

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF outer = new RectF(), inner = new RectF();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int pressed = -1;  // 0-3 = wedge, 4 = Home, -1 = none
    private int held = 0;
    private boolean movesEnabled = false, homeEnabled = true;
    private String homeLabel = "Home";
    Listener listener;

    private final Runnable repeat = new Runnable() {
        @Override
        public void run() {
            if (pressed < 0 || pressed > 3 || !movesEnabled) return;
            held++;
            if (listener != null) listener.onStep(AXIS[pressed], DIR[pressed], held);
            handler.postDelayed(this, 80);
        }
    };

    JogPad(Context c) {
        super(c);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        paint.setTextAlign(Paint.Align.CENTER);
    }

    void setState(boolean movesEnabled, boolean homeEnabled, String homeLabel) {
        if (this.movesEnabled == movesEnabled && this.homeEnabled == homeEnabled && this.homeLabel.equals(homeLabel)) return;
        this.movesEnabled = movesEnabled;
        this.homeEnabled = homeEnabled;
        this.homeLabel = homeLabel;
        invalidate();
    }

    private float centerRadius() {
        return getWidth() * 0.19f;
    }

    private int zoneAt(float x, float y) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f, dx = x - cx, dy = y - cy;
        double r = Math.sqrt(dx * dx + dy * dy);
        if (r <= centerRadius()) return 4;
        if (r > getWidth() / 2f) return -1;
        double a = Math.toDegrees(Math.atan2(dy, dx));  // 0 = right, 90 = down
        if (a >= -135 && a < -45) return 0;
        if (a >= -45 && a < 45) return 1;
        if (a >= 45 && a < 135) return 2;
        return 3;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                pressed = zoneAt(e.getX(), e.getY());
                if (pressed >= 0 && pressed <= 3 && movesEnabled) {
                    held = 0;
                    if (listener != null) listener.onStep(AXIS[pressed], DIR[pressed], 0);
                    handler.postDelayed(repeat, 400);
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                if (pressed == 4 && homeEnabled && zoneAt(e.getX(), e.getY()) == 4 && listener != null) listener.onHome();
                // fall through: the finger is off, nothing more moves
            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(repeat);
                if (pressed >= 0 && pressed <= 3 && listener != null) listener.onRelease();
                pressed = -1;
                invalidate();
                return true;
            default:
                return true;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        handler.removeCallbacks(repeat);
        pressed = -1;
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), cx = w / 2f, cy = getHeight() / 2f, r = w / 2f, r0 = centerRadius() + Ui.dp(getContext(), 4);
        outer.set(cx - r, cy - r, cx + r, cy + r);
        inner.set(cx - r0, cy - r0, cx + r0, cy + r0);
        float gap = 1.6f;
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 4; i++) {
            float start = -135 + i * 90 + gap, sweep = 90 - 2 * gap;
            path.reset();
            path.arcTo(outer, start, sweep, true);
            path.arcTo(inner, start + sweep, -sweep, false);
            path.close();
            paint.setColor(pressed == i && movesEnabled ? 0xFF3A3E45 : Ui.BTN);
            c.drawPath(path, paint);
            double mid = Math.toRadians(-90 + i * 90);
            float lr = (r + r0) / 2f;
            paint.setColor(movesEnabled ? Ui.TEXT : Ui.FAINT);
            paint.setTextSize(Ui.dp(getContext(), 17));
            c.drawText(LABEL[i], cx + (float) (lr * Math.cos(mid)), cy + (float) (lr * Math.sin(mid)) + Ui.dp(getContext(), 6), paint);
        }
        paint.setColor(!homeEnabled ? 0xFF1B5E3A : pressed == 4 ? 0xFF1CA254 : Ui.GREEN);
        c.drawCircle(cx, cy, centerRadius(), paint);
        paint.setColor(Ui.ON_GREEN);
        paint.setTextSize(Ui.dp(getContext(), 14));
        c.drawText(homeLabel, cx, cy + Ui.dp(getContext(), 5), paint);
    }
}
