package org.triprecorderng;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * The Statistics screen: pick what to chart and over which period, see it as bars and, beside them, the total (or
 * lowest), the average and the highest of what is charted.
 */
final class StatisticsPage {
    interface Host {
        /** Draws the page again (a choice changed). */
        void redraw();
    }

    private static final float SCALE = 1.35f;

    /** What is charted and over which period; kept while the app is open. */
    private static int metric = Stats.MILEAGE;
    private static boolean monthly;

    private final Context c;
    private final List<Trip> trips;
    private final Host host;
    private final Kit k;

    static View build(Context c, List<Trip> trips, Host host) {
        return new StatisticsPage(c, trips, host).create();
    }

    private StatisticsPage(Context c, List<Trip> trips, Host host) {
        this.c = c;
        this.trips = trips;
        this.host = host;
        this.k = new Kit(c, SCALE);
    }

    private View create() {
        LinearLayout col = k.column();

        LinearLayout head = k.row();
        head.addView(k.label(L.t("Statistics"), 19, Kit.TEXT, Kit.BOLD), new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout period = k.segmented();
        period.addView(k.segment(L.t("Daily"), !monthly, choose(-1, false)));
        period.addView(k.segment(L.t("Monthly"), monthly, choose(-1, true)));
        head.addView(period, new LinearLayout.LayoutParams(-2, -2));
        col.addView(head, new LinearLayout.LayoutParams(-1, k.d(46)));

        LinearLayout kinds = k.segmented();
        String[] names = {L.t("Mileage"), L.t("Consumption"), L.t("Drive score"), L.t("Trips"), L.t("Cost"), L.t("Fuel")};
        for (int i = 0; i < names.length; i++) kinds.addView(k.segment(names[i], metric == i, choose(i, monthly)));
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(-2, -2);
        klp.topMargin = k.d(4);
        col.addView(kinds, klp);

        Stats.Series s = Stats.series(c, trips, monthly, metric);
        LinearLayout body = k.row();
        body.setGravity(Gravity.TOP);
        body.addView(chartCard(s), new LinearLayout.LayoutParams(0, -1, 3f));
        LinearLayout side = summary(s);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, -1, 1f);
        slp.setMarginStart(k.d(10));
        body.addView(side, slp);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, 0, 1f);
        blp.topMargin = k.d(10);
        col.addView(body, blp);
        return col;
    }

    private View.OnClickListener choose(final int newMetric, final boolean newMonthly) {
        return new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (newMetric >= 0) metric = newMetric;
                monthly = newMonthly;
                host.redraw();
            }
        };
    }

    private static final int[] COLORS = {Kit.ACCENT, Kit.BLUE, 0xFF8FD36A, 0xFFB48CFF, Kit.AMBER, 0xFFFF8A65};

    private String unit() {
        String[] units = {L.t("Mileage (km)"), L.t("Consumption (kWh/100km)"), L.t("Average drive score"),
                L.t("Number of trips"), L.f("Cost (%s)", Prefs.currency(c)), L.t("Fuel used (L)")};
        return units[metric];
    }

    private View chartCard(Stats.Series s) {
        LinearLayout card = k.card();
        card.setPaddingRelative(k.d(16), k.d(14), k.d(16), k.d(10));
        card.addView(k.label(unit() + (monthly ? L.t(" - last 12 months") : L.t(" - last 14 days")), 13.5f,
                Kit.MUTED, Kit.MEDIUM), new LinearLayout.LayoutParams(-1, -2));
        BarChartView chart = new BarChartView(c);
        chart.setDecimals(metric == Stats.COST ? Cost.decimalsFor(Prefs.currency(c)) : 1);
        chart.set(s.values, s.labels, COLORS[metric]);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, 0, 1f);
        clp.topMargin = k.d(6);
        card.addView(chart, clp);
        return card;
    }

    /** Total (or lowest, when adding up makes no sense), average and highest of the periods that had data. */
    private LinearLayout summary(Stats.Series s) {
        double sum = 0;
        double max = Double.NaN;
        double min = Double.NaN;
        int n = 0;
        for (double v : s.values) {
            if (Double.isNaN(v) || v <= 0) continue;
            sum += v;
            n++;
            if (Double.isNaN(max) || v > max) max = v;
            if (Double.isNaN(min) || v < min) min = v;
        }
        boolean additive = metric == Stats.MILEAGE || metric == Stats.TRIPS || metric == Stats.COST
                || metric == Stats.FUEL;

        LinearLayout col = k.column();
        col.addView(stat(additive ? L.t("Total") : L.t("Lowest"), n == 0 ? "--" : format(additive ? sum : min),
                additive ? Kit.IconView.LIST : Kit.IconView.ARROW_DOWN), new LinearLayout.LayoutParams(-1, 0, 1f));
        LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(-1, 0, 1f);
        a.topMargin = k.d(8);
        col.addView(stat(L.t("Average"), n == 0 ? "--" : format(sum / n), Kit.IconView.PULSE), a);
        LinearLayout.LayoutParams h = new LinearLayout.LayoutParams(-1, 0, 1f);
        h.topMargin = k.d(8);
        col.addView(stat(L.t("Highest"), n == 0 ? "--" : format(max), Kit.IconView.ARROW_UP), h);
        return col;
    }

    /** A figure of the charted metric with its unit, in the app's language. */
    private CharSequence format(double v) {
        switch (metric) {
            case Stats.MILEAGE:
                return Kit.numUnit(L.f("%s km", Ui.f(v, "%.1f")), 0.5f);
            case Stats.CONSUMPTION:
                return Kit.valueUnit(Ui.f(v, "%.1f"), L.t("kWh/100km"), 0.5f);
            case Stats.COST:
                return Kit.numUnit(Cost.amount(c, v) + " " + Prefs.currency(c), 0.5f);
            case Stats.FUEL:
                return Kit.numUnit(L.f("%s L", Ui.f(v, "%.1f")), 0.5f);
            default:
                return Ui.f(v, "%.0f");
        }
    }

    private View stat(String caption, CharSequence value, int icon) {
        LinearLayout t = k.card();
        t.setPaddingRelative(k.d(12), k.d(10), k.d(12), k.d(10));
        t.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout top = k.row();
        top.addView(new Kit.IconView(c, icon, Kit.ACCENT), new LinearLayout.LayoutParams(k.d(16), k.d(16)));
        TextView cp = k.label(caption, 12.5f, Kit.MUTED, Kit.MEDIUM);
        cp.setPaddingRelative(k.d(7), 0, 0, 0);
        top.addView(cp);
        t.addView(top);
        TextView v = k.label("", 22, Kit.TEXT, Kit.BOLD);
        v.setText(value);
        v.setSingleLine(true);
        v.setPadding(0, k.d(6), 0, 0);
        t.addView(v);
        return t;
    }
}
