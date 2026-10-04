package dev.oleksandr.printhost.panel;

import dev.oleksandr.printhost.R;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

/** Moving the axes: the round X/Y pad with Home in the middle, Z beside it, motors off. Step sizes: Printer.jogStep(). */
final class ControlScreen extends Screen {
    private final TextView[] pos = new TextView[3];
    private JogPad pad;
    private TextView zUp, zDown, motorsOff;

    ControlScreen(Context c, Printer m, Runnable changed) {
        super(c, m, changed);
    }

    @Override
    View build() {
        ScrollView sv = new ScrollView(c);
        LinearLayout b = body(sv);
        b.addView(title("Control"));

        LinearLayout coords = Ui.card(c);
        coords.setOrientation(LinearLayout.HORIZONTAL);
        String[] axes = {"X", "Y", "Z"};
        for (int i = 0; i < 3; i++) {
            LinearLayout col = Ui.col(c);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            col.addView(Ui.text(c, axes[i], 13, Ui.DIM, false), new LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP));
            pos[i] = Ui.text(c, "", 20, Ui.TEXT, true);
            col.addView(pos[i], Ui.lp(c, Ui.WRAP, Ui.WRAP, 0, 0, 3, 0, 0));
            coords.addView(col, Ui.lp(0, Ui.WRAP, 1));
        }
        b.addView(coords, block());

        LinearLayout row = Ui.row(c);
        row.setGravity(Gravity.CENTER);
        pad = new JogPad(c);
        pad.listener = new JogPad.Listener() {
            @Override
            public void onStep(char axis, int dir, int held) {
                m.move(axis, dir * Printer.jogStep(axis, held));
                refresh();
            }

            @Override
            public void onRelease() {
                m.stopJog();
            }

            @Override
            public void onHome() {
                m.home();
                changed.run();
            }
        };
        row.addView(pad, new LinearLayout.LayoutParams(Ui.dp(c, 232), Ui.dp(c, 232)));

        LinearLayout zc = Ui.col(c);
        zUp = Ui.button(c, "Z+", Ui.BTN, Ui.TEXT);
        zDown = Ui.button(c, "Z−", Ui.BTN, Ui.TEXT);
        Ui.repeatWhileHeld(zUp, held -> {
            m.move('Z', Printer.jogStep('Z', held));
            refresh();
        }, m::stopJog);
        Ui.repeatWhileHeld(zDown, held -> {
            m.move('Z', -Printer.jogStep('Z', held));
            refresh();
        }, m::stopJog);
        TextView zl = Ui.text(c, "Z", 13, Ui.DIM, false);
        zl.setGravity(Gravity.CENTER);
        zc.addView(zUp, new LinearLayout.LayoutParams(Ui.dp(c, 64), Ui.dp(c, 88)));
        zc.addView(zl, new LinearLayout.LayoutParams(Ui.dp(c, 64), Ui.dp(c, 40)));
        zc.addView(zDown, new LinearLayout.LayoutParams(Ui.dp(c, 64), Ui.dp(c, 88)));
        row.addView(zc, Ui.lp(c, Ui.WRAP, Ui.WRAP, 0, 22, 0, 0, 0));
        b.addView(row, Ui.lp(c, Ui.FILL, Ui.WRAP, 0, 0, 20, 0, 8));

        motorsOff = Ui.ghost(c, "Motors off");
        motorsOff.setOnClickListener(v -> {
            m.toggleMotors();
            changed.run();
        });
        b.addView(motorsOff, block(48));
        b.addView(spring());
        return sv;
    }

    @Override
    void refresh() {
        if (pad == null) return;
        double[] v = {m.x, m.y, m.z};
        for (int i = 0; i < 3; i++) pos[i].setText(m.homed ? String.format(Locale.US, "%.1f", v[i]) : "–");
        boolean ok = m.manualOk();
        boolean jog = ok || m.jogging;  // also before homing; a move of ours on its way does not lock the buttons
        pad.setState(jog, ok, m.homing ? "Homing" : "Home");
        pad.setAlpha(m.homing ? 0.38f : 1f);  // nothing can be pressed until the printer has finished homing
        Ui.enabled(zUp, jog);
        Ui.enabled(zDown, jog);
        motorsOff.setText(m.motorsOn ? "Motors off" : "Motors on");
        Ui.enabled(motorsOff, ok);
    }
}
