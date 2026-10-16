package org.triprecorderng;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * The charging details screen: the battery level over the session as a chart, and on the right when it was, what it
 * added, the power and the cost. Show place and Delete are in the title bar (MainActivity).
 */
final class ChargeDetailPage {
    private static final int AMBER_SOFT = 0x21F2B24C;

    private final Context c;
    private final Charge ch;
    private final double kwhPerPct;
    private final Kit k;

    static View build(Context c, Charge ch, double kwhPerPercent) {
        return new ChargeDetailPage(c, ch, kwhPerPercent).create();
    }

    private ChargeDetailPage(Context c, Charge ch, double kwhPerPercent) {
        this.c = c;
        this.ch = ch;
        this.kwhPerPct = kwhPerPercent;
        this.k = new Kit(c, 1f);
    }

    private int dp(float v) {
        return Ui.dp(c, v);
    }

    private View create() {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout chartCard = k.bigCard();
        chartCard.setPaddingRelative(dp(18), dp(14), dp(18), dp(12));
        final TextView readout = k.t(L.t("Battery (%) while charging - touch the chart to inspect"), Kit.CAPTION,
                Kit.MUTED, Kit.REGULAR);
        chartCard.addView(readout);
        final TripChartView chart = new TripChartView(c);
        chart.setGapMs(Double.MAX_VALUE); // a long gap is the unit asleep: join the readings with a straight line
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, 0, 1f);
        clp.topMargin = dp(8);
        chartCard.addView(chart, clp);
        row.addView(chartCard, new LinearLayout.LayoutParams(0, -1, 1f));

