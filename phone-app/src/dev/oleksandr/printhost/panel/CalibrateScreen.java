package dev.oleksandr.printhost.panel;

import dev.oleksandr.printhost.R;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

/** Calibration: the Z offset and the bed mesh (back of the bed at the top, the front where you stand at the bottom). */
final class CalibrateScreen extends Screen {
    private TextView zVal, zMinus, zPlus, measure, level;
    private final TextView[][] cell = new TextView[4][4];

    CalibrateScreen(Context c, Printer m, Runnable changed) {
        super(c, m, changed);
    }

    @Override
    View build() {
        ScrollView sv = new ScrollView(c);
        LinearLayout b = body(sv);
        b.addView(title("Calibrate"));

        LinearLayout z = Ui.card(c);
        z.addView(Ui.text(c, "Z offset", 16, Ui.TEXT, true));
        LinearLayout row = Ui.row(c);
        zMinus = Ui.button(c, "−", Ui.BTN, Ui.TEXT);
        zMinus.setTextSize(24);
        zPlus = Ui.button(c, "+", Ui.BTN, Ui.TEXT);
        zPlus.setTextSize(24);
        zMinus.setOnClickListener(v -> {
            AdjustSheet.step(m, AdjustSheet.Z_OFFSET, -1);
            refresh();
        });
        zPlus.setOnClickListener(v -> {
            AdjustSheet.step(m, AdjustSheet.Z_OFFSET, 1);
            refresh();
        });
        LinearLayout mid = Ui.col(c);
        mid.setGravity(Gravity.CENTER_HORIZONTAL);
        zVal = Ui.text(c, "", 32, Ui.TEXT, true);
        zVal.setGravity(Gravity.CENTER);
        mid.addView(zVal);
        row.addView(zMinus, new LinearLayout.LayoutParams(Ui.dp(c, 72), Ui.dp(c, 56)));
        row.addView(mid, Ui.lp(0, Ui.WRAP, 1));
        row.addView(zPlus, new LinearLayout.LayoutParams(Ui.dp(c, 72), Ui.dp(c, 56)));
        z.addView(row, Ui.lp(c, Ui.FILL, Ui.WRAP, 0, 0, 12, 0, 0));
        measure = Ui.ghost(c, "Calibrate");
        measure.setOnClickListener(v -> {
            m.measureZOffset();
            changed.run();
        });
        z.addView(measure, Ui.lp(c, Ui.FILL, Ui.dp(c, 48), 0, 0, 12, 0, 0));
        b.addView(z, block());

        LinearLayout bed = Ui.card(c);
        bed.addView(Ui.text(c, "Bed leveling", 16, Ui.TEXT, true));
        LinearLayout.LayoutParams ip = Ui.lp(c, Ui.dp(c, 22), Ui.dp(c, 22), 0, 0, 10, 0, 8);
        ip.gravity = Gravity.CENTER_HORIZONTAL;
        View gantry = Ui.icon(c, R.drawable.ic_gantry, Ui.DIM, 22);
        gantry.setContentDescription("Back of the bed");
        bed.addView(gantry, ip);
        LinearLayout grid = Ui.col(c);
        for (int r = 0; r < 4; r++) {
            LinearLayout gr = Ui.row(c);
            for (int k = 0; k < 4; k++) {
                cell[r][k] = Ui.text(c, "", 14, Ui.TEXT, true);
                cell[r][k].setGravity(Gravity.CENTER);
                final int meshRow = r, meshColumn = k;
                cell[r][k].setOnClickListener(v -> {  // edit one point by hand
                    if (m.mesh != null && m.manualOk()) AdjustSheet.showMeshPoint(c, m, meshRow, meshColumn, this::refresh);
                });
                gr.addView(cell[r][k], Ui.lp(c, Ui.dp(c, 63), Ui.dp(c, 63), 0, k == 0 ? 0 : 6, 0, 0, 0));
            }
            grid.addView(gr, Ui.lp(c, Ui.WRAP, Ui.WRAP, 0, 0, r == 0 ? 0 : 6, 0, 0));
        }
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP);
        gp.gravity = Gravity.CENTER_HORIZONTAL;
        bed.addView(grid, gp);
        LinearLayout.LayoutParams pp = Ui.lp(c, Ui.dp(c, 22), Ui.dp(c, 22), 0, 0, 8, 0, 0);
        pp.gravity = Gravity.CENTER_HORIZONTAL;
        View person = Ui.icon(c, R.drawable.ic_person, Ui.DIM, 22);
        person.setContentDescription("Front of the bed");
        bed.addView(person, pp);
        level = Ui.button(c, "Calibrate", Ui.GREEN, Ui.ON_GREEN);
        level.setOnClickListener(v -> {
            m.levelBed();
            changed.run();
        });
        bed.addView(level, Ui.lp(c, Ui.FILL, Ui.dp(c, 48), 0, 0, 12, 0, 0));
        b.addView(bed, block());
        b.addView(spring());
        return sv;
    }

    @Override
    void refresh() {
        if (zVal == null) return;
        zVal.setText(AdjustSheet.value(m, AdjustSheet.Z_OFFSET));
        boolean ok = m.manualOk();
        Ui.enabled(zMinus, m.power && m.connected && !m.busy() && m.zOffsetKnown);
        Ui.enabled(zPlus, m.power && m.connected && !m.busy() && m.zOffsetKnown);
        measure.setVisibility(m.canMeasure ? View.VISIBLE : View.GONE);
        measure.setText(m.measuring ? "Calibrating…" : "Calibrate");
        Ui.enabled(measure, ok);
        level.setText(m.leveling ? "Calibrating…" : "Calibrate");
        Ui.enabled(level, ok);
        for (int r = 0; r < 4; r++) {
            for (int k = 0; k < 4; k++) {
                if (m.mesh == null) {
                    cell[r][k].setBackground(Ui.shape(c, Ui.BTN, 10));
                    cell[r][k].setText("–");
                    continue;
                }
                double v = m.mesh[r][k];
                int alpha = (int) (255 * (0.14 + 0.5 * Math.min(1, Math.abs(v) / 0.5)));
                int rgb = v < 0 ? 0x6FB2FF : 0xFF9A57;  // blue = lower, orange = higher
                cell[r][k].setBackground(Ui.shape(c, (alpha << 24) | rgb, 10));
                cell[r][k].setText((v > 0 ? "+" : v < 0 ? "−" : "") + String.format(Locale.US, "%.2f", Math.abs(v)));
            }
        }
    }
}
