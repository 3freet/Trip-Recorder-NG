package org.triprecorderng;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The Charging screen: what has been added and spent on top, then the sessions grouped by day, each a tile with
 * its time, the battery before and after and the energy. MainActivity reaches the data and the detail screen
 * through Host.
 */
final class ChargingPage {
    interface Host {
        List<Charge> charges();

        /** The trips: the energy of a session is estimated from how much a battery percent has taken so far. */
        List<Trip> trips();

        void openCharge(Charge ch, double kwhPerPercent);

        /** Resource id of the picture shown when there are no sessions. */
        int emptyImage();
    }

    private static final float SCALE = 1.35f;
    private static final int BLUE_TEXT = 0xFF8FB2FF;
    private static final int BLUE_SOFT = 0x2B5B8DEF;
    private static final int AMBER_SOFT = 0x21F2B24C;

    private final Context c;
    private final Host host;
    private final Kit k;
    private final double kwhPerPct;

    static View build(Context c, Host host) {
        return new ChargingPage(c, host).create();
    }

    private ChargingPage(Context c, Host host) {
        this.c = c;
        this.host = host;
        this.k = new Kit(c, SCALE);
        this.kwhPerPct = Charge.kwhPerPercent(host.trips());
    }

    private View create() {
        LinearLayout col = k.column();
        List<Charge> charges = host.charges();
        col.addView(header(charges), new LinearLayout.LayoutParams(-1, k.d(46)));
        LinearLayout.LayoutParams body = new LinearLayout.LayoutParams(-1, 0, 1f);
        body.topMargin = k.d(6);
        col.addView(charges.isEmpty() ? empty() : list(charges), body);
        return col;
    }

    private View header(List<Charge> charges) {
        LinearLayout head = k.row();
        head.addView(k.label(L.t("Charging"), 19, Kit.TEXT, Kit.BOLD));
        if (!charges.isEmpty()) {
            double kwh = 0;
            double cost = 0;
            boolean anyKwh = false;
            boolean anyCost = false;
            for (Charge ch : charges) {
                double e = ch.energyKwh(kwhPerPct);
                if (!Double.isNaN(e)) {
                    kwh += e;
                    anyKwh = true;
                }
                double v = Cost.charge(ch, kwhPerPct, c);
                if (!Double.isNaN(v)) {
                    cost += v;
                    anyCost = true;
                }
            }
            addChip(head, Kit.IconView.LIST, Kit.BLUE, L.f("%d session(s)", charges.size()), 16);
            if (anyKwh) addChip(head, Kit.IconView.BOLT, Kit.BLUE, L.f("about %s kWh added", Ui.f(kwh, "%.0f")), 8);
            if (anyCost) addChip(head, Kit.IconView.COIN, Kit.MUTED, L.f("about %s", Cost.money(c, cost)), 8);
        }
        return head;
    }

