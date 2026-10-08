package org.triprecorderng;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws a trip's GPS track on a plain dark canvas (no map tiles, works offline), coloured by speed,
 * with hard acceleration/braking markers and an optional cursor. Tapping it opens the trip in
 * Google Maps.
 */
final class RouteView extends View {
    /** speed at which the colour scale reaches red */
    private static final double RED_KMH = 130;

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint msg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint legend = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] hsv = new float[3];
    private List<double[]> pts = new ArrayList<>();
    private List<double[]> events = new ArrayList<>();
    private int cursor = -1;
    private final float d;

    // projection of the current draw
    private double minLon, maxLat, kx, scale, offX, offY;

    RouteView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        float sd = c.getResources().getDisplayMetrics().scaledDensity;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(4 * d);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2 * d);
        ring.setColor(0xFFFFFFFF);
        msg.setColor(Ui.TEXT_LABEL);
        msg.setTextAlign(Paint.Align.CENTER);
        msg.setTextSize(18 * sd);
        hint.setColor(Ui.TEXT_LABEL);
        hint.setTextAlign(Paint.Align.CENTER);
        hint.setTextSize(14 * sd);
        legend.setColor(Ui.TEXT_LABEL);
        legend.setTextSize(13 * sd);
        bar.setShader(new LinearGradient(16 * d, 0, 136 * d, 0,
                new int[]{speedColor(0), speedColor(RED_KMH * 0.5), speedColor(RED_KMH)}, null,
                Shader.TileMode.CLAMP));
        setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                MapLink.open(getContext(), pts);
            }
        });
    }

    void set(List<double[]> points, List<double[]> hardEvents) {
        pts = points == null ? new ArrayList<double[]>() : points;
        events = hardEvents == null ? new ArrayList<double[]>() : hardEvents;
        cursor = -1;
        invalidate();
    }

    /** Marks the sample at index (into the point list) on the map; -1 clears it. */
    void setCursor(int index) {
        cursor = index;
        invalidate();
    }

    private int speedColor(double kmh) {
        double f = Math.max(0, Math.min(1, kmh / RED_KMH));
        hsv[0] = (float) (130 * (1 - f)); // green -> yellow -> red
        hsv[1] = 0.75f;
        hsv[2] = 1f;
        return Color.HSVToColor(hsv);
    }

    private float px(double lon) {
        return (float) (offX + (lon - minLon) * kx * scale);
    }

    private float py(double lat) {
        return (float) (offY + (maxLat - lat) * scale);
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth();
        float h = getHeight();
        if (pts.size() < 2) {
            c.drawText(L.t("No GPS track for this trip"), w / 2f, h / 2f, msg);
            return;
        }
        c.drawText(L.t("Tap the map to open this trip in Google Maps"), w / 2f, h - 10 * d, hint);

        double minLat = 90, maxLon = -180;
        maxLat = -90;
        minLon = 180;
        for (double[] p : pts) {
            minLat = Math.min(minLat, p[1]);
            maxLat = Math.max(maxLat, p[1]);
            minLon = Math.min(minLon, p[2]);
            maxLon = Math.max(maxLon, p[2]);
        }
        double midLat = (minLat + maxLat) / 2.0;
        kx = Math.cos(Math.toRadians(midLat));
        double spanX = Math.max(1e-6, (maxLon - minLon) * kx);
        double spanY = Math.max(1e-6, maxLat - minLat);
        float pad = 28 * d;
        float usableH = h - 14 * d;
        scale = Math.min((w - 2 * pad) / spanX, (usableH - 2 * pad) / spanY);
        offX = (w - spanX * scale) / 2.0;
        offY = (usableH - spanY * scale) / 2.0;

        // the track, one segment per pair of samples, coloured by their mean speed
        float lastX = px(pts.get(0)[2]), lastY = py(pts.get(0)[1]);
        for (int i = 1; i < pts.size(); i++) {
            double[] p = pts.get(i);
            float x = px(p[2]), y = py(p[1]);
            line.setColor(speedColor((TripAnalysis.speed(pts.get(i - 1)) + TripAnalysis.speed(p)) / 2));
            c.drawLine(lastX, lastY, x, y, line);
            lastX = x;
            lastY = y;
        }

        dot.setStyle(Paint.Style.FILL);
        dot.setColor(Ui.GREEN);
        c.drawCircle(px(pts.get(0)[2]), py(pts.get(0)[1]), 7 * d, dot);
        dot.setColor(Ui.RED);
        c.drawCircle(lastX, lastY, 7 * d, dot);

        // hard braking (red) and hard acceleration (amber) markers
        int accel = 0, brake = 0;
        for (double[] e : events) {
            if (Double.isNaN(e[2]) || Double.isNaN(e[3])) continue;
            boolean isBrake = e[1] == 1;
            if (isBrake) brake++; else accel++;
            float x = px(e[3]), y = py(e[2]);
            dot.setColor(0xFFFFFFFF);
            c.drawCircle(x, y, 9 * d, dot);
            dot.setColor(isBrake ? Ui.RED : Ui.AMBER);
            c.drawCircle(x, y, 6.5f * d, dot);
        }

        if (cursor >= 0 && cursor < pts.size()) {
            float x = px(pts.get(cursor)[2]), y = py(pts.get(cursor)[1]);
            dot.setColor(0x66FFFFFF);
            c.drawCircle(x, y, 14 * d, dot);
            dot.setColor(0xFFFFFFFF);
            c.drawCircle(x, y, 6 * d, dot);
            c.drawCircle(x, y, 11 * d, ring);
        }

        // legend: speed scale (top left) and event markers (top right)
        float lx = 16 * d, ly = 16 * d, lw = 120 * d, lh = 8 * d;
        c.drawRoundRect(lx, ly, lx + lw, ly + lh, 4 * d, 4 * d, bar);
        legend.setTextAlign(Paint.Align.LEFT);
        c.drawText("0", lx, ly + lh + 16 * d, legend);
        legend.setTextAlign(Paint.Align.RIGHT);
        c.drawText(L.t("130+ km/h"), lx + lw, ly + lh + 16 * d, legend);
        if (accel + brake > 0) {
            legend.setTextAlign(Paint.Align.RIGHT);
            float rx = w - 16 * d;
            String t1 = L.f("hard braking %d", brake);
            c.drawText(t1, rx, ly + 11 * d, legend);
            dot.setColor(Ui.RED);
            c.drawCircle(rx - legend.measureText(t1) - 10 * d, ly + 6 * d, 5 * d, dot);
            String t2 = L.f("hard accel %d", accel);
            c.drawText(t2, rx, ly + 32 * d, legend);
            dot.setColor(Ui.AMBER);
            c.drawCircle(rx - legend.measureText(t2) - 10 * d, ly + 27 * d, 5 * d, dot);
        }
    }
}
