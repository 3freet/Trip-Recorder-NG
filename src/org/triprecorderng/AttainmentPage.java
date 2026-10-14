package org.triprecorderng;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * The Attainment screen: the driving level and the distance behind it on the left, the personal records and
 * the totals as tiles on the right.
 */
final class AttainmentPage {
    private static final float SCALE = 1.35f;

    private final Context c;
    private final List<Trip> trips;
    private final Kit k;

    static View build(Context c, List<Trip> trips) {
        return new AttainmentPage(c, trips).create();
    }

    private AttainmentPage(Context c, List<Trip> trips) {
        this.c = c;
        this.trips = trips;
        this.k = new Kit(c, SCALE);
    }

    private View create() {
        Stats.Records r = Stats.records(trips);
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(level(r), new LinearLayout.LayoutParams(0, -1, 3f));

        LinearLayout right = k.column();
        SimpleDateFormat df = new SimpleDateFormat("d MMM yyyy", L.dateLocale());

        right.addView(k.header(L.t("Highest achievements")));
        LinearLayout best = k.row();
        best.addView(tile(Kit.IconView.ROUTE, L.t("Longest trip"),
                r.longest == null ? "--" : L.f("%s km", Ui.f(r.longest.distanceKm, "%.1f")), date(r.longest, df)), cell(0));
        best.addView(tile(Kit.IconView.CLOCK, L.t("Longest drive"),
                r.longestTime == null ? "--" : Ui.duration(r.longestTime.durationSec()), date(r.longestTime, df)), cell(1));
        best.addView(tile(Kit.IconView.SPEED, L.t("Top speed"),
                r.fastest == null ? "--" : L.f("%s km/h", Ui.f(r.fastest.maxSpeedKmh, "%.0f")), date(r.fastest, df)), cell(1));
        best.addView(tile(Kit.IconView.LEAF, L.t("Most efficient (5 km+)"),
                r.mostEfficient == null ? "--" : Ui.f(r.mostEfficient.energyPer100Km(), "%.1f"),
                r.mostEfficient == null ? "" : L.t("kWh/100km") + "\n" + date(r.mostEfficient, df)), cell(1));
        best.addView(tile(Kit.IconView.STAR, L.t("Best drive score"),
                r.bestScore == null ? "--" : Ui.f(Stats.score(r.bestScore), "%.0f"), date(r.bestScore, df)), cell(1));
        right.addView(best, new LinearLayout.LayoutParams(-1, 0, 1f));

        double fuelSum = 0;
        double costSum = 0;
        int fuelTrips = 0;
        boolean anyCost = false;
        for (Trip t : trips) {
            if (!Double.isNaN(t.fuelUsed())) {
                fuelSum += t.fuelUsed();
                fuelTrips++;
            }
            double v = Cost.total(t, c);
            if (!Double.isNaN(v)) {
                costSum += v;
                anyCost = true;
            }
        }
        Stats.Co2 co2 = Stats.co2Avoided(trips);
        TextView h2 = k.header(L.t("Total"));
        LinearLayout.LayoutParams h2lp = new LinearLayout.LayoutParams(-1, -2);
        h2lp.topMargin = k.d(12);
        right.addView(h2, h2lp);
        LinearLayout totals = k.row();
        totals.addView(tile(Kit.IconView.LIST, L.t("Total trips"), String.valueOf(r.count), ""), cell(0));
        totals.addView(tile(Kit.IconView.CLOCK, L.t("Total drive time"), Ui.duration(r.totalSec), ""), cell(1));
        totals.addView(tile(Kit.IconView.COIN, L.t("Total cost") + " (" + Prefs.currency(c) + ")",
                anyCost ? Cost.amount(c, costSum) : "--", L.t("electricity + fuel")), cell(1));
        totals.addView(tile(Kit.IconView.PUMP, L.t("Fuel used"), fuelTrips > 0 ? L.f("%s L", Ui.f(fuelSum, "%.1f")) : "--",
                fuelTrips > 0 ? L.f("over %d trip(s)", fuelTrips) : L.t("recorded for new trips")), cell(1));
        totals.addView(tile(Kit.IconView.LEAF, L.t("CO2 avoided (estimate)"),
                co2.trips > 0 ? L.f("%s kg", Ui.f(co2.kg, "%.0f")) : "--",
                co2.trips > 0 ? L.f("about %s trees a year", Ui.f(co2.trees, "%.1f")) : L.t("needs fuel data")), cell(1));
        right.addView(totals, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, -1, 8f);
        rlp.setMarginStart(k.d(10));
        row.addView(right, rlp);
        return row;
    }

