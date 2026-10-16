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
import java.util.Locale;

/**
 * The trip details screen: the route and the charts on the left; on the right when and how far, then how it consumed
 * (electric and hybrid driving apart on a trip that burned fuel), the key figures and the rest, and the driving advice.
 * Export and Delete are in the title bar (MainActivity).
 */
final class TripDetailPage {
    interface Host {
        List<Charge> charges();
    }

    private static final int AMBER_TEXT = Kit.AMBER;

    private final Context c;
    private final Trip t;
    private final Host host;
    private final Kit k;
    private final TextView overView;
    private LinearLayout consumption;
    private View root;
    private int polls;

    static View build(Context c, Trip t, Host host) {
        return new TripDetailPage(c, t, host).create();
    }

    private TripDetailPage(Context c, Trip t, Host host) {
        this.c = c;
        this.t = t;
        this.host = host;
        this.k = new Kit(c, 1f);
        this.overView = k.t("...", Kit.BODY, Kit.TEXT, Kit.MEDIUM);
    }

    private int dp(float v) {
        return Ui.dp(c, v);
    }

    private View create() {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        root = row;

        LinearLayout left = k.column();
        LinearLayout mapCard = k.bigCard();
        final RouteView route = new RouteView(c);
        mapCard.addView(route, new LinearLayout.LayoutParams(-1, -1));
        left.addView(mapCard, new LinearLayout.LayoutParams(-1, 0, 8f));
        final TripChartsPanel charts = new TripChartsPanel(c, route);
        LinearLayout.LayoutParams chLp = new LinearLayout.LayoutParams(-1, 0, 5f);
        chLp.topMargin = dp(12);
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
        Date start = new Date(t.startMs);
        TextView date = k.t(df.format(start), Kit.LABEL, Kit.TEXT, Kit.MEDIUM);
        date.setSingleLine(true);
        info.addView(date);
        info.addView(k.t(dow.format(start) + " · " + tf.format(start) + " – "
                + (t.endMs > 0 ? tf.format(new Date(t.endMs)) : "..."), Kit.CAPTION, Kit.MUTED, Kit.REGULAR));

        LinearLayout hero = k.row();
        double sc = Stats.score(t);
        RingView ring = new RingView(c, 6.4f, 24, 12);
        ring.setTrackColor(0x17FFFFFF);
        ring.set(Double.isNaN(sc) ? 0 : sc, 100, Ui.f(sc, "%.0f"), "", Kit.scoreColor(sc));
        hero.addView(ring, new LinearLayout.LayoutParams(dp(64), dp(64)));
        LinearLayout big = k.column();
        big.setPaddingRelative(dp(14), 0, 0, 0);
        TextView dist = k.t("", Kit.VALUE_L, Kit.TEXT, Kit.BOLD);
        dist.setText(Kit.numUnit(L.f("%s km", Ui.f(t.distanceKm, "%.1f")), 20f / 38f));
        dist.setSingleLine(true);
        big.addView(dist);
        big.addView(k.t(Ui.duration(t.durationSec()), Kit.CAPTION, Kit.MUTED, Kit.REGULAR));
        hero.addView(big);
        // what the trip cost, in view from the start
        double total = Cost.total(t, c);
        if (Cost.enabled(c) && !Double.isNaN(total)) {
            hero.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1f));
            LinearLayout costBox = k.column();
            costBox.setGravity(Gravity.END);
            TextView amount = k.t("", Kit.TITLE, Kit.TEXT, Kit.BOLD);
            amount.setText(Kit.numUnit(Cost.money(c, total), 18f / 26f));
            amount.setSingleLine(true);
            amount.setGravity(Gravity.END);
            costBox.addView(amount);
            TextView what = k.t(Double.isNaN(t.fuelUsed()) ? L.t("Electricity only") : L.t("Trip cost"), Kit.CAPTION,
                    Kit.MUTED, Kit.REGULAR);
            what.setSingleLine(true);
            what.setGravity(Gravity.END);
            costBox.addView(what);
            hero.addView(costBox);
        }
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
        hlp.topMargin = dp(8);
        info.addView(hero, hlp);

        LinearLayout list = k.column();
        list.addView(heading(L.t("Consumption")));
        consumption = k.column();
        list.addView(consumption);
        fillConsumption();

        list.addView(heading(L.t("Trip")));
        LinearLayout grid = k.column();
        LinearLayout r1 = new LinearLayout(c);
        r1.setOrientation(LinearLayout.HORIZONTAL);
        r1.addView(tile(Kit.numUnit(L.f("%s km/h", Ui.f(t.avgSpeedKmh(), "%.0f")), 18f / 26f), L.t("Average speed")),
                new LinearLayout.LayoutParams(0, -2, 1f));
        r1.addView(tile(Kit.numUnit(L.f("%s km/h", Ui.f(t.maxSpeedKmh, "%.0f")), 18f / 26f), L.t("Max speed")),
                spaced());
        grid.addView(r1, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout r2 = new LinearLayout(c);
        r2.setOrientation(LinearLayout.HORIZONTAL);
        r2.addView(tile(Kit.numUnit(L.f("%s kWh", Ui.f(t.energyUsed(), "%.1f")), 18f / 26f),
                L.t("Battery") + ", " + L.ltr(Ui.f(t.socStart, "%.0f") + " → " + Ui.f(t.socEnd, "%.0f") + "%")),
                new LinearLayout.LayoutParams(0, -2, 1f));
        if (!Double.isNaN(t.fuelUsed())) {
            r2.addView(tile(Kit.numUnit(L.f("%s L", Ui.f(t.fuelUsed(), "%.1f")), 18f / 26f), L.t("Fuel used")), spaced());
        } else {
            r2.addView(tile(Ui.duration((long) t.idleSec), L.t("Idle time")), spaced());
        }
        LinearLayout.LayoutParams r2lp = new LinearLayout.LayoutParams(-1, -2);
        r2lp.topMargin = dp(8);
        grid.addView(r2, r2lp);
        list.addView(grid);

        LinearLayout more = k.column();
        more.setPadding(0, dp(8), 0, 0);
        addFact(more, L.t("Time above 100 km/h"), overView);
        if (!Double.isNaN(t.fuelUsed())) addFact(more, L.t("Idle time"), Ui.duration((long) t.idleSec));
        addFact(more, L.t("Hard accel / braking"), t.hardAccel + " / " + t.hardBrake);
        addFact(more, L.t("Odometer change"), L.f("%s km", Ui.f(t.odometerDeltaKm(), "%.1f")));
        addFact(more, L.t("Distance measured by"),
                !Double.isNaN(t.journeyAcc) && t.journeyAcc >= JourneyTracker.TRUST_KM
                        ? L.t("car trip counter") : L.t("speed"));
        if (!Stats.burnedFuel(t) && !Double.isNaN(t.evKm()) && !Double.isNaN(t.hevKm())) {
            addFact(more, L.t("Electric / hybrid distance"),
                    L.f("%1$s km / %2$s km", Ui.f(t.evKm(), "%.0f"), Ui.f(t.hevKm(), "%.0f")));
        }
        if (Cost.enabled(c)) {
            if (!Double.isNaN(Cost.elec(t, c))) addFact(more, L.t("Electricity cost"), Cost.money(c, Cost.elec(t, c)));
            if (!Double.isNaN(Cost.fuel(t, c))) addFact(more, L.t("Fuel cost"), Cost.money(c, Cost.fuel(t, c)));
        }
        list.addView(more);

        List<String> advice = Advice.forTrip(t);
        if (!advice.isEmpty()) {
            list.addView(heading(L.t("Driving advice")));
            for (String a : advice) {
                TextView at = k.t(a, Kit.BODY, Kit.SOFT, Kit.REGULAR);
                at.setLineSpacing(dp(2), 1f);
                at.setPadding(0, dp(6), 0, dp(4));
                list.addView(at);
            }
        }
        View pad = new View(c);
        list.addView(pad, new LinearLayout.LayoutParams(-1, dp(28)));

        ScrollView sv = new ScrollView(c);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(list, new ViewGroup.LayoutParams(-1, -2));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, 0, 1f);
        info.addView(Kit.fadedScroll(c, sv, dp(34), Kit.CARD), slp);
        return info;
    }

    private LinearLayout.LayoutParams spaced() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.setMarginStart(dp(8));
        return lp;
    }

    private TextView heading(String s) {
        TextView h = k.t(s, Kit.CAPTION, Kit.MUTED, Kit.MEDIUM);
        h.setPadding(0, dp(14), 0, dp(4));
        return h;
    }

    private View tile(CharSequence value, String caption) {
        LinearLayout tile = k.column();
        tile.setBackground(Ui.round(Kit.SHEET, 16, c));
        tile.setPadding(dp(14), dp(10), dp(14), dp(10));
        TextView v = k.t("", Kit.TITLE, Kit.TEXT, Kit.BOLD);
        v.setText(value);
        v.setSingleLine(true);
        tile.addView(v);
        TextView cp = k.t(caption, Kit.CAPTION, Kit.MUTED, Kit.REGULAR);
        cp.setSingleLine(true);
        tile.addView(cp);
        return tile;
    }

    /** A label and its value on one line, with a thin line above it (except for the first of a block). */
    private void addFact(LinearLayout into, String label, CharSequence value) {
        TextView v = k.t("", Kit.BODY, Kit.TEXT, Kit.MEDIUM);
        v.setText(value);
        addFact(into, label, v, Kit.MUTED);
    }

    private void addFact(LinearLayout into, String label, TextView value) {
        addFact(into, label, value, Kit.MUTED);
    }

    private void addFact(LinearLayout into, String label, TextView value, int labelColor) {
        if (into.getChildCount() > 0) {
            View hairline = new View(c);
            hairline.setBackgroundColor(Kit.LINE);
            into.addView(hairline, new LinearLayout.LayoutParams(-1, 1));
        }
        LinearLayout line = k.row();
        TextView l = k.t(label, Kit.BODY, labelColor, Kit.REGULAR);
        l.setSingleLine(true);
        line.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));
        value.setGravity(Gravity.END);
        value.setSingleLine(true);
        value.setPaddingRelative(dp(8), 0, 0, 0);
        if (value.getParent() instanceof ViewGroup) ((ViewGroup) value.getParent()).removeView(value);
        line.addView(value, new LinearLayout.LayoutParams(-2, -2));
        into.addView(line, new LinearLayout.LayoutParams(-1, dp(42)));
    }

    /**
     * The consumption block. A trip that burned fuel shows electric and hybrid driving apart and what the engine put
     * into the battery; the electric figure waits for the battery trace and fills in when it has been read.
     */
    private void fillConsumption() {
        consumption.removeAllViews();
        double cost = Cost.total(t, c);
        String costPer100 = Double.isNaN(cost) || t.distanceKm < 0.5 ? "--"
                : Cost.money(c, cost / t.distanceKm * 100.0) + "/100km";
        if (Stats.burnedFuel(t)) {
            TripEnergy e = EnergyLoader.get(c, t, host.charges());
            double ev = t.evKm();
            double hev = t.hevKm();
            addFact(consumption, L.t("Electric") + " · " + L.f("%s km", Double.isNaN(ev) ? "--" : Ui.f(ev, "%.0f")),
                    Double.isNaN(e.electricKwhPer100) ? "--"
                            : "≈ " + Ui.f(e.electricKwhPer100, "%.0f") + " " + L.t("kWh/100km"));
            addFact(consumption, L.t("Hybrid") + " · " + L.f("%s km", Double.isNaN(hev) ? "--" : Ui.f(hev, "%.0f")),
                    Double.isNaN(e.hybridLPer100) ? "--" : Ui.f(e.hybridLPer100, "%.1f") + " " + L.t("L/100km"));
            if (!Double.isNaN(e.chargedPct) && e.chargedPct > 0) {
                TextView v = k.t(L.ltr("≈ +" + Ui.f(e.chargedPct, "%.0f") + "%") + " · "
                        + L.f("%s kWh", Ui.f(e.chargedKwh, "%.1f")), Kit.BODY, AMBER_TEXT, Kit.MEDIUM);
                addFact(consumption, L.t("Engine charging"), v);
            }
            // what the whole trip used per 100 km, the two ways of driving taken together
            double kwh100 = t.energyPer100Km();
            double l100 = t.fuelPer100Km();
            addFact(consumption, L.t("Whole trip per 100 km"),
                    L.rtl(Double.isNaN(kwh100) ? "--" : L.f("%s kWh", Ui.f(kwh100, "%.1f"))) + " · "
                    + L.rtl(Double.isNaN(l100) ? "--" : L.f("%s L", Ui.f(l100, "%.1f"))));
            addFact(consumption, L.t("Cost"), costPer100);
            if (!e.traced && polls++ < 20) {
                root.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (root.isAttachedToWindow()) fillConsumption();
                    }
                }, 500);
            }
        } else {
            addFact(consumption, L.t("Energy used"), L.f("%s kWh", Ui.f(t.energyUsed(), "%.1f")));
            addFact(consumption, L.t("Consumption"), L.f("%s kWh/100km", Ui.f(t.energyPer100Km(), "%.1f")));
            addFact(consumption, L.t("Cost"), costPer100);
        }
    }
}
