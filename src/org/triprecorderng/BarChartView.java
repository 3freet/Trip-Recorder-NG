package org.triprecorderng;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Simple vertical bar chart used by the Statistics page. */
final class BarChartView extends View {
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axis = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint value = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private double[] data = new double[0];
    private String[] labels = new String[0];
    private int color = Ui.GREEN;
    private int decimals = 1;
    private final float d;

    BarChartView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        float sd = c.getResources().getDisplayMetrics().scaledDensity;
        axis.setColor(Ui.DIVIDER);
        axis.setStrokeWidth(1f * d);
        label.setColor(Ui.TEXT_LABEL);
        label.setTextSize(14 * sd);
        label.setTextAlign(Paint.Align.CENTER);
        value.setColor(Ui.TEXT_VALUE);
        value.setTextSize(14 * sd);
        value.setTextAlign(Paint.Align.CENTER);
    }

    /** Number of decimals for values under 100 (default 1). */
    void setDecimals(int d) {
        decimals = d;
    }

    void set(double[] values, String[] names, int barColor) {
        data = values;
        labels = names;
        color = barColor;
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        int n = data.length;
        if (n == 0) return;
        float w = getWidth();
        float h = getHeight();
        float bottom = h - 34 * d;
        float top = 28 * d;
        double max = 0;
        for (double v : data) if (!Double.isNaN(v) && v > max) max = v;
        if (max <= 0) max = 1;
        for (int i = 1; i <= 3; i++) {
            float y = bottom - (bottom - top) * i / 3f;
            c.drawLine(0, y, w, y, axis);
        }
        c.drawLine(0, bottom, w, bottom, axis);
        float slot = w / n;
        float bw = Math.min(slot * 0.55f, 46 * d);
        bar.setColor(color);
        for (int i = 0; i < n; i++) {
            float cx = slot * i + slot / 2f;
            double v = Double.isNaN(data[i]) ? 0 : data[i];
            float bh = (float) ((bottom - top) * (v / max));
            if (v > 0) {
                r.set(cx - bw / 2f, bottom - bh, cx + bw / 2f, bottom);
                c.drawRoundRect(r, 4 * d, 4 * d, bar);
                String t = v >= 100 ? String.format(java.util.Locale.US, "%.0f", v)
                        : String.format(java.util.Locale.US, "%." + decimals + "f", v);
                c.drawText(t, cx, bottom - bh - 6 * d, value);
            }
            if (i < labels.length && labels[i] != null) {
                c.drawText(labels[i], cx, bottom + 22 * d, label);
            }
        }
    }
}
