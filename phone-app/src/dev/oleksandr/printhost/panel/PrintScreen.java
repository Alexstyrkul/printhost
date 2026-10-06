package dev.oleksandr.printhost.panel;

import dev.oleksandr.printhost.R;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** The main screen: what is printing (3D preview, progress, the values that can be tuned) and the board's state. */
final class PrintScreen extends Screen {
    private TextView pill, fileName, percent, left, ends, layerLabel, pauseBtn, startBtn;
    private LinearLayout filesBox;
    private String filesShown = null;
    private final TextView[] boardValue = new TextView[7];
    private String loadedKey = "";
    private boolean loading = false;
    private final TextView[] tileValue = new TextView[6];
    private View printingBlock, buttonsRow, idleBox, lastCard, boardDot;
    private Bar bar;
    PreviewView preview;
    private LayerSlider slider;
    private GcodeModel model;
    private long userLayerUntil = 0;

    PrintScreen(Context c, Printer m, Runnable changed) {
        super(c, m, changed);
    }

    @Override
    View build() {
        ScrollView sv = new ScrollView(c);
        LinearLayout b = body(sv);

        LinearLayout head = Ui.row(c);
        head.addView(title("Ender-3 V3 SE"), Ui.lp(0, Ui.WRAP, 1));
        pill = Ui.text(c, "", 12, Ui.GREEN_T, true);
        pill.setPadding(Ui.dp(c, 11), Ui.dp(c, 5), Ui.dp(c, 11), Ui.dp(c, 5));
        head.addView(pill);
        b.addView(head);

        // The preview card. The GL view sits inside the card's padding and clears to the card's colour.
        FrameLayout card = new FrameLayout(c);
        card.setBackground(Ui.shape(c, Ui.CARD, 16));
        int pad = Ui.dp(c, 8);
        card.setPadding(pad, pad, pad, pad);
        preview = new PreviewView(c);
        FrameLayout.LayoutParams pv = new FrameLayout.LayoutParams(Ui.FILL, Ui.FILL);
        pv.rightMargin = Ui.dp(c, 34);
        card.addView(preview, pv);
        slider = new LayerSlider(c);
        slider.listener = (layer, byUser) -> {
            userLayerUntil = System.currentTimeMillis() + 8000;
            showLayer(layer);
        };
        card.addView(slider, new FrameLayout.LayoutParams(Ui.dp(c, 36), Ui.FILL, Gravity.END));
        layerLabel = Ui.text(c, "", 13, Ui.TEXT, true);
        FrameLayout.LayoutParams ll = new FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.BOTTOM | Gravity.START);
        ll.leftMargin = Ui.dp(c, 6);
        ll.bottomMargin = Ui.dp(c, 4);
        card.addView(layerLabel, ll);

        LinearLayout idle = Ui.col(c);
        idle.setGravity(Gravity.CENTER);
        idle.setBackground(Ui.shape(c, Ui.CARD, 12));
        idle.addView(Ui.icon(c, R.drawable.ic_print, Ui.DIM, 44));
        idle.addView(Ui.text(c, "Nothing is printing", 15, Ui.TEXT, true), Ui.lp(c, Ui.WRAP, Ui.WRAP, 0, 0, 10, 0, 0));
        idle.setOnClickListener(v -> {  // demo only: brings the pretend print back
            m.startPrint();
            changed.run();
        });
        idleBox = idle;
        card.addView(idle, new FrameLayout.LayoutParams(Ui.FILL, Ui.FILL));
        b.addView(card, block(250));

        // While printing: file, progress, pause / stop
        LinearLayout pb = Ui.col(c);
        fileName = Ui.text(c, "", 15, Ui.TEXT, true);
        fileName.setSingleLine(true);
        fileName.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        pb.addView(fileName);
        LinearLayout pr = Ui.row(c);
        pr.setGravity(Gravity.BOTTOM);
        percent = Ui.text(c, "", 34, Ui.GREEN_T, true);
        pr.addView(percent, Ui.lp(0, Ui.WRAP, 1));
        left = labelled(pr, "Left");
        ends = labelled(pr, "Ends");
        pb.addView(pr, Ui.lp(c, Ui.FILL, Ui.WRAP, 0, 0, 6, 0, 0));
        bar = new Bar(c, Ui.BTN, Ui.GREEN);
        pb.addView(bar, Ui.lp(c, Ui.FILL, Ui.dp(c, 6), 0, 0, 8, 0, 0));
        printingBlock = pb;
        b.addView(pb, block());

