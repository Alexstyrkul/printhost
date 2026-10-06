package dev.oleksandr.printhost.panel;

import dev.oleksandr.printhost.R;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Filament: material, load (its own step-by-step view), unload, feeding by hand, cool down. */
final class FilamentScreen extends Screen {
    private View main, loading;
    private final TextView[] matBtn = new TextView[2];
    private TextView loadBtn, unloadBtn, feedLen, feedMinus, feedPlus, extrude, retract, cool;
    private View feedButtons;
    private Progress unloadBox, feedBox, coolBox;
    private final Progress[] matBox = new Progress[2];
    // loading view
    private TextView loadTitle, loadTemp, loadContinue, loadCancel;
    private Bar loadBar;
    private final View[] dot = new View[4];
    private final TextView[] stepText = new TextView[4];

    FilamentScreen(Context c, Printer m, Runnable changed) {
        super(c, m, changed);
    }

    /** "What it is doing" + temperature + bar: shown in place of the button that started it. */
    private final class Progress {
        final LinearLayout box;
        final TextView label, temp;
        final Bar bar;

        Progress() {
            box = Ui.col(c);
            box.setBackground(Ui.shape(c, Ui.BTN, 12));
            box.setPadding(Ui.dp(c, 14), Ui.dp(c, 12), Ui.dp(c, 14), Ui.dp(c, 12));
            LinearLayout r = Ui.row(c);
            label = Ui.text(c, "", 15, Ui.TEXT, true);
            temp = Ui.text(c, "", 15, Ui.HOT, true);
            r.addView(label, Ui.lp(0, Ui.WRAP, 1));
            r.addView(temp);
            box.addView(r);
            bar = new Bar(c, Ui.TRACK, Ui.HOT);
            box.addView(bar, Ui.lp(c, Ui.FILL, Ui.dp(c, 6), 0, 0, 8, 0, 0));
        }

        void show(String what) {
            show(what, Math.round(m.nozzle) + "° / " + m.opTemp + "°", m.opPhase == 0 ? m.nozzle / m.opTemp : 1);
        }

        void show(String what, String temperature, double fraction) {
            label.setText(what);
            temp.setText(temperature);
            bar.set(fraction);
        }
    }

    @Override
    View build() {
        FrameLayout root = new FrameLayout(c);
        main = buildMain();
        loading = buildLoading();
        root.addView(main);
        root.addView(loading);
        return root;
    }

