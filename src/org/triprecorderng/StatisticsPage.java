package org.triprecorderng;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * The Statistics screen: pick what to chart and over which period, see it as bars and, beside them, the total (or
 * lowest), the average and the highest of what is charted. Consumption counts the trips that burned no fuel (the
 * kWh of a hybrid trip mixes two ways of driving); Fuel is litres per 100 km of hybrid driving.
 */
final class StatisticsPage {
    interface Host {
        /** Draws the page again (a choice changed). */
        void redraw();
    }

    /** What is charted and over which period; kept while the app is open. */
    private static int metric = Stats.MILEAGE;
    private static boolean monthly;

    private static final int[] COLORS = {Kit.ACCENT, Kit.BLUE, 0xFF8FD36A, 0xFFB48CFF, Kit.AMBER, 0xFFFF8A65};

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
        this.k = new Kit(c, 1f);
    }

    private int dp(float v) {
        return Ui.dp(c, v);
    }

    private View create() {
        LinearLayout col = k.column();

        LinearLayout head = k.row();
        head.addView(k.t(L.t("Statistics"), Kit.TITLE, Kit.TEXT, Kit.MEDIUM), new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout period = k.segmented();
        period.addView(k.segment(L.t("Daily"), !monthly, choose(-1, false)));
        period.addView(k.segment(L.t("Monthly"), monthly, choose(-1, true)));
        head.addView(period, new LinearLayout.LayoutParams(-2, -2));
        col.addView(head, new LinearLayout.LayoutParams(-1, dp(60)));

        LinearLayout kinds = k.segmented();
        String[] names = {L.t("Mileage"), L.t("Consumption"), L.t("Drive score"), L.t("Trips"), L.t("Cost"), L.t("Fuel")};
        for (int i = 0; i < names.length; i++) kinds.addView(k.segment(names[i], metric == i, choose(i, monthly)));
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(-2, -2);
        klp.topMargin = dp(4);
        col.addView(kinds, klp);

        Stats.Series s = Stats.series(c, trips, monthly, metric);
        LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.addView(chartCard(s), new LinearLayout.LayoutParams(0, -1, 3f));
        LinearLayout side = summary(s);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, -1, 1f);
        slp.setMarginStart(dp(12));
        body.addView(side, slp);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, 0, 1f);
        blp.topMargin = dp(12);
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

    private String unit() {
        String[] units = {L.t("Mileage (km)"), L.t("Electric consumption (kWh/100km)"), L.t("Average drive score"),
                L.t("Number of trips"), L.f("Cost (%s)", Prefs.currency(c)), L.t("Fuel economy (L/100km, hybrid driving)")};
        return units[metric];
    }

    private View chartCard(Stats.Series s) {
        LinearLayout card = k.bigCard();
        card.setPaddingRelative(dp(18), dp(14), dp(18), dp(10));
        card.addView(k.t(unit() + (monthly ? L.t(" - last 12 months") : L.t(" - last 14 days")), Kit.CAPTION,
                Kit.MUTED, Kit.REGULAR), new LinearLayout.LayoutParams(-1, -2));
        BarChartView chart = new BarChartView(c);
        chart.setDecimals(metric == Stats.COST ? Cost.decimalsFor(Prefs.currency(c)) : 1);
        chart.set(s.values, s.labels, COLORS[metric]);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, 0, 1f);
        clp.topMargin = dp(6);
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
        boolean additive = metric == Stats.MILEAGE || metric == Stats.TRIPS || metric == Stats.COST;

        LinearLayout col = k.column();
        col.addView(stat(additive ? L.t("Total") : L.t("Lowest"), n == 0 ? "--" : format(additive ? sum : min),
                additive ? Kit.IconView.LIST : Kit.IconView.ARROW_DOWN), new LinearLayout.LayoutParams(-1, 0, 1f));
        LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(-1, 0, 1f);
        a.topMargin = dp(12);
        col.addView(stat(L.t("Average"), n == 0 ? "--" : format(additive ? sum / n : sum / n), Kit.IconView.PULSE), a);
        LinearLayout.LayoutParams h = new LinearLayout.LayoutParams(-1, 0, 1f);
        h.topMargin = dp(12);
        col.addView(stat(L.t("Highest"), n == 0 ? "--" : format(max), Kit.IconView.ARROW_UP), h);
        return col;
    }

    /** A figure of the charted metric with its unit, in the app's language. */
    private CharSequence format(double v) {
        float u = Kit.CAPTION / Kit.VALUE;
        switch (metric) {
            case Stats.MILEAGE:
                return Kit.numUnit(L.f("%s km", Ui.f(v, "%.1f")), u);
            case Stats.CONSUMPTION:
                return Kit.valueUnit(Ui.f(v, "%.1f"), L.t("kWh/100km"), u);
            case Stats.COST:
                return Kit.numUnit(Cost.amount(c, v) + " " + Prefs.currency(c), u);
            case Stats.FUEL:
                return Kit.valueUnit(Ui.f(v, "%.1f"), L.t("L/100km"), u);
            default:
                return Ui.f(v, "%.0f");
        }
    }

    private View stat(String caption, CharSequence value, int icon) {
        LinearLayout t = k.bigCard();
        t.setPaddingRelative(dp(16), dp(12), dp(16), dp(12));
        t.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout top = k.row();
        top.addView(new Kit.IconView(c, icon, Kit.ACCENT), new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView cp = k.t(caption, Kit.CAPTION, Kit.MUTED, Kit.MEDIUM);
        cp.setPaddingRelative(dp(8), 0, 0, 0);
        top.addView(cp);
        t.addView(top);
        TextView v = k.t("", Kit.VALUE, Kit.TEXT, Kit.BOLD);
        v.setText(value);
        v.setSingleLine(true);
        v.setPadding(0, dp(6), 0, 0);
        t.addView(v);
        return t;
    }
}