    private void addChip(LinearLayout head, int icon, int color, String text, int marginStart) {
        Kit.Chip chip = k.new Chip(icon, color, "", text);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, k.d(34));
        lp.setMarginStart(k.d(marginStart));
        head.addView(chip.view, lp);
    }

    private View empty() {
        LinearLayout card = k.card();
        card.setGravity(Gravity.CENTER);
        card.setOrientation(LinearLayout.VERTICAL);
        ImageView img = new ImageView(c);
        img.setImageResource(host.emptyImage());
        card.addView(img, new LinearLayout.LayoutParams(k.d(220), k.d(147)));
        TextView t = k.label(L.t("No charging sessions yet"), 17, Kit.SOFT, Kit.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, k.d(10), 0, 0);
        card.addView(t, new LinearLayout.LayoutParams(-2, -2));
        TextView hint = k.label(Prefs.linkEnabled(c)
                ? L.t("A session is recorded automatically while the car charges")
                : L.t("Turn on Vehicle data link in Setting: charging is read from the car through it"),
                13.5f, Kit.MUTED, Kit.MEDIUM);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(k.d(24), k.d(6), k.d(24), 0);
        card.addView(hint, new LinearLayout.LayoutParams(-2, -2));
        return card;
    }

    private View list(List<Charge> charges) {
        final List<Object> items = new ArrayList<>();
        SimpleDateFormat key = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        SimpleDateFormat name = new SimpleDateFormat("EEEE'" + L.sep() + "'d MMM", L.dateLocale());
        int i = 0;
        while (i < charges.size()) {
            String d = key.format(new Date(charges.get(i).startMs));
            int j = i;
            while (j < charges.size() && key.format(new Date(charges.get(j).startMs)).equals(d)) j++;
            items.add(name.format(new Date(charges.get(i).startMs)) + "\n" + L.f("%d session(s)", j - i));
            for (int n = i; n < j; n++) items.add(charges.get(n));
            i = j;
        }
        ListView list = new ListView(c);
        list.setDivider(null);
        list.setSelector(android.R.color.transparent);
        list.setVerticalScrollBarEnabled(false);
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, k.d(8));
        list.setAdapter(new Kit.GroupedAdapter(items) {
            @Override View header(String text) {
                int nl = text.indexOf('\n');
                LinearLayout h = k.row();
                h.setPaddingRelative(k.d(6), k.d(12), k.d(6), k.d(8));
                h.addView(k.label(text.substring(0, nl), 14, Kit.SOFT, Kit.BOLD),
                        new LinearLayout.LayoutParams(0, -2, 1f));
                h.addView(k.label(text.substring(nl + 1), 12.5f, Kit.MUTED, Kit.MEDIUM));
                return h;
            }

            @Override View row(Object item) {
                return chargeRow((Charge) item);
            }
        });
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override public void onItemClick(android.widget.AdapterView<?> p, View v, int pos, long id) {
                Object o = items.get(pos);
                if (o instanceof Charge) host.openCharge((Charge) o, kwhPerPct);
            }
        });
        return list;
    }

    private static String kwhText(Charge ch, double kwhPerPct) {
        double e = ch.energyKwh(kwhPerPct);
        return Double.isNaN(e) ? "--" : L.ltr((ch.energyIsEstimate() ? "~" : "") + Ui.f(e, "%.1f"));
    }

    /** One session: a bolt badge, time and duration, the battery before and after, energy, cost and charger type. */
    private View chargeRow(Charge ch) {
        FrameLayout outer = new FrameLayout(c);
        outer.setPadding(0, 0, 0, k.d(8));
        LinearLayout tile = k.row();
        tile.setBackground(Ui.round(Kit.CARD, 16 * k.scale, c));
        tile.setPaddingRelative(k.d(14), k.d(10), k.d(14), k.d(10));

        FrameLayout badge = new FrameLayout(c);
        badge.setBackground(Ui.round(ch.partial ? AMBER_SOFT : BLUE_SOFT, 23 * k.scale, c));
        Kit.IconView bolt = new Kit.IconView(c, Kit.IconView.BOLT, ch.partial ? Kit.AMBER : BLUE_TEXT);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(k.d(22), k.d(22));
        blp.gravity = Gravity.CENTER;
        badge.addView(bolt, blp);
        tile.addView(badge, new LinearLayout.LayoutParams(k.d(46), k.d(46)));

        SimpleDateFormat tf = new SimpleDateFormat("HH:mm", L.dateLocale());
        LinearLayout when = k.column();
        TextView range = k.label(tf.format(new Date(ch.startMs)) + " – "
                + (ch.ended ? tf.format(new Date(ch.endMs)) : "..."), 16, Kit.TEXT, Kit.BOLD);
        range.setSingleLine(true);
        when.addView(range);
        LinearLayout second = k.row();
        TextView dur = k.label(Ui.duration(ch.durationSec()), 13, Kit.MUTED, Kit.MEDIUM);
        dur.setSingleLine(true);
        second.addView(dur);
        if (!ch.ended) second.addView(tag(L.t("Charging"), BLUE_TEXT, BLUE_SOFT));
        if (ch.partial) second.addView(tag(L.t("Partial"), Kit.AMBER, AMBER_SOFT));
        second.setPadding(0, k.d(2), 0, 0);
        when.addView(second);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(0, -2, 1.6f);
        wlp.setMarginStart(k.d(14));
        tile.addView(when, wlp);

        double gained = ch.socGained();
        tile.addView(metric(Double.isNaN(gained) ? "--"
                        : Kit.numUnit(L.ltr((gained >= 0 ? "+" : "") + Ui.f(gained, "%.0f") + "%"), 0.55f),
                L.t("Gained")), new LinearLayout.LayoutParams(0, -2, 1f));

        LinearLayout bat = metric(L.ltr(Ui.f(ch.socStart, "%.0f") + " → " + Ui.f(ch.socEnd, "%.0f")),
                L.t("Battery"));
        Kit.RangeBar bar = new Kit.RangeBar(c);
        bar.set(ch.socStart, ch.socEnd, Kit.BLUE);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, k.d(5));
        barLp.topMargin = k.d(5);
        bat.addView(bar, 1, barLp);
        tile.addView(bat, new LinearLayout.LayoutParams(0, -2, 1.3f));

        double e = ch.energyKwh(kwhPerPct);
        tile.addView(metric(Double.isNaN(e) ? "--" : Kit.valueUnit(kwhText(ch, kwhPerPct), "kWh", 0.5f),
                L.t("Energy added")), new LinearLayout.LayoutParams(0, -2, 1.2f));
        double cost = Cost.charge(ch, kwhPerPct, c);
        tile.addView(metric(Double.isNaN(cost) ? "--" : Kit.numUnit(Cost.money(c, cost), 0.5f), L.t("Cost")),
                new LinearLayout.LayoutParams(0, -2, 1.2f));
        tile.addView(metric(ch.typeName().isEmpty() ? "--" : ch.typeName(), L.t("Type")),
                new LinearLayout.LayoutParams(0, -2, 0.8f));

        Kit.IconView chev = new Kit.IconView(c, Kit.IconView.CHEVRON, Kit.MUTED);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(k.d(16), k.d(16));
        clp.setMarginStart(k.d(6));
        tile.addView(chev, clp);
        outer.addView(tile, new FrameLayout.LayoutParams(-1, -2));
        return outer;
    }

    private TextView tag(String text, int fg, int bg) {
        TextView t = k.label(text, 11, fg, Kit.BOLD);
        t.setPadding(k.d(8), k.d(2), k.d(8), k.d(2));
        t.setBackground(Ui.round(bg, 9 * k.scale, c));
        t.setSingleLine(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMarginStart(k.d(8));
        t.setLayoutParams(lp);
        return t;
    }

    private LinearLayout metric(CharSequence value, String caption) {
        LinearLayout m = k.column();
        m.setPaddingRelative(0, 0, k.d(8), 0);
        TextView v = k.label("", 20, Kit.TEXT, Kit.BOLD);
        v.setText(value);
        v.setSingleLine(true);
        m.addView(v);
        TextView cap = k.label(caption, 12, Kit.MUTED, Kit.MEDIUM);
        cap.setSingleLine(true);
        cap.setPadding(0, k.d(1), 0, 0);
        m.addView(cap);
        return m;
    }
}