    private static String date(Trip t, SimpleDateFormat df) {
        return t == null ? "" : df.format(new Date(t.startMs));
    }

    private LinearLayout.LayoutParams cell(int first) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
        if (first != 0) lp.setMarginStart(k.d(8));
        return lp;
    }

    /** The level card: a gauge of the way to the next level, its name, and the distance driven in all. */
    private View level(Stats.Records r) {
        LinearLayout card = k.card();
        card.setPaddingRelative(k.d(14), k.d(14), k.d(14), k.d(14));
        int lv = Stats.level(r.totalKm);
        boolean top = lv + 1 >= Stats.LEVEL_KM.length;
        double frac = top ? 1 : (r.totalKm - Stats.LEVEL_KM[lv]) / (Stats.LEVEL_KM[lv + 1] - Stats.LEVEL_KM[lv]);

        TextView cap = k.label(top ? L.t("Level (top)")
                : L.f("Level (next: %s km)", Ui.f(Stats.LEVEL_KM[lv + 1], "%.0f")), 12.5f, Kit.MUTED, Kit.MEDIUM);
        card.addView(cap, new LinearLayout.LayoutParams(-1, -2));

        GaugeView gauge = new GaugeView(c, k.d(190));
        gauge.set(frac, Kit.ACCENT, Kit.BLUE, String.valueOf(lv + 1), "", L.t("Level"));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, 0, 1f);
        glp.topMargin = k.d(4);
        card.addView(gauge, glp);

        TextView name = k.label(Stats.levelName(lv), 16, Kit.TEXT, Kit.BOLD);
        name.setGravity(Gravity.CENTER);
        card.addView(name, new LinearLayout.LayoutParams(-1, -2));

        double ev = 0;
        double hev = 0;
        for (Trip t : trips) {
            if (!Double.isNaN(t.evKm()) && !Double.isNaN(t.hevKm())) {
                ev += t.evKm();
                hev += t.hevKm();
            }
        }
        View line = new View(c);
        line.setBackgroundColor(Kit.LINE);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, 1);
        llp.topMargin = k.d(12);
        llp.bottomMargin = k.d(10);
        card.addView(line, llp);
        card.addView(k.label(L.t("Total distance"), 12.5f, Kit.MUTED, Kit.MEDIUM));
        TextView total = k.label("", 26, Kit.TEXT, Kit.BOLD);
        total.setText(Kit.numUnit(L.f("%s km", Ui.f(r.totalKm, "%.0f")), 0.5f));
        card.addView(total);
        if (ev + hev >= 1) {
            TextView sub = k.label(L.f("%s%% driven electric", Ui.f(ev / (ev + hev) * 100, "%.0f")), 12.5f,
                    Kit.ACCENT, Kit.MEDIUM);
            sub.setPadding(0, k.d(2), 0, 0);
            card.addView(sub);
        }
        return card;
    }

    /** A tile: icon and what it is, the figure, and a line under it (a date, or a note). */
    private View tile(int icon, String caption, CharSequence value, String sub) {
        LinearLayout t = k.card();
        t.setPaddingRelative(k.d(12), k.d(12), k.d(12), k.d(10));
        LinearLayout top = k.row();
        top.addView(new Kit.IconView(c, icon, Kit.ACCENT), new LinearLayout.LayoutParams(k.d(17), k.d(17)));
        TextView cp = k.label(caption, 12, Kit.MUTED, Kit.MEDIUM);
        cp.setPaddingRelative(k.d(7), 0, 0, 0);
        cp.setLines(3);
        top.addView(cp, new LinearLayout.LayoutParams(0, -2, 1f));
        t.addView(top, new LinearLayout.LayoutParams(-1, -2));
        TextView v = k.label("", 24, Kit.TEXT, Kit.BOLD);
        v.setText(value instanceof String ? Kit.numUnit((String) value, 0.5f) : value);
        v.setSingleLine(true);
        v.setPadding(0, k.d(10), 0, 0);
        t.addView(v, new LinearLayout.LayoutParams(-1, -2));
        if (!sub.isEmpty()) {
            TextView s = k.label(sub, 12, Kit.MUTED, Kit.MEDIUM);
            s.setPadding(0, k.d(3), 0, 0);
            s.setMaxLines(2);
            t.addView(s, new LinearLayout.LayoutParams(-1, -2));
        }
        return t;
    }
}
