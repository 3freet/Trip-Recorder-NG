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
 * Card under the route map on the trip details screen: speed, battery, elevation and efficiency
 * along the trip. Touching a chart marks that moment on the map.
 */
final class TripChartsPanel extends LinearLayout {
    private static final int SPEED = 0, BATTERY = 1, ELEVATION = 2, EFFICIENCY = 3;

    private final RouteView route;
    private final TextView[] chips = new TextView[4];
    private final TextView readout;
    private final TripChartView chart;
    private final BarChartView bars;
    private final TextView barsEmpty;
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm", L.dateLocale());

    private int mode = SPEED;
    private List<double[]> pts;
    private Trip trip;
    private double[] times, speeds, soc, alt, bands;

    TripChartsPanel(Context ctx, RouteView routeView) {
        super(ctx);
        route = routeView;
        setOrientation(VERTICAL);
        setBackground(Ui.round(Ui.CARD, 8, ctx));
        setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 10), Ui.dp(ctx, 16), Ui.dp(ctx, 8));

        LinearLayout top = new LinearLayout(ctx);
        top.setOrientation(HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        final String[] names = {L.t("Speed"), L.t("Battery"), L.t("Elevation"), L.t("Efficiency")};
        for (int i = 0; i < 4; i++) {
            final int m = i;
            TextView t = Ui.text(ctx, names[i], 17, Ui.TEXT_DATE);
            t.setGravity(Gravity.CENTER);
            t.setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 7), Ui.dp(ctx, 16), Ui.dp(ctx, 7));
            t.setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) {
                    select(m);
                }
            });
            chips[i] = t;
            top.addView(t);
        }
        readout = Ui.text(ctx, "", 15, Ui.TEXT_LABEL);
        readout.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        top.addView(readout, new LinearLayout.LayoutParams(0, -2, 1f));
        addView(top, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout frame = new FrameLayout(ctx);
        chart = new TripChartView(ctx);
        chart.setListener(new TripChartView.Listener() {
            @Override public void onCursor(int index, double timeMs) {
                route.setCursor(index);
                showCursor(index, timeMs);
            }
        });
        bars = new BarChartView(ctx);
        barsEmpty = Ui.text(ctx, "", 16, Ui.TEXT_LABEL);
        barsEmpty.setGravity(Gravity.CENTER);
        frame.addView(chart, new FrameLayout.LayoutParams(-1, -1));
        frame.addView(bars, new FrameLayout.LayoutParams(-1, -1));
        frame.addView(barsEmpty, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-1, 0, 1f);
        flp.topMargin = Ui.dp(ctx, 6);
        addView(frame, flp);

        style();
        render();
    }

    void setData(Trip t, List<double[]> points) {
        trip = t;
        pts = points;
        times = TripAnalysis.times(points);
        speeds = TripAnalysis.speeds(points);
        soc = TripAnalysis.soc(points);
        alt = TripAnalysis.altitude(points);
        bands = TripAnalysis.consumptionByBand(points);
        render();
    }

    private void select(int m) {
        mode = m;
        route.setCursor(-1);
        style();
        render();
    }

    private void style() {
        for (int i = 0; i < 4; i++) {
            boolean sel = i == mode;
            chips[i].setTextColor(sel ? Ui.TEXT_SELECTED : Ui.TEXT_DATE);
            chips[i].setBackground(Ui.round(sel ? 0x33FFFFFF : 0x00000000, 18, getContext()));
        }
    }

    private void render() {
        boolean haveData = pts != null && pts.size() >= 2;
        boolean eff = mode == EFFICIENCY;
        chart.setVisibility(eff ? GONE : VISIBLE);
        bars.setVisibility(GONE);
        barsEmpty.setVisibility(GONE);
        if (!haveData) {
            readout.setText("");
            chart.set(new double[0], new double[0], "", Ui.BLUE, L.t("No GPS track for this trip"));
            chart.setVisibility(VISIBLE);
            bars.setVisibility(GONE);
            return;
        }
        switch (mode) {
            case SPEED:
                chart.setGapMs(90_000);
                chart.setBridgeBelow(5);          // a stop with no samples (older trips) is a flat line at ~0, not a hole
                chart.set(times, speeds, "km/h", Ui.BLUE, L.t("No speed data"));
                readout.setText(L.t("Speed (km/h) - touch the chart to see it on the map"));
                break;
            case BATTERY:
                chart.setBridgeBelow(Double.NaN);
                chart.setGapMs(Double.MAX_VALUE); // battery changes slowly: join the samples across any gap
                chart.set(times, soc, "%", Ui.GREEN, trip != null && !Double.isNaN(trip.socStart)
                        ? L.f("Battery along the route was not recorded for this trip (%1$s%% -> %2$s%%)",
                        Ui.f(trip.socStart, "%.0f"), Ui.f(trip.socEnd, "%.0f"))
                        : L.t("No battery data for this trip"));
                readout.setText(L.t("Battery (%) - touch the chart to see it on the map"));
                break;
            case ELEVATION:
                chart.setBridgeBelow(Double.NaN);
                chart.setGapMs(Double.MAX_VALUE);
                chart.set(times, alt, "m", Ui.AMBER, L.t("No altitude data for this trip"));
                readout.setText(elevationSummary());
                break;
            default:
                renderEfficiency();
        }
    }

    private void renderEfficiency() {
        double[] b = bands;
        boolean any = false;
        if (b != null) for (double v : b) if (!Double.isNaN(v)) any = true;
        if (!any) {
            bars.setVisibility(GONE);
            barsEmpty.setVisibility(VISIBLE);
            barsEmpty.setText(b == null
                    ? L.t("Energy along the route was not recorded for this trip")
                    : L.t("Not enough distance in any speed band"));
            readout.setText("");
            return;
        }
        double[] vals = new double[b.length];
        for (int i = 0; i < b.length; i++) vals[i] = Double.isNaN(b[i]) ? 0 : b[i];
        bars.set(vals, TripAnalysis.BAND_NAMES, Ui.GREEN);
        bars.setVisibility(VISIBLE);
        String overall = trip != null && !Double.isNaN(trip.energyPer100Km())
                ? L.f("  -  whole trip %s", Ui.f(trip.energyPer100Km(), "%.1f")) : "";
        readout.setText(L.t("kWh/100km by speed (km/h)") + overall);
    }

    /** Total climb and descent from the smoothed altitude, ignoring changes under 3 m. */
    private String elevationSummary() {
        double up = 0, down = 0;
        boolean have = false;
        double ref = Double.NaN;
        for (double v : alt) {
            if (Double.isNaN(v)) continue;
            have = true;
            if (Double.isNaN(ref)) {
                ref = v;
                continue;
            }
            if (v - ref >= 3) {
                up += v - ref;
                ref = v;
            } else if (ref - v >= 3) {
                down += ref - v;
                ref = v;
            }
        }
        if (!have) return "";
        return L.f("Elevation (m)  -  climb %1$d m, descent %2$d m", Math.round(up), Math.round(down));
    }

    private void showCursor(int i, double timeMs) {
        if (i < 0 || pts == null || i >= pts.size() || Double.isNaN(timeMs)) {
            render();
            return;
        }
        // values at the time under the finger, interpolated between samples (a stop has none in older trips)
        double speed = TripAnalysis.valueAt(times, speeds, timeMs);
        double battery = TripAnalysis.valueAt(times, soc, timeMs);
        double elevation = TripAnalysis.valueAt(times, alt, timeMs);
        StringBuilder sb = new StringBuilder(clock.format(new Date((long) timeMs)));
        sb.append("   ").append(L.f("%s km/h", Ui.f(speed, "%.0f")));
        if (!Double.isNaN(battery)) sb.append("   ").append(Ui.f(battery, "%.0f")).append("%");
        if (!Double.isNaN(elevation)) sb.append("   ").append(L.f("%d m", Math.round(elevation)));
        readout.setText(sb.toString());
    }
}