    private View buildMain() {
        ScrollView sv = new ScrollView(c);
        LinearLayout b = body(sv);
        b.addView(title("Filament"));

        LinearLayout mats = Ui.row(c);
        for (int i = 0; i < 2; i++) {
            final int idx = i;
            matBtn[i] = Ui.button(c, m.matName[i] + " · " + m.matNozzle[i] + "°", Ui.BTN, Ui.TEXT);
            matBtn[i].setOnClickListener(v -> {  // an action, not a choice: heats for that material
                m.preheat(idx);
                changed.run();
            });
            FrameLayout slot = new FrameLayout(c);
            slot.addView(matBtn[i], new FrameLayout.LayoutParams(Ui.FILL, Ui.dp(c, 56)));
            matBox[i] = new Progress();
            slot.addView(matBox[i].box, new FrameLayout.LayoutParams(Ui.FILL, Ui.dp(c, 56)));
            mats.addView(slot, Ui.lp(c, 0, Ui.WRAP, 1, i == 0 ? 0 : 10, 0, 0, 0));
        }
        b.addView(mats, block());

        loadBtn = Ui.button(c, "Load", Ui.GREEN, Ui.ON_GREEN);
        loadBtn.setOnClickListener(v -> {
            m.load();
            changed.run();
        });
        b.addView(loadBtn, block(56));

        FrameLayout unloadSlot = new FrameLayout(c);
        unloadBtn = Ui.button(c, "Unload", Ui.BTN, Ui.TEXT);
        unloadBtn.setOnClickListener(v -> {
            m.unload();
            changed.run();
        });
        unloadSlot.addView(unloadBtn, new FrameLayout.LayoutParams(Ui.FILL, Ui.dp(c, 56)));
        unloadBox = new Progress();
        unloadSlot.addView(unloadBox.box, new FrameLayout.LayoutParams(Ui.FILL, Ui.WRAP));
        b.addView(unloadSlot, block());

        LinearLayout feed = Ui.card(c);
        feed.addView(Ui.text(c, "Feed by hand", 16, Ui.TEXT, true));
        LinearLayout st = Ui.row(c);
        feedMinus = Ui.button(c, "−", Ui.BTN, Ui.TEXT);
        feedMinus.setTextSize(22);
        feedPlus = Ui.button(c, "+", Ui.BTN, Ui.TEXT);
        feedPlus.setTextSize(22);
        feedLen = Ui.text(c, "", 24, Ui.TEXT, true);
        feedLen.setGravity(Gravity.CENTER);
        feedMinus.setOnClickListener(v -> {
            m.feedMm = Math.max(5, m.feedMm - 5);
            refresh();
        });
        feedPlus.setOnClickListener(v -> {
            m.feedMm = Math.min(100, m.feedMm + 5);
            refresh();
        });
        st.addView(feedMinus, new LinearLayout.LayoutParams(Ui.dp(c, 56), Ui.dp(c, 48)));
        st.addView(feedLen, Ui.lp(0, Ui.WRAP, 1));
        st.addView(feedPlus, new LinearLayout.LayoutParams(Ui.dp(c, 56), Ui.dp(c, 48)));
        feed.addView(st, Ui.lp(c, Ui.FILL, Ui.WRAP, 0, 0, 12, 0, 0));

        FrameLayout feedSlot = new FrameLayout(c);
        LinearLayout er = Ui.row(c);
        extrude = Ui.button(c, "Extrude", Ui.BTN, Ui.TEXT);
        retract = Ui.button(c, "Retract", Ui.BTN, Ui.TEXT);
        extrude.setOnClickListener(v -> {
            m.feed(1);
            changed.run();
        });
        retract.setOnClickListener(v -> {
            m.feed(-1);
            changed.run();
        });
        er.addView(extrude, Ui.lp(0, Ui.dp(c, 48), 1));
        er.addView(retract, Ui.lp(c, 0, Ui.dp(c, 48), 1, 10, 0, 0, 0));
        feedButtons = er;
        feedSlot.addView(er);
        feedBox = new Progress();
        feedSlot.addView(feedBox.box, new FrameLayout.LayoutParams(Ui.FILL, Ui.WRAP));
        feed.addView(feedSlot, Ui.lp(c, Ui.FILL, Ui.WRAP, 0, 0, 12, 0, 0));
        b.addView(feed, block());

        b.addView(spring());
        cool = Ui.ghost(c, "Cool down");
        cool.setOnClickListener(v -> {
            // the same button is Cancel while a heating action can still be stopped
            if (m.preheatMat >= 0) m.cancelPreheat();
            else if (heatingOp()) m.cancelOp();
            else m.coolDown();
            changed.run();
        });
        FrameLayout coolSlot = new FrameLayout(c);
        coolSlot.addView(cool, new FrameLayout.LayoutParams(Ui.FILL, Ui.dp(c, 56)));
        coolBox = new Progress();
        coolSlot.addView(coolBox.box, new FrameLayout.LayoutParams(Ui.FILL, Ui.dp(c, 56)));
        b.addView(coolSlot, block());
        return sv;
    }

    /** Unload or feed by hand is still heating the nozzle: it can be stopped (not once the filament moves). */
    private boolean heatingOp() {
        return (m.op == Printer.OP_UNLOAD || m.op == Printer.OP_FEED) && m.opPhase == 0;
    }

    private View buildLoading() {
        ScrollView sv = new ScrollView(c);
        sv.setBackgroundColor(Ui.BG);
        LinearLayout b = body(sv);
        loadTitle = title("");
        b.addView(loadTitle);

        LinearLayout t = Ui.card(c);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setPadding(Ui.dp(c, 14), Ui.dp(c, 22), Ui.dp(c, 14), Ui.dp(c, 22));
        t.addView(Ui.text(c, "Nozzle", 13, Ui.DIM, false), new LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP));
        loadTemp = Ui.text(c, "", 56, Ui.HOT, true);
        t.addView(loadTemp, Ui.lp(c, Ui.WRAP, Ui.WRAP, 0, 0, 4, 0, 0));
        loadBar = new Bar(c, Ui.BTN, Ui.HOT);
        t.addView(loadBar, Ui.lp(c, Ui.FILL, Ui.dp(c, 6), 0, 0, 14, 0, 0));
        b.addView(t, block());

        LinearLayout steps = Ui.card(c);
        String[] names = {"Heating the nozzle", "Insert the filament", "Feeding the filament", "Done"};
        for (int i = 0; i < 4; i++) {
            LinearLayout r = Ui.row(c);
            r.setMinimumHeight(Ui.dp(c, 46));
            dot[i] = new View(c);
            r.addView(dot[i], Ui.lp(c, Ui.dp(c, 14), Ui.dp(c, 14), 0, 8, 0, 14, 0));
            stepText[i] = Ui.text(c, names[i], 15, Ui.TEXT, true);
            r.addView(stepText[i]);
            steps.addView(r);
        }
        b.addView(steps, block());