        new Thread(new Runnable() {
            @Override public void run() {
                final List<double[]> pts = TripDb.get(c).chargePoints(ch.id);
                final double[] times = new double[pts.size()];
                final double[] soc = new double[pts.size()];
                for (int i = 0; i < times.length; i++) {
                    times[i] = pts.get(i)[0];
                    soc[i] = pts.get(i)[1];
                }
                chart.post(new Runnable() {
                    @Override public void run() {
                        chart.set(times, soc, "%", Kit.ACCENT, L.t("Not enough samples to draw a chart"));
                        chart.setListener(new TripChartView.Listener() {
                            @Override public void onCursor(int index, double timeMs) {
                                if (index < 0 || index >= pts.size() || Double.isNaN(timeMs)) {
                                    readout.setText(L.t("Battery (%) while charging - touch the chart to inspect"));
                                    return;
                                }
                                double[] p = pts.get(index);
                                String t = new SimpleDateFormat("HH:mm", L.dateLocale()).format(new Date((long) timeMs));
                                readout.setText(t + "   " + Ui.f(TripAnalysis.valueAt(times, soc, timeMs), "%.0f") + "%"
                                        + (Double.isNaN(p[2]) || p[2] <= 0 ? ""
                                        : "   " + L.f("%s kW", Ui.f(p[2], "%.1f"))));
                            }
                        });
                    }
                });
            }
        }).start();

        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(430), -1);
        ilp.setMarginStart(dp(12));
        row.addView(info(), ilp);
        return row;
    }

    private View info() {
        LinearLayout info = k.bigCard();
        info.setPaddingRelative(dp(18), dp(14), dp(18), 0);
        SimpleDateFormat df = new SimpleDateFormat("d MMMM yyyy", L.dateLocale());
        SimpleDateFormat dow = new SimpleDateFormat("EEEE", L.dateLocale());
        SimpleDateFormat tf = new SimpleDateFormat("HH:mm", L.dateLocale());
        Date start = new Date(ch.startMs);
        info.addView(k.t(df.format(start), Kit.LABEL, Kit.TEXT, Kit.MEDIUM));
        info.addView(k.t(dow.format(start) + " · " + tf.format(start) + " – "
                + (ch.ended ? tf.format(new Date(ch.endMs)) : L.t("charging now")), Kit.CAPTION, Kit.MUTED, Kit.REGULAR));

        double gained = ch.socGained();
        TextView big = k.t("", Kit.DISPLAY, Kit.TEXT, Kit.BOLD);
        big.setText(Double.isNaN(gained) ? "--"
                : Kit.numUnit(L.ltr((gained >= 0 ? "+" : "") + Ui.f(gained, "%.0f") + "%"), 0.46f));
        big.setSingleLine(true);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
        blp.topMargin = dp(8);
        info.addView(big, blp);
        info.addView(k.t(Ui.duration(ch.durationSec()), Kit.BODY, Kit.MUTED, Kit.REGULAR));
        Kit.RangeBar bar = new Kit.RangeBar(c);
        bar.set(ch.socStart, ch.socEnd, Kit.BLUE);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, dp(8));
        barLp.topMargin = dp(10);
        info.addView(bar, barLp);

        double e = ch.energyKwh(kwhPerPct);
        double hours = ch.durationSec() / 3600.0;
        LinearLayout list = k.column();
        list.setPadding(0, dp(8), 0, 0);
        fact(list, L.t("Battery"), L.ltr(Ui.f(ch.socStart, "%.0f") + "% → " + Ui.f(ch.socEnd, "%.0f") + "%"));
        fact(list, ch.energyIsEstimate() ? L.t("Energy added (estimate)") : L.t("Energy added"),
                Double.isNaN(e) ? "--" : L.f("%s kWh", Ui.f(e, "%.1f")));
        fact(list, L.t("Average power"), Double.isNaN(e) || hours < 0.02 || ch.partial ? "--"
                : L.f("%s kW", Ui.f(e / hours, "%.1f")));
        fact(list, L.t("Highest power reported"), Double.isNaN(ch.maxPowerKw) ? L.t("not reported")
                : L.f("%s kW", Ui.f(ch.maxPowerKw, "%.1f")));
        fact(list, L.t("Charger"), ch.typeName().isEmpty() ? "--" : ch.typeName());
        fact(list, L.t("Cost"), Cost.money(c, Cost.charge(ch, kwhPerPct, c)));
        if (ch.partial) {
            TextView note = k.t(ch.windowOnly
                    ? L.t("Partial: this charge happened while the head unit was asleep. Only the battery "
                    + "level before and after is known, so the times shown are the window in which it "
                    + "happened, not how long it charged.")
                    : L.t("Partial: the app missed part of this session (the head unit "
                    + "was asleep or the app was not running), so the battery gain and energy can be "
                    + "lower than the real total."), Kit.CAPTION, Kit.AMBER, Kit.REGULAR);
            note.setLineSpacing(dp(2), 1f);
            note.setBackground(Ui.round(AMBER_SOFT, 16, c));
            note.setPadding(dp(14), dp(10), dp(14), dp(10));
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, -2);
            nlp.topMargin = dp(12);
            list.addView(note, nlp);
        }
        list.addView(new View(c), new LinearLayout.LayoutParams(-1, dp(28)));

        ScrollView sv = new ScrollView(c);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(list, new ViewGroup.LayoutParams(-1, -2));
        info.addView(Kit.fadedScroll(c, sv, dp(34), Kit.CARD), new LinearLayout.LayoutParams(-1, 0, 1f));
        return info;
    }

    private void fact(LinearLayout into, String label, String value) {
        if (into.getChildCount() > 0) {
            View hairline = new View(c);
            hairline.setBackgroundColor(Kit.LINE);
            into.addView(hairline, new LinearLayout.LayoutParams(-1, 1));
        }
        LinearLayout line = k.row();
        TextView l = k.t(label, Kit.BODY, Kit.MUTED, Kit.REGULAR);
        l.setSingleLine(true);
        line.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView v = k.t(value, Kit.BODY, Kit.TEXT, Kit.MEDIUM);
        v.setGravity(Gravity.END);
        v.setSingleLine(true);
        v.setPaddingRelative(dp(8), 0, 0, 0);
        line.addView(v, new LinearLayout.LayoutParams(-2, -2));
        into.addView(line, new LinearLayout.LayoutParams(-1, dp(46)));
    }
}
