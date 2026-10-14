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
 * The charging details screen: the battery level over the session as a chart, and on the right when it was,
 * what it added, the power and the cost.
 */
final class ChargeDetailPage {
    interface Host {
        void confirmDelete(Charge ch);

        void showPlace(Charge ch);
    }

    private static final float SCALE = 1.25f;
    private static final int AMBER_SOFT = 0x21F2B24C;

    private final Context c;
    private final Charge ch;
    private final double kwhPerPct;
    private final Host host;
    private final Kit k;

    static View build(Context c, Charge ch, double kwhPerPercent, Host host) {
        return new ChargeDetailPage(c, ch, kwhPerPercent, host).create();
    }

    private ChargeDetailPage(Context c, Charge ch, double kwhPerPercent, Host host) {
        this.c = c;
        this.ch = ch;
        this.kwhPerPct = kwhPerPercent;
        this.host = host;
        this.k = new Kit(c, SCALE);
    }

    private View create() {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout chartCard = k.card();
        chartCard.setPaddingRelative(k.d(16), k.d(14), k.d(16), k.d(12));
        final TextView readout = k.label(L.t("Battery (%) while charging - touch the chart to inspect"), 13.5f,
                Kit.MUTED, Kit.MEDIUM);
        chartCard.addView(readout);
        final TripChartView chart = new TripChartView(c);
        chart.setGapMs(Double.MAX_VALUE); // a long gap is the unit asleep: join the readings with a straight line
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, 0, 1f);
        clp.topMargin = k.d(8);
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

        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(k.d(360), -1);
        ilp.setMarginStart(k.d(10));
        row.addView(info(), ilp);
        return row;
    }

    private View info() {
        LinearLayout info = k.card();
        info.setPaddingRelative(k.d(16), k.d(14), k.d(16), k.d(12));
        SimpleDateFormat df = new SimpleDateFormat("EEEE" + L.sep() + "d MMMM yyyy", L.dateLocale());
        SimpleDateFormat tf = new SimpleDateFormat("HH:mm", L.dateLocale());
        info.addView(k.label(df.format(new Date(ch.startMs)), 16, Kit.TEXT, Kit.BOLD));
        TextView range = k.label(tf.format(new Date(ch.startMs)) + " – "
                + (ch.ended ? tf.format(new Date(ch.endMs)) : L.t("charging now")), 13, Kit.MUTED, Kit.MEDIUM);
        range.setPadding(0, k.d(2), 0, k.d(10));
        info.addView(range);

        double gained = ch.socGained();
        TextView big = k.label("", 34, Kit.TEXT, Kit.BOLD);
        big.setText(Double.isNaN(gained) ? "--"
                : Kit.numUnit(L.ltr((gained >= 0 ? "+" : "") + Ui.f(gained, "%.0f") + "%"), 0.55f));
        info.addView(big);
        info.addView(k.label(Ui.duration(ch.durationSec()), 14, Kit.MUTED, Kit.MEDIUM));
        Kit.RangeBar bar = new Kit.RangeBar(c);
        bar.set(ch.socStart, ch.socEnd, Kit.BLUE);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, k.d(7));
        blp.topMargin = k.d(10);
        info.addView(bar, blp);

        double e = ch.energyKwh(kwhPerPct);
        double hours = ch.durationSec() / 3600.0;
        LinearLayout list = k.column();
        LinearLayout s = k.column();
        s.setBackground(Ui.round(Kit.SHEET, 14 * k.scale, c));
        s.setPaddingRelative(k.d(12), k.d(6), k.d(12), k.d(6));
        addRow(s, L.t("Battery"), L.ltr(Ui.f(ch.socStart, "%.0f") + "% → " + Ui.f(ch.socEnd, "%.0f") + "%"));
        addRow(s, ch.energyIsEstimate() ? L.t("Energy added (estimate)") : L.t("Energy added"),
                Double.isNaN(e) ? "--" : L.f("%s kWh", Ui.f(e, "%.1f")));
        addRow(s, L.t("Average power"), Double.isNaN(e) || hours < 0.02 || ch.partial ? "--"
                : L.f("%s kW", Ui.f(e / hours, "%.1f")));
        addRow(s, L.t("Highest power reported"), Double.isNaN(ch.maxPowerKw) ? L.t("not reported by the car")
                : L.f("%s kW", Ui.f(ch.maxPowerKw, "%.1f")));
        addRow(s, L.t("Charger"), ch.typeName().isEmpty() ? "--" : ch.typeName());
        addRow(s, L.t("Cost"), Cost.money(c, Cost.charge(ch, kwhPerPct, c)));
        list.addView(s, new LinearLayout.LayoutParams(-1, -2));

        if (ch.partial) {
            TextView note = k.label(ch.windowOnly
                    ? L.t("Partial: this charge happened while the head unit was asleep. Only the battery "
                    + "level before and after is known, so the times shown are the window in which it "
                    + "happened, not how long it charged.")
                    : L.t("Partial: the app missed part of this session (the head unit "
                    + "was asleep or the app was not running), so the battery gain and energy can be "
                    + "lower than the real total."), 13, Kit.AMBER, Kit.MEDIUM);
            note.setLineSpacing(k.d(2), 1f);
            note.setBackground(Ui.round(AMBER_SOFT, 14 * k.scale, c));
            note.setPadding(k.d(12), k.d(10), k.d(12), k.d(10));
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, -2);
            nlp.topMargin = k.d(10);
            list.addView(note, nlp);
        }
        ScrollView sv = new ScrollView(c);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(list, new ViewGroup.LayoutParams(-1, -2));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, 0, 1f);
        slp.topMargin = k.d(10);
        info.addView(Kit.fadedScroll(c, sv, k.d(28), Kit.CARD), slp);

        LinearLayout buttons = k.row();
        if (!Double.isNaN(ch.lat) && !Double.isNaN(ch.lon)) {
            TextView place = k.pill(L.t("Show place"), false);
            place.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    host.showPlace(ch);
                }
            });
            buttons.addView(place, new LinearLayout.LayoutParams(-2, k.d(40)));
        }
        TextView delete = k.pill(L.t("Delete"), false);
        delete.setTextColor(0xFFFF8D8D);
        delete.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                host.confirmDelete(ch);
            }
        });
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, k.d(40));
        dlp.setMarginStart(k.d(10));
        buttons.addView(delete, dlp);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2);
        bl.topMargin = k.d(10);
        info.addView(buttons, bl);
        return info;
    }

    private void addRow(LinearLayout section, String label, String value) {
        LinearLayout line = k.row();
        line.setPadding(0, k.d(7), 0, k.d(7));
        line.addView(k.label(label, 13.5f, Kit.MUTED, Kit.MEDIUM), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView v = k.label(value, 14.5f, Kit.TEXT, Kit.BOLD);
        v.setGravity(Gravity.END);
        v.setPaddingRelative(k.d(8), 0, 0, 0);
        line.addView(v, new LinearLayout.LayoutParams(-2, -2));
        section.addView(line, new LinearLayout.LayoutParams(-1, -2));
    }
}