        b.addView(spring());
        // shown once the nozzle is hot: the printer waits here until the filament is in
        loadContinue = Ui.button(c, "Continue", Ui.GREEN, Ui.ON_GREEN);
        loadContinue.setOnClickListener(v -> {
            m.continueLoad();
            changed.run();
        });
        b.addView(loadContinue, block(56));
        loadCancel = Ui.ghost(c, "Cancel");
        loadCancel.setOnClickListener(v -> {
            m.cancelOp();
            changed.run();
        });
        b.addView(loadCancel, block(48));
        return sv;
    }

    @Override
    void refresh() {
        if (main == null) return;
        boolean isLoading = m.op == Printer.OP_LOAD;
        loading.setVisibility(isLoading ? View.VISIBLE : View.GONE);
        main.setVisibility(isLoading ? View.GONE : View.VISIBLE);
        if (isLoading) {
            loadTitle.setText("Loading " + m.matName[m.material]);
            loadTemp.setText(Math.round(m.nozzle) + "°");
            loadBar.set(m.nozzle / m.opTemp);
            // steps: 0 heating, 1 insert the filament (waits for Continue), 2 feeding, 3 done
            int now = m.opPhase == 0 ? 0 : m.opPhase == 3 ? 1 : m.opPhase == 1 ? 2 : 3;
            loadContinue.setVisibility(m.opPhase == 3 ? View.VISIBLE : View.INVISIBLE);
            Ui.enabled(loadCancel, m.opPhase == 0 || m.opPhase == 3);  // not once the filament moves
            for (int i = 0; i < 4; i++) {
                boolean done = i < now || (i == 3 && m.opPhase == 2), cur = i == now && !done;
                GradientDrawable d = new GradientDrawable();
                d.setShape(GradientDrawable.OVAL);
                if (done) d.setColor(Ui.GREEN);
                else if (cur) d.setColor(Ui.TEXT);
                else {
                    d.setColor(0);
                    d.setStroke(Ui.dp(c, 1.5f), Ui.FAINT);
                }
                dot[i].setBackground(d);
                stepText[i].setTextColor(done || cur ? Ui.TEXT : Ui.FAINT);
            }
            return;
        }
        boolean ok = m.manualOk();
        for (int i = 0; i < 2; i++) {
            boolean heating = m.preheatMat == i;
            matBtn[i].setVisibility(heating ? View.GONE : View.VISIBLE);
            matBox[i].box.setVisibility(heating ? View.VISIBLE : View.GONE);
            if (heating) matBox[i].show(m.matName[i], Math.round(m.nozzle) + "° / " + m.matNozzle[i] + "°", m.nozzle / m.matNozzle[i]);
            Ui.enabled(matBtn[i], ok);
        }
        boolean canCancel = m.preheatMat >= 0 || heatingOp();
        cool.setText(canCancel ? "Cancel" : "Cool down");
        cool.setVisibility(m.cooling ? View.GONE : View.VISIBLE);
        coolBox.box.setVisibility(m.cooling ? View.VISIBLE : View.GONE);
        if (m.cooling) coolBox.show("Cooling", Math.round(m.nozzle) + "°", (m.nozzle - 24) / Math.max(1, m.coolFrom - 24));
        Ui.enabled(loadBtn, ok);
        boolean unloading = m.op == Printer.OP_UNLOAD, feeding = m.op == Printer.OP_FEED;
        unloadBtn.setVisibility(unloading ? View.GONE : View.VISIBLE);
        unloadBox.box.setVisibility(unloading ? View.VISIBLE : View.GONE);
        if (unloading) unloadBox.show("Unloading");
        Ui.enabled(unloadBtn, ok);
        feedLen.setText(m.feedMm + " mm");
        feedButtons.setVisibility(feeding ? View.GONE : View.VISIBLE);
        feedBox.box.setVisibility(feeding ? View.VISIBLE : View.GONE);
        if (feeding) feedBox.show(m.feedVerb + " " + m.feedMm + " mm");
        Ui.enabled(extrude, ok);
        Ui.enabled(retract, ok);
        Ui.enabled(feedMinus, !feeding);
        Ui.enabled(feedPlus, !feeding);
        Ui.enabled(cool, m.power && m.connected && (canCancel || !m.busy()));
    }
}
