package org.triprecorderng;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The Attainment screen: the driving level and the way to the next one on the left, then the personal records and the
 * totals as two lists (what it is, when, and the figure with its unit).
 */
final class AttainmentPage {
    private static final int BLUE_TEXT = 0xFF8FB2FF;

    private final Context c;
    private final List<Trip> trips;
    private final Kit k;

    static View build(Context c, List<Trip> trips) {
        return new AttainmentPage(c, trips).create();
    }

    private AttainmentPage(Context c, List<Trip> trips) {
        this.c = c;
        this.trips = trips;
        this.k = new Kit(c, 1f);
    }

    private int dp(float v) {
        return Ui.dp(c, v);
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.US) + s.substring(1);
    }

    private View create() {
        Stats.Records r = Stats.records(trips);
        SimpleDateFormat df = new SimpleDateFormat("d MMM yyyy", L.dateLocale());
        SimpleDateFormat shortDay = new SimpleDateFormat("d MMM", L.dateLocale());

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(level(r), new LinearLayout.LayoutParams(dp(262), -1));

        LinearLayout records = list(L.t("Highest achievements"));
        add(records, Kit.IconView.ROUTE, Kit.ACCENT, L.t("Longest trip"), date(r.longest, df),
                r.longest == null ? "--" : Ui.f(r.longest.distanceKm, "%.1f"), L.t("km"));
        add(records, Kit.IconView.CLOCK, Kit.ACCENT, L.t("Longest drive"), date(r.longestTime, df),
                r.longestTime == null ? "--" : Kit.numUnit(Ui.duration(r.longestTime.durationSec()), 0.62f), "");
        add(records, Kit.IconView.SPEED, Kit.ACCENT, L.t("Top speed"), date(r.fastest, df),
                r.fastest == null ? "--" : Ui.f(r.fastest.maxSpeedKmh, "%.0f"), L.t("km/h"));
        add(records, Kit.IconView.LEAF, Kit.ACCENT, L.t("Most efficient"),
                r.mostEfficient == null ? "" : L.t("Electric") + " · " + date(r.mostEfficient, shortDay),
                r.mostEfficient == null ? "--" : Ui.f(r.mostEfficient.energyPer100Km(), "%.1f"), L.t("kWh/100km"));
        add(records, Kit.IconView.PUMP, Kit.AMBER, L.t("Best fuel economy"),
                r.bestFuel == null ? "" : L.t("Hybrid") + " · " + date(r.bestFuel, shortDay),
                r.bestFuel == null ? "--" : Ui.f(Stats.fuelPer100Hybrid(r.bestFuel, 10), "%.1f"), L.t("L/100km"));
        add(records, Kit.IconView.STAR, Kit.ACCENT, L.t("Best drive score"), date(r.bestScore, df),
                r.bestScore == null ? "--" : Ui.f(Stats.score(r.bestScore), "%.0f"), L.t("points"));

        double fuelSum = 0;
        double costSum = 0;
        double hybridKm = 0;
        double hybridFuel = 0;
        int fuelTrips = 0;
        boolean anyCost = false;
        for (Trip t : trips) {
            if (!Double.isNaN(t.fuelUsed())) {
                fuelSum += t.fuelUsed();
                fuelTrips++;
            }
            double h = t.hevKm();
            if (Stats.burnedFuel(t) && !Double.isNaN(h) && h >= 1) {
                hybridKm += h;
                hybridFuel += t.fuelUsed();
            }
            double v = Cost.total(t, c);
            if (!Double.isNaN(v)) {
                costSum += v;
                anyCost = true;
            }
        }
        Stats.Co2 co2 = Stats.co2Avoided(trips);

        LinearLayout totals = list(L.t("Total"));
        add(totals, Kit.IconView.LIST, BLUE_TEXT, L.t("Total trips"), cap(L.t("recorded")),
                String.valueOf(r.count), "");
        add(totals, Kit.IconView.CLOCK, BLUE_TEXT, L.t("Total drive time"), cap(L.t("recorded")),
                Kit.numUnit(Ui.duration(r.totalSec), 0.62f), "");
        add(totals, Kit.IconView.COIN, Kit.AMBER, L.t("Total cost"), cap(L.t("electricity + fuel")),
                anyCost ? Cost.amount(c, costSum) : "--", Prefs.currency(c));
        add(totals, Kit.IconView.PUMP, Kit.AMBER, L.t("Fuel used"),
                fuelTrips > 0 ? cap(L.f("over %d trip(s)", fuelTrips)) : cap(L.t("recorded for new trips")),
                fuelTrips > 0 ? Ui.f(fuelSum, "%.1f") : "--", L.t("L"));
        add(totals, Kit.IconView.PUMP, Kit.AMBER, L.t("Fuel economy"), L.t("Hybrid driving"),
                hybridKm >= TripEnergy.MIN_MODE_KM ? Ui.f(hybridFuel / hybridKm * 100.0, "%.1f") : "--", L.t("L/100km"));
        add(totals, Kit.IconView.LEAF, Kit.ACCENT, L.t("CO2 avoided"),
                co2.trips > 0 ? cap(L.f("about %s trees a year", Ui.f(co2.trees, "%.1f"))) : cap(L.t("needs fuel data")),
                co2.trips > 0 ? Ui.f(co2.kg, "%.0f") : "--", L.t("kg"));

        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, -1, 1f);
        l1.setMarginStart(dp(12));
        row.addView(records, l1);
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(0, -1, 1f);
        l2.setMarginStart(dp(12));
        row.addView(totals, l2);
        return row;
    }

    private static String date(Trip t, SimpleDateFormat df) {
        return t == null ? "" : df.format(new Date(t.startMs));
    }

    /** A list card with its heading; the rows added to it share its height. */
    private LinearLayout list(String heading) {
        LinearLayout card = k.bigCard();
        card.setPaddingRelative(dp(16), dp(14), dp(16), dp(8));
        TextView h = k.t(heading, Kit.CAPTION, Kit.MUTED, Kit.MEDIUM);
        h.setPadding(dp(2), 0, 0, dp(2));
        card.addView(h, new LinearLayout.LayoutParams(-1, -2));
        return card;
    }

    /** One row: a badge, what it is with a line under it, and the figure with its unit under it. */
    private void add(LinearLayout card, int icon, int color, String label, String sub, CharSequence value, String unit) {
        LinearLayout row = k.row();
        if (card.getChildCount() > 1) {
            View hairline = new View(c);
            hairline.setBackgroundColor(Kit.LINE);
            card.addView(hairline, new LinearLayout.LayoutParams(-1, 1));
        }
        FrameLayout badge = new FrameLayout(c);
        badge.setBackground(Ui.round((color & 0x00FFFFFF) | 0x26000000, 22, c));
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(dp(24), dp(24));
        ilp.gravity = Gravity.CENTER;
        badge.addView(new Kit.IconView(c, icon, color), ilp);
        row.addView(badge, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout text = k.column();
        text.setPaddingRelative(dp(14), 0, dp(8), 0);
        TextView l = k.t(label, Kit.BODY, Kit.SOFT, Kit.MEDIUM);
        l.setSingleLine(true);
        text.addView(l);
        if (!sub.isEmpty()) {
            TextView s = k.t(sub, Kit.CAPTION, Kit.MUTED, Kit.REGULAR);
            s.setSingleLine(true);
            text.addView(s);
        }
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1f));

        LinearLayout fig = k.column();
        fig.setGravity(Gravity.END);
        TextView v = k.t("", 28, Kit.TEXT, Kit.BOLD);
        v.setText(value);
        v.setSingleLine(true);
        fig.addView(v);
        if (!unit.isEmpty()) {
            TextView u = k.t(unit, Kit.CAPTION, Kit.MUTED, Kit.REGULAR);
            u.setSingleLine(true);
            u.setGravity(Gravity.END);
            fig.addView(u);
        }
        row.addView(fig, new LinearLayout.LayoutParams(-2, -2));
        card.addView(row, new LinearLayout.LayoutParams(-1, 0, 1f));
    }

    /** The level card: a gauge of the way to the next level, its name, and the distance driven in all. */
    private View level(Stats.Records r) {
        LinearLayout card = k.bigCard();
        card.setPaddingRelative(dp(16), dp(16), dp(16), dp(16));
        int lv = Stats.level(r.totalKm);
        boolean top = lv + 1 >= Stats.LEVEL_KM.length;
        double frac = top ? 1 : (r.totalKm - Stats.LEVEL_KM[lv]) / (Stats.LEVEL_KM[lv + 1] - Stats.LEVEL_KM[lv]);

        LinearLayout head = k.row();
        head.addView(k.t(L.t("Driving level"), Kit.CAPTION, Kit.MUTED, Kit.REGULAR), new LinearLayout.LayoutParams(0, -2, 1f));
        head.addView(k.t(L.f("%1$d of %2$d", lv + 1, Stats.LEVEL_KM.length), Kit.CAPTION, Kit.MUTED, Kit.REGULAR));
        card.addView(head, new LinearLayout.LayoutParams(-1, -2));

        GaugeView gauge = new GaugeView(c, dp(214));
        gauge.set(frac, Kit.ACCENT, Kit.BLUE, String.valueOf(lv + 1), "", L.t("Level"));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, 0, 1f);
        glp.topMargin = dp(4);
        card.addView(gauge, glp);

        TextView name = k.t(Stats.levelName(lv), Kit.LABEL, Kit.TEXT, Kit.MEDIUM);
        name.setGravity(Gravity.CENTER);
        card.addView(name, new LinearLayout.LayoutParams(-1, -2));
        TextView next = k.t(top ? L.t("Top level")
                : L.f("%1$s km to level %2$d", Ui.f(Stats.LEVEL_KM[lv + 1] - r.totalKm, "%.0f"), lv + 2),
                Kit.CAPTION, Kit.MUTED, Kit.REGULAR);
        next.setGravity(Gravity.CENTER);
        card.addView(next, new LinearLayout.LayoutParams(-1, -2));

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
        llp.topMargin = dp(14);
        llp.bottomMargin = dp(12);
        card.addView(line, llp);
        card.addView(k.t(L.t("Total distance"), Kit.CAPTION, Kit.MUTED, Kit.REGULAR));
        TextView total = k.t("", Kit.VALUE_L, Kit.TEXT, Kit.BOLD);
        total.setText(Kit.numUnit(L.f("%s km", String.format(Locale.US, "%,.0f", r.totalKm)), 0.53f));
        total.setSingleLine(true);
        card.addView(total);
        if (ev + hev >= 1) {
            card.addView(k.t(L.f("%s%% driven electric", Ui.f(ev / (ev + hev) * 100, "%.0f")), Kit.BODY, Kit.ACCENT,
                    Kit.REGULAR));
        }
        return card;
    }
}