        LinearLayout br = Ui.row(c);
        pauseBtn = Ui.button(c, "Pause", Ui.BTN, Ui.TEXT);
        pauseBtn.setOnClickListener(v -> {
            m.togglePause();
            changed.run();
        });
        TextView stop = Ui.button(c, "Stop", Ui.RED_BG, Ui.RED_T);
        stop.setOnClickListener(v -> new android.app.AlertDialog.Builder(c, android.R.style.Theme_Material_Dialog_Alert)
                .setMessage("Stop the print?")
                .setPositiveButton("Stop", (d, w) -> {
                    m.stopPrint();
                    changed.run();
                })
                .setNegativeButton("Cancel", null).show());
        br.addView(pauseBtn, Ui.lp(0, Ui.dp(c, 48), 1));
        br.addView(stop, Ui.lp(c, 0, Ui.dp(c, 48), 1, 10, 0, 0, 0));
        buttonsRow = br;
        b.addView(br, block());

        // Not printing: the files on the board's card. Tap one to choose it (its model shows above), then Start.
        LinearLayout fc = Ui.card(c);
        fc.addView(Ui.text(c, "Files", 16, Ui.TEXT, true));
        filesBox = Ui.col(c);
        fc.addView(filesBox, Ui.lp(c, Ui.FILL, Ui.WRAP, 0, 0, 6, 0, 0));
        startBtn = Ui.button(c, "Start", Ui.GREEN, Ui.ON_GREEN);
        startBtn.setOnClickListener(v -> new android.app.AlertDialog.Builder(c, android.R.style.Theme_Material_Dialog_Alert)
                .setMessage("Start printing " + m.lastFile + "?")
                .setPositiveButton("Start", (d, w) -> {
                    m.startPrint();
                    changed.run();
                })
                .setNegativeButton("Cancel", null).show());
        fc.addView(startBtn, Ui.lp(c, Ui.FILL, Ui.dp(c, 48), 0, 0, 10, 0, 0));
        lastCard = fc;
        b.addView(fc, block());

        String[] tiles = {"Nozzle", "Bed", "Fan", "Speed", "Flow", "Z offset"};
        for (int r = 0; r < 2; r++) {
            LinearLayout row = Ui.row(c);
            for (int i = 0; i < 3; i++) {
                final int kind = r * 3 + i;
                LinearLayout t = Ui.col(c);
                t.setBackground(Ui.pressable(c, Ui.CARD, 14));
                t.setPadding(Ui.dp(c, 12), Ui.dp(c, 11), Ui.dp(c, 12), Ui.dp(c, 11));
                t.addView(Ui.text(c, tiles[kind], 12, Ui.DIM, false));
                tileValue[kind] = Ui.text(c, "", 20, Ui.TEXT, true);
                tileValue[kind].setSingleLine(true);
                t.addView(tileValue[kind], Ui.lp(c, Ui.FILL, Ui.WRAP, 0, 0, 4, 0, 0));
                t.setOnClickListener(v -> {
                    if (kind == AdjustSheet.Z_OFFSET && !m.zOffsetKnown) return;  // not read from the printer yet
                    if (m.power && m.connected) AdjustSheet.show(c, m, kind, changed);
                });
                row.addView(t, Ui.lp(c, 0, Ui.WRAP, 1, i == 0 ? 0 : 10, 0, 0, 0));
            }
            b.addView(row, block());
        }

        b.addView(spring());

        LinearLayout board = Ui.card(c);
        LinearLayout bh = Ui.row(c);
        boardDot = new View(c);
        bh.addView(boardDot, new LinearLayout.LayoutParams(Ui.dp(c, 10), Ui.dp(c, 10)));
        bh.addView(Ui.text(c, "Board", 15, Ui.TEXT, true), Ui.lp(c, 0, Ui.WRAP, 1, 8, 0, 0, 0));

