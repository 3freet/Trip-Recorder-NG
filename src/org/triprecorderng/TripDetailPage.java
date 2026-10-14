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
 * The trip details screen: the route and the charts on the left; on the right when and how far, then the figures in
 * groups (speed, energy, the trip itself, cost) and the driving advice.
 */
final class TripDetailPage {
    interface Host {
        void exportGpx(Trip t);

        void confirmDelete(Trip t);
    }

    private static final float SCALE = 1.25f;

    private final Context c;
    private final Trip t;
    private final Host host;
    private final Kit k;
    private final TextView overView;

    static View build(Context c, Trip t, Host host) {
        return new TripDetailPage(c, t, host).create();
    }

    private TripDetailPage(Context c, Trip t, Host host) {
        this.c = c;
        this.t = t;
        this.host = host;
        this.k = new Kit(c, SCALE);
        this.overView = k.label("...", 14.5f, Kit.TEXT, Kit.BOLD);
    }

    private View create() {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout left = k.column();
        LinearLayout mapCard = k.card();
        final RouteView route = new RouteView(c);
        mapCard.addView(route, new LinearLayout.LayoutParams(-1, -1));
        left.addView(mapCard, new LinearLayout.LayoutParams(-1, 0, 8f));
        final TripChartsPanel charts = new TripChartsPanel(c, route);
        LinearLayout.LayoutParams chLp = new LinearLayout.LayoutParams(-1, 0, 5f);
        chLp.topMargin = k.d(10);
        left.addView(charts, chLp);
        row.addView(left, new LinearLayout.LayoutParams(0, -1, 1f));

        new Thread(new Runnable() {
            @Override public void run() {
                final List<double[]> pts = TripAnalysis.holdStill(TripDb.get(c).points(t.id));
                final List<double[]> events = TripDb.get(c).events(t.id);
                route.post(new Runnable() {
                    @Override public void run() {
                        route.set(pts, events);
                        charts.setData(t, pts);
                        double over = TripAnalysis.secondsAbove(pts, 100);
                        overView.setText(pts.size() < 2 ? "--" : Ui.duration((long) over));
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
        info.addView(k.label(df.format(new Date(t.startMs)), 16, Kit.TEXT, Kit.BOLD));
        TextView range = k.label(tf.format(new Date(t.startMs)) + " – "
                + (t.endMs > 0 ? tf.format(new Date(t.endMs)) : "..."), 13, Kit.MUTED, Kit.MEDIUM);
        range.setPadding(0, k.d(2), 0, k.d(10));
        info.addView(range);

        LinearLayout hero = k.row();
        double sc = Stats.score(t);
        RingView ring = new RingView(c, 6 * k.scale, 17 * k.scale, 10 * k.scale);
        ring.setTrackColor(0x17FFFFFF);
        ring.set(Double.isNaN(sc) ? 0 : sc, 100, Ui.f(sc, "%.0f"), "", Kit.scoreColor(sc));
        hero.addView(ring, new LinearLayout.LayoutParams(k.d(60), k.d(60)));
        LinearLayout big = k.column();
        big.setPaddingRelative(k.d(14), 0, 0, 0);
        TextView dist = k.label("", 30, Kit.TEXT, Kit.BOLD);
        dist.setText(Kit.numUnit(L.f("%s km", Ui.f(t.distanceKm, "%.1f")), 0.5f));
        big.addView(dist);
        big.addView(k.label(Ui.duration(t.durationSec()), 14, Kit.MUTED, Kit.MEDIUM));
        hero.addView(big);
        info.addView(hero);

        LinearLayout list = k.column();
        LinearLayout speed = section(L.t("Speed"), Kit.IconView.SPEED);
        addRow(speed, L.t("Average speed"), L.f("%s km/h", Ui.f(t.avgSpeedKmh(), "%.0f")));
        addRow(speed, L.t("Max speed"), L.f("%s km/h", Ui.f(t.maxSpeedKmh, "%.0f")));
        addRow(speed, L.t("Time above 100 km/h"), overView);
        list.addView(speed, gap());

        LinearLayout energy = section(L.t("Energy"), Kit.IconView.BATTERY);
        addRow(energy, L.t("Battery"), L.ltr(Ui.f(t.socStart, "%.0f") + "% → " + Ui.f(t.socEnd, "%.0f") + "%"));
        addRow(energy, L.t("Energy used"), L.f("%s kWh", Ui.f(t.energyUsed(), "%.2f")));
        addRow(energy, L.t("Consumption"), L.f("%s kWh/100km", Ui.f(t.energyPer100Km(), "%.1f")));
        if (!Double.isNaN(t.fuelUsed())) {
            addRow(energy, L.t("Fuel used"), L.f("%s L", Ui.f(t.fuelUsed(), "%.1f"))
                    + (Double.isNaN(t.fuelPer100Km()) ? ""
                    : "  (" + L.f("%s L/100km", Ui.f(t.fuelPer100Km(), "%.1f")) + ")"));
        }
        if (!Double.isNaN(t.evKm()) && !Double.isNaN(t.hevKm())) {
            addRow(energy, L.t("Electric / hybrid distance"),
                    L.f("%1$s km / %2$s km", Ui.f(t.evKm(), "%.0f"), Ui.f(t.hevKm(), "%.0f")));
        }
        list.addView(energy, gap());

        LinearLayout trip = section(L.t("Trip details"), Kit.IconView.ROUTE);
        addRow(trip, L.t("Odometer change"), L.f("%s km", Ui.f(t.odometerDeltaKm(), "%.1f")));
        addRow(trip, L.t("Distance measured by"),
                !Double.isNaN(t.journeyAcc) && t.journeyAcc >= JourneyTracker.TRUST_KM
                        ? L.t("car trip counter") : L.t("speed"));
        addRow(trip, L.t("Hard accel / braking"), t.hardAccel + " / " + t.hardBrake);
        addRow(trip, L.t("Idle time"), Ui.duration((long) t.idleSec));
        list.addView(trip, gap());

        if (Cost.enabled(c)) {
            LinearLayout cost = section(L.t("Cost"), Kit.IconView.COIN);
            if (!Double.isNaN(Cost.elec(t, c))) addRow(cost, L.t("Electricity cost"), Cost.money(c, Cost.elec(t, c)));
            if (!Double.isNaN(Cost.fuel(t, c))) addRow(cost, L.t("Fuel cost"), Cost.money(c, Cost.fuel(t, c)));
            addRow(cost, L.t("Trip cost"), Cost.money(c, Cost.total(t, c))
                    + (Double.isNaN(t.fuelUsed()) ? L.t("  (fuel not recorded)") : ""));
            list.addView(cost, gap());
        }

        List<String> advice = Advice.forTrip(t);
        if (!advice.isEmpty()) {
            LinearLayout tip = section(L.t("Driving advice"), Kit.IconView.INFO);
            for (String a : advice) {
                TextView at = k.label(a, 13.5f, Kit.SOFT, Kit.MEDIUM);
                at.setLineSpacing(k.d(2), 1f);
                at.setPadding(0, k.d(5), 0, k.d(3));
                tip.addView(at);
            }
            list.addView(tip, gap());
        }
        ScrollView sv = new ScrollView(c);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(list, new ViewGroup.LayoutParams(-1, -2));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, 0, 1f);
        slp.topMargin = k.d(10);
        info.addView(Kit.fadedScroll(c, sv, k.d(28), Kit.CARD), slp);

        LinearLayout buttons = k.row();
        TextView export = k.pill(L.t("Export GPX"), false);
        export.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                host.exportGpx(t);
            }
        });
        buttons.addView(export, new LinearLayout.LayoutParams(-2, k.d(40)));
        TextView delete = k.pill(L.t("Delete"), false);
        delete.setTextColor(0xFFFF8D8D);
        delete.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                host.confirmDelete(t);
            }
        });
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, k.d(40));
        dlp.setMarginStart(k.d(10));
        buttons.addView(delete, dlp);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = k.d(10);
        info.addView(buttons, blp);
        return info;
    }

    private LinearLayout.LayoutParams gap() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = k.d(8);
        return lp;
    }

    /** A group of figures: a tile with a small heading. */
    private LinearLayout section(String title, int icon) {
        LinearLayout s = k.column();
        s.setBackground(Ui.round(Kit.SHEET, 14 * k.scale, c));
        s.setPaddingRelative(k.d(12), k.d(9), k.d(12), k.d(6));
        LinearLayout head = k.row();
        head.addView(new Kit.IconView(c, icon, Kit.MUTED), new LinearLayout.LayoutParams(k.d(14), k.d(14)));
        TextView l = k.label(L.isArabic() ? title : title.toUpperCase(java.util.Locale.US), 11, Kit.MUTED, Kit.BOLD);
        if (!L.isArabic()) l.setLetterSpacing(0.08f);
        l.setPaddingRelative(k.d(6), 0, 0, 0);
        head.addView(l);
        s.addView(head, new LinearLayout.LayoutParams(-1, -2));
        return s;
    }

    private void addRow(LinearLayout section, String label, String value) {
        TextView v = k.label(value, 14.5f, Kit.TEXT, Kit.BOLD);
        addRow(section, label, v);
    }

    private void addRow(LinearLayout section, String label, TextView value) {
        LinearLayout line = k.row();
        line.setPadding(0, k.d(6), 0, k.d(6));
        TextView l = k.label(label, 13.5f, Kit.MUTED, Kit.MEDIUM);
        line.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));
        value.setGravity(Gravity.END);
        value.setPaddingRelative(k.d(8), 0, 0, 0);
        line.addView(value, new LinearLayout.LayoutParams(-2, -2));
        section.addView(line, new LinearLayout.LayoutParams(-1, -2));
    }
}
