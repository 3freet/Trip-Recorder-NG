package org.triprecorderng;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

/**
 * Line chart of one value along a trip (speed, battery, elevation). X is time since the trip start.
 * Touching the chart reports the nearest sample so the route map can show where it was.
 */
final class TripChartView extends View {
    interface Listener {
        /**
         * index = the sample nearest to the touch (for the map), timeMs = the time under the finger (between
         * samples the cursor moves smoothly), or -1 / NaN when the finger is lifted
         */
        void onCursor(int index, double timeMs);
    }

    /** A gap in the samples longer than this (the car stood still) breaks the line. */
    private double gapMs = 90_000;
    private double bridgeMax = Double.NaN;

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cursor = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint msg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path area = new Path();
    private final float d;

    private double[] t = new double[0];   // ms
    private double[] y = new double[0];   // NaN where unknown
    private String unit = "";
    private int color = Ui.BLUE;
    private String empty = "";
    private int cursorIdx = -1;
    private double cursorT = Double.NaN;
    private double cursorY = Double.NaN;
    private Listener listener;

    TripChartView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        float sd = c.getResources().getDisplayMetrics().scaledDensity;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2.5f * d);
        line.setStrokeJoin(Paint.Join.ROUND);
        fill.setStyle(Paint.Style.FILL);
        grid.setColor(Ui.DIVIDER);
        grid.setStrokeWidth(1f * d);
        label.setColor(Ui.TEXT_LABEL);
        label.setTextSize(13 * sd);
        cursor.setColor(0xFFFFFFFF);
        cursor.setStrokeWidth(1.5f * d);
        msg.setColor(Ui.TEXT_LABEL);
        msg.setTextAlign(Paint.Align.CENTER);
        msg.setTextSize(16 * sd);
    }

    /** Samples further apart than this are not joined by a line (default 90 s). */
    void setGapMs(double ms) {
        gapMs = ms;
    }

    /** A gap between two samples that are both at or below this value is drawn as a flat line, not a break. */
    void setBridgeBelow(double value) {
        bridgeMax = value;
    }

    void setListener(Listener l) {
        listener = l;
    }

    /** times in ms, values in unit; emptyText is shown when fewer than two values exist. */
    void set(double[] times, double[] values, String unitName, int lineColor, String emptyText) {
        t = times;
        y = values;
        unit = unitName;
        color = lineColor;
        empty = emptyText;
        cursorIdx = -1;
        cursorT = Double.NaN;
        invalidate();
    }

    private int known() {
        int n = 0;
        for (double v : y) if (!Double.isNaN(v)) n++;
        return n;
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth();
        float h = getHeight();
        if (known() < 2) {
            c.drawText(empty, w / 2f, h / 2f, msg);
            return;
        }
        float left = 46 * d, right = 8 * d, top = 10 * d, bottom = h - 24 * d;
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (double v : y) {
            if (Double.isNaN(v)) continue;
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        if (max - min < 1e-6) {
            max = min + 1;
        }
        double pad = (max - min) * 0.08;
        if (min >= 0 && min - pad < 0) min = 0; else min -= pad;
        max += pad;
        double t0 = t[0], t1 = t[t.length - 1];
        if (t1 <= t0) t1 = t0 + 1;

        label.setTextAlign(Paint.Align.RIGHT);
        for (int i = 0; i <= 2; i++) {
            float yy = bottom - (bottom - top) * i / 2f;
            c.drawLine(left, yy, w - right, yy, grid);
            double v = min + (max - min) * i / 2.0;
            c.drawText(String.format(java.util.Locale.US, "%.0f", v), left - 6 * d, yy + 5 * d, label);
        }
        label.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i <= 2; i++) {
            float xx = left + (w - left - right) * i / 2f;
            long s = (long) ((t1 - t0) * i / 2.0 / 1000);
            String txt = (s / 3600) + ":" + String.format(java.util.Locale.US, "%02d", (s % 3600) / 60);
            if (i == 0) label.setTextAlign(Paint.Align.LEFT);
            else if (i == 2) label.setTextAlign(Paint.Align.RIGHT);
            else label.setTextAlign(Paint.Align.CENTER);
            c.drawText(txt, xx, h - 4 * d, label);
        }

        fill.setShader(new LinearGradient(0, top, 0, bottom, (color & 0x00FFFFFF) | 0x55000000,
                (color & 0x00FFFFFF) | 0x05000000, Shader.TileMode.CLAMP));
        line.setColor(color);
        path.reset();
        area.reset();
        boolean open = false;
        float lastX = 0;
        double prevT = 0, prevY = 0;
        for (int i = 0; i < y.length; i++) {
            if (Double.isNaN(y[i])) continue;
            float x = (float) (left + (w - left - right) * (t[i] - t0) / (t1 - t0));
            float yy = (float) (bottom - (bottom - top) * (y[i] - min) / (max - min));
            if (open && TripAnalysis.breaksLine(prevT, t[i], prevY, y[i], gapMs, bridgeMax)) {
                area.lineTo(lastX, bottom);
                area.close();
                open = false;
            }
            if (!open) {
                path.moveTo(x, yy);
                area.moveTo(x, bottom);
                area.lineTo(x, yy);
                open = true;
            } else {
                path.lineTo(x, yy);
                area.lineTo(x, yy);
            }
            lastX = x;
            prevT = t[i];
            prevY = y[i];
        }
        if (open) {
            area.lineTo(lastX, bottom);
            area.close();
        }
        c.drawPath(area, fill);
        c.drawPath(path, line);

        if (cursorIdx >= 0 && !Double.isNaN(cursorT)) {
            float x = (float) (left + (w - left - right) * (cursorT - t0) / (t1 - t0));
            c.drawLine(x, top, x, bottom, cursor);
            if (!Double.isNaN(cursorY)) {
                float yy = (float) (bottom - (bottom - top) * (cursorY - min) / (max - min));
                cursor.setStyle(Paint.Style.FILL);
                c.drawCircle(x, yy, 5 * d, cursor);
            }
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (known() < 2) return false;
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            cursorIdx = -1;
            cursorT = Double.NaN;
            invalidate();
            if (listener != null) listener.onCursor(-1, Double.NaN);
            return true;
        }
        getParent().requestDisallowInterceptTouchEvent(true);
        float left = 46 * d, right = 8 * d;
        double t0 = t[0], t1 = Math.max(t[t.length - 1], t[0] + 1);
        double want = t0 + (t1 - t0) * (e.getX() - left) / (getWidth() - left - right);
        want = Math.max(t0, Math.min(t1, want));
        // on the drawn line the cursor follows the finger, even across a stretch with no samples (a stop
        // recorded by an older version); across a real hole (line broken) it snaps to the nearest sample
        double[] cur = TripAnalysis.cursorAt(t, y, want, gapMs, bridgeMax);
        int best = (int) cur[0];
        double newT = cur[1], newY = cur[2];
        if (best != cursorIdx || newT != cursorT) {
            cursorIdx = best;
            cursorT = newT;
            cursorY = newY;
            invalidate();
            if (listener != null) listener.onCursor(best, newT);
        }
        return true;
    }

    String unit() {
        return unit;
    }
}
