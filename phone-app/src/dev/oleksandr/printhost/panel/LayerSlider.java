package dev.oleksandr.printhost.panel;

import dev.oleksandr.printhost.R;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/** Vertical slider beside the preview: picks the layer up to which the model is shown as printed. */
final class LayerSlider extends View {
    interface Listener {
        void onLayer(int layer, boolean byUser);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int max = 1, value = 0, live = -1;  // live = the layer being printed now (-1 = no mark)
    Listener listener;

    LayerSlider(Context c) {
        super(c);
    }

    void setMax(int m) {
        max = Math.max(1, m);
        invalidate();
    }

    void setValue(int v) {
        v = Math.max(0, Math.min(max, v));
        if (v == value) return;
        value = v;
        invalidate();
    }

    void setLive(int layer) {
        if (layer == live) return;
        live = layer;
        invalidate();
    }

    int value() {
        return value;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        getParent().requestDisallowInterceptTouchEvent(true);
        float pad = Ui.dp(getContext(), 12), h = getHeight() - 2 * pad;
        float t = 1 - Math.max(0, Math.min(1, (e.getY() - pad) / Math.max(1, h)));
        int v = Math.round(t * max);
        // Sticks to the layer being printed while the finger is close to it, lets go when dragged further.
        if (live >= 0 && Math.abs(v - live) * h / max < Ui.dp(getContext(), 12)) v = live;
        setValue(v);
        if (listener != null) listener.onLayer(value, true);
        return true;
    }

    @Override
    protected void onDraw(Canvas c) {
        float pad = Ui.dp(getContext(), 12), cx = getWidth() / 2f, top = pad, bottom = getHeight() - pad;
        float y = bottom - (bottom - top) * value / max;
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(Ui.dp(getContext(), 4));
        paint.setColor(Ui.TRACK);
        c.drawLine(cx, top, cx, bottom, paint);
        float liveY = live < 0 ? y : bottom - (bottom - top) * Math.min(live, max) / max;
        paint.setColor(Ui.GREEN);
        c.drawLine(cx, liveY, cx, bottom, paint);
        paint.setColor(Ui.TEXT);
        c.drawCircle(cx, y, Ui.dp(getContext(), 9), paint);
        if (live >= 0) {  // the current layer: a green dot with a dark rim, drawn over the thumb when they coincide
            paint.setColor(Ui.CARD);
            c.drawCircle(cx, liveY, Ui.dp(getContext(), 6), paint);
            paint.setColor(Ui.GREEN);
            c.drawCircle(cx, liveY, Ui.dp(getContext(), 4), paint);
        }
    }
}