        board.addView(bh);
        // the same values as the dashboard's board line, in two rows
        String[] names = {"Chip", "CPU", "Wi-Fi", "Free RAM", "Resends", "Room", "Humidity"};
        for (int r = 0; r < 2; r++) {
            LinearLayout bs = Ui.row(c);
            for (int i = 0; i < 4; i++) {
                LinearLayout col = Ui.col(c);
                int k = r * 4 + i;
                if (k < names.length) {
                    boardValue[k] = Ui.text(c, "", 17, Ui.TEXT, true);
                    col.addView(boardValue[k]);
                    col.addView(Ui.text(c, names[k], 12, Ui.DIM, false), Ui.lp(c, Ui.WRAP, Ui.WRAP, 0, 0, 2, 0, 0));
                }
                bs.addView(col, Ui.lp(0, Ui.WRAP, 1));
            }
            board.addView(bs, Ui.lp(c, Ui.FILL, Ui.WRAP, 0, 0, r == 0 ? 10 : 12, 0, 0));
        }
        b.addView(board, block());

        return sv;
    }

    /** Small "label over value" column on the right of the progress row; returns the value view. */
    private TextView labelled(LinearLayout row, String label) {
        LinearLayout col = Ui.col(c);
        col.setGravity(Gravity.END);
        col.addView(Ui.text(c, label, 13, Ui.DIM, false));
        TextView v = Ui.text(c, "", 18, Ui.TEXT, true);
        col.addView(v, Ui.lp(c, Ui.WRAP, Ui.WRAP, 0, 0, 2, 0, 0));
        row.addView(col, Ui.lp(c, Ui.WRAP, Ui.WRAP, 0, 22, 0, 0, 0));
        return v;
    }

    /** Reads the gcode of the print (off the UI thread) and hands it to the preview; again whenever the file changes. */
    private void loadModel() {
        if (loading || m.modelKey.equals(loadedKey)) return;
        final String key = m.modelKey;
        if (key.isEmpty()) {
            loadedKey = key;
            model = null;
            return;
        }
        loading = true;
        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            GcodeModel parsed = null;
            try (InputStream in = m.openModel()) {
                parsed = GcodeModel.parse(in);
            } catch (Throwable e) {
                android.util.Log.e("PrintHostPanel", "preview model failed", e);
            }
            final GcodeModel gm = parsed;
            ui.post(() -> {
                loading = false;
                loadedKey = key;
                model = gm;
                if (gm != null) {
                    preview.setModel(gm);
                    slider.setMax(gm.layerZ.length);
                }
                refresh();
            });
        }, "preview-model").start();
    }

    /** Rebuilds the file rows when the list or the choice changed. */
    private void showFiles() {
        StringBuilder key = new StringBuilder(m.selectedFile);
        for (String[] f : m.files) key.append('|').append(f[0]).append(f[2]);
        if (key.toString().equals(filesShown)) return;
        filesShown = key.toString();
        filesBox.removeAllViews();
        if (m.files.length == 0) {
            filesBox.addView(Ui.text(c, "No files on the card", 14, Ui.DIM, false), Ui.lp(c, Ui.FILL, Ui.WRAP, 0, 0, 8, 0, 4));
            return;
        }
        for (final String[] f : m.files) {
            boolean chosen = f[0].equals(m.selectedFile);
            LinearLayout row = Ui.row(c);
            row.setMinimumHeight(Ui.dp(c, 46));
            row.setBackground(Ui.pressable(c, chosen ? Ui.GREEN_BG : Ui.CARD, 10));
            row.setPadding(Ui.dp(c, 10), 0, Ui.dp(c, 10), 0);
            TextView name = Ui.text(c, f[1], 15, chosen ? Ui.GREEN_T : Ui.TEXT, chosen);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            row.addView(name, Ui.lp(0, Ui.WRAP, 1));
            row.addView(Ui.text(c, f[2], 13, Ui.DIM, false), Ui.lp(c, Ui.WRAP, Ui.WRAP, 0, 10, 0, 0, 0));
            row.setOnClickListener(v -> {
                m.selectFile(f[0], f[1]);
                changed.run();
            });
            filesBox.addView(row, Ui.lp(c, Ui.FILL, Ui.WRAP, 0, 0, 4, 0, 0));
        }
    }

    private void showLayer(int layer) {
        if (model == null || model.layerZ.length == 0) return;
        layer = Math.max(0, Math.min(model.layerZ.length, layer));
        preview.setCutZ(layer == 0 ? -1 : model.layerZ[layer - 1]);
        layerLabel.setText("Layer " + layer + " of " + model.layerZ.length);
    }

    private CharSequence temp(double now, double target) {
        String a = Math.round(now) + "°", bPart = " / " + (target > 0 ? String.valueOf(Math.round(target)) : "off");
        SpannableString s = new SpannableString(a + bPart);
        s.setSpan(new ForegroundColorSpan(Ui.DIM), a.length(), s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new RelativeSizeSpan(0.65f), a.length(), s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    @Override
    void refresh() {
        if (pill == null) return;
        loadModel();
        boolean online = m.power && m.connected;
        String state = !m.power ? "Off" : !m.connected ? "Disconnected" : m.printing ? (m.paused ? "Paused" : "Printing") : "Ready";
        boolean active = online && m.printing && !m.paused;
        pill.setText(state);
        boolean paused = online && m.paused;
        pill.setTextColor(active ? Ui.GREEN_T : paused ? Ui.YELLOW : 0xFFA7ABB3);
        pill.setBackground(Ui.shape(c, active ? Ui.GREEN_BG : paused ? 0xFF3D310A : 0xFF26282D, 20));

        int vis = m.printing ? View.VISIBLE : View.GONE, inv = m.printing ? View.GONE : View.VISIBLE;
        printingBlock.setVisibility(vis);
        buttonsRow.setVisibility(vis);
        // The model is shown while it prints and also before: once a file is chosen and waits to be started.
        boolean hasModel = (m.printing || m.fileReady) && model != null;
        preview.setVisibility(m.printing || hasModel ? View.VISIBLE : View.GONE);
        slider.setVisibility(hasModel ? View.VISIBLE : View.GONE);
        layerLabel.setVisibility(hasModel ? View.VISIBLE : View.GONE);
        idleBox.setVisibility(m.printing || hasModel ? View.GONE : View.VISIBLE);
        lastCard.setVisibility(m.printing ? View.GONE : View.VISIBLE);

        if (m.printing) {
            fileName.setText(m.file);
            percent.setText((int) m.progress + "%");
            left.setText(m.leftSec() > 0 ? Printer.duration(m.leftSec()) : "–");
            ends.setText(m.leftSec() > 0 ? new SimpleDateFormat("HH:mm", Locale.US).format(new Date(System.currentTimeMillis() + m.leftSec() * 1000L)) : "–");
            bar.set(m.progress / 100);
            pauseBtn.setText(m.paused ? "Resume" : "Pause");
            if (model != null) {
                // the layer the phone counted in the file when it has one, else the share of the file that is done
                int layer = m.layer >= 0 && m.layers > 0 ? Math.round((float) m.layer * model.layerZ.length / m.layers)
                        : (int) Math.round(m.progress / 100 * model.layerZ.length);
                slider.setLive(layer);
                if (System.currentTimeMillis() > userLayerUntil) {
                    slider.setValue(layer);
                    showLayer(layer);
                }
            }
        } else {
            showFiles();
            Ui.enabled(startBtn, m.fileReady && m.power && m.connected && !m.busy());
            if (hasModel) {  // nothing is printed yet: the whole model, the slider free to look through the layers
                slider.setLive(-1);
                if (System.currentTimeMillis() > userLayerUntil) {
                    slider.setValue(model.layerZ.length);
                    showLayer(model.layerZ.length);
                }
            }
        }

        tileValue[0].setTextColor(Ui.HOT);
        tileValue[1].setTextColor(Ui.COLD);
        if (online) {
            tileValue[0].setText(temp(m.nozzle, m.nozzleTarget));
            tileValue[1].setText(temp(m.bed, m.bedTarget));
            for (int k = 2; k < 6; k++) tileValue[k].setText(AdjustSheet.value(m, k));
        } else {
            for (TextView t : tileValue) t.setText("–");
        }

        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);  // green = online, yellow = starting up, red = offline
        dot.setColor(m.boardState == Printer.BOARD_ONLINE ? Ui.GREEN : m.boardState == Printer.BOARD_STARTING ? Ui.YELLOW : Ui.RED);
        boardDot.setBackground(dot);
        for (int i = 0; i < boardValue.length; i++) boardValue[i].setText(m.board[i]);
    }
}
